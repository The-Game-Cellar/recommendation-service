package com.thegamecellar.recommendationservice.repository;

import com.thegamecellar.recommendationservice.model.entity.UserProfileSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserProfileSnapshotRepository extends JpaRepository<UserProfileSnapshot, String> {
}
