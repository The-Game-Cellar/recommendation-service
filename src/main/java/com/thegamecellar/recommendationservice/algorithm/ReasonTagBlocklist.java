package com.thegamecellar.recommendationservice.algorithm;

import java.util.Set;

// Keywords that never become the stated reason for a recommendation: words that say nothing
// about what kind of game it is, or that read badly in "Because you rated X 9 · ...". Sub-genre
// and mechanic words that identify a kind of game (roguelike, metroidvania, survival horror,
// permadeath) are deliberately not here, since they are the most telling reasons there are.
// Lowercase, matched case-insensitively. Genres and themes are curated catalog taxonomies and
// are never filtered.
public final class ReasonTagBlocklist {

    public static final Set<String> BLOCKED = Set.of(
            "2.5d", "3d", "8-bit", "a.i. companion", "abstract", "action-adventure",
            "adjustable difficulty", "artificial intelligence", "bloody", "bow and arrow",
            "breaking the fourth wall", "cartoony", "casual", "character creation",
            "character customization", "climbing", "collectibles", "colorful", "creepy",
            "customizable characters", "cute", "dark", "darkness", "day/night cycle",
            "deliberately retro", "destructible environment", "dialogue trees", "difficult",
            "difficulty level", "dual wielding", "emotional", "eroge", "experimental",
            "extreme violence", "fairy", "fast paced", "flight", "forest", "funny",
            "good vs evil", "gore", "grapple", "grid-based movement", "immersive",
            "leaderboard", "low-poly", "magic", "mercenary", "minimalist",
            "multiple protagonists", "murder", "nsfw", "nudity", "parody", "party system",
            "physics", "plot twist", "poisoning", "pve", "ragdoll physics", "real-time combat",
            "realism", "rivaling factions", "sexual content", "sexual themes", "shared screen",
            "shopping", "short", "side quests", "skeletons", "speedrun", "sprinting mechanics",
            "stylized", "swimming", "teleportation", "throwing weapons", "time limit", "undead",
            "underwater", "underwater gameplay", "unlockables", "upgradeable weapons", "violent",
            "world map"
    );

    private ReasonTagBlocklist() {}

    public static boolean blocks(String tag) {
        return tag != null && BLOCKED.contains(tag.trim().toLowerCase());
    }
}
