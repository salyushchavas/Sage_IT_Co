package com.spire.backend.repository;

import com.spire.backend.entity.AgreementErmAssignment;
import com.spire.backend.entity.AgreementUserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Build K — ERM ↔ approver (MANAGER / ACCOUNTS) assignments.
 */
public interface AgreementErmAssignmentRepository
        extends JpaRepository<AgreementErmAssignment, Long> {

    List<AgreementErmAssignment> findByErmUserId(String ermUserId);

    List<AgreementErmAssignment> findByErmUserIdAndRole(String ermUserId, AgreementUserRole role);

    @Transactional
    void deleteByErmUserIdAndRole(String ermUserId, AgreementUserRole role);
}
