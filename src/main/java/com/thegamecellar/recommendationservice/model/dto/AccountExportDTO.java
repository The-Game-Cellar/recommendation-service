package com.thegamecellar.recommendationservice.model.dto;

import com.thegamecellar.recommendationservice.model.entity.ComputeQueueEntry;
import com.thegamecellar.recommendationservice.model.entity.PoolHolding;
import com.thegamecellar.recommendationservice.model.entity.UserCandidatePool;
import com.thegamecellar.recommendationservice.model.entity.UserProfileSnapshot;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Every column of every row kept for the user, as stored, apart from user_id, which userId
// already carries. AccountExportServiceTest fails when an entity gains a field left out here.
public record AccountExportDTO(
        String userId,
        String exportedAt,
        ExportedProfile profile,
        List<ExportedCandidate> candidatePool,
        List<ExportedHolding> heldBackGames,
        ExportedQueueEntry pendingRecompute
) {

    public record ExportedProfile(Map<String, Double> genreWeights,
                                  Map<String, Double> tagWeights,
                                  Map<String, Double> platformWeights,
                                  Map<String, Double> decadeWeights,
                                  Map<String, Double> libraryGenreCounts,
                                  Integer ratedCount,
                                  LocalDateTime updatedAt) {
        public static ExportedProfile of(UserProfileSnapshot p) {
            return new ExportedProfile(new HashMap<>(p.getGenreWeights()), new HashMap<>(p.getTagWeights()),
                    new HashMap<>(p.getPlatformWeights()), new HashMap<>(p.getDecadeWeights()),
                    new HashMap<>(p.getLibraryGenreCounts()), p.getRatedCount(), p.getUpdatedAt());
        }
    }

    public record ExportedCandidate(Integer igdbId,
                                    String name,
                                    BigDecimal baseScore,
                                    Short tier,
                                    BigDecimal rating,
                                    String backgroundImage,
                                    List<String> genres,
                                    List<String> platforms,
                                    Integer seedIgdbId,
                                    String seedName,
                                    BigDecimal seedRating,
                                    List<String> sharedTags,
                                    LocalDateTime computedAt) {
        public static ExportedCandidate of(UserCandidatePool c) {
            return new ExportedCandidate(c.getIgdbId(), c.getName(), c.getBaseScore(), c.getTier(), c.getRating(),
                    c.getBackgroundImage(), new ArrayList<>(c.getGenres()), new ArrayList<>(c.getPlatforms()),
                    c.getSeedIgdbId(), c.getSeedName(), c.getSeedRating(), new ArrayList<>(c.getSharedTags()),
                    c.getComputedAt());
        }
    }

    public record ExportedHolding(String genre, Integer igdbId, LocalDateTime releasedAt) {
        public static ExportedHolding of(PoolHolding h) {
            return new ExportedHolding(h.getGenre(), h.getIgdbId(), h.getReleasedAt());
        }
    }

    public record ExportedQueueEntry(LocalDateTime queuedAt, Short attempts, String targetGenre) {
        public static ExportedQueueEntry of(ComputeQueueEntry q) {
            return new ExportedQueueEntry(q.getQueuedAt(), q.getAttempts(), q.getTargetGenre());
        }
    }
}
