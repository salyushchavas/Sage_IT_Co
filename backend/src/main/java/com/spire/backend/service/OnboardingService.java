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
 * Phase 4 — orchestrates the chain that runs once a participant has
 * finished every onboarding step (the agreement signed and the check
 * step done), in the roadmap's order (checklists 2.3 and 3.1):
 *
 *   10. The signed agreement goes to an ERM (the least-loaded one is
 *       assigned now if none yet) → SIGNED_AGREEMENT_SENT_TO_ERM
 *   11. Welcome email → WELCOME_SENT
 *   12. Coordinator intro → DEEPTHI_INTRO_SENT
 *   13. ERM introduction: the participant meets their ERM, the ERM
 *       gets the new-participant note → ERM_ASSIGNED
 *   14. Coaches assigned, participant emailed → COACHES_ASSIGNED
 *   15. Dashboard opened → DASHBOARD_ENABLED
 *
 * Each step runs once: its status moves forward only after it ran, and
 * a step already passed is never repeated. An email that fails doesn't
 * hold the participant up; it shows as FAILED in the email log (1.4).
 * With no ERM available the chain waits at step 10 (the participant
 * shows in Operations → Assignments) and continues from there when an
 * ERM is assigned.
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
    private final SignedAgreementService signedAgreementService;

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
        // No separate "Welcome aboard, your profile is complete" email any
        // more: it arrived alongside the official welcome, so participants
        // got two welcomes (checklist 3.1). The chain's welcome is the one.
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
        // Only once every profile step is done. Manual ERM / coach
        // assignment and the /welcome refresh also call this; before the
        // profile is complete they must not send the welcome emails or
        // move the status (the assignment itself is kept, and the chain
        // runs when the last step is finished).
        if (!ProfileCompletionService.allStepsComplete(user)) {
            log.info("Onboarding chain not started for user {}: profile not complete yet", user.getId());
            return;
        }
        log.info("OnboardingService chain starting for user {}", user.getId());

        // Step 10: the signed agreement goes to an ERM (the roadmap's
        // "assigned/pending ERM queue"). Without an ERM the chain waits
        // here; Operations assigns one and the chain continues.
        Optional<User> erm = ermForAgreement(user);
        if (erm.isEmpty()) {
            recordService.logAction(user.getId(), RecordService.Category.ACCOUNT,
                    "ERM assignment pending",
                    "No active ERM available — left for manual assignment", null);
            log.info("Onboarding chain waiting for an ERM for user {}", user.getId());
            return;
        }
        if (!workflowService.isStatusAtLeast(user, WorkflowService.Status.SIGNED_AGREEMENT_SENT_TO_ERM)) {
            signedAgreementService.routeToErm(user, erm.get());
            workflowService.transition(user,
                    WorkflowService.Status.SIGNED_AGREEMENT_SENT_TO_ERM, "agreement_routed_to_erm");
        }

        // Step 11: welcome email, after the agreement reached the ERM.
        if (!workflowService.isStatusAtLeast(user, WorkflowService.Status.WELCOME_SENT)) {
            try {
                emailTemplateService.sendWelcomeEmail(user);
            } catch (Exception e) {
                log.warn("Welcome email failed for user {}: {}", user.getId(), e.getMessage());
            }
            workflowService.transition(user,
                    WorkflowService.Status.WELCOME_SENT, "welcome_email");
        }

        // Step 12: coordinator intro.
        if (!workflowService.isStatusAtLeast(user, WorkflowService.Status.DEEPTHI_INTRO_SENT)) {
            try {
                emailTemplateService.sendCoordinatorIntroEmail(user);
            } catch (Exception e) {
                log.warn("Coordinator intro failed for user {}: {}", user.getId(), e.getMessage());
            }
            workflowService.transition(user,
                    WorkflowService.Status.DEEPTHI_INTRO_SENT, "coordinator_intro");
        }

        // Step 13: ERM introduction (both sides).
        if (!workflowService.isStatusAtLeast(user, WorkflowService.Status.ERM_ASSIGNED)) {
            introduceErm(user, erm.get());
            workflowService.transition(user,
                    WorkflowService.Status.ERM_ASSIGNED, "erm_assigned");
        }

        // Step 14: coaches — only once the ERM is in place.
        boolean anyCoach = ensureCoaches(user);

        // Step 15: dashboard. Gate 5 — ERM + at least one coach.
        if (anyCoach) {
            if (!workflowService.isStatusAtLeast(user, WorkflowService.Status.DASHBOARD_ENABLED)) {
                workflowService.transition(user,
                        WorkflowService.Status.DASHBOARD_ENABLED, "dashboard_enabled");
            }
        } else {
            log.info("Dashboard NOT enabled yet for user {} — no coach assigned", user.getId());
        }
    }

    /**
     * Operations assigned (or changed) a participant's ERM. A new ERM who
     * joins after steps 10 or 13 already happened still gets the signed
     * agreement, and the participant meets them; then the chain carries
     * on (checklists 2.3 and 3.1).
     */
    @Transactional
    public void ermAssignedByOperations(User user) {
        Optional<User> erm = ermAssignmentService.getAssignedErm(user.getId());
        if (erm.isPresent() && ProfileCompletionService.allStepsComplete(user)) {
            if (workflowService.isStatusAtLeast(user, WorkflowService.Status.SIGNED_AGREEMENT_SENT_TO_ERM)) {
                signedAgreementService.routeToErm(user, erm.get());
            }
            if (workflowService.isStatusAtLeast(user, WorkflowService.Status.ERM_ASSIGNED)) {
                introduceErm(user, erm.get());
            }
        }
        completeOnboarding(user);
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
        out.put("agreementSentToErm", workflowService.isStatusAtLeast(user,
                WorkflowService.Status.SIGNED_AGREEMENT_SENT_TO_ERM));
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

    /** The participant's ERM, assigning the least-loaded active ERM if none yet. */
    private Optional<User> ermForAgreement(User user) {
        Optional<User> existing = ermAssignmentService.getAssignedErm(user.getId());
        if (existing.isPresent()) return existing;
        var assigned = ermAssignmentService.assignErm(user);
        if (assigned.isEmpty() || assigned.get().getErmUserId() == null) return Optional.empty();
        return ermAssignmentService.getAssignedErm(user.getId());
    }

    /**
     * Step 13 emails: the participant meets their ERM, and the ERM gets
     * the new-participant note. The intro's real outcome is recorded on
     * the assignment (1.4).
     */
    private void introduceErm(User user, User erm) {
        Optional<ProgramSelection> program = programSelectionRepository
                .findFirstByUserIdOrderBySelectionDateDesc(user.getId());
        boolean introSent = false;
        try {
            introSent = emailTemplateService.sendErmIntroEmail(user, erm);
        } catch (Exception e) {
            log.warn("ERM participant intro email failed: {}", e.getMessage());
        }
        ermAssignmentService.recordIntroEmail(user.getId(), introSent);
        try {
            emailTemplateService.sendErmAssignmentNotification(erm, user, program.orElse(null));
        } catch (Exception e) {
            log.warn("ERM internal notification failed: {}", e.getMessage());
        }
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
