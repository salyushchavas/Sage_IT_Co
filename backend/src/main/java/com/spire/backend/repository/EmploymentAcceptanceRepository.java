package com.spire.backend.repository;

import com.spire.backend.entity.EmploymentAcceptance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EmploymentAcceptanceRepository extends JpaRepository<EmploymentAcceptance, Long> {
    List<EmploymentAcceptance> findByUserIdOrderByAcceptanceDateDesc(Long userId);
    Optional<EmploymentAcceptance> findFirstByUserIdAndErmVerifiedTrueOrderByErmVerifiedDateDesc(Long userId);
}
