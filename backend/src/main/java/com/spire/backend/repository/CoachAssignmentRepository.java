package com.spire.backend.repository;

import com.spire.backend.entity.CoachAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CoachAssignmentRepository extends JpaRepository<CoachAssignment, Long> {
    List<CoachAssignment> findByUserIdAndStatus(Long userId, String status);
    List<CoachAssignment> findByCoachUserIdAndStatus(Long coachUserId, String status);
}
