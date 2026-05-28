package com.spire.backend.repository;

import com.spire.backend.entity.WorkflowState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WorkflowStateRepository extends JpaRepository<WorkflowState, Long> {
    List<WorkflowState> findByUserIdOrderByCreatedAtAsc(Long userId);
}
