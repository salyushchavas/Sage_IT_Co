package com.spire.backend.repository;

import com.spire.backend.entity.AgreementRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AgreementRequestRepository extends JpaRepository<AgreementRequest, Long> {
    Optional<AgreementRequest> findByUserId(Long userId);
    List<AgreementRequest> findAllByOrderByRequestedAtAsc();

    /** The request row, locked: website agreement starts for one participant take turns on it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AgreementRequest> findForUpdateByUserId(Long userId);
}
