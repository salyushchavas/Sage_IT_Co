package com.spire.backend.service;

import com.spire.backend.entity.User;
import com.spire.backend.entity.WebAgreementApproval;
import com.spire.backend.entity.WebAgreementErmAssignment;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.repository.UserRepository;
import com.spire.backend.repository.WebAgreementApprovalRepository;
import com.spire.backend.repository.WebAgreementErmAssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * The approver team of each website ERM, the website copy of the console's
 * {@link AgreementAssignmentService} (untouched): the Managers and Accounts
 * approvers an ERM may route an agreement to, set by the System Admin on the
 * ERM's user page. Many-to-many: an ERM can have several of each, and an
 * approver can serve several ERMs. Only ERM users have a team.
 *
 * Rows are never deleted (the console deletes them): a team edit or a purge
 * stamps removedAt/removedBy on the links that go, and adding a link again
 * re-activates its old row. Every read uses active links only, so what
 * everyone sees is the console's end state.
 */
@Service
@RequiredArgsConstructor
public class WebAgreementAssignmentService {

    private static final String MANAGER = WebAgreementApproval.ApproverRole.MANAGER.name();
    private static final String ACCOUNTS = WebAgreementApproval.ApproverRole.ACCOUNTS.name();
    private static final String PENDING = WebAgreementApproval.Decision.PENDING.name();

    private final WebAgreementErmAssignmentRepository assignmentRepository;
    private final WebAgreementApprovalRepository approvalRepository;
    private final UserRepository userRepository;
    private final WebAgreementAccess access;

    /** An ERM's team as the System Admin's card and the ERM's pickers see it: active approvers only. */
    public record Team(List<Long> managerIds, List<Long> accountsIds) {}

    /**
     * The approvers linked to an ERM for a role who are still active and
     * still hold that role, sorted by name (console AAS:35-50). These are
     * the only people the ERM's pickers offer.
     */
    @Transactional(readOnly = true)
    public List<User> assignedApprovers(Long ermUserId, String role) {
        String r = approverRole(role);
        List<User> out = new ArrayList<>();
        for (WebAgreementErmAssignment a : assignmentRepository.findByErmUserIdAndRoleAndRemovedAtIsNull(ermUserId, r)) {
            userRepository.findById(a.getApproverUserId())
                    .filter(u -> !Boolean.FALSE.equals(u.getIsActive()) && r.equals(WebAgreementAccess.roleOf(u)))
                    .ifPresent(out::add);
        }
        out.sort((x, y) -> {
            String nx = x.getFullName() == null ? "" : x.getFullName();
            String ny = y.getFullName() == null ? "" : y.getFullName();
            return nx.compareToIgnoreCase(ny);
        });
        return out;
    }

    /**
     * Every approver id linked to an ERM for a role, deactivated or re-roled
     * ones included (console AAS:53-60): tells "no longer active" apart from
     * "never assigned".
     */
    @Transactional(readOnly = true)
    public Set<Long> assignedApproverIds(Long ermUserId, String role) {
        String r = approverRole(role);
        Set<Long> ids = new LinkedHashSet<>();
        for (WebAgreementErmAssignment a : assignmentRepository.findByErmUserIdAndRoleAndRemovedAtIsNull(ermUserId, r)) {
            ids.add(a.getApproverUserId());
        }
        return ids;
    }

    /**
     * Replaces an ERM's team for one role with the given approver ids
     * (console AAS:67-97). The ERM must exist (404) and be an ERM; every id
     * must exist, hold the role and be active (400 with the console's
     * messages). Idempotent.
     *
     * The console deletes the role's rows and inserts the new set. Same end
     * state here without deleting: links not in the new set are marked
     * removed; ids in it re-activate their old row or get a new one.
     */
    @Transactional
    public void replaceAssignments(Long ermUserId, String role, List<Long> approverIds, Long callerId) {
        String r = approverRole(role);
        User erm = userRepository.findById(ermUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", ermUserId));
        if (!"ERM".equals(WebAgreementAccess.roleOf(erm))) {
            throw new IllegalArgumentException("Assignments can only be set for ERM users.");
        }
        List<Long> clean = approverIds == null ? List.of()
                : approverIds.stream().filter(Objects::nonNull).distinct().toList();
        for (Long id : clean) {
            User u = userRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown user in assignment: " + id));
            if (!r.equals(WebAgreementAccess.roleOf(u))) {
                throw new IllegalArgumentException("User " + u.getEmail() + " is not a " + r + ".");
            }
            if (Boolean.FALSE.equals(u.getIsActive())) {
                throw new IllegalArgumentException("User " + u.getEmail() + " is not active and cannot be assigned.");
            }
        }

        LocalDateTime now = LocalDateTime.now();
        for (WebAgreementErmAssignment a : assignmentRepository.findByErmUserIdAndRoleAndRemovedAtIsNull(ermUserId, r)) {
            if (!clean.contains(a.getApproverUserId())) {
                a.setRemovedAt(now);
                a.setRemovedBy(callerId);
                assignmentRepository.save(a);
            }
        }
        for (Long id : clean) {
            WebAgreementErmAssignment a = assignmentRepository
                    .findByErmUserIdAndApproverUserIdAndRole(ermUserId, id, r).orElse(null);
            if (a == null) {
                assignmentRepository.save(WebAgreementErmAssignment.builder()
                        .ermUserId(ermUserId)
                        .approverUserId(id)
                        .role(r)
                        .createdBy(callerId)
                        .build());
            } else if (a.getRemovedAt() != null) {
                a.setRemovedAt(null);
                a.setRemovedBy(null);
                assignmentRepository.save(a);
            }
        }
    }

    /**
     * After a role change or a delete (console AAS:105-116): every active
     * link involving the user, as the ERM or as an approver, is marked
     * removed, and every PENDING gate routed to them is un-routed (role-wide
     * again, so any approver of that role can decide it). The console blanks
     * the routing; here it is first copied into the row's unrouted* history
     * columns. Never called on deactivate. Runs in the caller's transaction.
     */
    @Transactional
    public void purgeUserLinks(Long userId, Long callerId) {
        if (userId == null) return;
        LocalDateTime now = LocalDateTime.now();
        List<WebAgreementErmAssignment> links = new ArrayList<>(assignmentRepository.findByErmUserIdAndRemovedAtIsNull(userId));
        links.addAll(assignmentRepository.findByApproverUserIdAndRemovedAtIsNull(userId));
        for (WebAgreementErmAssignment a : links) {
            if (a.getRemovedAt() != null) continue;
            a.setRemovedAt(now);
            a.setRemovedBy(callerId);
            assignmentRepository.save(a);
        }
        for (WebAgreementApproval row : approvalRepository.findByApproverUserIdAndStatus(userId, PENDING)) {
            row.setUnroutedFromUserId(row.getApproverUserId());
            row.setUnroutedFromName(row.getApproverName());
            row.setUnroutedAt(now);
            row.setApproverUserId(null);
            row.setApproverName(null);
            approvalRepository.save(row);
        }
    }

    // ── The System Admin's team endpoints (console ADC:414-450) ────────

    /** An ERM's active team (System Admin only): 404 for an unknown user, 400 for a non-ERM. */
    @Transactional(readOnly = true)
    public Team team(Long ermUserId, Long callerId) {
        access.requireSystemAdmin(callerId);
        User erm = userRepository.findById(ermUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", ermUserId));
        if (!"ERM".equals(WebAgreementAccess.roleOf(erm))) {
            throw new IllegalArgumentException("Assignments only apply to ERM users.");
        }
        return activeTeam(ermUserId);
    }

    /** Replaces both roles of an ERM's team in one transaction (System Admin only); returns the active team. */
    @Transactional
    public Team replaceTeam(Long ermUserId, List<Long> managerIds, List<Long> accountsIds, Long callerId) {
        access.requireSystemAdmin(callerId);
        replaceAssignments(ermUserId, MANAGER, managerIds, callerId);
        replaceAssignments(ermUserId, ACCOUNTS, accountsIds, callerId);
        return activeTeam(ermUserId);
    }

    /** Active approvers only, so the admin's view matches the ERM's pickers. */
    private Team activeTeam(Long ermUserId) {
        return new Team(
                assignedApprovers(ermUserId, MANAGER).stream().map(User::getId).toList(),
                assignedApprovers(ermUserId, ACCOUNTS).stream().map(User::getId).toList());
    }

    /** MANAGER or ACCOUNTS, upper-cased; 400 for anything else. */
    private static String approverRole(String role) {
        String r = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
        if (!MANAGER.equals(r) && !ACCOUNTS.equals(r)) {
            throw new IllegalArgumentException("Role must be MANAGER or ACCOUNTS.");
        }
        return r;
    }
}
