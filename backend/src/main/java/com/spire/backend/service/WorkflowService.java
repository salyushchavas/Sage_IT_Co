package com.spire.backend.service;

import com.spire.backend.entity.User;
import com.spire.backend.entity.WorkflowState;
import com.spire.backend.repository.UserRepository;
import com.spire.backend.repository.WorkflowStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Phase 1A: 20-step participant lifecycle state machine.
 *
 * Every status change funnels through {@link #transition} so the
 * append-only {@code workflow_states} table plus the existing
 * {@code user_records} audit log always agree. The 6 {@code can…}
 * gate methods are the canonical authority for "is this action
 * allowed yet?" — controllers must call them before mutating any
 * downstream resource (creating a participant ID, sending the agreement,
 * enabling the dashboard, …).
 *
 * The ordering of {@link Status} values reflects the on-paper
 * 20-step sequence; {@link #ordinal(Status)} treats that ordering
 * as a monotonic ladder for {@link #isStatusAtLeast}.
 *
 * Soft validation: {@link #transition} accepts ANY status change
 * (no whitelist of allowed pairs). The workflow_states row records
 * the from/to pair so an out-of-order move is auditable. Adding a
 * strict whitelist is left for a follow-up once the on-paper flow
 * is locked in.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowService {

    /**
     * The 26 status codes the participant lifecycle uses. Ordered to
     * match the on-paper 20-step ladder; later statuses are "higher"
     * for the {@link #isStatusAtLeast} comparison. The codes also
     * cover the post-Phase-1 payment/invoicing tail (PAYMENT_PLAN_…
     * through PAYMENTS_TRACKED) so a single workflow string drives
     * the whole journey.
     */
    public enum Status {
        DRAFT_STARTED,
        BASIC_INFO_SUBMITTED,
        EMAIL_VERIFICATION_PENDING,
        EMAIL_VERIFIED,
        PARTICIPANT_ID_CREATED,
        ID_EMAIL_SENT,
        ACKNOWLEDGMENT_ACCEPTED,
        DOCUMENTS_SUBMITTED,
        DOC_REVIEW_PENDING,
        PROGRAM_SELECTED,
        AGREEMENT_SENT,
        AGREEMENT_COMPLETED,
        CHECK_COPY_UPLOADED,
        SIGNED_AGREEMENT_SENT_TO_ERM,
        WELCOME_SENT,
        DEEPTHI_INTRO_SENT,
        ERM_ASSIGNED,
        COACHES_ASSIGNED,
        DASHBOARD_ENABLED,
        WEEKLY_REPORTING_ACTIVE,
        EMPLOYMENT_ACCEPTED,
        PHASE_1_COMPLETED,
        PAYMENT_PLAN_ACCEPTED,
        CHECK_TRACKING_ADDED,
        INVOICING_ACTIVE,
        PAYMENTS_TRACKED
    }

    private final UserRepository userRepository;
    private final WorkflowStateRepository workflowStateRepository;
    private final RecordService recordService;

    // ── Status helpers ──────────────────────────────────────────────

    public Status currentStatus(User user) {
        String s = user.getCurrentStatus();
        if (s == null || s.isBlank()) return Status.DRAFT_STARTED;
        try {
            return Status.valueOf(s);
        } catch (IllegalArgumentException e) {
            // Defensive: unknown status string on an older row;
            // surface as DRAFT_STARTED so downstream gates still
            // refuse to advance.
            log.warn("Unknown workflow status '{}' on user {} — defaulting to DRAFT_STARTED",
                    s, user.getId());
            return Status.DRAFT_STARTED;
        }
    }

    public boolean isStatusAtLeast(User user, Status target) {
        return currentStatus(user).ordinal() >= target.ordinal();
    }

    // ── 6 gates: the canonical "is this allowed yet?" answers ──────

    /** Email-verified — required before minting a participant ID. */
    public boolean canCreateParticipantId(User user) {
        return Boolean.TRUE.equals(user.getEmailVerified());
    }

    /** Acknowledgment accepted — required before opening document upload. */
    public boolean canSubmitDocuments(User user) {
        return isStatusAtLeast(user, Status.ACKNOWLEDGMENT_ACCEPTED);
    }

    /** Program selected — required before issuing an agreement envelope. */
    public boolean canStartAgreement(User user) {
        return isStatusAtLeast(user, Status.PROGRAM_SELECTED);
    }

    /** ERM assigned — required before pairing coaches. */
    public boolean canAssignCoaches(User user) {
        return isStatusAtLeast(user, Status.ERM_ASSIGNED);
    }

    /** Coaches assigned — required before opening the participant dashboard. */
    public boolean canEnableDashboard(User user) {
        return isStatusAtLeast(user, Status.COACHES_ASSIGNED);
    }

    /** Phase 1 complete — required before activating the payment plan. */
    public boolean canActivatePayment(User user) {
        return isStatusAtLeast(user, Status.PHASE_1_COMPLETED);
    }

    // ── Transition (audited) ────────────────────────────────────────

    /**
     * Sets the user's currentStatus and appends a workflow_states
     * row plus a user_records WORKFLOW entry. Runs in the caller's
     * transaction so the user save and the audit row commit together.
     *
     * The audit row is best-effort — a failure to write to
     * user_records won't roll back the user save (RecordService uses
     * REQUIRES_NEW). The workflow_states row IS in the same tx and
     * thus does roll back on failure: that's the legal source of
     * truth for the lifecycle so we accept the linkage.
     */
    @Transactional
    public void transition(User user, Status newStatus, String trigger) {
        transition(user, newStatus, trigger, null);
    }

    @Transactional
    public void transition(User user, Status newStatus, String trigger, String notes) {
        String oldStatus = user.getCurrentStatus();
        user.setCurrentStatus(newStatus.name());
        userRepository.save(user);

        WorkflowState ws = WorkflowState.builder()
                .userId(user.getId())
                .fromStatus(oldStatus)
                .toStatus(newStatus.name())
                .triggerEvent(trigger)
                .notes(notes)
                .build();
        workflowStateRepository.save(ws);

        recordService.record(user.getId(), "WORKFLOW",
                RecordService.Category.ACCOUNT,
                "Status changed: " + (oldStatus == null ? "—" : oldStatus) + " → " + newStatus.name(),
                "trigger=" + (trigger == null ? "(none)" : trigger),
                Map.of(
                        "fromStatus", oldStatus == null ? "" : oldStatus,
                        "toStatus", newStatus.name(),
                        "trigger", trigger == null ? "" : trigger,
                        "notes", notes == null ? "" : notes
                ));

        log.info("Workflow transition user={} {}→{} trigger={}",
                user.getId(), oldStatus, newStatus.name(), trigger);
    }
}
