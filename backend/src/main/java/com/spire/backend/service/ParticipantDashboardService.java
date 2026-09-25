package com.spire.backend.service;

import com.spire.backend.entity.ProgramSelection;
import com.spire.backend.entity.User;
import com.spire.backend.entity.UserRecord;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.repository.ProgramSelectionRepository;
import com.spire.backend.repository.UserRecordRepository;
import com.spire.backend.repository.UserRepository;
import com.spire.backend.repository.WeeklyReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 5A — aggregates everything the participant dashboard needs
 * for its first render:
 *   - greeting + participant id
 *   - 20-step roadmap progress (mapped from workflow status)
 *   - "next action" card hint
 *   - team summary (ERM + coaches)
 *   - recent activity (last 5 user_records rows)
 *   - quick stats (weeks enrolled, reports submitted, ...)
 *
 * The dashboard polls this on mount so the legacy
 * /api/participants/me + /team + /reports/weekly endpoints don't
 * need three round-trips.
 */
@Service
@RequiredArgsConstructor
public class ParticipantDashboardService {

    /** Names of the 20 lifecycle steps in display order. */
    public static final List<String> ROADMAP_STEPS = List.of(
            "Enrollment",
            "Email verification",
            "Participant ID",
            "Acknowledgment",
            "Documents",
            "Program selection",
            "Agreement sent",
            "Check upload",
            "Agreement complete",
            "Signed to ERM",
            "Welcome",
            "Coordinator intro",
            "ERM assigned",
            "Coaches assigned",
            "Dashboard active",
            "Weekly reporting",
            "Employment & Phase 1",
            "Payment plan & checks",
            "Invoices",
            "Payments tracked"
    );

    private final UserRepository userRepository;
    private final ProgramSelectionRepository programSelectionRepository;
    private final UserRecordRepository userRecordRepository;
    private final WeeklyReportRepository weeklyReportRepository;
    private final ErmAssignmentService ermAssignmentService;
    private final CoachAssignmentService coachAssignmentService;
    private final WorkflowService workflowService;
    private final com.spire.backend.repository.EmailLogRepository emailLogRepository;
    private final com.spire.backend.repository.InvoiceRepository invoiceRepository;
    private final com.spire.backend.repository.PaymentPlanRepository paymentPlanRepository;
    private final BusinessClock clock;
    private final WeeklyReportService weeklyReportService;
    private final com.spire.backend.repository.EmploymentAcceptanceRepository employmentRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> snapshot(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("participantId", user.getParticipantId());
        out.put("fullName", user.getFullName());
        out.put("email", user.getEmail());
        out.put("currentStatus", user.getCurrentStatus());

        // Roadmap progress: each step ticked from what really happened,
        // and the current step is the first one not done (it used to be
        // read off the status alone, which was jumped to step 15 at
        // sign-up and ticked everything before it).
        RoadmapFacts facts = factsFor(user);
        List<Boolean> done = roadmapDone(user, facts);
        out.put("roadmapTotal", ROADMAP_STEPS.size());
        out.put("roadmapStep", currentStep(done));
        out.put("roadmapDone", done);
        out.put("roadmapLabels", ROADMAP_STEPS);
        out.put("nextAction", nextActionFor(user, facts));
        // Checklist 3.5: Weekly, Employment and Payments open once the team
        // is ready (ERM + coaches, the dashboard enabled), not before.
        out.put("teamReady", facts.teamReady());

        // Program selection
        ProgramSelection program = programSelectionRepository
                .findFirstByUserIdOrderBySelectionDateDesc(userId)
                .orElse(null);
        if (program != null) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("program", program.getProgram());
            p.put("phase", program.getPhase());
            p.put("skillset", program.getSkillset());
            p.put("targetJobTitle", program.getTargetJobTitle());
            p.put("availability", program.getAvailability());
            out.put("program", p);
        }

        // Team
        Map<String, Object> team = new LinkedHashMap<>();
        ermAssignmentService.getAssignedErm(userId).ifPresent(e -> {
            team.put("ermName", e.getFullName());
            team.put("ermEmail", e.getEmail());
        });
        team.put("coaches", coachAssignmentService.getAssignedCoaches(userId));
        out.put("team", team);

        // Recent activity: the participant's own last 5 events, newest first.
        // Staff-side entries (a document or check viewed by staff, ERM
        // notes), sign-ins and raw status changes stay on the audit trail.
        List<UserRecord> all = userRecordRepository.findTop50ByUserIdOrderByCreatedAtDesc(userId);
        List<Map<String, Object>> recent = all.stream()
                .filter(ParticipantDashboardService::shownToParticipant)
                .limit(5)
                .map(r -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("title", r.getTitle());
                    row.put("category", r.getCategory());
                    row.put("createdAt", r.getCreatedAt());
                    return row;
                })
                .toList();
        out.put("recentActivity", recent);

        // Quick stats
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("weeksEnrolled", weeksEnrolledFor(user));
        long reportsSubmitted = weeklyReportRepository
                .findByUserIdOrderByWeekStartDesc(userId).stream()
                .filter(r -> "SUBMITTED".equals(r.getStatus()) || "REVIEWED".equals(r.getStatus()))
                .count();
        stats.put("reportsSubmitted", reportsSubmitted);
        out.put("stats", stats);

        // Current-week report shortcut, in business time (checklist 4.3):
        // a week's report is due the Monday after it ends.
        LocalDate weekStart = clock.startOfWeek();
        out.put("currentWeekStart", weekStart);
        out.put("currentWeekEnd", weekStart.plusDays(6));
        out.put("currentWeekDue", weekStart.plusDays(7));
        weeklyReportRepository.findByUserIdAndWeekStart(userId, weekStart).ifPresent(r -> {
            out.put("currentWeekReportStatus", r.getStatus());
            out.put("currentWeekReportId", r.getId());
        });
        // Last week can still be filed (and is what the Monday reminder asks for).
        LocalDate previous = weekStart.minusWeeks(1);
        out.put("previousWeekStart", previous);
        out.put("previousWeekEnd", previous.plusDays(6));
        out.put("previousWeekDue", weekStart);
        out.put("previousWeekOwed", facts.lastWeekOwed());
        weeklyReportRepository.findByUserIdAndWeekStart(userId, previous)
                .ifPresent(r -> out.put("previousWeekReportStatus", r.getStatus()));
        LocalDate opened = weeklyReportService.dashboardOpenedOn(user);
        out.put("earliestReportWeek", opened == null ? null : BusinessClock.startOfWeek(opened));

        return out;
    }

    // ── Helpers ────────────────────────────────────────────────

    /**
     * What really happened for the later roadmap steps (checklist 3.4),
     * gathered once per snapshot.
     */
    record RoadmapFacts(boolean welcomeEmailed, boolean coordinatorEmailed, boolean hasErm, boolean hasCoach,
                        long reportsSubmitted, boolean hasInvoice, boolean hasPlan, boolean teamReady,
                        boolean lastWeekOwed, boolean employmentReturned, boolean planAwaitingAcceptance) {
        RoadmapFacts(boolean welcomeEmailed, boolean coordinatorEmailed, boolean hasErm, boolean hasCoach,
                     long reportsSubmitted, boolean hasInvoice, boolean hasPlan, boolean teamReady) {
            this(welcomeEmailed, coordinatorEmailed, hasErm, hasCoach, reportsSubmitted, hasInvoice, hasPlan,
                    teamReady, false, false, false);
        }
    }

    RoadmapFacts factsFor(User u) {
        // Emails are proven by the email log (1.4). An account with no
        // log rows at all dates from before the log existed: its status
        // stands in for the email.
        boolean logged = emailLogRepository.existsByUserId(u.getId());
        WorkflowService.Status s = statusOf(u);
        boolean welcome = logged
                ? emailLogRepository.existsByEmailTypeAndUserIdAndStatus("WELCOME", u.getId(), EmailLogService.SENT)
                : atLeast(s, WorkflowService.Status.WELCOME_SENT);
        boolean coordinator = logged
                ? emailLogRepository.existsByEmailTypeAndUserIdAndStatus("COORDINATOR_INTRO", u.getId(), EmailLogService.SENT)
                : atLeast(s, WorkflowService.Status.DEEPTHI_INTRO_SENT);
        long reports = weeklyReportRepository.findByUserIdOrderByWeekStartDesc(u.getId()).stream()
                .filter(r -> "SUBMITTED".equals(r.getStatus()) || "REVIEWED".equals(r.getStatus()))
                .count();
        // Last week's report is owed and not in yet (checklist 4.3).
        LocalDate lastWeek = clock.startOfWeek().minusWeeks(1);
        LocalDate firstOwed = weeklyReportService.owesReports(u) ? weeklyReportService.firstOwedWeek(u) : null;
        boolean lastWeekOwed = firstOwed != null && !lastWeek.isBefore(firstOwed)
                && weeklyReportRepository.findByUserIdAndWeekStart(u.getId(), lastWeek)
                        .map(r -> !"SUBMITTED".equals(r.getStatus()) && !"REVIEWED".equals(r.getStatus()))
                        .orElse(true);
        var plan = paymentPlanRepository.findLatestByUserId(u.getId());
        return new RoadmapFacts(welcome, coordinator,
                ermAssignmentService.getAssignedErm(u.getId()).isPresent(),
                coachAssignmentService.hasAnyCoach(u.getId()),
                reports,
                !invoiceRepository.findByUserIdOrderByIssueDateDesc(u.getId()).isEmpty(),
                plan.isPresent(),
                atLeast(s, WorkflowService.Status.DASHBOARD_ENABLED),
                lastWeekOwed,
                // The ERM sent the employment details back (checklist 4.5).
                employmentRepository.findByUserIdOrderByAcceptanceDateDesc(u.getId()).stream().findFirst()
                        .map(e -> e.getReturnedAt() != null).orElse(false),
                // A plan waiting for their acceptance, new or changed by Finance (checklist 5.1).
                plan.map(p -> p.getAcceptedAt() == null && "PENDING".equals(p.getStatus())).orElse(false));
    }

    /**
     * Whether each of the 20 roadmap steps is done, from what really
     * happened (checklist 3.4): onboarding steps (1–9) from the profile
     * step flags; the agreement reaching the ERM (10) and the dashboard
     * (15) from the status, which only moves on real events now; the
     * welcome and coordinator emails (11–12) from the email log; the ERM
     * (13) and coaches (14) from real assignments; weekly reporting (16)
     * from a submitted report; employment and Phase 1 (17) from the
     * Phase 1 acknowledgment, which needs the ERM-verified employment;
     * the payment plan (18) from its acceptance;
     * invoices (19) from an issued invoice; payments (20) from the status.
     */
    static List<Boolean> roadmapDone(User u, RoadmapFacts f) {
        WorkflowService.Status s = statusOf(u);
        boolean ack = Boolean.TRUE.equals(u.getAcknowledgmentComplete());
        boolean agreement = Boolean.TRUE.equals(u.getAgreementComplete());
        return List.of(
                Boolean.TRUE.equals(u.getBasicInfoComplete()),                  // 1 Enrollment (incl. About You)
                Boolean.TRUE.equals(u.getEmailVerified()),                      // 2 Email verification
                u.getParticipantId() != null && !u.getParticipantId().isBlank(), // 3 Participant ID
                ack,                                                             // 4 Acknowledgment
                Boolean.TRUE.equals(u.getDocumentsComplete()),                  // 5 Documents
                Boolean.TRUE.equals(u.getProgramSelectionComplete()),           // 6 Program selection
                agreement || atLeast(s, WorkflowService.Status.AGREEMENT_SENT), // 7 Agreement sent
                Boolean.TRUE.equals(u.getCheckUploadComplete()),                // 8 Check upload
                agreement,                                                       // 9 Agreement complete
                atLeast(s, WorkflowService.Status.SIGNED_AGREEMENT_SENT_TO_ERM), // 10 Signed agreement to ERM
                f.welcomeEmailed(),                                              // 11 Welcome email
                f.coordinatorEmailed(),                                          // 12 Coordinator intro
                f.hasErm() && atLeast(s, WorkflowService.Status.ERM_ASSIGNED),  // 13 ERM introduction
                f.hasCoach() && atLeast(s, WorkflowService.Status.COACHES_ASSIGNED), // 14 Coaches
                atLeast(s, WorkflowService.Status.DASHBOARD_ENABLED),           // 15 Dashboard
                // 16: a report is in, or reports are no longer owed (employment
                // reported before the first one was due): it mustn't stay stuck.
                f.reportsSubmitted() > 0 || atLeast(s, WorkflowService.Status.EMPLOYMENT_ACCEPTED), // 16 Weekly reporting
                atLeast(s, WorkflowService.Status.PHASE_1_COMPLETED),           // 17 Employment & Phase 1
                atLeast(s, WorkflowService.Status.PAYMENT_PLAN_ACCEPTED),       // 18 Payment plan & checks
                f.hasInvoice(),                                                  // 19 Invoices
                atLeast(s, WorkflowService.Status.PAYMENTS_TRACKED));           // 20 Payments tracked
    }

    /** Record types that stay on the audit trail only (staff views, sign-ins, raw status changes). */
    private static final java.util.Set<String> STAFF_SIDE_RECORDS = java.util.Set.of(
            "WORKFLOW", "DOCUMENT_VIEWED", "AGREEMENT_VIEWED", "CHECK_IMAGE_VIEWED", "CHECK_NUMBER_VIEWED",
            "ACCOUNT_LOGIN", "ACCOUNT_LOGIN_FAILED", "ACCOUNT_SIGNUP_REPLACED", "ACCOUNT_VERIFICATION_LOCKED",
            "ACCOUNT_LOGIN_DETAILS_SENT");

    static boolean shownToParticipant(UserRecord r) {
        if (r == null || STAFF_SIDE_RECORDS.contains(r.getRecordType())) return false;
        String title = r.getTitle() == null ? "" : r.getTitle().toLowerCase(java.util.Locale.ROOT);
        return !title.contains("viewed by") && !title.contains("escalation") && !title.contains("erm note");
    }

    /** 1-based number of the first step not done (20 when all are). */
    static int currentStep(List<Boolean> done) {
        for (int i = 0; i < done.size(); i++) {
            if (!Boolean.TRUE.equals(done.get(i))) return i + 1;
        }
        return done.size();
    }

    private static WorkflowService.Status statusOf(User u) {
        try {
            return u.getCurrentStatus() == null ? WorkflowService.Status.DRAFT_STARTED
                    : WorkflowService.Status.valueOf(u.getCurrentStatus());
        } catch (IllegalArgumentException e) {
            return WorkflowService.Status.DRAFT_STARTED;
        }
    }

    private static boolean atLeast(WorkflowService.Status s, WorkflowService.Status target) {
        return s.ordinal() >= target.ordinal();
    }

    /** The next profile step's page while onboarding isn't finished. */
    private static Map<String, String> onboardingActionFor(User user) {
        Map<String, String> action = new LinkedHashMap<>();
        if (!Boolean.TRUE.equals(user.getBasicInfoComplete())) {
            action.put("label", "Tell us about yourself");
            action.put("href", "#complete-profile");
        } else if (!Boolean.TRUE.equals(user.getAcknowledgmentComplete())) {
            action.put("label", "Accept the program acknowledgment");
            action.put("href", "/acknowledgment");
        } else if (!Boolean.TRUE.equals(user.getDocumentsComplete())) {
            action.put("label", "Upload your documents");
            action.put("href", "/document-upload");
        } else if (!Boolean.TRUE.equals(user.getProgramSelectionComplete())) {
            action.put("label", "Choose your program");
            action.put("href", "/program-selection");
        } else if (!Boolean.TRUE.equals(user.getAgreementComplete())) {
            action.put("label", "Sign your agreement");
            action.put("href", "/agreement");
        } else {
            action.put("label", "Upload your check copies (or mark them not applicable)");
            action.put("href", "/check-upload");
        }
        return action;
    }

    /**
     * The one next action (checklist 3.4). It never points at a tab that
     * is still locked: while the team is being set up it points to the
     * welcome page, and the payment plan only once one exists.
     */
    static Map<String, String> nextActionFor(User user, RoadmapFacts f) {
        Map<String, String> action = new LinkedHashMap<>();
        String status = user.getCurrentStatus();
        if (status == null) {
            action.put("label", "Continue your onboarding");
            action.put("href", "/enroll");
            return action;
        }
        if (user.getParticipantId() != null && !ProfileCompletionService.allStepsComplete(user)) {
            return onboardingActionFor(user);
        }
        WorkflowService.Status s = statusOf(user);
        if (!f.teamReady()) {
            action.put("label", "See your team being set up");
            action.put("href", "/welcome");
        } else if (f.planAwaitingAcceptance() && atLeast(s, WorkflowService.Status.PHASE_1_COMPLETED)) {
            action.put("label", "Review and accept your payment plan");
            action.put("href", "#payments");
        } else if (atLeast(s, WorkflowService.Status.PAYMENT_PLAN_ACCEPTED)) {
            action.put("label", "Check your invoices and payments");
            action.put("href", "#payments");
        } else if (f.employmentReturned()) {
            action.put("label", "Correct your employment details");
            action.put("href", "#employment");
        } else if (atLeast(s, WorkflowService.Status.PHASE_1_COMPLETED)) {
            if (f.hasPlan()) {
                action.put("label", "Review and accept your payment plan");
                action.put("href", "#payments");
            } else {
                action.put("label", "Finance is preparing your payment plan");
                action.put("href", "#home");
            }
        } else if (f.lastWeekOwed()) {
            action.put("label", "Submit last week's report");
            action.put("href", "#weekly");
        } else if (atLeast(s, WorkflowService.Status.EMPLOYMENT_ACCEPTED)) {
            action.put("label", "Review your employment and Phase 1 completion");
            action.put("href", "#employment");
        } else if (f.reportsSubmitted() == 0) {
            action.put("label", "Submit your first weekly report");
            action.put("href", "#weekly");
        } else {
            action.put("label", "Submit this week's report");
            action.put("href", "#weekly");
        }
        return action;
    }

    private static int weeksEnrolledFor(User user) {
        if (user.getCreatedAt() == null) return 0;
        long days = java.time.Duration
                .between(user.getCreatedAt(), java.time.LocalDateTime.now())
                .toDays();
        return (int) Math.max(0, days / 7);
    }

}
