package com.spire.backend.service;

import com.spire.backend.entity.AgreementErmAssignment;
import com.spire.backend.entity.AgreementUser;
import com.spire.backend.entity.AgreementUserRole;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.repository.AgreementErmAssignmentRepository;
import com.spire.backend.repository.AgreementUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Build K — manages the super-admin-configured ERM ↔ approver assignments
 * (MANAGER / ACCOUNTS) and resolves the eligible approvers an ERM may route
 * an agreement to. Many-to-many; assignments are editable add/remove.
 */
@Service
@RequiredArgsConstructor
public class AgreementAssignmentService {

    private final AgreementErmAssignmentRepository assignmentRepository;
    private final AgreementUserRepository userRepository;

    /** The approver users currently assigned to an ERM for a role (active only, by name). */
    @Transactional(readOnly = true)
    public List<AgreementUser> assignedApprovers(String ermUserId, AgreementUserRole role) {
        List<AgreementErmAssignment> rows =
                assignmentRepository.findByErmUserIdAndRole(ermUserId, role);
        List<AgreementUser> out = new ArrayList<>();
        for (AgreementErmAssignment a : rows) {
            userRepository.findById(a.getApproverUserId())
                    .filter(u -> u.isActive() && u.getRole() == role)
                    .ifPresent(out::add);
        }
        out.sort((x, y) -> {
            String nx = x.getFullName() == null ? "" : x.getFullName();
            String ny = y.getFullName() == null ? "" : y.getFullName();
            return nx.compareToIgnoreCase(ny);
        });
        return out;
    }

    /** The set of approver user-ids assigned to an ERM for a role (includes inactive). */
    @Transactional(readOnly = true)
    public Set<String> assignedApproverIds(String ermUserId, AgreementUserRole role) {
        Set<String> ids = new LinkedHashSet<>();
        for (AgreementErmAssignment a : assignmentRepository.findByErmUserIdAndRole(ermUserId, role)) {
            ids.add(a.getApproverUserId());
        }
        return ids;
    }

    /**
     * Replace the ERM's assignments for one role with the given approver
     * ids. Validates the ERM + every approver id (must exist + hold the
     * matching role). Idempotent.
     */
    @Transactional
    public void replaceAssignments(String ermUserId, AgreementUserRole role, List<String> approverIds) {
        AgreementUser erm = userRepository.findById(ermUserId)
                .orElseThrow(() -> new ResourceNotFoundException("AgreementUser", "id", ermUserId));
        if (erm.getRole() != AgreementUserRole.ERM) {
            throw new IllegalArgumentException("Assignments can only be set for ERM users.");
        }
        List<String> clean = approverIds == null ? List.of()
                : approverIds.stream().filter(s -> s != null && !s.isBlank()).distinct().toList();
        for (String id : clean) {
            AgreementUser u = userRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown user in assignment: " + id));
            if (u.getRole() != role) {
                throw new IllegalArgumentException(
                        "User " + u.getEmail() + " is not a " + role + ".");
            }
            if (!u.isActive()) {
                throw new IllegalArgumentException(
                        "User " + u.getEmail() + " is not active and cannot be assigned.");
            }
        }
        assignmentRepository.deleteByErmUserIdAndRole(ermUserId, role);
        for (String id : clean) {
            assignmentRepository.save(AgreementErmAssignment.builder()
                    .ermUserId(ermUserId)
                    .approverUserId(id)
                    .role(role)
                    .build());
        }
    }
}
