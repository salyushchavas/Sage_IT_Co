package com.spire.backend.repository;

import com.spire.backend.entity.CoachingSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CoachingSessionRepository extends JpaRepository<CoachingSession, Long> {
    List<CoachingSession> findByCoachUserIdOrderByCreatedAtDesc(Long coachUserId);
    List<CoachingSession> findByParticipantUserIdOrderByCreatedAtDesc(Long participantUserId);
    List<CoachingSession> findByCoachUserIdAndParticipantUserIdOrderByCreatedAtDesc(
            Long coachUserId, Long participantUserId);
}
