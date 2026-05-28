package com.spire.backend.repository;

import com.spire.backend.entity.Course;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;


@Repository
public interface CourseRepository extends JpaRepository<Course, Long> {

    List<Course> findByLevel(Course.Level level);

    List<Course> findByLevelAndIsPublished(Course.Level level, Boolean isPublished);

    List<Course> findByIsPublished(Boolean isPublished);

    Optional<Course> findBySlug(String slug);

    @Query("SELECT c FROM Course c WHERE c.isPublished = true AND (LOWER(c.title) LIKE LOWER(CONCAT('%', :query, '%')) OR LOWER(c.description) LIKE LOWER(CONCAT('%', :query, '%')))")
    List<Course> searchByTitle(@Param("query") String query);

    List<Course> findByCategory(String category);

    List<Course> findByInstructorId(Long instructorId);

    List<Course> findByTypeAndIsPublished(String type, Boolean isPublished);
}
