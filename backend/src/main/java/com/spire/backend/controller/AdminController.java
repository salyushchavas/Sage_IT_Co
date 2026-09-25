package com.spire.backend.controller;

import com.spire.backend.dto.AdminEnrollmentRow;
import com.spire.backend.dto.AdminSessionRow;
import com.spire.backend.dto.ApiResponse;
import com.spire.backend.dto.CourseDTO;
import com.spire.backend.dto.InstructorRequestDTO;
import com.spire.backend.dto.ParticipantDocumentDTO;
import com.spire.backend.dto.ProfileDTO;
import com.spire.backend.dto.UserDTO;
import com.spire.backend.entity.ParticipantDocument;
import com.spire.backend.entity.Payment;
import com.spire.backend.service.AdminRevenueService;
import com.spire.backend.service.AdminService;
import com.spire.backend.service.CoachAssignmentService;
import com.spire.backend.service.CourseService;
import com.spire.backend.service.DocumentService;
import com.spire.backend.service.ErmAssignmentService;
import com.spire.backend.service.InstructorRequestService;
import com.spire.backend.service.OnboardingService;
import com.spire.backend.service.ProfileService;
import com.spire.backend.repository.CoachAssignmentRepository;
import com.spire.backend.repository.ErmAssignmentRepository;
import com.spire.backend.repository.UserRepository;
import com.spire.backend.entity.CoachAssignment;
import com.spire.backend.entity.ErmAssignment;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','OPERATIONS_ADMIN','SYSTEM_ADMIN')")  // Double-layer: URL config + method-level
public class AdminController {

    private final AdminService adminService;
    private final CourseService courseService;
    private final InstructorRequestService instructorRequestService;
    private final ProfileService profileService;
    private final AdminRevenueService adminRevenueService;
    private final DocumentService documentService;
    private final ErmAssignmentService ermAssignmentService;
    private final CoachAssignmentService coachAssignmentService;
    private final OnboardingService onboardingService;
    private final UserRepository userRepository;
    private final ErmAssignmentRepository ermAssignmentRepository;
    private final CoachAssignmentRepository coachAssignmentRepository;
    private final com.spire.backend.repository.UserRecordRepository userRecordRepository;
    private final com.spire.backend.repository.AgreementAcceptanceRepository agreementAcceptanceRepository;
    private final com.spire.backend.service.EmailLogService emailLogService;
    private final com.spire.backend.service.AgreementQueueService agreementQueueService;
    private final com.spire.backend.service.OperationsExceptionService operationsExceptionService;
    private final com.spire.backend.service.StaffOnboardingService staffOnboardingService;

    // CSV timestamps render in business time (checklist 5.3: US Central).
    // The DB stores LocalDateTime (timezone-naive, server-local = UTC on
    // Railway) so we rebase before formatting. Headers carry a "(CT)"
    // suffix so a recipient downloading the file knows the zone.
    @org.springframework.beans.factory.annotation.Value("${app.business-zone:America/Chicago}")
    private String businessZone;
    private static final DateTimeFormatter CSV_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @GetMapping("/analytics")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getAnalytics() {
        return ResponseEntity.ok(ApiResponse.success(adminService.getAnalytics()));
    }

    @GetMapping("/users")
    public ResponseEntity<ApiResponse<List<UserDTO>>> getAllUsers(
            @RequestParam(value = "status", required = false) String status) {
        return ResponseEntity.ok(ApiResponse.success(adminService.getAllUsers(status)));
    }

    /**
     * Active / inactive / total user counts. Drives the count badges
     * on the admin Users tab pills (Active users (15) / Deactivated
     * users (3)).
     */
    // ─── Staff onboarding ────────────────────────────────────────────

    /**
     * Adds a staff member (System Admin only): company login email, role and
     * personal email. A temporary password is emailed to the personal email;
     * they choose their own at first sign-in.
     */
    @PostMapping("/users")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createStaffUser(
            @RequestBody Map<String, String> body, Authentication authentication) {
        Long callerId = Long.parseLong(authentication.getPrincipal().toString());
        var result = staffOnboardingService.createStaff(callerId, body.get("fullName"), body.get("email"),
                body.get("personalEmail"), body.get("role"));
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED).body(ApiResponse.success(
                result.emailSent() ? "Account created; login details emailed to " + result.sentTo()
                        : "Account created, but the login email couldn't be sent. Use \"Send new login details\" once email works.",
                Map.of("user", result.user(), "emailSent", result.emailSent(), "sentTo", result.sentTo())));
    }

    /** A new temporary password for a staff member, emailed to them (System Admin only). */
    @PostMapping("/users/{id}/send-login")
    public ResponseEntity<ApiResponse<Map<String, Object>>> sendNewLoginDetails(
            @PathVariable Long id, Authentication authentication) {
        Long callerId = Long.parseLong(authentication.getPrincipal().toString());
        var result = staffOnboardingService.sendNewLoginDetails(callerId, id);
        return ResponseEntity.ok(ApiResponse.success(
                result.emailSent() ? "New login details emailed to " + result.sentTo()
                        : "The new login details couldn't be emailed. Check the email log.",
                Map.of("emailSent", result.emailSent(), "sentTo", result.sentTo())));
    }

    /** Emails someone an invitation to enroll as a participant (System Admin or Operations). */
    @PostMapping("/users/invite-participant")
    public ResponseEntity<ApiResponse<Map<String, Object>>> inviteParticipant(
            @RequestBody Map<String, String> body, Authentication authentication) {
        Long callerId = Long.parseLong(authentication.getPrincipal().toString());
        boolean sent = staffOnboardingService.inviteParticipant(callerId, body.get("fullName"), body.get("email"));
        return ResponseEntity.ok(ApiResponse.success(
                sent ? "Invitation emailed" : "The invitation couldn't be sent. Check the email log.",
                Map.of("emailSent", sent)));
    }

    @GetMapping("/users/counts")
    public ResponseEntity<ApiResponse<Map<String, Long>>> getUserCounts() {
        return ResponseEntity.ok(ApiResponse.success(adminService.getUserCounts()));
    }

    /**
     * Reactivates a previously soft-deactivated account. Sugar over
     * {@link #updateUserStatus} with active=true; exists as its own
     * verb so the admin UI's "Reactivate" button has a clean,
     * intent-revealing URL and so we can later add reactivate-only
     * side-effects (welcome-back email, etc.) without touching the
     * generic toggle.
     */
    @PutMapping("/users/{id}/reactivate")
    public ResponseEntity<ApiResponse<UserDTO>> reactivateUser(
            @PathVariable Long id,
            Authentication authentication) {
        Long currentAdminId = Long.parseLong(authentication.getPrincipal().toString());
        UserDTO user = adminService.updateUserStatus(id, currentAdminId, true);
        return ResponseEntity.ok(ApiResponse.success("User reactivated", user));
    }

    // Admin oversight: full profile of any user — name, contact, learning
    // stats, activity analytics, contribution heatmap.
    @GetMapping("/users/{userId}/profile")
    public ResponseEntity<ApiResponse<ProfileDTO>> getUserProfile(@PathVariable Long userId) {
        return ResponseEntity.ok(ApiResponse.success(profileService.getProfile(userId)));
    }

    @PutMapping("/users/{id}/role")
    public ResponseEntity<ApiResponse<UserDTO>> updateUserRole(
            @PathVariable Long id, @RequestBody Map<String, String> body,
            Authentication authentication) {
        String role = body.get("role");
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("Role is required");
        }
        Long callerId = Long.parseLong(authentication.getPrincipal().toString());
        UserDTO user = adminService.updateUserRole(id, role, callerId);
        return ResponseEntity.ok(ApiResponse.success("Role updated", user));
    }

    @PutMapping("/users/{id}/status")
    public ResponseEntity<ApiResponse<UserDTO>> updateUserStatus(
            @PathVariable Long id,
            @RequestBody Map<String, Boolean> body,
            Authentication authentication) {
        Boolean active = body.get("active");
        if (active == null) {
            throw new IllegalArgumentException("'active' field is required");
        }
        Long currentAdminId = Long.parseLong(authentication.getPrincipal().toString());
        UserDTO user = adminService.updateUserStatus(id, currentAdminId, active);
        return ResponseEntity.ok(ApiResponse.success(
                active ? "User activated" : "User deactivated", user));
    }

    /**
     * Soft-delete: deactivates the account and scrubs personal data.
     * Foreign-key heavy entities (records, certificates, enrollments)
     * stay intact for audit. See {@link AdminService#softDeleteUser}.
     */
    @DeleteMapping("/users/{id}")
    public ResponseEntity<ApiResponse<UserDTO>> softDeleteUser(
            @PathVariable Long id,
            Authentication authentication) {
        Long currentAdminId = Long.parseLong(authentication.getPrincipal().toString());
        UserDTO user = adminService.softDeleteUser(id, currentAdminId);
        return ResponseEntity.ok(ApiResponse.success(
                "User deactivated and anonymized", user));
    }

    // ─── Platform-wide oversight ────────────────────────────────────

    @GetMapping("/enrollments")
    public ResponseEntity<ApiResponse<List<AdminEnrollmentRow>>> getAllEnrollments() {
        return ResponseEntity.ok(ApiResponse.success(adminService.getAllEnrollments()));
    }

    @GetMapping("/sessions")
    public ResponseEntity<ApiResponse<List<AdminSessionRow>>> getAllSessions() {
        return ResponseEntity.ok(ApiResponse.success(adminService.getAllSessions()));
    }

    // ─── All Courses (including unpublished) ─────────────────────────

    @GetMapping("/courses")
    public ResponseEntity<ApiResponse<List<CourseDTO>>> getAllCourses() {
        return ResponseEntity.ok(ApiResponse.success(courseService.getAllCoursesAdmin()));
    }

    // ─── Instructor Approval System ─────────────────────────────────

    @GetMapping("/instructor-requests")
    public ResponseEntity<ApiResponse<List<InstructorRequestDTO>>> getPendingRequests() {
        List<InstructorRequestDTO> dtos = instructorRequestService.getPendingRequests()
                .stream().map(InstructorRequestDTO::from).toList();
        return ResponseEntity.ok(ApiResponse.success(dtos));
    }

    @PutMapping("/approve-instructor/{requestId}")
    public ResponseEntity<ApiResponse<String>> approveInstructor(@PathVariable Long requestId) {
        instructorRequestService.approveInstructor(requestId);
        return ResponseEntity.ok(ApiResponse.success("Instructor approved successfully"));
    }

    @PutMapping("/reject-instructor/{requestId}")
    public ResponseEntity<ApiResponse<String>> rejectInstructor(@PathVariable Long requestId) {
        instructorRequestService.rejectInstructor(requestId);
        return ResponseEntity.ok(ApiResponse.success("Instructor request rejected"));
    }

    // ─── Revenue Dashboard ───────────────────────────────────────────

    @GetMapping("/revenue/summary")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getRevenueSummary() {
        return ResponseEntity.ok(ApiResponse.success(adminRevenueService.getSummary()));
    }

    @GetMapping("/revenue/transactions")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getRevenueTransactions(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(ApiResponse.success(
                adminRevenueService.getTransactions(from, to, status)));
    }

    // ─── CSV Exports ─────────────────────────────────────────────────

    @GetMapping("/export/users")
    public void exportUsers(HttpServletResponse response) throws IOException {
        prepareCsv(response, "users");
        PrintWriter w = response.getWriter();
        w.println("ID,FullName,Email,Role,Active,InstructorApproved,CreatedAt (" + com.spire.backend.service.BusinessTime.label(businessZone) + ")");
        for (UserDTO u : adminService.getAllUsers()) {
            w.println(String.join(",",
                    csv(u.getId()),
                    csv(u.getFullName()),
                    csv(u.getEmail()),
                    csv(u.getRole()),
                    csv(u.getIsActive()),
                    csv(u.getInstructorApproved()),
                    csv(u.getCreatedAt())));
        }
        w.flush();
    }

    @GetMapping("/export/enrollments")
    public void exportEnrollments(HttpServletResponse response) throws IOException {
        prepareCsv(response, "enrollments");
        PrintWriter w = response.getWriter();
        w.println("EnrollmentID,StudentName,StudentEmail,CourseTitle,Type,EnrolledAt (" + com.spire.backend.service.BusinessTime.label(businessZone) + "),Progress%,Completed,Mentor,MentorStatus");
        for (AdminEnrollmentRow r : adminService.getAllEnrollments()) {
            w.println(String.join(",",
                    csv(r.getEnrollmentId()),
                    csv(r.getStudentName()),
                    csv(r.getStudentEmail()),
                    csv(r.getCourseTitle()),
                    csv(r.getCourseType()),
                    csv(r.getEnrolledAt()),
                    csv(r.getProgressPercent()),
                    csv(r.getCompleted()),
                    csv(r.getMentorName()),
                    csv(r.getMentorAssignmentStatus())));
        }
        w.flush();
    }

    @GetMapping("/export/sessions")
    public void exportSessions(HttpServletResponse response) throws IOException {
        prepareCsv(response, "sessions");
        PrintWriter w = response.getWriter();
        w.println("SessionID,StudentName,StudentEmail,Mentor,CourseTitle,Status,Topic,RequestedAt (" + com.spire.backend.service.BusinessTime.label(businessZone) + "),ScheduledAt (" + com.spire.backend.service.BusinessTime.label(businessZone) + "),CompletedAt (" + com.spire.backend.service.BusinessTime.label(businessZone) + "),MeetingURL");
        for (AdminSessionRow r : adminService.getAllSessions()) {
            w.println(String.join(",",
                    csv(r.getSessionId()),
                    csv(r.getStudentName()),
                    csv(r.getStudentEmail()),
                    csv(r.getMentorName()),
                    csv(r.getCourseTitle()),
                    csv(r.getStatus()),
                    csv(r.getTopic()),
                    csv(r.getRequestedAt()),
                    csv(r.getScheduledAt()),
                    csv(r.getCompletedAt()),
                    csv(r.getMeetingUrl())));
        }
        w.flush();
    }

    @GetMapping("/export/revenue")
    public void exportRevenue(HttpServletResponse response) throws IOException {
        prepareCsv(response, "revenue");
        PrintWriter w = response.getWriter();
        w.println("PaymentID,StudentName,StudentEmail,Amount,Currency,Status,Provider,PaymentRef,OrderOrSessionRef,CreatedAt (" + com.spire.backend.service.BusinessTime.label(businessZone) + ")");
        for (Payment p : adminRevenueService.getAllPaymentsRaw()) {
            w.println(String.join(",",
                    csv(p.getId()),
                    csv(p.getUser() != null ? p.getUser().getFullName() : null),
                    csv(p.getUser() != null ? p.getUser().getEmail() : null),
                    csv(p.getAmount()),
                    csv("USD"),
                    csv(p.getStatus()),
                    csv(p.getProvider() == null ? "RAZORPAY" : p.getProvider()),
                    csv(p.getRazorpayPaymentId() != null ? p.getRazorpayPaymentId() : p.getStripePaymentIntentId()),
                    csv(p.getRazorpayOrderId() != null ? p.getRazorpayOrderId() : p.getStripeSessionId()),
                    csv(p.getCreatedAt())));
        }
        w.flush();
    }

    private void prepareCsv(HttpServletResponse response, String name) {
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition",
                "attachment; filename=spire-" + name + "-" + LocalDate.now() + ".csv");
    }

    private String csv(Object value) {
        if (value == null) return "";
        String s = value instanceof LocalDateTime
                ? ((LocalDateTime) value)
                        .atZone(ZoneId.systemDefault())
                        .withZoneSameInstant(com.spire.backend.service.BusinessTime.zone(businessZone))
                        .toLocalDateTime()
                        .format(CSV_TS)
                : value.toString();
        // A cell starting with = + - @ (or tab / return) runs as a formula when
        // the export is opened in Excel: a name like =HYPERLINK(...) would.
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0 && !(value instanceof Number)) {
            s = "'" + s;
        }
        boolean needsQuote = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r");
        if (needsQuote) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    // ─── Phase 2B: document review queue ────────────────────────────

    /**
     * Operations document review screen. {@code status}: NEEDS_REVIEW
     * (default: uploads waiting for a decision plus "not applicable"
     * exception requests, oldest first), ALL, or one review status.
     * Rows carry the participant's name, email and Participant ID; the
     * file itself is opened through the logged view endpoint.
     */
    @GetMapping("/documents")
    public ResponseEntity<ApiResponse<List<DocumentService.ReviewRow>>> listDocumentsForReview(
            @RequestParam(value = "status", required = false, defaultValue = "NEEDS_REVIEW") String status) {
        return ResponseEntity.ok(ApiResponse.success(documentService.reviewQueue(status)));
    }

    /**
     * Approve / reject a participant document, or approve / decline a
     * "not applicable" exception request. Body: { status:
     * "APPROVED"|"REJECTED", notes: "..." }. Rejecting needs a reason:
     * the participant is emailed it and sees it on the upload page.
     */
    @PutMapping("/documents/{documentId}/review")
    public ResponseEntity<ApiResponse<ParticipantDocumentDTO>> reviewDocument(
            @PathVariable Long documentId,
            @RequestBody Map<String, String> body,
            Authentication auth) {
        Long reviewerId = Long.parseLong(auth.getPrincipal().toString());
        String newStatus = body.get("status");
        String notes = body.get("notes");
        ParticipantDocument saved = documentService.review(documentId, reviewerId, newStatus, notes);
        String message = switch (saved.getReviewStatus()) {
            case DocumentService.APPROVED -> "Document approved";
            case DocumentService.EXCEPTION_APPROVED -> "Exception approved";
            case DocumentService.EXCEPTION_DECLINED -> "Exception declined";
            default -> "Document rejected";
        };
        return ResponseEntity.ok(ApiResponse.success(message, ParticipantDocumentDTO.from(saved)));
    }

    // ─── Phase 4: assignment queue (manual ERM / coach assignment) ──

    /**
     * Returns participants whose onboarding chain is stuck waiting
     * on a manual ERM / coach assignment. Used by the Operations
     * admin "Assignments" tab.
     */
    @GetMapping("/assignments/queue")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> assignmentQueue() {
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        // Every active participant whose onboarding reached the ERM step
        // (agreement signed, check step done) and who has no active ERM or
        // an empty coach slot, whatever their status: the dashboard may be
        // open already, or their ERM or coach may have been deactivated.
        for (com.spire.backend.entity.User u : userRepository.findAll()) {
            if (Boolean.FALSE.equals(u.getIsActive()) || u.getRole() == null) continue;
            String role = u.getRole().getName() == null ? "" : u.getRole().getName().toUpperCase();
            if (!role.equals("PARTICIPANT") && !role.equals("STUDENT")) continue;
            if (!Boolean.TRUE.equals(u.getAgreementComplete()) || !Boolean.TRUE.equals(u.getCheckUploadComplete())) continue;
            boolean hasErm = ermAssignmentService.getAssignedErm(u.getId()).isPresent();
            List<String> emptySlots = coachAssignmentService.emptySlots(u.getId());
            if (hasErm && emptySlots.isEmpty()) continue;

            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("userId", u.getId());
            row.put("participantId", u.getParticipantId());
            row.put("fullName", u.getFullName());
            row.put("email", u.getEmail());
            row.put("skillset", u.getSelectedTechnology());
            row.put("currentStatus", u.getCurrentStatus());
            row.put("ermAssigned", hasErm);
            row.put("coachesAssigned", emptySlots.size() < com.spire.backend.service.CoachAssignmentService.COACH_ROLES.size());
            row.put("emptyCoachSlots", emptySlots);
            rows.add(row);
        }
        return ResponseEntity.ok(ApiResponse.success(rows));
    }

    /**
     * Manually assigns an ERM to a participant. Body: {@code
     * { ermUserId: 42 }}. After saving the assignment row, re-runs
     * the OnboardingService chain so the participant's workflow
     * rolls forward without waiting on a scheduled tick.
     */
    @PutMapping("/assignments/erm/{participantId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> assignErm(
            @PathVariable Long participantId,
            @RequestBody Map<String, Object> body,
            org.springframework.security.core.Authentication auth) {
        Object ermIdRaw = body.get("ermUserId");
        if (ermIdRaw == null) throw new IllegalArgumentException("ermUserId is required");
        Long ermUserId = ermIdRaw instanceof Number n ? n.longValue() : Long.parseLong(ermIdRaw.toString());

        com.spire.backend.entity.User participant = userRepository.findById(participantId)
                .orElseThrow(() -> new com.spire.backend.exception.ResourceNotFoundException(
                        "User", "id", participantId));
        com.spire.backend.entity.User erm = userRepository.findById(ermUserId)
                .orElseThrow(() -> new com.spire.backend.exception.ResourceNotFoundException(
                        "User", "id", ermUserId));

        // An active ERM only; a new row keeps the history (see assignManually).
        Long operatorId = auth == null ? null : Long.parseLong(auth.getPrincipal().toString());
        ermAssignmentService.assignManually(participant, erm, operatorId);

        // Bring a new ERM up to date (the signed agreement and the
        // participant introduction, if those steps already happened),
        // then push the chain forward — which opens the dashboard once
        // a coach is in place too.
        onboardingService.ermAssignedByOperations(participant);
        com.spire.backend.entity.User refreshed = userRepository.findById(participantId).orElse(participant);
        return ResponseEntity.ok(ApiResponse.success(
                "ERM assigned",
                Map.of(
                        "ermUserId", erm.getId(),
                        "ermName", erm.getFullName() == null ? "" : erm.getFullName(),
                        "workflowStatus", refreshed.getCurrentStatus()
                )));
    }

    /**
     * Manually assigns a coach to a participant for a specific
     * coach_role. Body: {@code { coachUserId: 42, coachRole:
     * "CAREER_COACH" }}. Re-runs the OnboardingService chain after.
     */
    @PutMapping("/assignments/coach/{participantId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> assignCoach(
            @PathVariable Long participantId,
            @RequestBody Map<String, Object> body,
            Authentication auth) {
        Object coachIdRaw = body.get("coachUserId");
        String coachRole = (String) body.get("coachRole");
        if (coachIdRaw == null || coachRole == null || coachRole.isBlank()) {
            throw new IllegalArgumentException("coachUserId and coachRole are required");
        }
        Long coachUserId = coachIdRaw instanceof Number n ? n.longValue() : Long.parseLong(coachIdRaw.toString());
        com.spire.backend.entity.User participant = userRepository.findById(participantId)
                .orElseThrow(() -> new com.spire.backend.exception.ResourceNotFoundException(
                        "User", "id", participantId));
        com.spire.backend.entity.User coach = userRepository.findById(coachUserId)
                .orElseThrow(() -> new com.spire.backend.exception.ResourceNotFoundException(
                        "User", "id", coachUserId));

        // Checklist 3.2: a real coach in a real slot; the previous coach in
        // that slot is ended (kept for history), the new one is emailed.
        coachAssignmentService.assignManually(participant, coach, coachRole,
                Long.parseLong(auth.getPrincipal().toString()));

        onboardingService.completeOnboarding(participant);
        com.spire.backend.entity.User refreshed = userRepository.findById(participantId).orElse(participant);
        return ResponseEntity.ok(ApiResponse.success(
                "Coach assigned",
                Map.of(
                        "coachUserId", coachUserId,
                        "coachRole", coachRole,
                        "workflowStatus", refreshed.getCurrentStatus()
                )));
    }

    /**
     * Checklist 3.2: which coach slots a coach fills and which skills they
     * cover, so automatic matching puts the right coach in each slot.
     * Body: { coachTypes: ["CAREER_COACH", ...], coachSkills: ["Java Full Stack", ...] }.
     */
    @PutMapping("/coaches/{coachUserId}/profile")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateCoachProfile(
            @PathVariable Long coachUserId,
            @RequestBody Map<String, List<String>> body) {
        com.spire.backend.entity.User coach = userRepository.findById(coachUserId)
                .orElseThrow(() -> new com.spire.backend.exception.ResourceNotFoundException(
                        "User", "id", coachUserId));
        if (!coachAssignmentService.isActiveCoach(coach)) {
            throw new IllegalArgumentException("That person isn't an active coach.");
        }
        String types = com.spire.backend.service.CoachProfiles.types(body.get("coachTypes"));
        if (types.isEmpty()) throw new IllegalArgumentException("Pick at least one coach type.");
        coach.setCoachTypes(types);
        coach.setCoachSkills(com.spire.backend.service.CoachProfiles.skills(body.get("coachSkills")));
        userRepository.save(coach);
        return ResponseEntity.ok(ApiResponse.success("Coach profile saved", Map.of(
                "coachTypes", coach.getCoachTypes(), "coachSkills", coach.getCoachSkills())));
    }

    // ─── Phase 5B Operations tabs ───────────────────────────────────

    /**
     * Participants stuck at DRAFT_STARTED or BASIC_INFO_SUBMITTED —
     * Operations Admin's "Enrollment Queue" tab.
     */
    @GetMapping("/operations/enrollment-queue")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> enrollmentQueue() {
        java.util.Set<String> incomplete = java.util.Set.of(
                "DRAFT_STARTED", "BASIC_INFO_SUBMITTED", "EMAIL_VERIFICATION_PENDING");
        List<Map<String, Object>> rows = userRepository.findAll().stream()
                .filter(u -> incomplete.contains(u.getCurrentStatus() == null ? "" : u.getCurrentStatus()))
                .map(u -> {
                    Map<String, Object> r = new java.util.LinkedHashMap<>();
                    r.put("userId", u.getId());
                    r.put("fullName", u.getFullName());
                    r.put("email", u.getEmail());
                    r.put("currentStatus", u.getCurrentStatus());
                    r.put("createdAt", u.getCreatedAt());
                    r.put("emailVerified", Boolean.TRUE.equals(u.getEmailVerified()));
                    return r;
                })
                .toList();
        return ResponseEntity.ok(ApiResponse.success(rows));
    }

    /**
     * Operations' agreement queue (checklist 2.5): declined, expired and
     * waiting agreements, signed ones still missing the check step or an
     * ERM, and ones with the ERM for review (AgreementQueueService).
     */
    @GetMapping("/operations/agreement-queue")
    public ResponseEntity<ApiResponse<List<com.spire.backend.service.AgreementQueueService.Row>>> agreementQueue() {
        return ResponseEntity.ok(ApiResponse.success(agreementQueueService.queue()));
    }

    /**
     * Audit trail — user_records for any participant, filtered by
     * category or date range. Drives the "Audit Trail" tab.
     */
    @GetMapping("/operations/audit")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> auditTrail(
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "limit", required = false, defaultValue = "200") Integer limit) {
        var stream = userId != null
                ? userRecordRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                : userRecordRepository.findAll().stream()
                        .sorted((a, b) -> {
                            if (a.getCreatedAt() == null) return 1;
                            if (b.getCreatedAt() == null) return -1;
                            return b.getCreatedAt().compareTo(a.getCreatedAt());
                        });
        List<Map<String, Object>> rows = stream
                .filter(r -> category == null || category.isBlank()
                        || category.equalsIgnoreCase(r.getCategory()))
                .limit(Math.max(1, Math.min(limit == null ? 200 : limit, 1000)))
                .map(r -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("id", r.getId());
                    row.put("userId", r.getUserId());
                    row.put("recordType", r.getRecordType());
                    row.put("category", r.getCategory());
                    row.put("title", r.getTitle());
                    row.put("description", r.getDescription());
                    row.put("createdAt", r.getCreatedAt());
                    return row;
                })
                .toList();
        return ResponseEntity.ok(ApiResponse.success(rows));
    }

    /**
     * Email log (checklist 1.4): every email the platform tried to send,
     * newest first, with SENT / FAILED / SKIPPED and the reason when it
     * failed. Filter by status, a user id, or an email address.
     */
    @GetMapping("/operations/emails")
    public ResponseEntity<ApiResponse<List<com.spire.backend.service.EmailLogService.Row>>> emailLog(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "email", required = false) String email) {
        return ResponseEntity.ok(ApiResponse.success(emailLogService.list(status, userId, email)));
    }

    /**
     * Checklist 6.2: every exception case in the roadmap (§14) plus emails
     * that couldn't be delivered, each with the participant's name and ID,
     * what's wrong, since when and where to fix it.
     */
    @GetMapping("/operations/exceptions")
    public ResponseEntity<ApiResponse<List<com.spire.backend.service.OperationsExceptionService.Row>>> exceptions() {
        return ResponseEntity.ok(ApiResponse.success(operationsExceptionService.all()));
    }

    /**
     * Available staff for the Assignments tab dropdowns. Returns
     * ERM-eligible users and coach-eligible users grouped by role.
     */
    @GetMapping("/operations/staff-pool")
    public ResponseEntity<ApiResponse<Map<String, List<Map<String, Object>>>>> staffPool() {
        Map<String, List<Map<String, Object>>> out = new java.util.LinkedHashMap<>();
        java.util.function.Function<com.spire.backend.entity.User, Map<String, Object>> toRow = u ->
                Map.of("id", u.getId(), "fullName", u.getFullName() == null ? "" : u.getFullName(),
                        "email", u.getEmail() == null ? "" : u.getEmail(),
                        // Checklist 3.2: coach slots and skills (defaults when none are set).
                        "coachTypes", List.copyOf(com.spire.backend.service.CoachProfiles.typesOf(u)),
                        "coachSkills", u.getCoachSkills() == null || u.getCoachSkills().isBlank()
                                ? List.of() : List.of(u.getCoachSkills().split(",")));
        var byRole = userRepository.findAll().stream()
                .filter(u -> Boolean.TRUE.equals(u.getIsActive()))
                .filter(u -> u.getRole() != null && u.getRole().getName() != null)
                .collect(java.util.stream.Collectors.groupingBy(u -> u.getRole().getName().toUpperCase()));
        out.put("erm", byRole.getOrDefault("ERM", java.util.List.of()).stream().map(toRow).toList());
        out.put("coach", byRole.getOrDefault("COACH", java.util.List.of()).stream().map(toRow).toList());
        out.put("technicalAdvisor", byRole.getOrDefault("TECHNICAL_ADVISOR", java.util.List.of()).stream().map(toRow).toList());
        return ResponseEntity.ok(ApiResponse.success(out));
    }
}
