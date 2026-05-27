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

import java.time.LocalDateTime;

@Entity
@Table(name = "compute_queue")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ComputeQueueEntry {

    @Id
    @Column(name = "user_id", length = 40, nullable = false)
    private String userId;

    @Column(name = "queued_at", nullable = false)
    private LocalDateTime queuedAt;

    @Column(name = "attempts", nullable = false)
    private Short attempts;

    // NULL = full pool replace. Set = per-genre top-up: worker only refreshes this bucket and
    // parks the displaced ids in pool_holding so the next batch is strictly fresh.
    @Column(name = "target_genre", length = 100)
    private String targetGenre;
}
