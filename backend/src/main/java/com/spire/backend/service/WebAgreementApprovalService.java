package com.spire.backend.service;

import com.spire.backend.entity.User;
import com.spire.backend.entity.WebAgreement;
import com.spire.backend.entity.WebAgreementApproval;
import com.spire.backend.entity.WebAgreementEvent;
import com.spire.backend.entity.WebAgreementVersion;
import com.spire.backend.repository.WebAgreementApprovalRepository;
import com.spire.backend.repository.WebAgreementRepository;
import com.spire.backend.repository.WebAgreementVersionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * The ERM's half of the approval gate on website agreements, the website
 * copy of the console's (ConsultantApplicationService's requiredApprovers,
 * eligibleApprovers, sendForApproval, listApprovals and approvalBoard; all
 * untouched). The approvers' half lives in its own service.
 *
 * The ERM routes a verified agreement to approvers from the OWNING ERM's
 * team (never the caller's): Phase 1 needs a Manager, Phase 2 a Manager and
 * an Accounts approver, in parallel. Every send or re-send opens the next
 * round, counted across the whole agreement, with one PENDING gate per
 * required role; earlier rounds stay on record. No email is sent: the
 * approver finds the agreement in their queue.
 */
@Service
@RequiredArgsConstructor
public class WebAgreementApprovalService {

    private static final String MANAGER = WebAgreementApproval.ApproverRole.MANAGER.name();
    private static final String ACCOUNTS = WebAgreementApproval.ApproverRole.ACCOUNTS.name();

    /** The approval stages the ERM's board shows (console CAS:3553-3556). */
    static final List<String> BOARD_STATUSES = List.of(
            WebAgreement.Status.AWAITING_APPROVALS.name(),
            WebAgreement.Status.APPROVAL_REVISION_REQUESTED.name(),
            WebAgreement.Status.READY_TO_SIGN.name());

    private final WebAgreementRepository agreementRepository;
    private final WebAgreementApprovalRepository approvalRepository;
    private final WebAgreementVersionRepository versionRepository;
    private final WebAgreementAssignmentService assignmentService;
    private final WebAgreementEventService eventService;
    private final WebAgreementAccess access;

    /** Detaches board rows before their PII is removed (open-in-view is on). */
    @PersistenceContext
    private EntityManager entityManager;

    /** The gates a phase needs: Phase 1 = {MANAGER}; Phase 2 = {MANAGER, ACCOUNTS}. */
    public static List<String> requiredRoles(int phase) {
        if (phase >= 2) {
            return List.of(MANAGER, ACCOUNTS);
        }
        return List.of(MANAGER);
    }

    /**
     * The approvers the ERM may route this agreement to, for the send
     * pickers: {phase, managers, accounts}, each [{id, name, email}]. The
     * owning ERM's active Managers always, their Accounts approvers from
     * Phase 2 on ([] before). Owner or admin.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> eligibleApprovers(String applicationId, Long callerId) {
        WebAgreement a = access.requireAccess(applicationId, callerId);
        int phase = a.getPhase() == null ? 1 : a.getPhase();
        Long owner = a.getOwnerUserId();
        Function<User, Map<String, Object>> slim =
                u -> Map.of("id", u.getId(),
                        "name", u.getFullName() == null ? "" : u.getFullName(),
                        "email", u.getEmail() == null ? "" : u.getEmail());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("phase", phase);
        out.put("managers", assignmentService.assignedApprovers(owner, MANAGER)
                .stream().map(slim).toList());
        out.put("accounts", phase >= 2
                ? assignmentService.assignedApprovers(owner, ACCOUNTS).stream().map(slim).toList()
                : List.of());
        return out;
    }

    /**
     * "Send for approval", and the re-send after an approver asked for a
     * revision (the console's sendForApproval, one transaction). The first
     * send needs VERIFIED and a verified (released) version; the re-send
     * comes from APPROVAL_REVISION_REQUESTED and resets every required gate
     * to PENDING in a fresh round.
     *
     * Each pick must be an active approver on the owning ERM's team. The
     * approver reviews the chosen version (the latest when none is given).
     * The gates are written before the version is checked, so a version
     * that doesn't exist rolls the whole send back. The agreement moves to
     * AWAITING_APPROVALS; no email is sent.
     */
    @Transactional
    public WebAgreement sendForApproval(String applicationId, Long managerUserId, Long accountsUserId,
                                        Integer versionNumber, Long callerId, HttpServletRequest request) {
        WebAgreement a = access.requireAccess(applicationId, callerId);
        String st = a.getStatus();
        boolean firstSend = WebAgreement.Status.VERIFIED.name().equals(st);
        boolean resend = WebAgreement.Status.APPROVAL_REVISION_REQUESTED.name().equals(st);
        if (!firstSend && !resend) {
            throw new IllegalStateException(
                    "Send for Approval is only available from VERIFIED (after the "
                            + "agreement is verified) or APPROVAL_REVISION_REQUESTED "
                            + "(status=" + st + ").");
        }
        if (firstSend && !Boolean.TRUE.equals(a.getConsultantCopyReleased())) {
            throw new IllegalStateException("Verify the agreement before sending it for approval.");
        }
        int phase = a.getPhase() == null ? 1 : a.getPhase();
        Integer prevRound = approvalRepository.maxRound(a.getId());
        int round = (prevRound == null ? 0 : prevRound) + 1;
        List<String> roles = requiredRoles(phase);

        // The chosen approver for each required gate, from the OWNER's team.
        Long owner = a.getOwnerUserId();
        Map<String, User> chosen = new LinkedHashMap<>();
        for (String role : roles) {
            Long picked = MANAGER.equals(role) ? managerUserId : accountsUserId;
            String noun = role.toLowerCase(Locale.ROOT);
            List<User> assigned = assignmentService.assignedApprovers(owner, role);
            if (assigned.isEmpty()) {
                throw new IllegalStateException(
                        "No " + noun + " assigned — ask an admin to assign one.");
            }
            if (picked == null) {
                throw new IllegalArgumentException("Select a " + noun + " to send for approval.");
            }
            User match = assigned.stream()
                    .filter(u -> picked.equals(u.getId())).findFirst().orElse(null);
            if (match == null) {
                // The pickers only offer active approvers: tell "assigned but
                // deactivated (or re-roled)" apart from "never assigned".
                if (assignmentService.assignedApproverIds(owner, role).contains(picked)) {
                    throw new IllegalArgumentException(
                            "The selected " + noun
                                    + " is no longer active. Ask an admin to reactivate or assign another.");
                }
                throw new IllegalArgumentException(
                        "The selected " + noun + " is not assigned to this ERM.");
            }
            chosen.put(role, match);
        }

        for (String role : roles) {
            User approver = chosen.get(role);
            approvalRepository.save(WebAgreementApproval.builder()
                    .agreementId(a.getId())
                    .role(role)
                    .status(WebAgreementApproval.Decision.PENDING.name())
                    .phase(phase)
                    .round(round)
                    .approverUserId(approver.getId())
                    .approverName(approver.getFullName())
                    .build());
        }

        // The version the approvers review: the chosen one, or the latest
        // (the one the ERM just verified). None at all only happens on an
        // agreement verified before versions existed; the approvers then see
        // a live render.
        Integer selectedVersion = versionNumber;
        if (selectedVersion == null) {
            selectedVersion = versionRepository.findTopByAgreementIdOrderByVersionNumberDesc(a.getId())
                    .map(WebAgreementVersion::getVersionNumber)
                    .orElse(null);
        } else if (versionRepository.findByAgreementIdAndVersionNumber(a.getId(), selectedVersion).isEmpty()) {
            throw new IllegalArgumentException(
                    "Selected version V" + selectedVersion + " was not found for this agreement.");
        }
        a.setApprovalVersionNumber(selectedVersion);
        a.setStatus(WebAgreement.Status.AWAITING_APPROVALS.name());
        agreementRepository.save(a);

        eventService.append(a.getId(),
                WebAgreementEvent.EventType.SENT_FOR_APPROVAL,
                WebAgreementEvent.ActorType.ERM, callerId,
                Map.of("phase", phase,
                        "round", round,
                        "approvers", roles,
                        "routedTo", chosen.values().stream().map(User::getFullName).toList(),
                        "resend", resend,
                        "version", String.valueOf(selectedVersion)),
                request);
        return a;
    }

    /** Every gate of one agreement, oldest first (the ERM detail). */
    @Transactional(readOnly = true)
    public List<WebAgreementApproval> listApprovals(Long agreementId) {
        return approvalRepository.findByAgreementIdOrderByCreatedAtAsc(agreementId);
    }

    /**
     * The ERM's approval board: every live agreement in an approval stage
     * (AWAITING_APPROVALS, APPROVAL_REVISION_REQUESTED, READY_TO_SIGN),
     * newest change first, each as {application, approvals (oldest first)}.
     * An ERM sees their own, admins see every one; the owner's name is
     * filled for the grouping and the most sensitive PII is removed.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> approvalBoard(Long callerId) {
        User caller = access.requireStaff(callerId);
        List<WebAgreement> agreements = WebAgreementAccess.isAdmin(caller)
                ? agreementRepository.findByStatusInAndDeletedFalseOrderByUpdatedAtDesc(BOARD_STATUSES)
                : agreementRepository.findByOwnerUserIdAndStatusInAndDeletedFalseOrderByUpdatedAtDesc(
                        callerId, BOARD_STATUSES);
        access.populateOwnerNames(agreements);
        WebAgreementRules.stripSensitivePii(entityManager, agreements);
        // Every board agreement's gates in one query, not one per agreement.
        Map<Long, List<WebAgreementApproval>> approvals = agreements.isEmpty() ? Map.of()
                : WebAgreementApprovalSummary.oldestFirstByAgreement(approvalRepository.findByAgreementIdIn(
                        agreements.stream().map(WebAgreement::getId).distinct().toList()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (WebAgreement a : agreements) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("application", a);
            row.put("approvals", approvals.getOrDefault(a.getId(), List.of()));
            out.add(row);
        }
        return out;
    }
}
