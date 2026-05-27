package com.thegamecellar.recommendationservice.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

// Row per (user, game) candidate. Denormalized game data (name, cover, rating, genres,
// platforms) rides along so the request path can build RecommendationDTO without a
// game-service round-trip. Worker writes 1000 rows per user; request path applies
// jitter + shown-penalty + MMR in-memory.
@Entity
@Table(name = "user_candidate_pool")
@IdClass(UserCandidatePoolId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserCandidatePool {

    @Id
    @Column(name = "user_id", length = 40, nullable = false)
    private String userId;

    @Id
    @Column(name = "igdb_id", nullable = false)
    private Integer igdbId;

    @Column(name = "base_score", nullable = false, precision = 5, scale = 4)
    private BigDecimal baseScore;

    @Column(name = "tier", nullable = false)
    private Short tier;

    @Column(name = "name", nullable = false, columnDefinition = "TEXT")
    private String name;

    @Column(name = "background_image", columnDefinition = "TEXT")
    private String backgroundImage;

    @Column(name = "rating", precision = 4, scale = 2)
    private BigDecimal rating;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "genres", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<String> genres = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "platforms", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<String> platforms = new ArrayList<>();

    @Column(name = "computed_at", nullable = false)
    private LocalDateTime computedAt;
}
