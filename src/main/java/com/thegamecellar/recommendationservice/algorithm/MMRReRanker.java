package com.thegamecellar.recommendationservice.algorithm;

import com.thegamecellar.recommendationservice.model.dto.game.GameDTO;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

// MMR: pick the candidate maximising λ * relevance - (1-λ) * max-similarity-to-picked. Avoids near-duplicate top-N.
public class MMRReRanker {

    public static final double DEFAULT_LAMBDA = 0.85;

    private MMRReRanker() {}

    public static List<GameDTO> reRank(List<GameDTO> sorted, UserProfile profile, int k) {
        return reRank(sorted, profile, k, DEFAULT_LAMBDA, 0.0, 0.0, Set.of());
    }

    public static List<GameDTO> reRank(List<GameDTO> sorted, UserProfile profile, int k, double lambda) {
        return reRank(sorted, profile, k, lambda, 0.0, 0.0, Set.of());
    }

    public static List<GameDTO> reRank(List<GameDTO> sorted, UserProfile profile, int k, double lambda, double jitter) {
        return reRank(sorted, profile, k, lambda, jitter, 0.0, Set.of());
    }

    // jitter randomises anchor + cascade across calls; penalty soft-drops recency-set ids without excluding.
    public static List<GameDTO> reRank(List<GameDTO> sorted, UserProfile profile, int k,
                                        double lambda, double jitter,
                                        double penalty, Set<Integer> penalizedIds) {
        if (sorted == null || sorted.isEmpty() || k <= 0) return List.of();
        if (profile == null || profile.isEmpty()) {
            return sorted.subList(0, Math.min(k, sorted.size()));
        }
        Set<Integer> penaltySet = penalizedIds == null ? Set.of() : penalizedIds;

        int outputSize = Math.min(k, sorted.size());
        List<GameDTO> picked = new ArrayList<>(outputSize);
        List<GameDTO> remaining = new ArrayList<>(sorted);

        // First slot anchors on the top of the supplied pool.
        picked.add(remaining.remove(0));

        while (picked.size() < outputSize && !remaining.isEmpty()) {
            int bestIdx = 0;
            double bestScore = Double.NEGATIVE_INFINITY;

            for (int i = 0; i < remaining.size(); i++) {
                GameDTO candidate = remaining.get(i);
                double relevance = SimilarityScorer.scoreMultiDim(candidate, profile)
                        + SimilarityScorer.EPSILON * SimilarityScorer.platformBoost(candidate, profile.platforms())
                        + SimilarityScorer.RELEASE_YEAR_EPSILON * SimilarityScorer.releaseYearBoost(candidate, profile.declaredReleaseYears());
                if (jitter > 0.0) {
                    relevance += ThreadLocalRandom.current().nextDouble() * jitter;
                }
                if (penalty > 0.0 && penaltySet.contains(candidate.getIgdbId())) {
                    relevance -= penalty;
                }
                double maxSim = 0.0;
                for (GameDTO p : picked) {
                    double sim = featureSimilarity(candidate, p);
                    if (sim > maxSim) maxSim = sim;
                }
                double mmr = lambda * relevance - (1.0 - lambda) * maxSim;
                if (mmr > bestScore) {
                    bestScore = mmr;
                    bestIdx = i;
                }
            }
            picked.add(remaining.remove(bestIdx));
        }

        return picked;
    }

    private static double featureSimilarity(GameDTO a, GameDTO b) {
        Set<String> aFeatures = unionFeatures(a);
        Set<String> bFeatures = unionFeatures(b);
        if (aFeatures.isEmpty() || bFeatures.isEmpty()) return 0.0;

        int intersection = 0;
        for (String f : aFeatures) {
            if (bFeatures.contains(f)) intersection++;
        }
        return intersection / (Math.sqrt(aFeatures.size()) * Math.sqrt(bFeatures.size()));
    }

    private static Set<String> unionFeatures(GameDTO g) {
        Set<String> all = new HashSet<>();
        addAllNonBlank(all, g.getGenres());
        addAllNonBlank(all, g.getThemes());
        addAllNonBlank(all, g.getTags());
        return all;
    }

    private static void addAllNonBlank(Set<String> target, List<String> source) {
        if (source == null) return;
        for (String s : source) {
            if (s == null) continue;
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) target.add(trimmed);
        }
    }
}
