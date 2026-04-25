package com.thegamecellar.recommendationservice.algorithm;

import com.thegamecellar.recommendationservice.model.dto.game.GameDTO;
import com.thegamecellar.recommendationservice.model.dto.library.UserGameDTO;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class UserProfileBuilder {

    private UserProfileBuilder() {}

    public static Map<String, Double> build(List<UserGameDTO> ratedGames, Map<Integer, GameDTO> gameDetails) {
        Map<String, List<Integer>> genreRatings = new HashMap<>();

        for (UserGameDTO ratedGame : ratedGames) {
            GameDTO details = gameDetails.get(ratedGame.getIgdbGameId());
            if (details == null || details.getGenres() == null) {
                continue;
            }
            for (String genre : details.getGenres()) {
                genreRatings.computeIfAbsent(genre, k -> new ArrayList<>()).add(ratedGame.getRating());
            }
        }

        Map<String, Double> profile = new HashMap<>();
        for (Map.Entry<String, List<Integer>> entry : genreRatings.entrySet()) {
            double avg = entry.getValue().stream()
                    .mapToInt(Integer::intValue)
                    .average()
                    .orElse(0.0);
            profile.put(entry.getKey(), avg);
        }
        return profile;
    }
}
