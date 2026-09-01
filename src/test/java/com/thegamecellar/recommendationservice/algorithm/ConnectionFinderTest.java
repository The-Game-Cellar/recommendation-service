package com.thegamecellar.recommendationservice.algorithm;

import com.thegamecellar.recommendationservice.algorithm.ConnectionFinder.Connection;
import com.thegamecellar.recommendationservice.model.dto.game.GameDTO;
import com.thegamecellar.recommendationservice.model.dto.library.UserGameDTO;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionFinderTest {

    private static final String RPG = "Role-playing (RPG)";

    @Test
    void namesTheRatedGameWithTheLargestOverlap() {
        UserGameDTO hades = rated(1, "Hades", 9, List.of("Adventure", "Indie"), List.of("Action", "Fantasy"), List.of("mythology"));
        UserGameDTO stardew = rated(2, "Stardew Valley", 8, List.of("Simulator", RPG), List.of("Fantasy"), List.of("farming"));
        GameDTO candidate = game(List.of("Adventure", "Indie"), List.of("Action"), List.of("mythology", "isometric"));

        Connection c = finder(List.of(hades, stardew), List.of(hades, stardew)).find(candidate);

        assertThat(c.seedIgdbId()).isEqualTo(1);
        assertThat(c.seedName()).isEqualTo("Hades");
        assertThat(c.seedRating()).isEqualTo(9);
        // Four features are shared; the three with the most profile weight are kept. Action loses
        // because Fantasy (also on Stardew Valley) sets the theme dimension's maximum above it.
        assertThat(c.sharedTags()).containsExactly("Adventure", "Indie", "mythology");
    }

    @Test
    void aSeedNeedsARatingOfSevenAndTwoSharedFeatures() {
        UserGameDTO lukewarm = rated(1, "Meh", 6, List.of("Adventure", "Indie"), List.of("Action"), List.of());
        UserGameDTO thin = rated(2, "One thing in common", 10, List.of("Adventure"), List.of(), List.of());
        GameDTO candidate = game(List.of("Adventure", "Indie"), List.of("Action"), List.of());

        Connection c = finder(List.of(lukewarm, thin), List.of(lukewarm, thin)).find(candidate);

        assertThat(c.seedIgdbId()).isNull();
        assertThat(c.seedName()).isNull();
        // Without a seed the shared features are the ones the profile weighs.
        assertThat(c.sharedTags()).containsExactlyInAnyOrder("Adventure", "Indie", "Action");
    }

    @Test
    void withoutASeedOnlyProfileWeightedFeaturesAreShared() {
        UserGameDTO rpg = rated(1, "Witcher", 9, List.of(RPG), List.of("Fantasy"), List.of());
        GameDTO candidate = game(List.of(RPG, "Sport"), List.of("Fantasy", "Comedy"), List.of("golf"));

        Connection c = finder(List.of(rpg), List.of(rpg)).find(candidate);

        assertThat(c.seedIgdbId()).isEqualTo(1);
        assertThat(c.sharedTags()).containsExactlyInAnyOrder(RPG, "Fantasy");

        UserGameDTO onlyOne = rated(2, "Golf Story", 9, List.of("Sport"), List.of(), List.of());
        Connection noSeed = finder(List.of(onlyOne), List.of(onlyOne)).find(candidate);
        assertThat(noSeed.seedIgdbId()).isNull();
        assertThat(noSeed.sharedTags()).containsExactly("Sport");
    }

    @Test
    void sharedFeaturesRankByProfileWeightAcrossDimensions() {
        UserProfile profile = profile(
                Map.of("Adventure", 1.0, "Indie", 4.0),
                Map.of("Action", 8.0),
                Map.of("mythology", 2.0));
        UserGameDTO seed = rated(1, "Hades", 9, List.of("Adventure", "Indie"), List.of("Action"), List.of("mythology"));
        GameDTO candidate = game(List.of("Adventure", "Indie"), List.of("Action"), List.of("mythology"));

        Connection c = new ConnectionFinder(List.of(seed), profile).find(candidate);

        // Each dimension is scaled by its own maximum: Action 1.0, Indie 1.0, mythology 1.0, Adventure 0.25.
        // Equal weights order genre, theme, tag.
        assertThat(c.sharedTags()).containsExactly("Indie", "Action", "mythology");
    }

    @Test
    void blockedKeywordsNeverBecomeTheReasonWhileSubGenreWordsDo() {
        UserGameDTO seed = rated(1, "Hades", 9, List.of("Indie"), List.of(), List.of("roguelike", "3d", "colorful"));
        GameDTO candidate = game(List.of("Indie"), List.of(), List.of("roguelike", "3d", "colorful"));

        Connection c = finder(List.of(seed), List.of(seed)).find(candidate);

        assertThat(c.seedIgdbId()).isEqualTo(1);
        assertThat(c.sharedTags()).containsExactlyInAnyOrder("Indie", "roguelike");
    }

    @Test
    void blockedKeywordsDoNotCountTowardTheOverlapThreshold() {
        UserGameDTO seed = rated(1, "Hades", 9, List.of("Indie"), List.of(), List.of("colorful", "3d"));
        GameDTO candidate = game(List.of("Indie"), List.of(), List.of("colorful", "3d"));

        Connection c = finder(List.of(seed), List.of(seed)).find(candidate);

        assertThat(c.seedIgdbId()).isNull();
        assertThat(c.sharedTags()).containsExactly("Indie");
    }

    @Test
    void keepsTheCatalogsCasingAndMatchesCaseInsensitively() {
        UserGameDTO seed = rated(1, "Persona 5", 10, List.of("Role-Playing (RPG)", "turn-based strategy (tbs)"), List.of(), List.of());
        GameDTO candidate = game(List.of(RPG, "Turn-based strategy (TBS)"), List.of(), List.of());

        Connection c = finder(List.of(seed), List.of(seed)).find(candidate);

        assertThat(c.seedIgdbId()).isEqualTo(1);
        assertThat(c.sharedTags()).containsExactlyInAnyOrder(RPG, "Turn-based strategy (TBS)");
    }

    @Test
    void tiesOnOverlapGoToTheHigherRating() {
        UserGameDTO eight = rated(1, "Eight", 8, List.of("Adventure", "Indie"), List.of(), List.of());
        UserGameDTO ten = rated(2, "Ten", 10, List.of("Adventure", "Indie"), List.of(), List.of());
        GameDTO candidate = game(List.of("Adventure", "Indie"), List.of(), List.of());

        Connection c = finder(List.of(eight, ten), List.of(eight, ten)).find(candidate);

        assertThat(c.seedName()).isEqualTo("Ten");
    }

    @Test
    void emptyProfileAndNoRatingsGiveNoConnection() {
        GameDTO candidate = game(List.of("Adventure"), List.of("Action"), List.of("mythology"));

        Connection c = new ConnectionFinder(List.of(), emptyProfile()).find(candidate);

        assertThat(c).isEqualTo(Connection.NONE);
        assertThat(c.sharedTags()).isEmpty();
    }

    @Test
    void sharedFeaturesOfTwoCatalogGamesOrderGenresThemesTags() {
        GameDTO a = game(List.of("Indie", "Adventure"), List.of("Action"), List.of("mythology", "roguelike"));
        GameDTO b = game(List.of("Adventure", "Indie"), List.of("Action"), List.of("mythology", "roguelike"));

        assertThat(ConnectionFinder.sharedFeatures(a, b)).containsExactly("Indie", "Adventure", "Action");
        assertThat(ConnectionFinder.sharedGenres(List.of("Indie", "Adventure", "Puzzle"), List.of("adventure", "Puzzle")))
                .containsExactly("Adventure", "Puzzle");
    }

    private static ConnectionFinder finder(List<UserGameDTO> rated, List<UserGameDTO> profileFrom) {
        return new ConnectionFinder(rated, UserProfileBuilder.buildMultiDim(profileFrom));
    }

    private static UserProfile profile(Map<String, Double> genres, Map<String, Double> themes, Map<String, Double> tags) {
        return new UserProfile(new HashMap<>(genres), new HashMap<>(themes), new HashMap<>(tags),
                new HashMap<>(), new HashMap<>(), Set.of(), 1);
    }

    private static UserProfile emptyProfile() {
        return new UserProfile(new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(), Set.of(), 0);
    }

    private static UserGameDTO rated(int igdbId, String name, int rating, List<String> genres, List<String> themes, List<String> tags) {
        UserGameDTO g = new UserGameDTO();
        g.setIgdbGameId(igdbId);
        g.setGameName(name);
        g.setRating(rating);
        g.setStatus("COMPLETED");
        g.setGenres(genres);
        g.setThemes(themes);
        g.setTags(tags);
        return g;
    }

    private static GameDTO game(List<String> genres, List<String> themes, List<String> tags) {
        GameDTO g = new GameDTO();
        g.setIgdbId(500);
        g.setName("Candidate");
        g.setGenres(genres);
        g.setThemes(themes);
        g.setTags(tags);
        return g;
    }
}
