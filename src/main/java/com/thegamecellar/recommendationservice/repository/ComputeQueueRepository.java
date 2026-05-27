package com.thegamecellar.recommendationservice.repository;

import com.thegamecellar.recommendationservice.model.entity.ComputeQueueEntry;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ComputeQueueRepository extends JpaRepository<ComputeQueueEntry, String> {

    // SKIP LOCKED keeps concurrent workers from blocking on each other (future horizontal scaling).
    // Hibernate translates @Lock PESSIMISTIC_WRITE + jakarta.persistence.lock.timeout = -2 to
    // SELECT ... FOR UPDATE SKIP LOCKED on Postgres.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")})
    @Query("SELECT q FROM ComputeQueueEntry q ORDER BY q.queuedAt ASC")
    List<ComputeQueueEntry> dequeueBatch(org.springframework.data.domain.Pageable pageable);

    @Modifying
    @Query(value = """
            INSERT INTO compute_queue (user_id, queued_at, attempts, target_genre)
            VALUES (:userId, :queuedAt, 0, NULL)
            ON CONFLICT (user_id) DO UPDATE SET queued_at = EXCLUDED.queued_at,
                                                target_genre = NULL
            """, nativeQuery = true)
    void upsert(@Param("userId") String userId,
                @Param("queuedAt") java.time.LocalDateTime queuedAt);

    // Per-genre top-up upsert. If a full-replace upsert is already queued for the user, we
    // keep that (target_genre stays NULL) since the full replace will cover the genre anyway.
    @Modifying
    @Query(value = """
            INSERT INTO compute_queue (user_id, queued_at, attempts, target_genre)
            VALUES (:userId, :queuedAt, 0, :genre)
            ON CONFLICT (user_id) DO UPDATE SET queued_at = EXCLUDED.queued_at,
                                                target_genre = CASE
                                                    WHEN compute_queue.target_genre IS NULL THEN NULL
                                                    ELSE EXCLUDED.target_genre
                                                END
            """, nativeQuery = true)
    void upsertGenre(@Param("userId") String userId,
                     @Param("queuedAt") java.time.LocalDateTime queuedAt,
                     @Param("genre") String genre);

    @Modifying
    @Query("UPDATE ComputeQueueEntry q SET q.attempts = q.attempts + 1 WHERE q.userId = :userId")
    void incrementAttempts(@Param("userId") String userId);
}
