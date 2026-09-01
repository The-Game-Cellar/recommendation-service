package com.thegamecellar.recommendationservice.algorithm;

import com.thegamecellar.recommendationservice.model.dto.game.GameDTO;
import com.thegamecellar.recommendationservice.model.dto.library.UserGameDTO;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// What ties a candidate to the user: the rated game it overlaps most (the seed) and the shared
// features that carry the most weight in the profile. Features are genres, themes and tags;
// tags pass ReasonTagBlocklist first. Names keep the catalog's casing and match
// case-insensitively. Built once per compute so the rated games' feature sets and the
// normalised profile weights are prepared a single time for the whole pool.
public final class ConnectionFinder {

    public static final int SEED_MIN_RATING = 7;
    public static final int SEED_MIN_OVERLAP = 2;
    public static final int MAX_SHARED = 3;

    public record Connection(Integer seedIgdbId, String seedName, Integer seedRating, List<String> sharedTags) {
        public static final Connection NONE = new Connection(null, null, null, List.of());
    }

    // dimension orders ties: 0 genre, 1 theme, 2 tag.
    private record Feature(String name, int dimension) {}

    private record RatedFeatures(UserGameDTO game, Set<String> keys) {}

    private final List<RatedFeatures> rated;
    // One lowercase-keyed weight map per dimension, each divided by its own maximum so a genre
    // weight and a tag weight compare on the same scale.
    private final List<Map<String, Double>> weights;

    public ConnectionFinder(List<UserGameDTO> ratedGames, UserProfile profile) {
        this.rated = new ArrayList<>();
        if (ratedGames != null) {
            for (UserGameDTO g : ratedGames) {
                if (g == null || g.getIgdbGameId() == null || g.getRating() == null) continue;
                if (g.getRating() < SEED_MIN_RATING) continue;
                Map<String, Feature> f = features(g.getGenres(), g.getThemes(), g.getTags());
                if (!f.isEmpty()) rated.add(new RatedFeatures(g, f.keySet()));
            }
        }
        this.weights = List.of(
                normalise(profile == null ? null : profile.genres()),
                normalise(profile == null ? null : profile.themes()),
                normalise(profile == null ? null : profile.tags()));
    }

    public Connection find(GameDTO candidate) {
        if (candidate == null) return Connection.NONE;
        Map<String, Feature> cand = features(candidate.getGenres(), candidate.getThemes(), candidate.getTags());
        if (cand.isEmpty()) return Connection.NONE;

        RatedFeatures seed = null;
        Set<String> seedOverlap = Set.of();
        for (RatedFeatures r : rated) {
            Set<String> overlap = new HashSet<>(cand.keySet());
            overlap.retainAll(r.keys());
            if (overlap.size() < SEED_MIN_OVERLAP) continue;
            boolean better = seed == null
                    || overlap.size() > seedOverlap.size()
                    || (overlap.size() == seedOverlap.size() && r.game().getRating() > seed.game().getRating());
            if (better) {
                seed = r;
                seedOverlap = overlap;
            }
        }

        // With a seed the shared features are the ones it has in common with the candidate;
        // without one they are whatever the profile weighs at all, so nothing is claimed that
        // the user's ratings and preferences do not carry.
        List<String> shared = rank(seed != null ? seedOverlap : cand.keySet(), cand, seed == null);
        if (seed == null && shared.isEmpty()) return Connection.NONE;
        UserGameDTO g = seed == null ? null : seed.game();
        return new Connection(
                g == null ? null : g.getIgdbGameId(),
                g == null ? null : g.getGameName(),
                g == null ? null : g.getRating(),
                shared);
    }

    // Shared features of two catalog games with no profile to weigh them: genres first, then
    // themes, then tags, each in the order the first game lists them.
    public static List<String> sharedFeatures(GameDTO a, GameDTO b) {
        if (a == null || b == null) return List.of();
        Map<String, Feature> fa = features(a.getGenres(), a.getThemes(), a.getTags());
        Map<String, Feature> fb = features(b.getGenres(), b.getThemes(), b.getTags());
        List<Feature> out = new ArrayList<>();
        for (Map.Entry<String, Feature> e : fa.entrySet()) {
            if (fb.containsKey(e.getKey())) out.add(e.getValue());
        }
        out.sort(Comparator.comparingInt(Feature::dimension));
        return out.stream().limit(MAX_SHARED).map(Feature::name).toList();
    }

    // Genres two games share, in the first list's order. For pool rows, which carry genres only.
    public static List<String> sharedGenres(List<String> a, List<String> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return List.of();
        Set<String> other = new HashSet<>();
        for (String s : b) if (s != null) other.add(s.trim().toLowerCase());
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String s : a) {
            if (s == null) continue;
            String key = s.trim().toLowerCase();
            if (key.isEmpty() || !seen.add(key) || !other.contains(key)) continue;
            out.add(s.trim());
            if (out.size() >= MAX_SHARED) break;
        }
        return out;
    }

    private record Ranked(Feature feature, double weight) {}

    private List<String> rank(Collection<String> keys, Map<String, Feature> cand, boolean requireWeight) {
        List<Ranked> ranked = new ArrayList<>();
        for (String key : keys) {
            Feature f = cand.get(key);
            if (f == null) continue;
            double w = weights.get(f.dimension()).getOrDefault(key, 0.0);
            if (requireWeight && w <= 0.0) continue;
            ranked.add(new Ranked(f, w));
        }
        ranked.sort(Comparator.comparingDouble(Ranked::weight).reversed()
                .thenComparingInt(r -> r.feature().dimension())
                .thenComparing(r -> r.feature().name()));
        return ranked.stream().limit(MAX_SHARED).map(r -> r.feature().name()).toList();
    }

    // First occurrence wins, so a name that is both a genre and a tag counts as the genre.
    private static Map<String, Feature> features(List<String> genres, List<String> themes, List<String> tags) {
        Map<String, Feature> out = new LinkedHashMap<>();
        add(out, genres, 0, false);
        add(out, themes, 1, false);
        add(out, tags, 2, true);
        return out;
    }

    private static void add(Map<String, Feature> out, List<String> names, int dimension, boolean filtered) {
        if (names == null) return;
        for (String n : names) {
            if (n == null) continue;
            String name = n.trim();
            if (name.isEmpty()) continue;
            if (filtered && ReasonTagBlocklist.blocks(name)) continue;
            out.putIfAbsent(name.toLowerCase(), new Feature(name, dimension));
        }
    }

    private static Map<String, Double> normalise(Map<String, Double> raw) {
        if (raw == null || raw.isEmpty()) return Map.of();
        double max = 0.0;
        for (Double v : raw.values()) if (v != null && v > max) max = v;
        if (max <= 0.0) return Map.of();
        Map<String, Double> out = new HashMap<>(raw.size() * 2);
        for (Map.Entry<String, Double> e : raw.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getValue() <= 0.0) continue;
            out.merge(e.getKey().trim().toLowerCase(), e.getValue() / max, Math::max);
        }
        return out;
    }
}
