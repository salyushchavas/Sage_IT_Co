package com.spire.backend.repository;

import com.spire.backend.entity.WebAgreementApproval;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Approver gates on website agreements (the console's
 * {@code AgreementApprovalRepository}). Role and status are plain String
 * columns: pass {@code ApproverRole.X.name()} / {@code Decision.X.name()},
 * never the enum. Rows are never deleted.
 */
@Repository
public interface WebAgreementApprovalRepository extends JpaRepository<WebAgreementApproval, Long> {

    /** Every gate of one agreement, oldest first (ERM detail, board, certificate). */
    List<WebAgreementApproval> findByAgreementIdOrderByCreatedAtAsc(Long agreementId);

    /** Every gate across a page of agreements (list summary columns). */
    List<WebAgreementApproval> findByAgreementIdIn(Collection<Long> agreementIds);

    /** Highest round recorded for an agreement (null if it was never sent). */
    @Query("select max(a.round) from WebAgreementApproval a where a.agreementId = :agreementId")
    Integer maxRound(@Param("agreementId") Long agreementId);

    /** {@link #maxRound} for many agreements in one query: rows of [agreementId, max round]. */
    @Query("select a.agreementId, max(a.round) from WebAgreementApproval a"
            + " where a.agreementId in :agreementIds group by a.agreementId")
    List<Object[]> maxRounds(@Param("agreementIds") Collection<Long> agreementIds);

    /** The gate row for (agreement, role, round), if present. */
    Optional<WebAgreementApproval> findFirstByAgreementIdAndRoleAndRound(
            Long agreementId, String role, Integer round);

    /** Every gate of one round of an agreement. */
    List<WebAgreementApproval> findByAgreementIdAndRound(Long agreementId, Integer round);

    /** All rows in one decision state for a role (the approver queue). */
    List<WebAgreementApproval> findByStatusAndRole(String status, String role);

    /** Every row (any round or status) routed to one approver in a role ("All agreements"). */
    List<WebAgreementApproval> findByRoleAndApproverUserId(String role, Long approverUserId);

    /** Was this approver ever routed this agreement in this role (any round)? */
    boolean existsByAgreementIdAndRoleAndApproverUserId(
            Long agreementId, String role, Long approverUserId);

    /** Did this user decide this agreement's gate with this status in this role (any round)? */
    boolean existsByAgreementIdAndRoleAndStatusAndDecidedBy(
            Long agreementId, String role, String status, Long decidedBy);

    /** An approver's own decisions in a role, newest first ("Approved agreements"). */
    List<WebAgreementApproval> findByStatusAndRoleAndDecidedByOrderByDecidedAtDesc(
            String status, String role, Long decidedBy);

    /** Gates in a state routed to one user (PENDING ones are un-routed by a purge). */
    List<WebAgreementApproval> findByApproverUserIdAndStatus(Long approverUserId, String status);
}
