package com.thegamecellar.recommendationservice.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

// Distinct name from algorithm.UserProfile (pure-data scoring input). This entity is the
// persisted snapshot the per-user worker writes after each compute so downstream caches and
// event-driven invalidation paths can read it without rebuilding the profile.
@Entity
@Table(name = "user_profiles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserProfileSnapshot {

    @Id
    @Column(name = "user_id", length = 40, nullable = false)
    private String userId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "genre_weights", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Double> genreWeights = new HashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tag_weights", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Double> tagWeights = new HashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "platform_weights", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Double> platformWeights = new HashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "decade_weights", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Double> decadeWeights = new HashMap<>();

    // Raw count per genre across the whole library (each game contributes +1 per genre on it).
    // Drives /recommendations row order so it matches /profile/statistics. Distinct from
    // genreWeights which is rating-weighted and drives scoring inside the algorithm.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "library_genre_counts", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Double> libraryGenreCounts = new HashMap<>();

    @Column(name = "rated_count", nullable = false)
    private Integer ratedCount;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
