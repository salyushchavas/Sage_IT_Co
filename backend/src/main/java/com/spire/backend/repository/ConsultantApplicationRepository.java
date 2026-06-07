package com.spire.backend.repository;

import com.spire.backend.entity.ConsultantApplication;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ConsultantApplicationRepository
        extends JpaRepository<ConsultantApplication, Long> {

    Optional<ConsultantApplication> findByApplicationId(String applicationId);

    Page<ConsultantApplication> findByErmUserId(Long ermUserId, Pageable pageable);

    Page<ConsultantApplication> findByErmUserIdAndStatus(
            Long ermUserId, String status, Pageable pageable);

    // Phase B — per-ERM data isolation. Owner-scoped list queries; the
    // {@code = ?} predicate naturally excludes null-owner rows, so an
    // ERM never sees an unowned (super-admin-only) application.
    Page<ConsultantApplication> findByOwnerErmId(String ownerErmId, Pageable pageable);

    Page<ConsultantApplication> findByOwnerErmIdAndStatus(
            String ownerErmId, String status, Pageable pageable);

    // Super-admin status filter (sees all owners).
    Page<ConsultantApplication> findByStatus(String status, Pageable pageable);

    /** Cron sweep — find apps past their expiry that are still in flight. */
    List<ConsultantApplication> findByStatusInAndExpiresAtBefore(
            List<String> statuses, LocalDateTime cutoff);

    long countByErmUserIdAndStatus(Long ermUserId, String status);
}
