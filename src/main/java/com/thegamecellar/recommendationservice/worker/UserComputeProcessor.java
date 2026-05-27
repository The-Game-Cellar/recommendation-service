package com.thegamecellar.recommendationservice.worker;

import com.thegamecellar.recommendationservice.client.InternalGameClient;
import com.thegamecellar.recommendationservice.client.InternalLibraryClient;
import com.thegamecellar.recommendationservice.model.dto.library.UserGameDTO;
import com.thegamecellar.recommendationservice.model.dto.library.UserPlatformDTO;
import com.thegamecellar.recommendationservice.model.entity.ComputeQueueEntry;
import com.thegamecellar.recommendationservice.model.entity.PoolHolding;
import com.thegamecellar.recommendationservice.model.entity.UserCandidatePool;
import com.thegamecellar.recommendationservice.model.entity.UserProfileSnapshot;
import com.thegamecellar.recommendationservice.repository.ComputeQueueRepository;
import com.thegamecellar.recommendationservice.repository.PoolHoldingRepository;
import com.thegamecellar.recommendationservice.repository.UserCandidatePoolRepository;
import com.thegamecellar.recommendationservice.repository.UserProfileSnapshotRepository;
import com.thegamecellar.recommendationservice.service.RecommendationComputer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Atomic per-user compute step. Two branches:
//   - target_genre NULL = full pool replace (delete-by-user + insert N rows + write profile snapshot).
//   - target_genre set = per-genre top-up. Move existing bucket to pool_holding (TTL ~60s), fetch
//     fresh candidates for the genre excluding holding-ids, append to pool. Profile snapshot
//     untouched. Snappier than full replace, lets the user spin one genre without burning others.
// Both share dequeue + retry/dead-letter handling. Holding cleanup runs at the start of every tick.
@Slf4j
@Component
@RequiredArgsConstructor
public class UserComputeProcessor {

    public static final int MAX_ATTEMPTS = 3;
    // Released-at offset for displaced bucket entries. Roughly two worker fixed-delay ticks at
    // 30s. After this window the holding rows are pruned and the catalog entries can resurface.
    private static final long HOLDING_TTL_SECONDS = 60L;
    // Per-genre top-up target size. Pool stays bounded around poolSize even with repeated top-ups
    // because the displaced bucket is removed before the new one is inserted.
    private static final int PER_GENRE_TOP_UP_SIZE = 500;

    private final ComputeQueueRepository queueRepository;
    private final UserCandidatePoolRepository poolRepository;
    private final UserProfileSnapshotRepository profileRepository;
    private final PoolHoldingRepository holdingRepository;
    private final RecommendationComputer computer;
    private final InternalLibraryClient libraryClient;
    private final InternalGameClient gameClient;

    @Value("${recommendation.pool.size:2500}")
    private int poolSize;

    @Transactional
    public boolean processOneAtomicallyOrSkip() {
        // Cheap prune at the head of every tick; cap on memory + avoids stale-holding bias on
        // the next per-genre top-up's exclude set.
        holdingRepository.deleteExpired(LocalDateTime.now());

        List<ComputeQueueEntry> entries = queueRepository.dequeueBatch(PageRequest.of(0, 1));
        if (entries.isEmpty()) return false;

        ComputeQueueEntry entry = entries.get(0);
        String userId = entry.getUserId();
        String targetGenre = entry.getTargetGenre();
        long started = System.currentTimeMillis();

        try {
            if (targetGenre == null || targetGenre.isBlank()) {
                runFullReplace(userId);
            } else {
                runGenreTopUp(userId, targetGenre);
            }
        } catch (RuntimeException ex) {
            short nextAttempts = (short) (entry.getAttempts() + 1);
            if (nextAttempts >= MAX_ATTEMPTS) {
                log.error("Dead-lettering compute_queue userId={} targetGenre={} after {} attempts: {}",
                        userId, targetGenre, nextAttempts, ex.toString());
                queueRepository.deleteById(userId);
            } else {
                log.warn("Compute failed userId={} targetGenre={} attempt={}, will retry: {}",
                        userId, targetGenre, nextAttempts, ex.toString());
                queueRepository.incrementAttempts(userId);
            }
            return true;
        }

        queueRepository.deleteById(userId);
        log.info("processOneAtomicallyOrSkip(userId={} targetGenre={} elapsedMs={})",
                userId, targetGenre, System.currentTimeMillis() - started);
        return true;
    }

    private void runFullReplace(String userId) {
        ComputeOutput output = doCompute(userId);
        writeResults(userId, output, LocalDateTime.now());
    }

    // Floor for the post-top-up bucket size. If the fresh-fetch alone cannot reach this we
    // skip the bucket eviction so the read-side row still has enough material to fill itself.
    private static final int MIN_BUCKET_FLOOR = 15;

    private void runGenreTopUp(String userId, String genre) {
        // Bucket membership = "row contains this genre", same looser rule the request path uses
        // so what we evict here lines up with what was shown.
        List<UserCandidatePool> existingBucket = poolRepository.findByUserId(userId).stream()
                .filter(r -> r.getGenres() != null && r.getGenres().contains(genre))
                .toList();

        LocalDateTime now = LocalDateTime.now();
        Set<Integer> existingIds = new HashSet<>();
        for (UserCandidatePool row : existingBucket) existingIds.add(row.getIgdbId());

        // Fetch new candidates FIRST. Exclude current bucket + anything still in holding so we
        // get strictly fresh ids when the catalog has room to give.
        Set<Integer> excludeIds = new HashSet<>(existingIds);
        excludeIds.addAll(holdingRepository.findIgdbIdsForUserGenre(userId, genre));

        List<UserGameDTO> games = libraryClient.getGames(userId);
        List<UserPlatformDTO> platforms = libraryClient.getPlatforms(userId);
        List<String> genrePrefs = libraryClient.getGenrePreferences(userId);
        List<String> tagPrefs = libraryClient.getTagPreferences(userId);
        List<String> yearPrefs = libraryClient.getReleaseYearPreferences(userId);

        List<RecommendationComputer.PoolCandidate> topUp = computer.topUpGenre(
                genre, excludeIds, games, platforms, genrePrefs, tagPrefs, yearPrefs,
                gameClient, PER_GENRE_TOP_UP_SIZE);

        // Catalog-exhaust path: not enough new candidates to safely evict the bucket. Append
        // whatever we got without holding eviction so the bucket cannot collapse. Read-side
        // shown-penalty still rotates the visible top-15 across consecutive refreshes.
        boolean replace = topUp.size() >= Math.max(MIN_BUCKET_FLOOR, existingBucket.size() / 2);

        if (replace) {
            LocalDateTime releaseAt = now.plusSeconds(HOLDING_TTL_SECONDS);
            List<PoolHolding> heldRows = new ArrayList<>(existingBucket.size());
            for (UserCandidatePool row : existingBucket) {
                heldRows.add(PoolHolding.builder()
                        .userId(userId)
                        .genre(genre)
                        .igdbId(row.getIgdbId())
                        .releasedAt(releaseAt)
                        .build());
            }
            holdingRepository.saveAll(heldRows);
            holdingRepository.flush();

            for (UserCandidatePool row : existingBucket) {
                poolRepository.deleteById(new com.thegamecellar.recommendationservice.model.entity.UserCandidatePoolId(
                        row.getUserId(), row.getIgdbId()));
            }
            poolRepository.flush();
        } else {
            log.info("Per-genre top-up for userId={} genre={} got only {} fresh (bucket={}); append-only mode",
                    userId, genre, topUp.size(), existingBucket.size());
        }

        if (topUp.isEmpty()) return;

        List<UserCandidatePool> rows = new ArrayList<>(topUp.size());
        for (RecommendationComputer.PoolCandidate c : topUp) {
            // In append-only mode the existing bucket stayed, so skip ids already present to
            // avoid PK collision on insert.
            if (!replace && existingIds.contains(c.igdbId())) continue;
            rows.add(UserCandidatePool.builder()
                    .userId(userId)
                    .igdbId(c.igdbId())
                    .baseScore(c.baseScore())
                    .tier(c.tier())
                    .name(c.name())
                    .backgroundImage(c.backgroundImage())
                    .rating(c.rating())
                    .genres(c.genres())
                    .platforms(c.platforms())
                    .computedAt(now)
                    .build());
        }
        if (!rows.isEmpty()) poolRepository.saveAll(rows);
    }

    record ComputeOutput(RecommendationComputer.Result result, Map<String, Double> libraryGenreCounts) {}

    private ComputeOutput doCompute(String userId) {
        List<UserGameDTO> games = libraryClient.getGames(userId);
        List<UserPlatformDTO> platforms = libraryClient.getPlatforms(userId);
        List<String> genrePrefs = libraryClient.getGenrePreferences(userId);
        List<String> tagPrefs = libraryClient.getTagPreferences(userId);
        List<String> yearPrefs = libraryClient.getReleaseYearPreferences(userId);

        RecommendationComputer.Result result = computer.computePool(
                games, platforms, genrePrefs, tagPrefs, yearPrefs, gameClient, poolSize);
        return new ComputeOutput(result, rawGenreCounts(games));
    }

    // Raw genre count over the full library. Matches /profile/statistics ordering. Each game
    // contributes +1 to every genre it carries; no fractional split.
    private static Map<String, Double> rawGenreCounts(List<UserGameDTO> games) {
        Map<String, Double> counts = new java.util.HashMap<>();
        if (games == null) return counts;
        for (UserGameDTO g : games) {
            List<String> genres = g.getGenres();
            if (genres == null) continue;
            for (String genre : genres) {
                if (genre == null) continue;
                String trimmed = genre.trim();
                if (trimmed.isEmpty()) continue;
                counts.merge(trimmed, 1.0, Double::sum);
            }
        }
        return counts;
    }

    private void writeResults(String userId, ComputeOutput output, LocalDateTime now) {
        RecommendationComputer.Result result = output.result();
        // flush + clear so DELETE runs before saveAll INSERT in the replace-all txn; otherwise
        // (user_id, igdb_id) unique constraint fires on re-insert via Hibernate action-queue ordering.
        poolRepository.deleteByUserId(userId);
        poolRepository.flush();

        List<UserCandidatePool> rows = new ArrayList<>(result.candidates().size());
        for (RecommendationComputer.PoolCandidate c : result.candidates()) {
            rows.add(UserCandidatePool.builder()
                    .userId(userId)
                    .igdbId(c.igdbId())
                    .baseScore(c.baseScore())
                    .tier(c.tier())
                    .name(c.name())
                    .backgroundImage(c.backgroundImage())
                    .rating(c.rating())
                    .genres(c.genres())
                    .platforms(c.platforms())
                    .computedAt(now)
                    .build());
        }
        poolRepository.saveAll(rows);

        UserProfileSnapshot snapshot = UserProfileSnapshot.builder()
                .userId(userId)
                .genreWeights(result.profile().genres())
                .tagWeights(result.profile().tags())
                .platformWeights(result.profile().platforms())
                .decadeWeights(result.profile().releaseYears())
                .libraryGenreCounts(output.libraryGenreCounts())
                .ratedCount(result.profile().ratedGameCount())
                .updatedAt(now)
                .build();
        profileRepository.save(snapshot);
    }
}
