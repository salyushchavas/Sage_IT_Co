package com.spire.backend.repository;

import com.spire.backend.entity.Task;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TaskRepository extends JpaRepository<Task, Long> {
    List<Task> findByLessonIdOrderByOrderIndex(Long lessonId);
}
