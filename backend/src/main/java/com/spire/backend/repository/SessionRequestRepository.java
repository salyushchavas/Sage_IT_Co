package com.spire.backend.repository;

import com.spire.backend.entity.SessionRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SessionRequestRepository extends JpaRepository<SessionRequest, Long> {
    List<SessionRequest> findByMentorAssignmentIdOrderByRequestedAtDesc(Long mentorAssignmentId);
    List<SessionRequest> findByMentorAssignment_MentorIdAndStatus(Long mentorId, String status);
    List<SessionRequest> findByMentorAssignment_MentorIdOrderByRequestedAtDesc(Long mentorId);
    List<SessionRequest> findByRequestedByIdOrderByRequestedAtDesc(Long userId);
}
