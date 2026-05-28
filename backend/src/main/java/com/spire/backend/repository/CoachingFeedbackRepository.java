package com.spire.backend.repository;

import com.spire.backend.entity.CoachingFeedback;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CoachingFeedbackRepository extends JpaRepository<CoachingFeedback, Long> {
    List<CoachingFeedback> findByCoachUserIdOrderByCreatedAtDesc(Long coachUserId);
    List<CoachingFeedback> findByParticipantUserIdOrderByCreatedAtDesc(Long participantUserId);
    List<CoachingFeedback> findByCoachUserIdAndParticipantUserIdOrderByCreatedAtDesc(
            Long coachUserId, Long participantUserId);
}
