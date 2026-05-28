package com.spire.backend.repository;

import com.spire.backend.entity.CoachingTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CoachingTaskRepository extends JpaRepository<CoachingTask, Long> {
    List<CoachingTask> findByCoachUserIdOrderByCreatedAtDesc(Long coachUserId);
    List<CoachingTask> findByParticipantUserIdOrderByCreatedAtDesc(Long participantUserId);
    List<CoachingTask> findByCoachUserIdAndParticipantUserIdOrderByCreatedAtDesc(
            Long coachUserId, Long participantUserId);
}
