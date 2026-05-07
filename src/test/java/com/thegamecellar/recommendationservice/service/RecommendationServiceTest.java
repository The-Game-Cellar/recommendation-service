package com.thegamecellar.recommendationservice.service;

import com.thegamecellar.recommendationservice.client.GameServiceClient;
import com.thegamecellar.recommendationservice.client.LibraryServiceClient;
import com.thegamecellar.recommendationservice.model.dto.RecommendationDTO;
import com.thegamecellar.recommendationservice.model.dto.game.GameDTO;
import com.thegamecellar.recommendationservice.model.dto.library.UserGameDTO;
import com.thegamecellar.recommendationservice.model.dto.library.UserPlatformDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecommendationServiceTest {

    @Mock
    private GameServiceClient gameServiceClient;

    @Mock
    private LibraryServiceClient libraryServiceClient;

    @InjectMocks
    private RecommendationService recommendationService;

    // --- Tier 3: new user, no rated games ---

    @Test
    void getPersonalized_returns_tier3_for_new_user_with_no_rated_games() {
        when(libraryServiceClient.getGames("token")).thenReturn(List.of());
        when(libraryServiceClient.getPlatforms("token")).thenReturn(List.of(platform("PC")));
        when(gameServiceClient.getPopularGames(eq("PC"), anyString())).thenReturn(List.of(game(1, "Popular Game")));

        List<RecommendationDTO> result = recommendationService.getPersonalized("token", 10);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTier()).isEqualTo(3);
        assertThat(result.get(0).getReason()).isEqualTo("Popular on your platforms");
    }

    @Test
    void getPersonalized_tier3_falls_back_to_global_popular_when_no_platforms() {
        when(libraryServiceClient.getGames("token")).thenReturn(List.of());
        when(libraryServiceClient.getPlatforms("token")).thenReturn(List.of());
        when(gameServiceClient.getPopularGames(isNull(), anyString())).thenReturn(List.of(game(1, "Global Popular")));

        List<RecommendationDTO> result = recommendationService.getPersonalized("token", 10);

        assertThat(result).hasSize(1);
    }

    // --- Tier 2: 1-4 rated games ---

    @Test
    void getPersonalized_returns_tier2_for_user_with_few_rated_games() {
        UserGameDTO rated = ratedGame(1, 8, "RPG");
        when(libraryServiceClient.getGames("token")).thenReturn(List.of(rated));
        when(libraryServiceClient.getPlatforms("token")).thenReturn(List.of(platform("PC")));
        when(gameServiceClient.randomQualityByGenre(eq("RPG"), any(), anyInt(), anyInt(), anyString())).thenReturn(List.of(game(2, "Popular RPG", "RPG")));

        List<RecommendationDTO> result = recommendationService.getPersonalized("token", 10);

        assertThat(result.get(0).getTier()).isEqualTo(2);
        assertThat(result.get(0).getReason()).isEqualTo("Popular in your genres");
    }

    // --- Tier 1: 5+ rated games ---

    @Test
    void getPersonalized_returns_tier1_for_user_with_5_or_more_rated_games() {
        List<UserGameDTO> ratedGames = List.of(
                ratedGame(1, 9, "RPG"), ratedGame(2, 8, "RPG"), ratedGame(3, 7, "RPG"),
                ratedGame(4, 9, "RPG"), ratedGame(5, 8, "RPG")
        );
        when(libraryServiceClient.getGames("token")).thenReturn(ratedGames);
        when(libraryServiceClient.getPlatforms("token")).thenReturn(List.of(platform("PC")));
        when(gameServiceClient.randomQualityByGenre(eq("RPG"), any(), anyInt(), anyInt(), anyString())).thenReturn(List.of(game(6, "New RPG", "RPG")));

        List<RecommendationDTO> result = recommendationService.getPersonalized("token", 10);

        assertThat(result.get(0).getTier()).isEqualTo(1);
        assertThat(result.get(0).getReason()).isEqualTo("Based on your ratings");
    }

    // --- Collection exclusion ---

    @Test
    void getPersonalized_excludes_games_already_in_collection() {
        when(libraryServiceClient.getGames("token")).thenReturn(List.of(ownedGame(1)));
        when(libraryServiceClient.getPlatforms("token")).thenReturn(List.of(platform("PC")));
        when(gameServiceClient.getPopularGames(eq("PC"), anyString())).thenReturn(
                List.of(game(1, "Owned Game"), game(2, "New Game"))
        );

        List<RecommendationDTO> result = recommendationService.getPersonalized("token", 10);

        assertThat(result).noneMatch(r -> r.getIgdbId() == 1);
        assertThat(result).anyMatch(r -> r.getIgdbId() == 2);
    }

    // --- IGDB similar-games graph augmentation ---

    @Test
    void getPersonalized_tier1_includes_candidates_from_similar_games_graph() {
        // Top-rated game (id 1) has IGDB similar_games pointing at 100 + 101.
        UserGameDTO topRated = ratedGame(1, 9, "RPG");
        when(libraryServiceClient.getGames("token")).thenReturn(List.of(
                topRated, ratedGame(2, 8, "RPG"), ratedGame(3, 7, "RPG"),
                ratedGame(4, 9, "RPG"), ratedGame(5, 8, "RPG")
        ));
        when(libraryServiceClient.getPlatforms("token")).thenReturn(List.of(platform("PC")));

        // Genre search returns nothing — only graph candidates should populate the pool.
        when(gameServiceClient.randomQualityByGenre(eq("RPG"), any(), anyInt(), anyInt(), anyString()))
                .thenReturn(List.of());

        // Other rated titles have no cached game data yet (worker still backfilling).
        lenient().when(gameServiceClient.getGameById(anyInt(), anyString())).thenReturn(null);

        // Source game carries similarGameIds; the two graph fetches return real games.
        GameDTO source = game(1, "Source RPG", "RPG");
        source.setSimilarGameIds(List.of(100, 101));
        lenient().when(gameServiceClient.getGameById(eq(1), anyString())).thenReturn(source);
        lenient().when(gameServiceClient.getGameById(eq(100), anyString())).thenReturn(game(100, "Graph Neighbor A", "RPG"));
        lenient().when(gameServiceClient.getGameById(eq(101), anyString())).thenReturn(game(101, "Graph Neighbor B", "RPG"));

        List<RecommendationDTO> result = recommendationService.getPersonalized("token", 10);

        assertThat(result).extracting(RecommendationDTO::getIgdbId).contains(100, 101);
        assertThat(result).allMatch(r -> r.getTier() == 1);
    }

    @Test
    void getPersonalized_tier2_includes_candidates_from_similar_games_graph() {
        UserGameDTO topRated = ratedGame(1, 9, "RPG");
        when(libraryServiceClient.getGames("token")).thenReturn(List.of(topRated));
        when(libraryServiceClient.getPlatforms("token")).thenReturn(List.of(platform("PC")));
        when(gameServiceClient.randomQualityByGenre(eq("RPG"), any(), anyInt(), anyInt(), anyString()))
                .thenReturn(List.of());

        lenient().when(gameServiceClient.getGameById(anyInt(), anyString())).thenReturn(null);

        GameDTO source = game(1, "Source RPG", "RPG");
        source.setSimilarGameIds(List.of(200));
        lenient().when(gameServiceClient.getGameById(eq(1), anyString())).thenReturn(source);
        lenient().when(gameServiceClient.getGameById(eq(200), anyString())).thenReturn(game(200, "Graph Neighbor", "RPG"));

        List<RecommendationDTO> result = recommendationService.getPersonalized("token", 10);

        assertThat(result).extracting(RecommendationDTO::getIgdbId).contains(200);
        assertThat(result).allMatch(r -> r.getTier() == 2);
    }

    // --- Status-aware filter on rated-set ---

    @Test
    void getPersonalized_excludes_dropped_games_from_tier_selection_and_profile() {
        // 5 rated games, all DROPPED → filtered out → tier 3 popular fallback.
        List<UserGameDTO> allDropped = List.of(
                ratedGameWithStatus(1, 9, "DROPPED", "RPG"),
                ratedGameWithStatus(2, 8, "DROPPED", "RPG"),
                ratedGameWithStatus(3, 9, "DROPPED", "Action"),
                ratedGameWithStatus(4, 7, "DROPPED", "RPG"),
                ratedGameWithStatus(5, 8, "DROPPED", "Action")
        );
        when(libraryServiceClient.getGames("token")).thenReturn(allDropped);
        when(libraryServiceClient.getPlatforms("token")).thenReturn(List.of(platform("PC")));
        when(gameServiceClient.getPopularGames(eq("PC"), anyString())).thenReturn(List.of(game(99, "Popular Fallback")));

        List<RecommendationDTO> result = recommendationService.getPersonalized("token", 10);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTier()).isEqualTo(3);
        assertThat(result.get(0).getReason()).isEqualTo("Popular on your platforms");
    }

    @Test
    void getPersonalized_excludes_wishlist_games_from_tier_selection() {
        // 5 rated WISHLIST entries → all filtered → tier 3.
        List<UserGameDTO> wishlist = List.of(
                ratedGameWithStatus(1, 9, "WISHLIST", "RPG"),
                ratedGameWithStatus(2, 8, "WISHLIST", "RPG"),
                ratedGameWithStatus(3, 7, "WISHLIST", "RPG"),
                ratedGameWithStatus(4, 9, "WISHLIST", "RPG"),
                ratedGameWithStatus(5, 8, "WISHLIST", "RPG")
        );
        when(libraryServiceClient.getGames("token")).thenReturn(wishlist);
        when(libraryServiceClient.getPlatforms("token")).thenReturn(List.of(platform("PC")));
        when(gameServiceClient.getPopularGames(eq("PC"), anyString())).thenReturn(List.of(game(99, "Popular Fallback")));

        List<RecommendationDTO> result = recommendationService.getPersonalized("token", 10);

        assertThat(result.get(0).getTier()).isEqualTo(3);
    }

    @Test
    void getPersonalized_keeps_eligible_statuses_in_tier_selection() {
        // Mixed library: 3 eligible (COMPLETED/PLAYING/BACKLOG/DUSTY) + 2 DROPPED.
        // Eligible count = 3 → tier 2.
        List<UserGameDTO> mixed = List.of(
                ratedGameWithStatus(1, 9, "COMPLETED", "RPG"),
                ratedGameWithStatus(2, 8, "PLAYING", "RPG"),
                ratedGameWithStatus(3, 9, "BACKLOG", "RPG"),
                ratedGameWithStatus(4, 9, "DROPPED", "Strategy"),
                ratedGameWithStatus(5, 8, "DROPPED", "Strategy")
        );
        when(libraryServiceClient.getGames("token")).thenReturn(mixed);
        when(libraryServiceClient.getPlatforms("token")).thenReturn(List.of(platform("PC")));
        when(gameServiceClient.randomQualityByGenre(eq("RPG"), any(), anyInt(), anyInt(), anyString()))
                .thenReturn(List.of(game(10, "RPG result", "RPG")));
        // Strategy genre should NOT be queried — DROPPED games filtered out before profile build.
        lenient().when(gameServiceClient.randomQualityByGenre(eq("Strategy"), any(), anyInt(), anyInt(), anyString()))
                .thenReturn(List.of(game(20, "Strategy result", "Strategy")));

        List<RecommendationDTO> result = recommendationService.getPersonalized("token", 10);

        assertThat(result.get(0).getTier()).isEqualTo(2);
        assertThat(result).extracting(RecommendationDTO::getIgdbId).doesNotContain(20);
    }

    @Test
    void getPersonalized_treats_null_status_as_eligible_for_legacy_rows() {
        // Legacy rated row without status — keep it eligible so we don't silently drop it.
        UserGameDTO rated = ratedGame(1, 8, "RPG");
        rated.setStatus(null);
        when(libraryServiceClient.getGames("token")).thenReturn(List.of(rated));
        when(libraryServiceClient.getPlatforms("token")).thenReturn(List.of(platform("PC")));
        when(gameServiceClient.randomQualityByGenre(eq("RPG"), any(), anyInt(), anyInt(), anyString()))
                .thenReturn(List.of(game(2, "RPG result", "RPG")));

        List<RecommendationDTO> result = recommendationService.getPersonalized("token", 10);

        assertThat(result.get(0).getTier()).isEqualTo(2);
    }

    // --- Platform weighting ---

    @Test
    void getPersonalized_tier1_favours_primary_platform_in_output() {
        // User: 5 rated PS5 games (rating 9), 1 rated PC game (rating 9).
        // Sqrt-normalised platform weights: PS5 ≈ 0.91, PC ≈ 0.41 → normalised PS5 ≈ 0.69, PC ≈ 0.31.
        // Both platforms connected via onboarding so matchesAnyPlatform passes both.
        List<UserGameDTO> ratedGames = List.of(
                ratedOnPlatform(1, 9, "PlayStation 5", "RPG"),
                ratedOnPlatform(2, 9, "PlayStation 5", "RPG"),
                ratedOnPlatform(3, 9, "PlayStation 5", "RPG"),
                ratedOnPlatform(4, 9, "PlayStation 5", "RPG"),
                ratedOnPlatform(5, 9, "PlayStation 5", "RPG"),
                ratedOnPlatform(6, 9, "PC", "RPG")
        );
        when(libraryServiceClient.getGames("token")).thenReturn(ratedGames);
        when(libraryServiceClient.getPlatforms("token")).thenReturn(List.of(platform("PlayStation 5"), platform("PC")));

        // Two RPG candidates with identical genre/theme/tag profile but different platforms.
        // PS5-exclusive should outscore PC-exclusive due to platformBoost.
        GameDTO ps5Candidate = gameOnPlatforms(100, "PS5 RPG", List.of("PlayStation 5"), "RPG");
        GameDTO pcCandidate = gameOnPlatforms(101, "PC RPG", List.of("PC"), "RPG");
        when(gameServiceClient.randomQualityByGenre(eq("RPG"), any(), anyInt(), anyInt(), anyString()))
                .thenReturn(List.of(ps5Candidate, pcCandidate));

        // Run multiple times — even with score jitter, PS5 should land first the vast majority.
        int ps5First = 0;
        int trials = 50;
        for (int i = 0; i < trials; i++) {
            List<RecommendationDTO> result = recommendationService.getPersonalized("token", 10);
            if (!result.isEmpty() && result.get(0).getIgdbId() == 100) ps5First++;
        }

        // platformBoost contribution = 0.15 * (0.69 - 0.31) ≈ 0.057, jitter range = 0.08 — close
        // call but PS5 should still dominate. Assert majority lower-bound.
        assertThat(ps5First).as("PS5-exclusive should rank first more often than PC-exclusive")
                .isGreaterThan(trials / 2);
    }

    @Test
    void getPersonalized_single_platform_user_unchanged_by_platform_weighting() {
        // User has only PC games rated → sqrt-normalised PC weight = 1.0 → constant boost across
        // all candidates that pass the filter → ranking determined by genre/theme/tag only.
        List<UserGameDTO> ratedGames = List.of(
                ratedOnPlatform(1, 9, "PC", "RPG"),
                ratedOnPlatform(2, 9, "PC", "RPG"),
                ratedOnPlatform(3, 9, "PC", "RPG"),
                ratedOnPlatform(4, 9, "PC", "RPG"),
                ratedOnPlatform(5, 9, "PC", "RPG")
        );
        when(libraryServiceClient.getGames("token")).thenReturn(ratedGames);
        when(libraryServiceClient.getPlatforms("token")).thenReturn(List.of(platform("PC")));
        when(gameServiceClient.randomQualityByGenre(eq("RPG"), any(), anyInt(), anyInt(), anyString()))
                .thenReturn(List.of(gameOnPlatforms(100, "PC RPG", List.of("PC"), "RPG")));

        List<RecommendationDTO> result = recommendationService.getPersonalized("token", 10);

        // Sanity: the only candidate makes it through.
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getIgdbId()).isEqualTo(100);
        assertThat(result.get(0).getTier()).isEqualTo(1);
    }

    // --- Library service down ---

    @Test
    void getPersonalized_returns_empty_when_library_service_is_down() {
        when(libraryServiceClient.getGames("token")).thenReturn(List.of());
        // getPlatforms and getPopularGames not mocked — Mockito returns empty list by default

        List<RecommendationDTO> result = recommendationService.getPersonalized("token", 10);

        assertThat(result).isEmpty();
    }

    // --- Helpers ---

    private UserGameDTO ratedGame(int igdbId, int rating, String... genres) {
        UserGameDTO game = new UserGameDTO();
        game.setIgdbGameId(igdbId);
        game.setRating(rating);
        game.setStatus("COMPLETED");
        if (genres.length > 0) {
            game.setGenres(List.of(genres));
        }
        return game;
    }

    private UserGameDTO ratedGameWithStatus(int igdbId, int rating, String status, String... genres) {
        UserGameDTO game = ratedGame(igdbId, rating, genres);
        game.setStatus(status);
        return game;
    }

    private UserGameDTO ratedOnPlatform(int igdbId, int rating, String platform, String... genres) {
        UserGameDTO game = ratedGame(igdbId, rating, genres);
        game.setPlatform(platform);
        return game;
    }

    private GameDTO gameOnPlatforms(int igdbId, String name, List<String> platforms, String... genres) {
        GameDTO g = game(igdbId, name, genres);
        g.setPlatforms(platforms);
        return g;
    }

    private UserGameDTO ownedGame(int igdbId) {
        UserGameDTO game = new UserGameDTO();
        game.setIgdbGameId(igdbId);
        return game;
    }

    private UserPlatformDTO platform(String name) {
        UserPlatformDTO p = new UserPlatformDTO();
        p.setPlatformName(name);
        return p;
    }

    private GameDTO game(int rawgId, String name, String... genres) {
        GameDTO game = new GameDTO();
        game.setIgdbId(rawgId);
        game.setName(name);
        game.setRating(BigDecimal.valueOf(8.0));
        game.setTotalRating(BigDecimal.valueOf(8.0));
        game.setTotalRatingCount(100);
        game.setPlatforms(List.of("PC"));
        if (genres.length > 0) {
            game.setGenres(List.of(genres));
        }
        return game;
    }

}