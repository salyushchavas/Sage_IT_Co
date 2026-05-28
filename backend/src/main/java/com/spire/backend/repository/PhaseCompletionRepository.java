package com.spire.backend.repository;

import com.spire.backend.entity.PhaseCompletion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PhaseCompletionRepository extends JpaRepository<PhaseCompletion, Long> {
    List<PhaseCompletion> findByUserId(Long userId);
    Optional<PhaseCompletion> findByUserIdAndPhase(Long userId, String phase);
}
