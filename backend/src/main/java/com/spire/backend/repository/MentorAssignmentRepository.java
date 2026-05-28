package com.spire.backend.repository;

import com.spire.backend.entity.MentorAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MentorAssignmentRepository extends JpaRepository<MentorAssignment, Long> {
    Optional<MentorAssignment> findByEnrollmentId(Long enrollmentId);
    List<MentorAssignment> findByMentorIdAndStatus(Long mentorId, String status);
    long countByMentorIdAndStatus(Long mentorId, String status);

    /**
     * Used by the retroactive-fill pass when a new mentor joins a
     * course pool — looks up every PENDING_ASSIGNMENT row scoped to
     * the course so those rows can be promoted to ACTIVE.
     *
     * Spring Data derives the JPQL from the property path
     * (enrollment.course.id + status); no @Query needed.
     */
    List<MentorAssignment> findByEnrollment_Course_IdAndStatus(Long courseId, String status);
}
