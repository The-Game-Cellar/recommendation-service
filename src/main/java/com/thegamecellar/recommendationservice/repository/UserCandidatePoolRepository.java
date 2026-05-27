package com.thegamecellar.recommendationservice.repository;

import com.thegamecellar.recommendationservice.model.entity.UserCandidatePool;
import com.thegamecellar.recommendationservice.model.entity.UserCandidatePoolId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface UserCandidatePoolRepository extends JpaRepository<UserCandidatePool, UserCandidatePoolId> {

    List<UserCandidatePool> findByUserId(String userId);

    @Modifying
    @Query("DELETE FROM UserCandidatePool p WHERE p.userId = :userId")
    int deleteByUserId(@Param("userId") String userId);

    boolean existsByUserId(String userId);

    // Users whose newest pool row is older than the cutoff. Used by the hourly safety-net to
    // re-enqueue users who never triggered a request-path stale check.
    @Query("SELECT p.userId FROM UserCandidatePool p GROUP BY p.userId HAVING MAX(p.computedAt) < :cutoff")
    List<String> findStaleUserIds(@Param("cutoff") LocalDateTime cutoff);
}
