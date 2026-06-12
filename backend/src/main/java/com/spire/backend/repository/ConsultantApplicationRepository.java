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

    // Phase C — archive (soft delete) excluded from every console list.
    // After the DataSeeder backfill, `deleted = false` matches all live
    // rows; archived rows (deleted = true) are dropped.
    Page<ConsultantApplication> findByOwnerErmIdAndDeletedFalse(
            String ownerErmId, Pageable pageable);

    Page<ConsultantApplication> findByOwnerErmIdAndStatusAndDeletedFalse(
            String ownerErmId, String status, Pageable pageable);

    Page<ConsultantApplication> findByDeletedFalse(Pageable pageable);

    Page<ConsultantApplication> findByStatusAndDeletedFalse(String status, Pageable pageable);

    /** Admin "Agreements by ERM" grouped view — all live rows, newest first. */
    List<ConsultantApplication> findByDeletedFalseOrderByCreatedAtDesc();

    // Portal phase — every live agreement addressed to a given consultant
    // email (case-insensitive). Backs the dashboard list endpoint + the
    // request-otp probe that decides whether to send a code.
    List<ConsultantApplication>
            findByConsultantEmailIgnoreCaseAndDeletedFalseOrderByCreatedAtDesc(
                    String consultantEmail);

    /** Cron sweep — find apps past their expiry that are still in flight. */
    List<ConsultantApplication> findByStatusInAndExpiresAtBefore(
            List<String> statuses, LocalDateTime cutoff);

    /** Build L — sweep apps whose invite is past the 15-day window. */
    List<ConsultantApplication> findByStatusInAndInviteSentAtBefore(
            List<String> statuses, LocalDateTime cutoff);

    long countByErmUserIdAndStatus(Long ermUserId, String status);

    /** Build K2 — live agreements owned by an ERM (delete-user safety check). */
    long countByOwnerErmIdAndDeletedFalse(String ownerErmId);

    // 3B — approval status boards. All live rows in the given statuses
    // (super-admin) or just the owning ERM's (per-ERM isolation), newest
    // activity first.
    List<ConsultantApplication>
            findByStatusInAndDeletedFalseOrderByUpdatedAtDesc(List<String> statuses);

    List<ConsultantApplication>
            findByOwnerErmIdAndStatusInAndDeletedFalseOrderByUpdatedAtDesc(
                    String ownerErmId, List<String> statuses);

    // Phase 2 (Cloudinary→S3 migration) — candidates: records whose FINAL
    // PDF still lives on Cloudinary (public_id set) but isn't yet in S3
    // (s3_key null). Deleted rows are intentionally included: the Cloudinary
    // object exists regardless of soft-delete, so its s3_key should migrate
    // too. Idempotent: once s3_key is set the row drops out of this set.
    List<ConsultantApplication> findByS3KeyIsNullAndFinalPdfPublicIdIsNotNull();

    // Phase 2 — candidates: records whose CONSULTANT-VERSION PDF (consultant
    // copy + Certificate of Completion) still lives on Cloudinary but isn't
    // yet in S3.
    List<ConsultantApplication>
            findByConsultantPdfS3KeyIsNullAndConsultantPdfPublicIdIsNotNull();
}
