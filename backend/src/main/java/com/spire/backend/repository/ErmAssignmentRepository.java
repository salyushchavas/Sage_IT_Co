package com.spire.backend.repository;

import com.spire.backend.entity.ErmAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ErmAssignmentRepository extends JpaRepository<ErmAssignment, Long> {
    Optional<ErmAssignment> findFirstByUserIdOrderByAssignedDateDesc(Long userId);
    List<ErmAssignment> findByErmUserId(Long ermUserId);
}
