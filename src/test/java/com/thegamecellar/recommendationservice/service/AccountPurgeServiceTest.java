package com.thegamecellar.recommendationservice.service;

import com.thegamecellar.recommendationservice.repository.ComputeQueueRepository;
import com.thegamecellar.recommendationservice.repository.PoolHoldingRepository;
import com.thegamecellar.recommendationservice.repository.UserCandidatePoolRepository;
import com.thegamecellar.recommendationservice.repository.UserProfileSnapshotRepository;
import jakarta.persistence.Entity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountPurgeServiceTest {

    private static final String USER_ID = "6f1c2d3e-0000-4000-8000-000000000001";
    private static final String ENTITY_PACKAGE = "com.thegamecellar.recommendationservice.model.entity";

    @Mock private ComputeQueueRepository queueRepository;
    @Mock private PoolHoldingRepository holdingRepository;
    @Mock private UserCandidatePoolRepository poolRepository;
    @Mock private UserProfileSnapshotRepository profileRepository;
    @Mock private UserProfileCache profileCache;
    @Mock private UserStateCache stateCache;
    @Mock private TransactionTemplate transactionTemplate;

    @InjectMocks
    private AccountPurgeService service;

    private final AtomicBoolean inTransaction = new AtomicBoolean();

    @BeforeEach
    void runCallbacksInline() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            inTransaction.set(true);
            try {
                return callback.doInTransaction(null);
            } finally {
                inTransaction.set(false);
            }
        });
    }

    @Test
    void purgeReportsWhatEachTableRemoved() {
        when(queueRepository.deleteByUserId(USER_ID)).thenReturn(1);
        when(holdingRepository.deleteByUserId(USER_ID)).thenReturn(30);
        when(poolRepository.deleteByUserId(USER_ID)).thenReturn(2500);
        when(profileRepository.deleteByUserId(USER_ID)).thenReturn(1);

        AccountPurgeService.PurgeResult result = service.purgeUser(USER_ID);

        assertThat(result).isEqualTo(new AccountPurgeService.PurgeResult(1, 30, 2500, 1));
    }

    // The queue row is the lock a running compute holds; deleting it first is what makes the
    // purge wait out a compute in flight instead of racing it.
    @Test
    void queueGoesFirstSoAComputeInFlightIsWaitedOut() {
        service.purgeUser(USER_ID);

        InOrder order = inOrder(queueRepository, holdingRepository, poolRepository, profileRepository);
        order.verify(queueRepository).deleteByUserId(USER_ID);
        order.verify(holdingRepository).deleteByUserId(USER_ID);
        order.verify(poolRepository).deleteByUserId(USER_ID);
        order.verify(profileRepository).deleteByUserId(USER_ID);
    }

    @Test
    void cachesAreEvictedOnlyAfterTheDeletesCommit() {
        AtomicBoolean evictedInsideTransaction = new AtomicBoolean();
        doAnswer(invocation -> {
            if (inTransaction.get()) evictedInsideTransaction.set(true);
            return null;
        }).when(profileCache).invalidate(USER_ID);
        doAnswer(invocation -> {
            if (inTransaction.get()) evictedInsideTransaction.set(true);
            return null;
        }).when(stateCache).invalidate(USER_ID);

        service.purgeUser(USER_ID);

        verify(profileCache).invalidate(USER_ID);
        verify(stateCache).invalidate(USER_ID);
        assertThat(evictedInsideTransaction).isFalse();
    }

    // A new user-keyed table is a new place personal data survives a deletion request. This
    // test finds every entity with a userId, finds the repository that manages it, and checks
    // that the purge called deleteByUserId on it. Adding the entity without wiring it fails loudly.
    @Test
    void purgeCoversEveryEntityThatCarriesAUserId() throws Exception {
        service.purgeUser(USER_ID);

        Set<Class<?>> userKeyedEntities = userKeyedEntities();
        assertThat(userKeyedEntities).isNotEmpty();

        for (Class<?> entity : userKeyedEntities) {
            Object mock = repositoryMockFor(entity);
            Method deleteByUserId = mock.getClass().getMethod("deleteByUserId", String.class);
            deleteByUserId.invoke(verify(mock), USER_ID);
        }
    }

    private static Set<Class<?>> userKeyedEntities() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        Set<Class<?>> entities = new HashSet<>();
        for (var candidate : scanner.findCandidateComponents(ENTITY_PACKAGE)) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            boolean carriesUserId = Arrays.stream(type.getDeclaredFields())
                    .anyMatch(field -> field.getName().equals("userId"));
            if (carriesUserId) {
                entities.add(type);
            }
        }
        return entities;
    }

    private Object repositoryMockFor(Class<?> entity) throws IllegalAccessException {
        for (Field field : getClass().getDeclaredFields()) {
            if (managedEntity(field.getType()) == entity) {
                return field.get(this);
            }
        }
        return fail("%s carries a userId but AccountPurgeService has no repository to purge it; inject it, call deleteByUserId in purgeUser, and add a @Mock here",
                entity.getSimpleName());
    }

    private static Class<?> managedEntity(Class<?> repositoryType) {
        for (Type type : repositoryType.getGenericInterfaces()) {
            if (type instanceof ParameterizedType parameterized && parameterized.getRawType() == JpaRepository.class) {
                return (Class<?>) parameterized.getActualTypeArguments()[0];
            }
        }
        return null;
    }
}
