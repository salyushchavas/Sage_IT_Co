package com.spire.backend.repository;

import com.spire.backend.entity.AgreementApproval;
import com.spire.backend.entity.AgreementApproval.ApproverRole;
import com.spire.backend.entity.AgreementApproval.Decision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 3B — per-approver gate records for consultant agreements.
 */
public interface AgreementApprovalRepository
        extends JpaRepository<AgreementApproval, Long> {

    /** Full decision history for an application (oldest first) — detail + certificate. */
    List<AgreementApproval> findByApplicationIdOrderByCreatedAtAsc(Long applicationId);

    /** Every approval row for a specific round of an application. */
    List<AgreementApproval> findByApplicationIdAndRound(Long applicationId, Integer round);

    /** The single gate row for (application, role, round), if present. */
    Optional<AgreementApproval> findFirstByApplicationIdAndRoleAndRound(
            Long applicationId, ApproverRole role, Integer round);

    /** All rows in a given decision state for a role — drives the approver queue. */
    List<AgreementApproval> findByStatusAndRole(Decision status, ApproverRole role);

    /** Build K2 — pending gates routed to a user being re-roled/deleted (to un-route). */
    List<AgreementApproval> findByApproverUserIdAndStatus(String approverUserId, Decision status);

    /** Build L — an approver's own approved decisions for a role (newest first). */
    List<AgreementApproval> findByStatusAndRoleAndDecidedByOrderByDecidedAtDesc(
            Decision status, ApproverRole role, String decidedBy);

    /** Highest round number recorded for an application (null if none). */
    @Query("select max(a.round) from AgreementApproval a where a.applicationId = :appId")
    Integer maxRound(@Param("appId") Long appId);
}
