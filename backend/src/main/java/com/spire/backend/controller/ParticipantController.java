package com.spire.backend.controller;

import com.spire.backend.dto.AcknowledgmentSubmitRequest;
import com.spire.backend.dto.ApiResponse;
import com.spire.backend.dto.BasicInfoRequest;
import com.spire.backend.dto.CheckDocumentDTO;
import com.spire.backend.dto.ParticipantDocumentDTO;
import com.spire.backend.dto.ParticipantEnrollRequest;
import com.spire.backend.dto.ProfileCompletionDto;
import com.spire.backend.dto.ProgramSelectionDTO;
import com.spire.backend.dto.ProgramSelectionRequest;
import com.spire.backend.dto.RegistrationResponse;
import com.spire.backend.dto.UserDTO;
import com.spire.backend.dto.WishlistItemDto;
import com.spire.backend.entity.Acknowledgment;
import com.spire.backend.entity.CheckDocument;
import com.spire.backend.entity.ParticipantDocument;
import com.spire.backend.entity.ProgramSelection;
import com.spire.backend.entity.User;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.repository.UserRepository;
import com.spire.backend.service.AcknowledgmentService;
import com.spire.backend.service.AuthService;
import com.spire.backend.service.DocumentService;
import com.spire.backend.service.DocumentStorageService;
import com.spire.backend.service.ParticipantAgreementService;
import com.spire.backend.service.ParticipantCheckService;
import com.spire.backend.service.ProgramSelectionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * Phase 1B participant endpoints.
 *
 *   POST /api/participants/enroll  — public, replaces /signup
 *   GET  /api/participants/me      — auth'd, returns the caller's
 *                                    full profile incl. participantId
 *                                    + currentStatus
 *
 * The /enroll path is intentionally mounted under /api/participants
 * (not /api/auth) so it's clear at a glance which surface a caller
 * is on; the older /api/auth/register endpoint stays operational for
 * legacy clients but new traffic should use this one.
 */
@RestController
@RequestMapping("/api/participants")
@RequiredArgsConstructor
public class ParticipantController {

    private final AuthService authService;
    private final UserRepository userRepository;
    private final AcknowledgmentService acknowledgmentService;
    private final DocumentService documentService;
    private final DocumentStorageService storageService;
    private final ProgramSelectionService programSelectionService;
    private final ParticipantAgreementService participantAgreementService;
    private final ParticipantCheckService participantCheckService;
    private final com.spire.backend.service.OnboardingService onboardingService;
    private final com.spire.backend.service.WorkflowService workflowService;
    private final com.spire.backend.service.ParticipantDashboardService participantDashboardService;
    private final com.spire.backend.service.WeeklyReportService weeklyReportService;
    private final com.spire.backend.service.ErmAssignmentService ermAssignmentService;
    private final com.spire.backend.service.CoachAssignmentService coachAssignmentService;
    private final com.spire.backend.service.EmploymentService employmentService;
    private final com.spire.backend.service.PaymentService paymentService;
    private final com.spire.backend.service.CheckTrackingService checkTrackingService;
    private final com.spire.backend.repository.InvoiceRepository invoiceRepository;
    private final com.spire.backend.repository.PaymentLedgerRepository paymentLedgerRepository;
    private final com.spire.backend.service.ProfileCompletionService profileCompletionService;
    private final com.spire.backend.service.WishlistService wishlistService;
    private final com.spire.backend.service.EnrollmentService enrollmentService;

    /** Public — anyone can enroll. Behind the scenes walks the workflow
     *  ladder DRAFT_STARTED → BASIC_INFO_SUBMITTED → EMAIL_VERIFICATION_PENDING. */
    @PostMapping("/enroll")
    public ResponseEntity<ApiResponse<RegistrationResponse>> enroll(
            @Valid @RequestBody ParticipantEnrollRequest request) {
        RegistrationResponse data = authService.enrollParticipant(request);
        return ResponseEntity.ok(ApiResponse.success("Enrollment received", data));
    }

    /** Auth'd — returns the caller's current profile. Used by the
     *  participant-id page + every routing-guard check on the FE. */
    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<UserDTO>> me(Authentication auth) {
        Long userId = Long.parseLong(auth.getPrincipal().toString());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        return ResponseEntity.ok(ApiResponse.success(UserDTO.from(user)));
    }

    /**
     * Phase 2A — accepts the participant's "Acknowledgment of
     * Interest and Program Acceptance" submission (Step 4).
     * Validates consents + signature, persists an immutable
     * acknowledgments row with the full audit trail, then walks
     * the workflow to ACKNOWLEDGMENT_ACCEPTED.
     */
    @PostMapping("/acknowledgments")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Map<String, Object>>> submitAcknowledgment(
            @Valid @RequestBody AcknowledgmentSubmitRequest request,
            Authentication auth,
            HttpServletRequest httpRequest) {
        Long userId = Long.parseLong(auth.getPrincipal().toString());
        Acknowledgment saved = acknowledgmentService.submit(userId, request, httpRequest);
        return ResponseEntity.ok(ApiResponse.success(
                "Acknowledgment accepted",
                Map.of(
                        "acknowledgmentId", saved.getId(),
                        "version", saved.getAcceptedTextVersion(),
                        "nextStep", "/document-upload",
                        "success", true
                )));
    }

    // ─── Phase 2B: secure document vault ────────────────────────────

    /** Upload a single document for a known type. Multipart only. */
    @PostMapping("/documents/upload")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<ParticipantDocumentDTO>> uploadDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam("documentType") String documentType,
            Authentication auth) {
        Long userId = Long.parseLong(auth.getPrincipal().toString());
        ParticipantDocument saved = documentService.upload(userId, documentType, file);
        return ResponseEntity.ok(ApiResponse.success(
                "Document uploaded",
                ParticipantDocumentDTO.from(saved)));
    }

    /** List every document the caller has uploaded (or marked N/A). */
    @GetMapping("/documents")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<ParticipantDocumentDTO>>> listDocuments(Authentication auth) {
        Long userId = Long.parseLong(auth.getPrincipal().toString());
        List<ParticipantDocumentDTO> docs = documentService.listForUser(userId)
                .stream().map(ParticipantDocumentDTO::from).toList();
        return ResponseEntity.ok(ApiResponse.success(docs));
    }

    /** Remove a document (owner only; rejected by service if APPROVED). */
    @DeleteMapping("/documents/{documentId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteDocument(
            @PathVariable Long documentId,
            Authentication auth) {
        Long userId = Long.parseLong(auth.getPrincipal().toString());
        documentService.delete(documentId, userId);
        return ResponseEntity.ok(ApiResponse.success("Document removed",
                Map.of("removed", true, "documentId", documentId)));
    }

    /** Marks a document type as Not Applicable (e.g. domestic candidates skipping Work Authorization). */
    @PostMapping("/documents/mark-na")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<ParticipantDocumentDTO>> markNotApplicable(
            @RequestBody Map<String, String> body,
            Authentication auth) {
        Long userId = Long.parseLong(auth.getPrincipal().toString());
        String documentType = body.get("documentType");
        if (documentType == null || documentType.isBlank()) {
            throw new IllegalArgumentException("documentType is required");
        }
        ParticipantDocument saved = documentService.markNotApplicable(userId, documentType);
        return ResponseEntity.ok(ApiResponse.success(
                "Marked as N/A",
                ParticipantDocumentDTO.from(saved)));
    }

    /** Completeness gate — returns nextStep + transitions workflow when all required docs are present. */
    @PostMapping("/documents/complete")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Map<String, Object>>> completeDocuments(Authentication auth) {
        Long userId = Long.parseLong(auth.getPrincipal().toString());
        Map<String, Object> result = documentService.complete(userId);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /**
     * Auth-gated view. Owner or admin only. For Cloudinary-backed
     * URLs we issue a 5-minute signed link (PRD §13.1); for
     * local-disk paths we stream the file inline. The local stream
     * stays behind JWT so it provides equivalent gating without
     * a public-URL ever existing.
     */
    @GetMapping("/documents/{documentId}/view")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> viewDocument(
            @PathVariable Long documentId,
            Authentication auth) {
        Long callerId = Long.parseLong(auth.getPrincipal().toString());
        boolean isAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> {
                    String r = a.getAuthority();
                    return "ROLE_ADMIN".equals(r) || "ROLE_OPERATIONS_ADMIN".equals(r)
                            || "ROLE_SYSTEM_ADMIN".equals(r) || "ROLE_ERM".equals(r);
                });
        ParticipantDocument doc = documentService.get(documentId, callerId, isAdmin);

        // N/A markers carry no file.
        if (Boolean.TRUE.equals(doc.getNotApplicable())
                || doc.getFileUrl() == null || doc.getFileUrl().isBlank()) {
            return ResponseEntity.notFound().build();
        }

        String url = doc.getFileUrl();
        if (url.startsWith("http")) {
            // Cloudinary path — return a 302-style payload with the
            // signed URL the client can fetch directly.
            String signed = storageService.signedUrl(url);
            return ResponseEntity.ok(ApiResponse.success(Map.of(
                    "url", signed,
                    "expiresIn", 300
            )));
        }
        // Local-disk path — stream the file inline under auth.
        File file = new File(url);
        if (!file.exists()) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = new FileSystemResource(file);
        String contentType = guessContentType(doc.getFileName());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\""
                                + (doc.getFileName() == null ? "document" : doc.getFileName())
                                + "\"")
                .body(resource);
    }

    private static String guessContentType(String filename) {
        if (filename == null) return "application/octet-stream";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        return "application/octet-stream";
    }

    // ─── Phase 3A: program selection ────────────────────────────────

    /**
     * Read the participant's current (or in-progress draft) program
     * selection. Returns 200 with {@code null} data when none exists
     * yet — keeps the FE pre-fill logic simple.
     */
    @GetMapping("/program-selection")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<ProgramSelectionDTO>> getProgramSelection(Authentication auth) {
        Long userId = Long.parseLong(auth.getPrincipal().toString());
        ProgramSelectionDTO dto = programSelectionService.getCurrent(userId)
                .map(ProgramSelectionDTO::from).orElse(null);
        return ResponseEntity.ok(ApiResponse.success(dto));
    }

    /**
     * Final submit. Validates all required fields, transitions
     * workflow to PROGRAM_SELECTED, fires the confirmation email
     * (participant + internal operations CC), returns nextStep.
     */
    @PostMapping("/program-selection")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Map<String, Object>>> submitProgramSelection(
            @RequestBody ProgramSelectionRequest request,
            Authentication auth) {
        Long userId = Long.parseLong(auth.getPrincipal().toString());
        ProgramSelection saved = programSelectionService.submit(userId, request);
        return ResponseEntity.ok(ApiResponse.success(
                "Program selection saved",
                Map.of(
                        "selectionId", saved.getId(),
                        "serviceSummaryVersion", saved.getServiceSummaryVersion(),
                        "nextStep", "/agreement",
                        "success", true
                )));
    }

    /**
     * Partial save — "Save and Continue Later". Mutates the same
     * row in place, no validation, no workflow transition. Returns
     * the saved row so the page can confirm fields persisted.
     */
    @PostMapping("/program-selection/draft")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<ProgramSelectionDTO>> saveProgramSelectionDraft(
            @RequestBody ProgramSelectionRequest request,
            Authentication auth) {
        Long userId = Long.parseLong(auth.getPrincipal().toString());
        ProgramSelection saved = programSelectionService.saveDraft(userId, request);
        return ResponseEntity.ok(ApiResponse.success(
                "Draft saved",
                ProgramSelectionDTO.from(saved)));
    }

    // ─── Phase 3B: agreement signing (on-site, one-click) ───────────

    /**
     * Sign the agreement on the website. One round-trip:
     *   - persists VERIFIED row with full audit trail
     *   - generates signed PDF and emails it
     *   - advances workflow through AGREEMENT_SENT → AGREEMENT_COMPLETED
     *     → SIGNED_AGREEMENT_SENT_TO_ERM
     *   - kicks off the onboarding chain (welcome, coordinator,
     *     ERM, coaches, dashboard)
     */
    @PostMapping("/agreement/sign")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Map<String, Object>>> signAgreement(
            @RequestBody Map<String, Object> body,
            Authentication auth,
            HttpServletRequest httpRequest) {
        Long userId = Long.parseLong(auth.getPrincipal().toString());
        String legalName = (String) body.get("legalName");
        String signatureImage = (String) body.get("signatureImage");
        Object methodObj = body.getOrDefault("signatureMethod", "draw");
        String signatureMethod = methodObj == null ? "draw" : methodObj.toString();
        if (legalName == null || legalName.isBlank()
                || signatureImage == null || signatureImage.isBlank()) {
            throw new IllegalArgumentException("legalName and signatureImage are required");
        }
        Map<String, Object> data = participantAgreementService.sign(
                userId, legalName, signatureImage, signatureMethod,
                clientIp(httpRequest), httpRequest.getHeader("User-Agent"));
        return ResponseEntity.ok(ApiResponse.success("Agreement signed", data));
    }

    /** Legacy endpoints — removed in favour of /agreement/sign. */
    @PostMapping({"/agreement/send", "/agreement/verify-code"})
    public ResponseEntity<ApiResponse<Map<String, Object>>> legacyAgreementGone() {
        return ResponseEntity.status(410).body(ApiResponse.error(
                "This endpoint has been removed. Use POST /api/participants/agreement/sign."));
    }

    @GetMapping("/agreement/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> legacyAgreementStatusGone() {
        return ResponseEntity.status(410).body(ApiResponse.error(
                "This endpoint has been removed. Use POST /api/participants/agreement/sign."));
    }

    // ─── Phase 3B: check soft-copy upload ───────────────────────────

    @PostMapping("/checks/upload")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<CheckDocumentDTO>> uploadCheck(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "checkNumber", required = false) String checkNumber,
            @RequestParam(value = "amount", required = false) java.math.BigDecimal amount,
            @RequestParam(value = "checkDate", required = false) String checkDate,
            @RequestParam(value = "notes", required = false) String notes,
            Authentication auth) {
        Long userId = Long.parseLong(auth.getPrincipal().toString());
        java.time.LocalDate date = null;
        if (checkDate != null && !checkDate.isBlank()) {
            try { date = java.time.LocalDate.parse(checkDate); }
            catch (Exception e) {
                throw new IllegalArgumentException("checkDate must be YYYY-MM-DD");
            }
        }
        CheckDocument saved = participantCheckService.upload(
                userId, file, checkNumber, amount, date, notes);
        return ResponseEntity.ok(ApiResponse.success("Check uploaded",
                CheckDocumentDTO.from(saved)));
    }

    @PostMapping("/checks/mark-na")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Map<String, Object>>> markCheckNotApplicable(Authentication auth) {
        Long userId = Long.parseLong(auth.getPrincipal().toString());
        participantCheckService.markNotApplicable(userId);
        return ResponseEntity.ok(ApiResponse.success("Marked as N/A",
                Map.of("workflowStatus", "CHECK_COPY_UPLOADED")));
    }

    @GetMapping("/checks")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<CheckDocumentDTO>>> listChecks(Authentication auth) {
        Long userId = Long.parseLong(auth.getPrincipal().toString());
        List<CheckDocumentDTO> rows = participantCheckService.listForUser(userId)
                .stream().map(CheckDocumentDTO::from).toList();
        return ResponseEntity.ok(ApiResponse.success(rows));
    }

    // ─── Phase 4: welcome page polling + dashboard gate ─────────────

    /**
     * Status snapshot for the /welcome page. The page polls this
     * every 5 seconds to update its checklist + team cards as the
     * post-agreement onboarding chain runs.
     */
    @GetMapping("/welcome-status")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Map<String, Object>>> welcomeStatus(Authentication auth) {
        Long userId = Long.parseLong(auth.getPrincipal().toString());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        return ResponseEntity.ok(ApiResponse.success(
                onboardingService.snapshotForWelcome(user)));
    }

    /**
     * Idempotent re-run of the OnboardingService chain. Useful when
     * an admin has just manually assigned an ERM and we want the
     * participant's workflow to roll forward without waiting on a
     * scheduled tick.
     */
    @PostMapping("/welcome-status/refresh")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Map<String, Object>>> refreshWelcome(Authentication auth) {
        Long userId = Long.parseLong(auth.getPrincipal().toString());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        // Only re-run when the chain is mid-flight — already at
        // DASHBOARD_ENABLED means no-op.
        if (!workflowService.isStatusAtLeast(user,
                com.spire.backend.service.WorkflowService.Status.DASHBOARD_ENABLED)) {
            onboardingService.completeOnboarding(user);
            user = userRepository.findById(userId)
                    .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        }
        return ResponseEntity.ok(ApiResponse.success(
                onboardingService.snapshotForWelcome(user)));
    }

    // ─── Phase 5A: dashboard + team + weekly reports ───────────────

    /** Aggregate dashboard payload — roadmap, team, recent activity, stats. */
    @GetMapping("/dashboard")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDashboard(Authentication auth) {
        Long userId = Long.parseLong(auth.getPrincipal().toString());
        return ResponseEntity.ok(ApiResponse.success(
                participantDashboardService.snapshot(userId)));
    }

    /** Team contact cards — ERM + four coach roles. */
    @GetMapping("/team")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getTeam(Authentication auth) {
        Long userId = Long.parseLong(auth.getPrincipal().toString());
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        ermAssignmentService.getAssignedErm(userId).ifPresent(e -> {
            Map<String, Object> erm = new java.util.LinkedHashMap<>();
            erm.put("name", e.getFullName());
            erm.put("email", e.getEmail());
            erm.put("bio", e.getBio());
            out.put("erm", erm);
        });
        out.put("coaches", coachAssignmentService.getAssignedCoaches(userId));
        return ResponseEntity.ok(ApiResponse.success(out));
    }

    @PostMapping("/reports/weekly")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<com.spire.backend.dto.WeeklyReportDTO>> submitWeeklyReport(
            @RequestBody com.spire.backend.dto.WeeklyReportRequest req,
            Authentication auth) {
        Long userId = Long.parseLong(auth.getPrincipal().toString());
        com.spire.backend.entity.WeeklyReport saved = weeklyReportService.submit(userId, req);
        return ResponseEntity.ok(ApiResponse.success(
                "Report submitted",
                com.spire.backend.dto.WeeklyReportDTO.from(saved)));
    }

    @PostMapping("/reports/weekly/draft")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<com.spire.backend.dto.WeeklyReportDTO>> saveWeeklyDraft(
            @RequestBody com.spire.backend.dto.WeeklyReportRequest req,
            Authentication auth) {
        Long userId = Long.parseLong(auth.getPrincipal().toString());
        com.spire.backend.entity.WeeklyReport saved = weeklyReportService.saveDraft(userId, req);
        return ResponseEntity.ok(ApiResponse.success(
                "Draft saved",
                com.spire.backend.dto.WeeklyReportDTO.from(saved)));
    }

    @GetMapping("/reports/weekly")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<com.spire.backend.dto.WeeklyReportDTO>>> listWeeklyReports(
            Authentication auth) {
        Long userId = Long.parseLong(auth.getPrincipal().toString());
        List<com.spire.backend.dto.WeeklyReportDTO> rows = weeklyReportService.listForUser(userId)
                .stream().map(com.spire.backend.dto.WeeklyReportDTO::from).toList();
        return ResponseEntity.ok(ApiResponse.success(rows));
    }

    @GetMapping("/reports/weekly/{reportId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<com.spire.backend.dto.WeeklyReportDTO>> getWeeklyReport(
            @PathVariable Long reportId,
            Authentication auth) {
        Long userId = Long.parseLong(auth.getPrincipal().toString());
        return ResponseEntity.ok(ApiResponse.success(
                com.spire.backend.dto.WeeklyReportDTO.from(
                        weeklyReportService.getReport(userId, reportId))));
    }

    // ─── Phase 6: employment acceptance + Phase 1 completion ───────

    /**
     * Submit employment acceptance details. Optional offer document
     * can be uploaded separately via {@code /employment/offer-upload}
     * and the resulting URL passed in {@code offerDocumentUrl}.
     */
    @PostMapping("/employment/accept")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Map<String, Object>>> acceptEmployment(
            @RequestBody Map<String, Object> body,
            Authentication auth) {
        Long userId = Long.parseLong(auth.getPrincipal().toString());
        com.spire.backend.entity.EmploymentAcceptance in =
                com.spire.backend.entity.EmploymentAcceptance.builder()
                        .employerClient((String) body.get("employer"))
                        .jobTitle((String) body.get("jobTitle"))
                        .startDate(parseDate(body.get("startDate")))
                        .location((String) body.get("location"))
                        .employmentType((String) body.get("employmentType"))
                        .offerDocumentUrl((String) body.get("offerDocumentUrl"))
                        .notes((String) body.get("notes"))
                        .build();
        com.spire.backend.entity.EmploymentAcceptance saved =
                employmentService.acceptEmployment(userId, in);
        return ResponseEntity.ok(ApiResponse.success(
                "Employment acceptance submitted",
                Map.of(
                        "success", true,
                        "pendingVerification", true,
                        "employmentId", saved.getId()
                )));
    }

    /** Upload an offer document (PDF / image). Stores via the shared
     *  DocumentStorageService and returns the URL the caller should
     *  pass to {@code /employment/accept} as {@code offerDocumentUrl}. */
    @PostMapping("/employment/offer-upload")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Map<String, Object>>> uploadOffer(
            @RequestParam("file") MultipartFile file,
            Authentication auth) throws java.io.IOException {
        Long userId = Long.parseLong(auth.getPrincipal().toString());
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is required");
        }
        if (file.getSize() > 5L * 1024 * 1024) {
            throw new IllegalArgumentException("Offer document must be 5 MB or smaller");
        }
        String filename = file.getOriginalFilename() == null
                ? "offer.pdf" : file.getOriginalFilename();
        var stored = storageService.upload(userId, "offer-" + filename,
                file.getBytes(), file.getContentType());
        return ResponseEntity.ok(ApiResponse.success(
                "Offer uploaded",
                Map.of("url", stored.url())));
    }

    @GetMapping("/employment/status")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Map<String, Object>>> employmentStatus(Authentication auth) {
        Long userId = Long.parseLong(auth.getPrincipal().toString());
        return ResponseEntity.ok(ApiResponse.success(employmentService.employmentStatus(userId)));
    }

    @PostMapping("/phases/phase-1-complete")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Map<String, Object>>> acceptPhase1(
            @RequestBody Map<String, Object> body,
            Authentication auth) {
        Long userId = Long.parseLong(auth.getPrincipal().toString());
        if (!Boolean.TRUE.equals(body.get("acknowledgmentAccepted"))) {
            throw new IllegalArgumentException(
                    "You must accept the Phase 1 acknowledgment to continue.");
        }
        String version = (String) body.getOrDefault("acknowledgmentVersion",
                com.spire.backend.service.EmploymentService.PHASE_1_ACK_VERSION);
        com.spire.backend.entity.PhaseCompletion saved =
                employmentService.acceptPhase1Completion(userId, version);
        return ResponseEntity.ok(ApiResponse.success(
                "Phase 1 completion accepted",
                Map.of(
                        "success", true,
                        "paymentEnabled", true,
                        "phaseCompletionId", saved.getId(),
                        "acceptedAt", saved.getAcceptedAt()
                )));
    }

    private static java.time.LocalDate parseDate(Object raw) {
        if (raw == null) return null;
        try { return java.time.LocalDate.parse(raw.toString()); }
        catch (Exception e) {
            throw new IllegalArgumentException("Date must be YYYY-MM-DD");
        }
    }

    // ─── Phase 7: payments — plan, check tracking, invoices, summary ──

    @GetMapping("/payments/plan")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPaymentPlan(Authentication auth) {
        Long userId = Long.parseLong(auth.getPrincipal().toString());
        var planOpt = paymentService.latestPlanForUser(userId);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("plan", planOpt.orElse(null));
        out.put("schedule", planOpt.map(p ->
                paymentService.parseSchedule(p.getSchedule())).orElse(java.util.List.of()));
        out.put("acknowledgmentVersion",
                com.spire.backend.service.PaymentService.PLAN_ACK_VERSION);
        return ResponseEntity.ok(ApiResponse.success(out));
    }

    @PostMapping("/payments/plan/accept")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Map<String, Object>>> acceptPaymentPlan(
            @RequestBody Map<String, Object> body,
            Authentication auth,
            HttpServletRequest httpRequest) {
        Long userId = Long.parseLong(auth.getPrincipal().toString());
        if (!Boolean.TRUE.equals(body.get("accepted"))) {
            throw new IllegalArgumentException("You must accept the payment plan to continue.");
        }
        Object planIdRaw = body.get("planId");
        Long planId = planIdRaw == null
                ? paymentService.latestPlanForUser(userId)
                        .orElseThrow(() -> new IllegalArgumentException("No payment plan on file."))
                        .getId()
                : (planIdRaw instanceof Number n ? n.longValue() : Long.parseLong(planIdRaw.toString()));
        String version = (String) body.getOrDefault("acknowledgmentVersion",
                com.spire.backend.service.PaymentService.PLAN_ACK_VERSION);
        var saved = paymentService.acceptPlan(userId, planId, version, clientIp(httpRequest));
        return ResponseEntity.ok(ApiResponse.success(
                "Payment plan accepted",
                Map.of(
                        "success", true,
                        "planId", saved.getId(),
                        "planNumber", saved.getPlanId(),
                        "acceptedAt", saved.getAcceptedAt()
                )));
    }

    @PostMapping("/payments/check-tracking")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Map<String, Object>>> submitCheckTracking(
            @RequestBody Map<String, Object> body,
            Authentication auth) {
        Long userId = Long.parseLong(auth.getPrincipal().toString());
        com.spire.backend.entity.CheckTracking row =
                com.spire.backend.entity.CheckTracking.builder()
                        .checkNumber((String) body.get("checkNumber"))
                        .carrier((String) body.get("carrier"))
                        .physicalTrackingId((String) body.get("trackingId"))
                        .mailedDate(parseDate(body.get("mailedDate")))
                        .expectedReceiptDate(parseDate(body.get("expectedReceiptDate")))
                        .build();
        var saved = checkTrackingService.submit(userId, row);
        return ResponseEntity.ok(ApiResponse.success(
                "Tracking submitted",
                Map.of(
                        "success", true,
                        "trackingId", saved.getId(),
                        "status", saved.getStatus()
                )));
    }

    @GetMapping("/payments/check-tracking")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listCheckTracking(Authentication auth) {
        Long userId = Long.parseLong(auth.getPrincipal().toString());
        return ResponseEntity.ok(ApiResponse.success(
                checkTrackingService.trackingsForUser(userId)));
    }

    @GetMapping("/payments/invoices")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<com.spire.backend.entity.Invoice>>> myInvoices(Authentication auth) {
        Long userId = Long.parseLong(auth.getPrincipal().toString());
        return ResponseEntity.ok(ApiResponse.success(
                invoiceRepository.findByUserIdOrderByIssueDateDesc(userId)));
    }

    @GetMapping("/payments/summary")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Map<String, Object>>> paymentSummary(Authentication auth) {
        Long userId = Long.parseLong(auth.getPrincipal().toString());
        return ResponseEntity.ok(ApiResponse.success(
                paymentService.participantSummary(userId)));
    }

    @GetMapping("/payments/history")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<com.spire.backend.entity.PaymentLedger>>> paymentHistory(Authentication auth) {
        Long userId = Long.parseLong(auth.getPrincipal().toString());
        return ResponseEntity.ok(ApiResponse.success(
                paymentLedgerRepository.findByUserIdOrderByCreatedAtDesc(userId)));
    }

    // ─── Phase 5A: profile read / edit (dashboard Profile tab) ─────

    @GetMapping("/profile")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<UserDTO>> getProfile(Authentication auth) {
        Long userId = Long.parseLong(auth.getPrincipal().toString());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        return ResponseEntity.ok(ApiResponse.success(UserDTO.from(user)));
    }

    @PutMapping("/profile")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<UserDTO>> updateProfile(
            @RequestBody com.spire.backend.dto.ProfileUpdateRequest body,
            Authentication auth) {
        Long userId = Long.parseLong(auth.getPrincipal().toString());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        if (body.getFullName() != null && !body.getFullName().isBlank()) {
            user.setFullName(body.getFullName().trim());
        }
        if (body.getPhone() != null) {
            String phone = body.getPhone().trim();
            user.setPhone(phone.isEmpty() ? null : phone);
        }
        if (body.getLocation() != null) {
            String loc = body.getLocation().trim();
            user.setLocation(loc.isEmpty() ? null : loc);
        }
        if (body.getBio() != null) {
            String bio = body.getBio().trim();
            user.setBio(bio.isEmpty() ? null : bio);
        }
        if (body.getAvailability() != null) {
            String avail = body.getAvailability().trim();
            user.setAvailability(avail.isEmpty() ? null : avail);
        }
        userRepository.save(user);
        return ResponseEntity.ok(ApiResponse.success("Profile updated", UserDTO.from(user)));
    }

    // ── Phase 1C: progressive profile completion ───────────────────

    /** Snapshot of the six completion steps + percentage. */
    @GetMapping("/profile/completion")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<ProfileCompletionDto>> getProfileCompletion(
            Authentication auth) {
        Long userId = Long.parseLong(auth.getPrincipal().toString());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        return ResponseEntity.ok(ApiResponse.success(
                profileCompletionService.getStatus(user)));
    }

    /**
     * Step 1 of the progressive flow — moved off the public enrollment
     * form so the public sign-up is just 4 fields. Persists location,
     * availability, technology, and target experience level onto the
     * user row, then flips {@code basic_info_complete = true}.
     */
    @PostMapping("/profile/basic-info")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Map<String, Object>>> submitBasicInfo(
            @Valid @RequestBody BasicInfoRequest body,
            Authentication auth) {
        Long userId = Long.parseLong(auth.getPrincipal().toString());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        if (body.getLocation() != null) {
            String loc = body.getLocation().trim();
            user.setLocation(loc.isEmpty() ? null : loc);
        }
        user.setAvailability(body.getAvailability().trim());
        user.setSelectedTechnology(body.getSelectedTechnology().trim());
        user.setTargetExperienceLevel(body.getTargetExperienceLevel().trim());
        userRepository.save(user);

        profileCompletionService.markStepComplete(user, "BASIC_INFO");

        return ResponseEntity.ok(ApiResponse.success(
                "Basic info saved",
                Map.of(
                        "success", true,
                        "completion", profileCompletionService.getStatus(user)
                )));
    }

    // ── Phase 1C: wishlist ─────────────────────────────────────────

    @GetMapping("/wishlist")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<WishlistItemDto>>> listWishlist(
            Authentication auth) {
        Long userId = Long.parseLong(auth.getPrincipal().toString());
        return ResponseEntity.ok(ApiResponse.success(
                wishlistService.listForUser(userId)));
    }

    @PostMapping("/wishlist/add")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Map<String, Object>>> addToWishlist(
            @RequestBody Map<String, Object> body,
            Authentication auth) {
        Long userId = Long.parseLong(auth.getPrincipal().toString());
        Object courseRaw = body.get("courseId");
        if (courseRaw == null) {
            throw new IllegalArgumentException("courseId is required");
        }
        Long courseId = courseRaw instanceof Number n
                ? n.longValue()
                : Long.parseLong(courseRaw.toString());
        var saved = wishlistService.addCourse(userId, courseId);
        return ResponseEntity.ok(ApiResponse.success(
                "Added to wishlist",
                Map.of(
                        "success", true,
                        "wishlistId", saved.getId(),
                        "courseId", courseId
                )));
    }

    @DeleteMapping("/wishlist/{courseId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Map<String, Object>>> removeFromWishlist(
            @PathVariable Long courseId,
            Authentication auth) {
        Long userId = Long.parseLong(auth.getPrincipal().toString());
        wishlistService.removeCourse(userId, courseId);
        return ResponseEntity.ok(ApiResponse.success(
                "Removed from wishlist",
                Map.of("success", true, "courseId", courseId)));
    }

    /**
     * Bulk-enroll every wishlisted course. Gated on profile
     * completion so a half-set-up account can't ride the wishlist
     * around the gate. Iterates the existing EnrollmentService so
     * mentor-pool capacity, duplicate detection, and recordkeeping
     * are reused unchanged.
     */
    @PostMapping("/wishlist/enroll-all")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Map<String, Object>>> enrollAllFromWishlist(
            Authentication auth) {
        Long userId = Long.parseLong(auth.getPrincipal().toString());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        if (!profileCompletionService.canEnrollInCourses(user)) {
            Map<String, Object> body = Map.of(
                    "message", "Complete your profile to enroll",
                    "completion", profileCompletionService.getStatus(user)
            );
            return ResponseEntity.status(403).body(
                    ApiResponse.<Map<String, Object>>error("PROFILE_INCOMPLETE", body));
        }
        List<Long> courseIds = wishlistService.courseIdsForUser(userId);
        int enrolled = 0;
        List<String> skipped = new java.util.ArrayList<>();
        for (Long courseId : courseIds) {
            try {
                // EnrollmentService.enrollUser is reused so mentor
                // pool capacity, duplicates, and audit records all
                // run through one canonical path.
                if (enrollmentService != null) {
                    enrollmentService.enrollUser(userId, courseId);
                    wishlistService.removeCourse(userId, courseId);
                    enrolled++;
                }
            } catch (Exception e) {
                skipped.add(String.valueOf(courseId));
            }
        }
        return ResponseEntity.ok(ApiResponse.success(
                "Wishlist enrolled",
                Map.of(
                        "success", true,
                        "enrolledCount", enrolled,
                        "skipped", skipped
                )));
    }

    // ── Shared helper ───────────────────────────────────────────────

    private static String clientIp(HttpServletRequest request) {
        if (request == null) return null;
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return request.getRemoteAddr();
    }
}
