package com.spire.backend.service;

import com.spire.backend.entity.ProgramSelection;
import com.spire.backend.entity.User;
import com.spire.backend.repository.ProgramSelectionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 4 — orchestrates the chain that runs the moment a
 * participant finishes signing their agreement
 * ({@code SIGNED_AGREEMENT_SENT_TO_ERM} reached).
 *
 * Step ladder:
 *   1. Welcome email (Email #8) → WELCOME_SENT
 *   2. Coordinator intro (Email #9) → DEEPTHI_INTRO_SENT
 *   3. Pick + assign an ERM, email both sides (Email #10) → ERM_ASSIGNED
 *   4. Pick + assign coaches, email participant (Email #11) → COACHES_ASSIGNED
 *   5. Open the dashboard → DASHBOARD_ENABLED
 *
 * Every step is best-effort: an email outage doesn't roll back a
 * workflow transition, and a missing ERM / no-available-coach
 * scenario leaves the row pending without blocking the chain.
 * Gate 5 is enforced by skipping {@code DASHBOARD_ENABLED} if no
 * ERM exists yet — the participant stays on /welcome until an
 * admin manually assigns one, at which point the same chain re-runs.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OnboardingService {

    private final WorkflowService workflowService;
    private final EmailTemplateService emailTemplateService;
    private final ErmAssignmentService ermAssignmentService;
    private final CoachAssignmentService coachAssignmentService;
    private final ProgramSelectionRepository programSelectionRepository;
    private final RecordService recordService;

    /**
     * Phase 1C entry point. Fires when the participant reaches 100%
     * profile completion (all six per-step flags set). Runs the same
     * welcome → coordinator → ERM → coaches chain that used to fire
     * straight after agreement signing, plus a celebratory
     * "profile complete" email so the user knows everything is now
     * unlocked.
     *
     * Internally delegates to {@link #completeOnboarding} so the
     * single canonical chain runs from both entry points without
     * code duplication. Idempotent: safe to re-run.
     */
    @Transactional
    public void triggerProfileCompletionFlow(User user) {
        log.info("Profile-complete chain starting for user {}", user.getId());
        try {
            emailTemplateService.sendProfileCompleteEmail(user);
        } catch (Exception e) {
            log.warn("Profile-complete celebration email failed for user {}: {}",
                    user.getId(), e.getMessage());
        }
        completeOnboarding(user);
    }

    /**
     * Fired right after Phase 3B verify-code completes. Idempotent
     * — safe to re-run if a later step previously failed (e.g.
     * no ERM was available at first agreement-completion time but
     * has since been seeded).
     */
    @Transactional
    public void completeOnboarding(User user) {
        log.info("OnboardingService chain starting for user {}", user.getId());

        // Step 1: Welcome email (only if we haven't already passed
        // through this transition). The agreement-verify flow may
        // have already fired sendWelcomeEmail before this chain
        // runs — that's fine, the mailer call is idempotent enough
        // for a single duplicate to be a non-issue.
        if (!workflowService.isStatusAtLeast(user, WorkflowService.Status.WELCOME_SENT)) {
            try {
                emailTemplateService.sendWelcomeEmail(user);
            } catch (Exception e) {
                log.warn("Welcome email failed for user {}: {}", user.getId(), e.getMessage());
            }
            workflowService.transition(user,
                    WorkflowService.Status.WELCOME_SENT, "welcome_email");
        }

        // Step 2: Coordinator intro
        if (!workflowService.isStatusAtLeast(user, WorkflowService.Status.DEEPTHI_INTRO_SENT)) {
            try {
                emailTemplateService.sendCoordinatorIntroEmail(user);
            } catch (Exception e) {
                log.warn("Coordinator intro failed for user {}: {}", user.getId(), e.getMessage());
            }
            workflowService.transition(user,
                    WorkflowService.Status.DEEPTHI_INTRO_SENT, "coordinator_intro");
        }

        // Step 3: ERM assignment
        boolean ermNow = ensureErm(user);

        // Step 4: Coach assignment (independent of ERM — if no
        // coaches available we still record the row).
        boolean anyCoach = ensureCoaches(user);

        // Step 5: Dashboard. Gate 5 — needs ERM + at least one
        // coach. If either is missing, leave the workflow on the
        // last-completed step so the welcome page keeps polling
        // and surfaces the pending state.
        if (ermNow && anyCoach) {
            if (!workflowService.isStatusAtLeast(user, WorkflowService.Status.DASHBOARD_ENABLED)) {
                workflowService.transition(user,
                        WorkflowService.Status.DASHBOARD_ENABLED, "dashboard_enabled");
            }
        } else {
            log.info("Dashboard NOT enabled yet for user {} — erm={} anyCoach={}",
                    user.getId(), ermNow, anyCoach);
        }
    }

    /** Snapshot used by the /welcome-status polling endpoint. */
    @Transactional(readOnly = true)
    public Map<String, Object> snapshotForWelcome(User user) {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean welcome = workflowService.isStatusAtLeast(user, WorkflowService.Status.WELCOME_SENT);
        boolean coord = workflowService.isStatusAtLeast(user, WorkflowService.Status.DEEPTHI_INTRO_SENT);
        boolean erm = workflowService.isStatusAtLeast(user, WorkflowService.Status.ERM_ASSIGNED);
        boolean coaches = workflowService.isStatusAtLeast(user, WorkflowService.Status.COACHES_ASSIGNED);
        boolean dashboard = workflowService.isStatusAtLeast(user, WorkflowService.Status.DASHBOARD_ENABLED);
        out.put("workflowStatus", user.getCurrentStatus());
        out.put("welcomeEmailSent", welcome);
        out.put("coordinatorIntroSent", coord);
        out.put("ermAssigned", erm);
        out.put("coachesAssigned", coaches);
        out.put("dashboardReady", dashboard);

        ermAssignmentService.getAssignedErm(user.getId()).ifPresent(e -> {
            out.put("ermName", e.getFullName());
            out.put("ermEmail", e.getEmail());
        });
        out.put("coaches", coachAssignmentService.getAssignedCoaches(user.getId()));
        return out;
    }

    // ── Internals ────────────────────────────────────────────────

    /** Assigns an ERM if one is available, fires both intro emails.
     *  Returns true once an ERM is on the row (even from a prior
     *  attempt). */
    private boolean ensureErm(User user) {
        boolean alreadyAssigned = workflowService.isStatusAtLeast(user,
                WorkflowService.Status.ERM_ASSIGNED);
        Optional<User> existingErm = ermAssignmentService.getAssignedErm(user.getId());
        if (alreadyAssigned && existingErm.isPresent()) return true;

        var assigned = ermAssignmentService.assignErm(user);
        if (assigned.isEmpty() || assigned.get().getErmUserId() == null) {
            recordService.logAction(user.getId(), RecordService.Category.ACCOUNT,
                    "ERM assignment pending",
                    "No active ERM available — left for manual assignment", null);
            return false;
        }
        User erm = ermAssignmentService.getAssignedErm(user.getId()).orElse(null);
        if (erm == null) return false;
        Optional<ProgramSelection> program = programSelectionRepository
                .findFirstByUserIdOrderBySelectionDateDesc(user.getId());

        try {
            emailTemplateService.sendErmIntroEmail(user, erm);
        } catch (Exception e) {
            log.warn("ERM participant intro email failed: {}", e.getMessage());
        }
        try {
            emailTemplateService.sendErmAssignmentNotification(erm, user, program.orElse(null));
        } catch (Exception e) {
            log.warn("ERM internal notification failed: {}", e.getMessage());
        }
        workflowService.transition(user,
                WorkflowService.Status.ERM_ASSIGNED, "erm_assigned");
        return true;
    }

    /** Assigns coaches across the four canonical roles, emails the
     *  participant the team list (with "Awaiting assignment" for
     *  any role we couldn't fill). Returns true when at least one
     *  coach was assigned. */
    private boolean ensureCoaches(User user) {
        boolean alreadyAssigned = workflowService.isStatusAtLeast(user,
                WorkflowService.Status.COACHES_ASSIGNED);
        if (alreadyAssigned && coachAssignmentService.hasAnyCoach(user.getId())) return true;

        Map<String, String> outcome = coachAssignmentService.assignCoaches(user);
        boolean anyAssigned = outcome.values().stream()
                .anyMatch(v -> v != null && !v.equalsIgnoreCase("Awaiting assignment"));

        if (anyAssigned) {
            try {
                emailTemplateService.sendCoachAssignmentEmail(user, outcome);
            } catch (Exception e) {
                log.warn("Coach assignment email failed: {}", e.getMessage());
            }
            workflowService.transition(user,
                    WorkflowService.Status.COACHES_ASSIGNED, "coaches_assigned");
        } else {
            recordService.logAction(user.getId(), RecordService.Category.ACCOUNT,
                    "Coach assignment pending",
                    "No matching coaches available — left for manual assignment", null);
        }
        return anyAssigned;
    }
}
