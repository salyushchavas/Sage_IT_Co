package com.spire.backend.repository;

import com.spire.backend.entity.Enrollment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;


@Repository
public interface EnrollmentRepository extends JpaRepository<Enrollment, Long> {

    List<Enrollment> findByUserId(Long userId);

    Optional<Enrollment> findByUserIdAndCourseId(Long userId, Long courseId);

    boolean existsByUserIdAndCourseId(Long userId, Long courseId);

    long countByCourseId(Long courseId);

    @Query("""
            SELECT e FROM Enrollment e
            JOIN FETCH e.course c
            JOIN FETCH e.user u
            WHERE c.instructor.id = :instructorId
            ORDER BY e.enrolledAt DESC
            """)
    List<Enrollment> findByInstructorId(@Param("instructorId") Long instructorId);

    /**
     * Finds enrollments for a course that have no MentorAssignment
     * row at all. Different from PENDING_ASSIGNMENT (which means the
     * row exists but the mentor field is null).
     *
     * Both states show up in practice:
     *   - DataSeeder creates raw Enrollment rows for seed students,
     *     bypassing EnrollmentService.enrollUser — those don't get a
     *     sibling MentorAssignment.
     *   - Any enrollment created before the auto-assign code shipped
     *     (Step 2) is in the same boat.
     *
     * Service-type courses are excluded because services don't get
     * mentor assignments at all (PRODUCT.md). The retroactive fill
     * only makes sense for type='COURSE'.
     */
    @Query("""
            SELECT e FROM Enrollment e
            WHERE e.course.id = :courseId
              AND e.course.type = 'COURSE'
              AND NOT EXISTS (
                SELECT 1 FROM MentorAssignment a WHERE a.enrollment = e
              )
            """)
    List<Enrollment> findByCourseIdAndNoMentorAssignment(@Param("courseId") Long courseId);
}
