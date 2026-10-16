package com.thegamecellar.recommendationservice.service;

import com.thegamecellar.recommendationservice.model.dto.AccountExportDTO;
import com.thegamecellar.recommendationservice.model.dto.AccountExportDTO.ExportedCandidate;
import com.thegamecellar.recommendationservice.model.dto.AccountExportDTO.ExportedHolding;
import com.thegamecellar.recommendationservice.model.dto.AccountExportDTO.ExportedProfile;
import com.thegamecellar.recommendationservice.model.dto.AccountExportDTO.ExportedQueueEntry;
import com.thegamecellar.recommendationservice.model.entity.ComputeQueueEntry;
import com.thegamecellar.recommendationservice.model.entity.PoolHolding;
import com.thegamecellar.recommendationservice.model.entity.UserCandidatePool;
import com.thegamecellar.recommendationservice.model.entity.UserProfileSnapshot;
import com.thegamecellar.recommendationservice.repository.ComputeQueueRepository;
import com.thegamecellar.recommendationservice.repository.PoolHoldingRepository;
import com.thegamecellar.recommendationservice.repository.UserCandidatePoolRepository;
import com.thegamecellar.recommendationservice.repository.UserProfileSnapshotRepository;
import jakarta.persistence.Entity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountExportServiceTest {

    private static final String USER_ID = "user-123";
    private static final String ENTITY_PACKAGE = "com.thegamecellar.recommendationservice.model.entity";

    @Mock private UserProfileSnapshotRepository profileRepository;
    @Mock private UserCandidatePoolRepository poolRepository;
    @Mock private PoolHoldingRepository holdingRepository;
    @Mock private ComputeQueueRepository queueRepository;

    @InjectMocks
    private AccountExportService service;

    @Test
    void aUserWithNothingStoredExportsNoProfileNoQueueEntryAndEmptyLists() {
        when(profileRepository.findById(USER_ID)).thenReturn(Optional.empty());
        when(queueRepository.findById(USER_ID)).thenReturn(Optional.empty());

        AccountExportDTO export = service.exportUser(USER_ID);

        assertThat(export.userId()).isEqualTo(USER_ID);
        assertThat(export.profile()).isNull();
        assertThat(export.pendingRecompute()).isNull();
        assertThat(export.candidatePool()).isEmpty();
        assertThat(export.heldBackGames()).isEmpty();
    }

    @Test
    void poolAndHoldingsComeOutInAStableOrder() {
        LocalDateTime now = LocalDateTime.now();
        when(poolRepository.findByUserId(USER_ID)).thenReturn(List.of(
                UserCandidatePool.builder().userId(USER_ID).igdbId(30).name("C").computedAt(now).build(),
                UserCandidatePool.builder().userId(USER_ID).igdbId(10).name("A").computedAt(now).build()));
        when(holdingRepository.findByUserId(USER_ID)).thenReturn(List.of(
                PoolHolding.builder().userId(USER_ID).genre("RPG").igdbId(2).releasedAt(now).build(),
                PoolHolding.builder().userId(USER_ID).genre("Action").igdbId(9).releasedAt(now).build()));

        AccountExportDTO export = service.exportUser(USER_ID);

        assertThat(export.candidatePool()).extracting(ExportedCandidate::igdbId).containsExactly(10, 30);
        assertThat(export.heldBackGames()).extracting(ExportedHolding::genre).containsExactly("Action", "RPG");
    }

    // A column the export leaves out is personal data the user cannot see. Every field of every
    // user-keyed entity, bar the userId the file carries once at the top, must reach the file;
    // a new entity or a new column without its place in AccountExportDTO fails here.
    @Test
    void exportCarriesEveryFieldOfEveryEntityThatCarriesAUserId() throws Exception {
        Map<Class<?>, Set<String>> exported = Map.of(
                UserProfileSnapshot.class, components(ExportedProfile.class),
                UserCandidatePool.class, components(ExportedCandidate.class),
                PoolHolding.class, components(ExportedHolding.class),
                ComputeQueueEntry.class, components(ExportedQueueEntry.class));

        Set<Class<?>> entities = userKeyedEntities();
        assertThat(entities).isNotEmpty();
        for (Class<?> entity : entities) {
            assertThat(exported)
                    .withFailMessage("%s carries a userId but has no place in AccountExportDTO", entity.getSimpleName())
                    .containsKey(entity);
            Set<String> fields = Arrays.stream(entity.getDeclaredFields())
                    .filter(field -> !Modifier.isStatic(field.getModifiers()) && !field.isSynthetic())
                    .map(Field::getName)
                    .filter(name -> !name.equals("userId"))
                    .collect(Collectors.toSet());
            assertThat(exported.get(entity))
                    .withFailMessage("AccountExportDTO leaves out %s of %s", fields.stream()
                            .filter(name -> !exported.get(entity).contains(name)).toList(), entity.getSimpleName())
                    .containsAll(fields);
        }
    }

    private static Set<String> components(Class<?> record) {
        return Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName).collect(Collectors.toSet());
    }

    private static Set<Class<?>> userKeyedEntities() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        Set<Class<?>> entities = new HashSet<>();
        for (var candidate : scanner.findCandidateComponents(ENTITY_PACKAGE)) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            if (Arrays.stream(type.getDeclaredFields()).anyMatch(field -> field.getName().equals("userId"))) {
                entities.add(type);
            }
        }
        return entities;
    }
}
