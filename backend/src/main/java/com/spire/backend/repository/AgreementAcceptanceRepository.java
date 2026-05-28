package com.spire.backend.repository;

import com.spire.backend.entity.AgreementAcceptance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AgreementAcceptanceRepository
        extends JpaRepository<AgreementAcceptance, Long> {

    Optional<AgreementAcceptance> findByUserId(Long userId);
}
