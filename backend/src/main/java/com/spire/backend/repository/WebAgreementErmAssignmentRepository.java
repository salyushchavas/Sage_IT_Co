package com.spire.backend.repository;

import com.spire.backend.entity.WebAgreementErmAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * ERM approver teams. {@code role} is a plain String column (MANAGER or
 * ACCOUNTS). No delete methods on purpose: a link is removed by stamping
 * {@code removedAt}, and every rule reads active rows only.
 */
@Repository
public interface WebAgreementErmAssignmentRepository extends JpaRepository<WebAgreementErmAssignment, Long> {

    /** An ERM's active links for one role (the pickers, the team card). */
    List<WebAgreementErmAssignment> findByErmUserIdAndRoleAndRemovedAtIsNull(Long ermUserId, String role);

    /** The one row for this link, active or removed (re-activation). */
    Optional<WebAgreementErmAssignment> findByErmUserIdAndApproverUserIdAndRole(
            Long ermUserId, Long approverUserId, String role);

    /** Active links where the user is the ERM (the purge). */
    List<WebAgreementErmAssignment> findByErmUserIdAndRemovedAtIsNull(Long ermUserId);

    /** Active links where the user is the approver (the purge). */
    List<WebAgreementErmAssignment> findByApproverUserIdAndRemovedAtIsNull(Long approverUserId);
}
