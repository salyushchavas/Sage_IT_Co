package com.spire.backend.repository;

import com.spire.backend.entity.AgreementRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AgreementRequestRepository extends JpaRepository<AgreementRequest, Long> {
    Optional<AgreementRequest> findByUserId(Long userId);
    List<AgreementRequest> findAllByOrderByRequestedAtAsc();
}
