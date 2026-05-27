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

import java.time.LocalDateTime;

// Recently-served candidate ids parked here when a per-genre top-up runs. Worker excludes
// holding-ids when fetching the new batch so the user does not see the same set twice in a
// row. released_at marks when an entry can be pruned and the catalog game becomes eligible
// to resurface in a future fetch.
@Entity
@Table(name = "pool_holding")
@IdClass(PoolHoldingId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PoolHolding {

    @Id
    @Column(name = "user_id", length = 40, nullable = false)
    private String userId;

    @Id
    @Column(name = "genre", length = 100, nullable = false)
    private String genre;

    @Id
    @Column(name = "igdb_id", nullable = false)
    private Integer igdbId;

    @Column(name = "released_at", nullable = false)
    private LocalDateTime releasedAt;
}
