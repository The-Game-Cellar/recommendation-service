package com.thegamecellar.recommendationservice.worker;

import com.thegamecellar.recommendationservice.algorithm.UserProfile;
import com.thegamecellar.recommendationservice.client.InternalGameClient;
import com.thegamecellar.recommendationservice.client.InternalLibraryClient;
import com.thegamecellar.recommendationservice.model.entity.ComputeQueueEntry;
import com.thegamecellar.recommendationservice.model.entity.UserCandidatePool;
import com.thegamecellar.recommendationservice.model.entity.UserProfileSnapshot;
import com.thegamecellar.recommendationservice.repository.ComputeQueueRepository;
import com.thegamecellar.recommendationservice.repository.PoolHoldingRepository;
import com.thegamecellar.recommendationservice.repository.UserCandidatePoolRepository;
import com.thegamecellar.recommendationservice.repository.UserProfileSnapshotRepository;
import com.thegamecellar.recommendationservice.service.RecommendationComputer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserComputeProcessorTest {

    @Mock private ComputeQueueRepository queueRepository;
    @Mock private UserCandidatePoolRepository poolRepository;
    @Mock private UserProfileSnapshotRepository profileRepository;
    @Mock private PoolHoldingRepository holdingRepository;
    @Mock private RecommendationComputer computer;
    @Mock private InternalLibraryClient libraryClient;
    @Mock private InternalGameClient gameClient;

    private UserComputeProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new UserComputeProcessor(queueRepository, poolRepository,
                profileRepository, holdingRepository, computer, libraryClient, gameClient);
        ReflectionTestUtils.setField(processor, "poolSize", 1000);
    }

    @Test
    void emptyQueue_returnsFalse_writesNothing() {
        when(queueRepository.dequeueBatch(any(Pageable.class))).thenReturn(List.of());

        boolean processed = processor.processOneAtomicallyOrSkip();

        assertThat(processed).isFalse();
        verify(computer, never()).computePool(any(), any(), any(), any(), any(), any(), anyInt());
        verify(poolRepository, never()).saveAll(any());
        verify(profileRepository, never()).save(any());
    }

    @Test
    void successfulCompute_writesPoolAndProfile_deletesQueueRow() {
        ComputeQueueEntry entry = ComputeQueueEntry.builder()
                .userId("u1").queuedAt(LocalDateTime.now()).attempts((short) 0).build();
        when(queueRepository.dequeueBatch(any(Pageable.class))).thenReturn(List.of(entry));
        when(libraryClient.getGames("u1")).thenReturn(List.of());
        when(libraryClient.getPlatforms("u1")).thenReturn(List.of());
        when(libraryClient.getGenrePreferences("u1")).thenReturn(List.of());
        when(libraryClient.getTagPreferences("u1")).thenReturn(List.of());
        when(libraryClient.getReleaseYearPreferences("u1")).thenReturn(List.of());

        UserProfile profile = new UserProfile(
                new HashMap<>(), new HashMap<>(), new HashMap<>(),
                new HashMap<>(), new HashMap<>(), Set.of(), 3);
        RecommendationComputer.PoolCandidate candidate = new RecommendationComputer.PoolCandidate(
                100, new BigDecimal("0.8500"), (short) 1, "Test", null,
                BigDecimal.valueOf(8.0), List.of("Action"), List.of("PC"));
        RecommendationComputer.Result result = new RecommendationComputer.Result(
                profile, List.of(candidate),
                com.thegamecellar.recommendationservice.algorithm.RecommendationTier.ONE);
        when(computer.computePool(any(), any(), any(), any(), any(), any(), anyInt())).thenReturn(result);

        boolean processed = processor.processOneAtomicallyOrSkip();

        assertThat(processed).isTrue();
        verify(poolRepository).deleteByUserId("u1");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UserCandidatePool>> rows = ArgumentCaptor.forClass(List.class);
        verify(poolRepository).saveAll(rows.capture());
        assertThat(rows.getValue()).hasSize(1);
        assertThat(rows.getValue().get(0).getIgdbId()).isEqualTo(100);
        assertThat(rows.getValue().get(0).getName()).isEqualTo("Test");

        ArgumentCaptor<UserProfileSnapshot> snap = ArgumentCaptor.forClass(UserProfileSnapshot.class);
        verify(profileRepository).save(snap.capture());
        assertThat(snap.getValue().getUserId()).isEqualTo("u1");
        assertThat(snap.getValue().getRatedCount()).isEqualTo(3);

        verify(queueRepository).deleteById("u1");
    }

    @Test
    void computeFailure_belowMaxAttempts_incrementsAttempts() {
        ComputeQueueEntry entry = ComputeQueueEntry.builder()
                .userId("u2").queuedAt(LocalDateTime.now()).attempts((short) 0).build();
        when(queueRepository.dequeueBatch(any(Pageable.class))).thenReturn(List.of(entry));
        when(libraryClient.getGames("u2")).thenReturn(List.of());
        when(libraryClient.getPlatforms("u2")).thenReturn(List.of());
        when(libraryClient.getGenrePreferences("u2")).thenReturn(List.of());
        when(libraryClient.getTagPreferences("u2")).thenReturn(List.of());
        when(libraryClient.getReleaseYearPreferences("u2")).thenReturn(List.of());
        when(computer.computePool(any(), any(), any(), any(), any(), any(), anyInt()))
                .thenThrow(new RuntimeException("boom"));

        boolean processed = processor.processOneAtomicallyOrSkip();

        assertThat(processed).isTrue();
        verify(queueRepository).incrementAttempts("u2");
        verify(queueRepository, never()).deleteById(anyString());
        verify(poolRepository, never()).saveAll(any());
        verify(profileRepository, never()).save(any());
    }

    @Test
    void computeFailure_atMaxAttempts_deadLetters() {
        ComputeQueueEntry entry = ComputeQueueEntry.builder()
                .userId("u3").queuedAt(LocalDateTime.now())
                .attempts((short) (UserComputeProcessor.MAX_ATTEMPTS - 1)).build();
        when(queueRepository.dequeueBatch(any(Pageable.class))).thenReturn(List.of(entry));
        when(libraryClient.getGames("u3")).thenReturn(List.of());
        when(libraryClient.getPlatforms("u3")).thenReturn(List.of());
        when(libraryClient.getGenrePreferences("u3")).thenReturn(List.of());
        when(libraryClient.getTagPreferences("u3")).thenReturn(List.of());
        when(libraryClient.getReleaseYearPreferences("u3")).thenReturn(List.of());
        when(computer.computePool(any(), any(), any(), any(), any(), any(), anyInt()))
                .thenThrow(new RuntimeException("permanent"));

        boolean processed = processor.processOneAtomicallyOrSkip();

        assertThat(processed).isTrue();
        verify(queueRepository).deleteById("u3");
        verify(queueRepository, never()).incrementAttempts(anyString());
    }

    @Test
    void perGenreTopUp_writesNewBucketAndParksOld() {
        ComputeQueueEntry entry = ComputeQueueEntry.builder()
                .userId("uG").queuedAt(LocalDateTime.now()).attempts((short) 0).targetGenre("Action").build();
        when(queueRepository.dequeueBatch(any(Pageable.class))).thenReturn(List.of(entry));

        com.thegamecellar.recommendationservice.model.entity.UserCandidatePool existing =
                com.thegamecellar.recommendationservice.model.entity.UserCandidatePool.builder()
                        .userId("uG").igdbId(1)
                        .baseScore(new BigDecimal("0.5"))
                        .tier((short) 1)
                        .name("Old")
                        .rating(BigDecimal.valueOf(7.0))
                        .genres(List.of("Action"))
                        .platforms(List.of("PC"))
                        .computedAt(LocalDateTime.now())
                        .build();
        when(poolRepository.findByUserId("uG")).thenReturn(List.of(existing));
        when(holdingRepository.findIgdbIdsForUserGenre("uG", "Action")).thenReturn(List.of(1));
        when(libraryClient.getGames("uG")).thenReturn(List.of());
        when(libraryClient.getPlatforms("uG")).thenReturn(List.of());
        when(libraryClient.getGenrePreferences("uG")).thenReturn(List.of());
        when(libraryClient.getTagPreferences("uG")).thenReturn(List.of());
        when(libraryClient.getReleaseYearPreferences("uG")).thenReturn(List.of());

        // Fresh count must clear the bucket-size floor for the replace path to trigger; the
        // append-only fallback runs when fewer fresh candidates land than MIN_BUCKET_FLOOR.
        java.util.List<RecommendationComputer.PoolCandidate> freshBatch = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            freshBatch.add(new RecommendationComputer.PoolCandidate(
                    100 + i, new BigDecimal("0.8500"), (short) 1, "New" + i, null,
                    BigDecimal.valueOf(8.0), List.of("Action"), List.of("PC")));
        }
        when(computer.topUpGenre(eq("Action"), any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(freshBatch);

        boolean processed = processor.processOneAtomicallyOrSkip();

        assertThat(processed).isTrue();
        verify(holdingRepository).saveAll(any());
        verify(poolRepository, never()).deleteByUserId("uG");
        verify(poolRepository).saveAll(any());
        verify(profileRepository, never()).save(any());
        verify(queueRepository).deleteById("uG");
    }
}
