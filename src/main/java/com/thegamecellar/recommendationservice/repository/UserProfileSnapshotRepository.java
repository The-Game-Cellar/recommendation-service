package com.thegamecellar.recommendationservice.repository;

import com.thegamecellar.recommendationservice.model.entity.UserProfileSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface UserProfileSnapshotRepository extends JpaRepository<UserProfileSnapshot, String> {

    @Modifying
    @Query("DELETE FROM UserProfileSnapshot p WHERE p.userId = :userId")
    int deleteByUserId(@Param("userId") String userId);
}
