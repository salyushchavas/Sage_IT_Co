package com.spire.backend.repository;

import com.spire.backend.entity.ProgramSelection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProgramSelectionRepository extends JpaRepository<ProgramSelection, Long> {
    List<ProgramSelection> findByUserIdOrderBySelectionDateDesc(Long userId);
    Optional<ProgramSelection> findFirstByUserIdOrderBySelectionDateDesc(Long userId);
}
