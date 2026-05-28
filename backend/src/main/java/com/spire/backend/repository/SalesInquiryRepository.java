package com.spire.backend.repository;

import com.spire.backend.entity.SalesInquiry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SalesInquiryRepository extends JpaRepository<SalesInquiry, Long> {

    List<SalesInquiry> findByUserIdOrderByUpdatedAtDesc(Long userId);

    @Query("SELECT i FROM SalesInquiry i WHERE i.course.instructor.id = :instructorId " +
           "ORDER BY i.updatedAt DESC")
    List<SalesInquiry> findForInstructor(@Param("instructorId") Long instructorId);

    @Query("SELECT i FROM SalesInquiry i ORDER BY i.updatedAt DESC")
    List<SalesInquiry> findAllOrdered();

    /**
     * Used by the create-inquiry flow to enforce the "one open inquiry
     * per student per course" rule. Open = not in a terminal state.
     */
    @Query("SELECT i FROM SalesInquiry i WHERE i.user.id = :userId AND i.course.id = :courseId " +
           "AND i.status NOT IN ('CONVERTED', 'CLOSED', 'LOST')")
    Optional<SalesInquiry> findActiveForUserAndCourse(@Param("userId") Long userId,
                                                       @Param("courseId") Long courseId);

    long countByStatus(String status);
}
