package com.thegamecellar.recommendationservice.repository;

import com.thegamecellar.recommendationservice.model.entity.PoolHolding;
import com.thegamecellar.recommendationservice.model.entity.PoolHoldingId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface PoolHoldingRepository extends JpaRepository<PoolHolding, PoolHoldingId> {

    List<PoolHolding> findByUserId(String userId);

    @Query("SELECT h.igdbId FROM PoolHolding h WHERE h.userId = :userId AND h.genre = :genre")
    List<Integer> findIgdbIdsForUserGenre(@Param("userId") String userId, @Param("genre") String genre);

    @Modifying
    @Query("DELETE FROM PoolHolding h WHERE h.releasedAt < :cutoff")
    int deleteExpired(@Param("cutoff") LocalDateTime cutoff);

    @Modifying
    @Query("DELETE FROM PoolHolding h WHERE h.userId = :userId")
    int deleteByUserId(@Param("userId") String userId);
}
