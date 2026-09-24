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

import java.time.DayOfWeek;
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
            "Employment accepted",
            "Phase 1 complete",
            "Payment plan",
            "Payments tracked"
    );

    private final UserRepository userRepository;
    private final ProgramSelectionRepository programSelectionRepository;
    private final UserRecordRepository userRecordRepository;
    private final WeeklyReportRepository weeklyReportRepository;
    private final ErmAssignmentService ermAssignmentService;
    private final CoachAssignmentService coachAssignmentService;
    private final WorkflowService workflowService;

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
        List<Boolean> done = roadmapDone(user);
        out.put("roadmapTotal", ROADMAP_STEPS.size());
        out.put("roadmapStep", currentStep(done));
        out.put("roadmapDone", done);
        out.put("roadmapLabels", ROADMAP_STEPS);
        out.put("nextAction", nextActionFor(user));

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

        // Recent activity (last 5 user_records). Newest first.
        List<UserRecord> all = userRecordRepository.findByUserIdOrderByCreatedAtDesc(userId);
        List<Map<String, Object>> recent = all.stream()
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

        // Current-week report shortcut
        LocalDate weekStart = startOfWeek(LocalDate.now());
        out.put("currentWeekStart", weekStart);
        out.put("currentWeekEnd", weekStart.plusDays(6));
        weeklyReportRepository.findByUserIdAndWeekStart(userId, weekStart).ifPresent(r -> {
            out.put("currentWeekReportStatus", r.getStatus());
            out.put("currentWeekReportId", r.getId());
        });

        return out;
    }

    // ── Helpers ────────────────────────────────────────────────

    /** Maps backend workflow status → 1-20 step number. */
    public static int stepForStatus(String status) {
        if (status == null) return 1;
        return switch (status) {
            case "DRAFT_STARTED", "BASIC_INFO_SUBMITTED" -> 1;
            case "EMAIL_VERIFICATION_PENDING", "EMAIL_VERIFIED" -> 2;
            case "PARTICIPANT_ID_CREATED", "ID_EMAIL_SENT" -> 3;
            case "ACKNOWLEDGMENT_ACCEPTED" -> 4;
            case "DOCUMENTS_SUBMITTED", "DOC_REVIEW_PENDING" -> 5;
            case "PROGRAM_SELECTED" -> 6;
            case "AGREEMENT_SENT" -> 7;
            case "AGREEMENT_COMPLETED" -> 8;
            case "CHECK_COPY_UPLOADED" -> 9;
            case "SIGNED_AGREEMENT_SENT_TO_ERM" -> 10;
            case "WELCOME_SENT" -> 11;
            case "DEEPTHI_INTRO_SENT" -> 12;
            case "ERM_ASSIGNED" -> 13;
            case "COACHES_ASSIGNED" -> 14;
            case "DASHBOARD_ENABLED" -> 15;
            case "WEEKLY_REPORTING_ACTIVE" -> 16;
            case "EMPLOYMENT_ACCEPTED" -> 17;
            case "PHASE_1_COMPLETED" -> 18;
            case "PAYMENT_PLAN_ACCEPTED" -> 19;
            case "CHECK_TRACKING_ADDED", "INVOICING_ACTIVE", "PAYMENTS_TRACKED" -> 20;
            default -> 1;
        };
    }

    /**
     * Whether each of the 20 roadmap steps is done. Onboarding steps
     * (1–9) come from the profile step flags, which only a real
     * submission sets; the later steps from the status, which now only
     * moves forward on real events.
     */
    static List<Boolean> roadmapDone(User u) {
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
                atLeast(s, WorkflowService.Status.SIGNED_AGREEMENT_SENT_TO_ERM),
                atLeast(s, WorkflowService.Status.WELCOME_SENT),
                atLeast(s, WorkflowService.Status.DEEPTHI_INTRO_SENT),
                atLeast(s, WorkflowService.Status.ERM_ASSIGNED),
                atLeast(s, WorkflowService.Status.COACHES_ASSIGNED),
                atLeast(s, WorkflowService.Status.DASHBOARD_ENABLED),
                atLeast(s, WorkflowService.Status.WEEKLY_REPORTING_ACTIVE),
                atLeast(s, WorkflowService.Status.EMPLOYMENT_ACCEPTED),
                atLeast(s, WorkflowService.Status.PHASE_1_COMPLETED),
                atLeast(s, WorkflowService.Status.PAYMENT_PLAN_ACCEPTED),
                atLeast(s, WorkflowService.Status.PAYMENTS_TRACKED));
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

    private static Map<String, String> nextActionFor(User user) {
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
        switch (status) {
            case "DASHBOARD_ENABLED" -> {
                action.put("label", "Submit your first weekly report");
                action.put("href", "#weekly");
            }
            case "WEEKLY_REPORTING_ACTIVE" -> {
                action.put("label", "Submit this week's report");
                action.put("href", "#weekly");
            }
            case "EMPLOYMENT_ACCEPTED" -> {
                action.put("label", "Review your phase-1 completion");
                action.put("href", "#employment");
            }
            case "PHASE_1_COMPLETED" -> {
                action.put("label", "Accept your payment plan");
                action.put("href", "#payments");
            }
            default -> {
                action.put("label", "Stay on track — your team will reach out");
                action.put("href", "#home");
            }
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

    private static LocalDate startOfWeek(LocalDate date) {
        int dow = date.getDayOfWeek().getValue();
        return date.minusDays(dow - DayOfWeek.MONDAY.getValue());
    }
}
