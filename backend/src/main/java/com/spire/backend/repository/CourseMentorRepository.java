package com.spire.backend.repository;

import com.spire.backend.entity.CourseMentor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CourseMentorRepository extends JpaRepository<CourseMentor, Long> {
    List<CourseMentor> findByCourseIdAndIsActiveTrue(Long courseId);
    Optional<CourseMentor> findByCourseIdAndUserId(Long courseId, Long userId);
    boolean existsByCourseIdAndUserId(Long courseId, Long userId);
}
