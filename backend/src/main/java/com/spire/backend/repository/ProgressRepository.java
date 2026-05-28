package com.spire.backend.repository;

import com.spire.backend.entity.Progress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProgressRepository extends JpaRepository<Progress, Long> {

    List<Progress> findByUserId(Long userId);

    List<Progress> findByUserIdAndCourseId(Long userId, Long courseId);

    Optional<Progress> findByUserIdAndLessonId(Long userId, Long lessonId);

    boolean existsByUserIdAndLessonIdAndCompletedTrue(Long userId, Long lessonId);

    @Query("SELECT COUNT(p) FROM Progress p WHERE p.user.id = :userId AND p.course.id = :courseId AND p.completed = true")
    long countCompletedLessons(@Param("userId") Long userId, @Param("courseId") Long courseId);
}
