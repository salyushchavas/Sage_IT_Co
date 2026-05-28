package com.spire.backend.repository;

import com.spire.backend.entity.TaskProgress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TaskProgressRepository extends JpaRepository<TaskProgress, Long> {
    Optional<TaskProgress> findByUserIdAndTaskId(Long userId, Long taskId);
    boolean existsByUserIdAndTaskId(Long userId, Long taskId);
}
