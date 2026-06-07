package com.spire.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spire.backend.entity.ConsultantApplication;
import com.spire.backend.entity.ConsultantApplicationEvent;
import com.spire.backend.entity.ConsultantApplicationRevision;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.repository.ConsultantApplicationEventRepository;
import com.spire.backend.repository.ConsultantApplicationRepository;
import com.spire.backend.repository.ConsultantApplicationRevisionRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Core service for the Consultant Agreement feature (hidden internal
 * surface — not exposed via the marketing site).
 *
 * Two callers:
 *
 *   1. Agreement-ERM console (one hardcoded operator, no DB row).
 *      Goes through {@link
 *      com.spire.backend.controller.ConsultantApplicationController}
 *      under /api/agreement-erm/applications/**, gated by
 *      ROLE_AGREEMENT_ERM.
 *
 *   2. Consultant himself. Goes through /api/consultant/applications/**
 *      — entirely public + rate-limited. The applicationId (UUID v4)
 *      is the credential.
 *
 * State machine (see {@link ConsultantApplication.Status}):
 *   DRAFT -> SUBMITTED -> (REVISION_REQUESTED -> UPDATED)*
 *           -> VERIFIED -> SIGNED -> COMPLETED
 * Off-ramps: CANCELLED (operator) / EXPIRED (cron).
 *
 * JSON payloads are stored as TEXT and parsed with ObjectMapper here
 * rather than via JSONB columns. Keeps the schema portable across
 * MySQL dev and Postgres prod and mirrors the convention from
 * {@link AcknowledgmentService}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConsultantApplicationService {

    private static final int APPLICATION_TTL_DAYS = 7;

    /**
     * Sentinel ermUserId for applications created via the hardcoded
     * agreement-erm console. Lets us reuse the existing
     * {@code erm_user_id} column without a User row.
     */
    public static final Long AGREEMENT_ERM_USER_ID = 0L;

    private final ConsultantApplicationRepository applicationRepository;
    private final ConsultantApplicationEventRepository eventRepository;
    private final ConsultantApplicationRevisionRepository revisionRepository;
    private final EmailTemplateService emailTemplateService;
    private final ConsultantPdfService consultantPdfService;
    private final AgreementDocumentService agreementDocumentService;
    private final com.cloudinary.Cloudinary cloudinary;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── Agreement-ERM operations (single operator, no per-ERM scoping) ──

    /**
     * Two-stage workflow create (Phase 3). The ERM seeds a row with
     * the consultant identity + rate card; status starts at SUBMITTED
     * and the consultant gets an "action required: complete your
     * details" email pointing at the new /consultant/{appId}/fill
     * surface.
     *
     * The legacy {@code payload}, {@code consultantPhone} fields are
     * left null in the new flow -- the structured columns added in
     * Phase 1 (primaryPhone, residenceAddress, etc.) replace them.
     * Existing callers that still pass a payload have it persisted
     * untouched, but no consumer reads it on the new flow.
     */
    @Transactional
    public ConsultantApplication createApplication(
            String consultantEmail,
            String consultantName,
            String consultantPhone,
            String ratePeriod1,
            String rateAmount1,
            String ratePeriod2,
            String rateAmount2,
            JsonNode payload,
            String ownerErmId,
            HttpServletRequest request
    ) {
        validateRequired("consultantEmail", consultantEmail);

        String applicationId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        String payloadJson = stringify(payload);

        ConsultantApplication app = ConsultantApplication.builder()
                .applicationId(applicationId)
                .ermUserId(AGREEMENT_ERM_USER_ID)
                // Stamp the authenticated agreement user as the owner.
                // Not yet used to filter reads (next phase).
                .ownerErmId(ownerErmId)
                .consultantEmail(consultantEmail.trim().toLowerCase())
                .consultantName(consultantName)
                .consultantPhone(consultantPhone)
                .ratePeriod1(ratePeriod1)
                .rateAmount1(rateAmount1)
                .ratePeriod2(ratePeriod2)
                .rateAmount2(rateAmount2)
                .payload(payloadJson)
                .status(ConsultantApplication.Status.SUBMITTED.name())
                .expiresAt(now.plusDays(APPLICATION_TTL_DAYS))
                .build();
        app = applicationRepository.save(app);

        saveRevision(app.getId(), 1, payloadJson, "ERM", AGREEMENT_ERM_USER_ID);

        appendEvent(app.getId(),
                ConsultantApplicationEvent.EventType.CREATED,
                ConsultantApplicationEvent.ActorType.ERM,
                AGREEMENT_ERM_USER_ID,
                Map.of("applicationId", applicationId,
                        "consultantEmail", app.getConsultantEmail()),
                request);

        try {
            emailTemplateService.sendConsultantInitialFill(app);
            appendEvent(app.getId(),
                    ConsultantApplicationEvent.EventType.EMAIL_SENT,
                    ConsultantApplicationEvent.ActorType.SYSTEM, null,
                    Map.of("template", "consultant_initial_fill",
                            "to", app.getConsultantEmail()),
                    null);
        } catch (Exception e) {
            log.warn("Failed to send initial-fill email for {}: {}",
                    applicationId, e.getMessage());
        }

        return app;
    }

    @Transactional(readOnly = true)
    public ConsultantApplication getByApplicationId(String applicationId) {
        return applicationRepository.findByApplicationId(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "ConsultantApplication", "applicationId", applicationId));
    }

    @Transactional(readOnly = true)
    public Page<ConsultantApplication> listApplications(String status, Pageable pageable) {
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) {
            return applicationRepository.findByErmUserId(AGREEMENT_ERM_USER_ID, pageable);
        }
        return applicationRepository.findByErmUserIdAndStatus(
                AGREEMENT_ERM_USER_ID, status, pageable);
    }

    @Transactional
    public ConsultantApplication updateApplication(
            String applicationId,
            String consultantEmail,
            String consultantName,
            String consultantPhone,
            JsonNode payload,
            HttpServletRequest request
    ) {
        ConsultantApplication app = getByApplicationId(applicationId);
        String status = app.getStatus();
        if (!ConsultantApplication.Status.DRAFT.name().equals(status)
                && !ConsultantApplication.Status.REVISION_REQUESTED.name().equals(status)
                && !ConsultantApplication.Status.SUBMITTED.name().equals(status)
                && !ConsultantApplication.Status.UPDATED.name().equals(status)) {
            throw new IllegalStateException(
                    "Cannot edit application in status " + status + ".");
        }

        if (consultantEmail != null && !consultantEmail.isBlank()) {
            app.setConsultantEmail(consultantEmail.trim().toLowerCase());
        }
        if (consultantName != null) app.setConsultantName(consultantName);
        if (consultantPhone != null) app.setConsultantPhone(consultantPhone);
        if (payload != null) {
            String payloadJson = stringify(payload);
            app.setPayload(payloadJson);

            int nextVersion = revisionRepository
                    .findFirstByApplicationIdOrderByVersionNumberDesc(app.getId())
                    .map(rev -> rev.getVersionNumber() + 1)
                    .orElse(2);
            saveRevision(app.getId(), nextVersion, payloadJson,
                    "ERM", AGREEMENT_ERM_USER_ID);
        }

        app.setStatus(ConsultantApplication.Status.UPDATED.name());
        applicationRepository.save(app);

        appendEvent(app.getId(),
                ConsultantApplicationEvent.EventType.REVISED,
                ConsultantApplicationEvent.ActorType.ERM, AGREEMENT_ERM_USER_ID,
                Map.of("status", app.getStatus()),
                request);

        // Re-notify the consultant whenever the ERM revises after a
        // pushback. Best-effort; mirrors the Phase 1 behaviour.
        if (ConsultantApplication.Status.UPDATED.name().equals(app.getStatus())) {
            try {
                emailTemplateService.sendConsultantApplicationUpdated(app);
                appendEvent(app.getId(),
                        ConsultantApplicationEvent.EventType.EMAIL_SENT,
                        ConsultantApplicationEvent.ActorType.SYSTEM, null,
                        Map.of("template", "application_updated"),
                        null);
            } catch (Exception e) {
                log.warn("Failed to send updated-for-review email for {}: {}",
                        applicationId, e.getMessage());
            }
        }

        return app;
    }

    @Transactional
    public ConsultantApplication cancel(
            String applicationId, HttpServletRequest request) {
        ConsultantApplication app = getByApplicationId(applicationId);
        String status = app.getStatus();
        if (ConsultantApplication.Status.SIGNED.name().equals(status)
                || ConsultantApplication.Status.COMPLETED.name().equals(status)) {
            throw new IllegalStateException(
                    "Cannot cancel a signed or completed application.");
        }
        app.setStatus(ConsultantApplication.Status.CANCELLED.name());
        applicationRepository.save(app);

        appendEvent(app.getId(),
                ConsultantApplicationEvent.EventType.CANCELLED,
                ConsultantApplicationEvent.ActorType.ERM, AGREEMENT_ERM_USER_ID,
                Map.of(), request);
        return app;
    }

    @Transactional
    public void resendInvite(String applicationId, HttpServletRequest request) {
        ConsultantApplication app = getByApplicationId(applicationId);
        try {
            emailTemplateService.sendConsultantApplicationCreated(app);
            appendEvent(app.getId(),
                    ConsultantApplicationEvent.EventType.EMAIL_SENT,
                    ConsultantApplicationEvent.ActorType.SYSTEM, null,
                    Map.of("template", "application_created",
                            "to", app.getConsultantEmail(),
                            "trigger", "resend"),
                    request);
        } catch (Exception e) {
            throw new IllegalStateException("Couldn't resend invite: " + e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public List<ConsultantApplicationEvent> listEvents(String applicationId) {
        ConsultantApplication app = getByApplicationId(applicationId);
        return eventRepository.findByApplicationIdOrderByCreatedAtDesc(app.getId());
    }

    @Transactional(readOnly = true)
    public List<ConsultantApplicationRevision> listRevisions(String applicationId) {
        ConsultantApplication app = getByApplicationId(applicationId);
        return revisionRepository.findByApplicationIdOrderByVersionNumberDesc(app.getId());
    }

    // ── Consultant-side (public, rate-limited at controller) ────────

    /**
     * Public read used by the consultant /review page. Appends an
     * ACCESSED audit event with the caller IP + UA so the operator
     * can see every view of a sensitive application.
     */
    @Transactional
    public ConsultantApplication getForConsultant(String applicationId,
                                                  HttpServletRequest request) {
        ConsultantApplication app = getByApplicationId(applicationId);
        if (isTerminalCancellation(app.getStatus())) {
            throw new IllegalStateException(
                    "This application is no longer accepting consultant actions.");
        }
        appendEvent(app.getId(),
                ConsultantApplicationEvent.EventType.ACCESSED,
                ConsultantApplicationEvent.ActorType.CONSULTANT, null,
                Map.of("status", app.getStatus()),
                request);
        return app;
    }

    @Transactional
    public ConsultantApplication verifyDetails(String applicationId,
                                               HttpServletRequest request) {
        ConsultantApplication app = getByApplicationId(applicationId);
        String status = app.getStatus();
        if (!ConsultantApplication.Status.SUBMITTED.name().equals(status)
                && !ConsultantApplication.Status.UPDATED.name().equals(status)
                && !ConsultantApplication.Status.REVISION_REQUESTED.name().equals(status)) {
            throw new IllegalStateException(
                    "Application is in status " + status + " and cannot be verified.");
        }
        app.setStatus(ConsultantApplication.Status.VERIFIED.name());
        applicationRepository.save(app);

        appendEvent(app.getId(),
                ConsultantApplicationEvent.EventType.DETAILS_VERIFIED,
                ConsultantApplicationEvent.ActorType.CONSULTANT, null,
                Map.of("from", status, "to", app.getStatus()),
                request);
        return app;
    }

    @Transactional
    public ConsultantApplication requestRevision(String applicationId, String reason,
                                                 HttpServletRequest request) {
        ConsultantApplication app = getByApplicationId(applicationId);
        String status = app.getStatus();
        if (ConsultantApplication.Status.SIGNED.name().equals(status)
                || ConsultantApplication.Status.COMPLETED.name().equals(status)
                || ConsultantApplication.Status.CANCELLED.name().equals(status)
                || ConsultantApplication.Status.EXPIRED.name().equals(status)) {
            throw new IllegalStateException(
                    "Application is in status " + status
                            + " and cannot be sent back for revision.");
        }
        app.setStatus(ConsultantApplication.Status.REVISION_REQUESTED.name());
        app.setRevisionNotes(reason);
        applicationRepository.save(app);

        appendEvent(app.getId(),
                ConsultantApplicationEvent.EventType.REVISION_REQUESTED,
                ConsultantApplicationEvent.ActorType.CONSULTANT, null,
                Map.of("reason", reason == null ? "" : reason,
                        "from", status),
                request);

        try {
            emailTemplateService.sendConsultantRevisionRequested(app);
            appendEvent(app.getId(),
                    ConsultantApplicationEvent.EventType.EMAIL_SENT,
                    ConsultantApplicationEvent.ActorType.SYSTEM, null,
                    Map.of("template", "revision_requested"),
                    null);
        } catch (Exception e) {
            log.warn("Failed to email ERM about revision request for {}: {}",
                    applicationId, e.getMessage());
        }
        return app;
    }

    @Transactional
    public ConsultantApplication sign(String applicationId,
                                      String legalName,
                                      String signatureImage,
                                      HttpServletRequest request) {
        ConsultantApplication app = getByApplicationId(applicationId);
        if (!ConsultantApplication.Status.VERIFIED.name().equals(app.getStatus())) {
            throw new IllegalStateException(
                    "Application must be VERIFIED before signing (status="
                            + app.getStatus() + ").");
        }
        if (legalName == null || legalName.trim().split("\\s+").length < 2) {
            throw new IllegalArgumentException(
                    "Please enter your full legal name (first and last).");
        }
        if (signatureImage == null || signatureImage.isBlank()) {
            throw new IllegalArgumentException(
                    "A signature is required to sign the agreement.");
        }
        if (!signatureImage.startsWith("data:image/")) {
            throw new IllegalArgumentException(
                    "Signature must be an image (PNG / JPG).");
        }

        LocalDateTime now = LocalDateTime.now();
        app.setSignedLegalName(legalName.trim());
        app.setSignatureImage(signatureImage);
        app.setSignedAt(now);
        app.setSignedIp(clientIp(request));
        app.setSignedUserAgent(request == null ? null : request.getHeader("User-Agent"));
        app.setStatus(ConsultantApplication.Status.SIGNED.name());
        applicationRepository.save(app);

        appendEvent(app.getId(),
                ConsultantApplicationEvent.EventType.SIGNED,
                ConsultantApplicationEvent.ActorType.CONSULTANT, null,
                Map.of("legalName", legalName.trim()),
                request);

        // PDF + completion side-effects. If PDF generation fails we
        // leave the application in SIGNED so an operator can retry;
        // the signature row is already persisted and replayable.
        try {
            String pdfUrl = consultantPdfService.generateAndUpload(app);
            app.setSignedPdfUrl(pdfUrl);
            app.setStatus(ConsultantApplication.Status.COMPLETED.name());
            applicationRepository.save(app);

            appendEvent(app.getId(),
                    ConsultantApplicationEvent.EventType.PDF_GENERATED,
                    ConsultantApplicationEvent.ActorType.SYSTEM, null,
                    Map.of("url", pdfUrl),
                    null);

            try {
                emailTemplateService.sendConsultantApplicationSigned(app);
                emailTemplateService.sendConsultantApplicationCopy(app);
                appendEvent(app.getId(),
                        ConsultantApplicationEvent.EventType.EMAIL_SENT,
                        ConsultantApplicationEvent.ActorType.SYSTEM, null,
                        Map.of("templates", "signed,copy"),
                        null);
            } catch (Exception emailErr) {
                log.warn("Post-sign emails failed for {}: {}",
                        applicationId, emailErr.getMessage());
            }
        } catch (Exception pdfErr) {
            log.error("PDF generation failed for {}: {}",
                    applicationId, pdfErr.getMessage());
        }

        return app;
    }

    @Transactional
    public void requestCopy(String applicationId, HttpServletRequest request) {
        ConsultantApplication app = getByApplicationId(applicationId);
        if (!ConsultantApplication.Status.COMPLETED.name().equals(app.getStatus())
                || app.getSignedPdfUrl() == null) {
            throw new IllegalStateException(
                    "The agreement has not been signed yet.");
        }
        try {
            emailTemplateService.sendConsultantApplicationCopy(app);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Couldn't email a copy: " + e.getMessage());
        }
        appendEvent(app.getId(),
                ConsultantApplicationEvent.EventType.EMAIL_SENT,
                ConsultantApplicationEvent.ActorType.CONSULTANT, null,
                Map.of("template", "copy", "to", app.getConsultantEmail()),
                request);
    }

    private boolean isTerminalCancellation(String status) {
        return ConsultantApplication.Status.CANCELLED.name().equals(status)
                || ConsultantApplication.Status.EXPIRED.name().equals(status);
    }

    // ── Two-stage workflow transitions (Phase 3) ────────────────────
    //
    // State machine reinterpretation:
    //   SUBMITTED          -- ERM created; consultant must fill + sign
    //   REVISION_REQUESTED -- ERM kicked back; consultant must fix
    //   VERIFIED           -- consultant signed; awaiting ERM review
    //   COMPLETED          -- ERM countersigned; final PDF persisted
    //
    // The transient UPDATED and SIGNED states are skipped on the new
    // flow -- transitions go straight to VERIFIED / COMPLETED. The
    // origin state is captured in event metadata so the audit timeline
    // can still distinguish first-submit from re-submit.

    /**
     * Consultant patches their fill-in fields. Allowed only while the
     * application is awaiting consultant input (SUBMITTED or
     * REVISION_REQUESTED). Returns the saved row.
     *
     * Idempotent: nullable parameters mean callers can save partial
     * progress (a "Save and continue later" button) without clobbering
     * fields they didn't touch. If no provided field was non-null
     * the call is a no-op and no audit event is emitted.
     */
    @Transactional
    public ConsultantApplication consultantFill(
            String applicationId,
            ConsultantFillPatch patch,
            HttpServletRequest request) {
        ConsultantApplication app = getByApplicationId(applicationId);
        String status = app.getStatus();
        if (!ConsultantApplication.Status.SUBMITTED.name().equals(status)
                && !ConsultantApplication.Status.REVISION_REQUESTED.name().equals(status)) {
            throw new IllegalStateException(
                    "Application is in status " + status
                            + " and cannot be edited by the consultant.");
        }
        boolean changed = patch.applyTo(app);
        if (!changed) {
            return app;
        }
        applicationRepository.save(app);
        appendEvent(app.getId(),
                ConsultantApplicationEvent.EventType.CONSULTANT_FILLED,
                ConsultantApplicationEvent.ActorType.CONSULTANT, null,
                Map.of("status", status, "fieldsTouched", patch.touchedFieldNames()),
                request);
        return app;
    }

    /**
     * Consultant signs and submits. Allowed from SUBMITTED (first pass)
     * or REVISION_REQUESTED (re-submit after ERM kickback). Uploads the
     * signature image to Cloudinary, stores the secure URL on
     * {@code signatureImage}, locks in {@code signedLegalName} +
     * {@code signatureDate}, and transitions to VERIFIED.
     *
     * The intermediate consultant-only PDF is intentionally NOT
     * generated here -- generation costs ~10s of LibreOffice cold
     * start, which we don't want on the consultant's submit click.
     * The final PDF is produced once on {@link #ermApproveAndSign}.
     */
    @Transactional
    public ConsultantApplication consultantSubmit(
            String applicationId,
            String signatureBase64,
            String signedLegalName,
            HttpServletRequest request) {
        ConsultantApplication app = getByApplicationId(applicationId);
        String fromStatus = app.getStatus();
        if (!ConsultantApplication.Status.SUBMITTED.name().equals(fromStatus)
                && !ConsultantApplication.Status.REVISION_REQUESTED.name().equals(fromStatus)) {
            throw new IllegalStateException(
                    "Application is in status " + fromStatus
                            + " and cannot be submitted by the consultant.");
        }
        if (signedLegalName == null
                || signedLegalName.trim().split("\\s+").length < 2) {
            throw new IllegalArgumentException(
                    "Please enter your full legal name (first and last).");
        }
        if (signatureBase64 == null || signatureBase64.isBlank()
                || !signatureBase64.startsWith("data:image/")) {
            throw new IllegalArgumentException(
                    "A signature image is required (data:image/...).");
        }

        String signatureUrl;
        try {
            signatureUrl = uploadSignatureToCloudinary(
                    signatureBase64, "signatures/consultant-" + applicationId);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Couldn't store signature: " + e.getMessage(), e);
        }

        LocalDateTime now = LocalDateTime.now();
        app.setSignedLegalName(signedLegalName.trim());
        app.setSignatureImage(signatureUrl);
        app.setSignedAt(now);
        app.setSignedIp(clientIp(request));
        app.setSignedUserAgent(request == null ? null : request.getHeader("User-Agent"));
        app.setStatus(ConsultantApplication.Status.VERIFIED.name());
        applicationRepository.save(app);

        appendEvent(app.getId(),
                ConsultantApplicationEvent.EventType.SIGNED,
                ConsultantApplicationEvent.ActorType.CONSULTANT, null,
                Map.of("legalName", signedLegalName.trim(),
                        "from", fromStatus,
                        "to", app.getStatus()),
                request);

        try {
            emailTemplateService.sendErmReviewNotification(app);
            appendEvent(app.getId(),
                    ConsultantApplicationEvent.EventType.EMAIL_SENT,
                    ConsultantApplicationEvent.ActorType.SYSTEM, null,
                    Map.of("template", "erm_review_notification"),
                    null);
        } catch (Exception e) {
            log.warn("Failed to notify ERM after consultant submit for {}: {}",
                    applicationId, e.getMessage());
        }

        return app;
    }

    /**
     * ERM-side request-revision. Distinct from the consultant-side
     * {@link #requestRevision(String, String, HttpServletRequest)},
     * which exists for the legacy /consultant/.../request-revision
     * endpoint and operates from SUBMITTED/UPDATED. This one only
     * fires from VERIFIED -- the ERM looks at the consultant's signed
     * submission and asks for changes before countersigning.
     */
    @Transactional
    public ConsultantApplication ermRequestRevision(
            String applicationId, String remarks, HttpServletRequest request) {
        ConsultantApplication app = getByApplicationId(applicationId);
        if (!ConsultantApplication.Status.VERIFIED.name().equals(app.getStatus())) {
            throw new IllegalStateException(
                    "Only VERIFIED applications can be sent back for revision "
                            + "(status=" + app.getStatus() + ").");
        }
        if (remarks == null || remarks.isBlank()) {
            throw new IllegalArgumentException(
                    "Remarks are required when requesting a revision.");
        }
        app.setCurrentRevisionRemarks(remarks);
        Integer prevCount = app.getRevisionCount();
        app.setRevisionCount((prevCount == null ? 0 : prevCount) + 1);
        app.setStatus(ConsultantApplication.Status.REVISION_REQUESTED.name());
        applicationRepository.save(app);

        appendEvent(app.getId(),
                ConsultantApplicationEvent.EventType.REVISION_REQUESTED,
                ConsultantApplicationEvent.ActorType.ERM,
                AGREEMENT_ERM_USER_ID,
                Map.of("remarks", remarks, "revisionCount", app.getRevisionCount()),
                request);

        try {
            emailTemplateService.sendConsultantRevisionRequest(app, remarks);
            appendEvent(app.getId(),
                    ConsultantApplicationEvent.EventType.EMAIL_SENT,
                    ConsultantApplicationEvent.ActorType.SYSTEM, null,
                    Map.of("template", "consultant_revision_request"),
                    null);
        } catch (Exception e) {
            log.warn("Failed to notify consultant of revision request for {}: {}",
                    applicationId, e.getMessage());
        }

        return app;
    }

    /**
     * ERM countersigns the agreement and locks it as COMPLETED. Spawns
     * the AgreementDocumentService to render the final PDF and stores
     * it on {@code finalPdfUrl}. Sends a copy of the signed PDF to both
     * parties.
     *
     * PDF generation failure does NOT roll the state back -- the row is
     * already marked COMPLETED with the ERM signature persisted. An
     * operator can re-trigger the document rendering via the existing
     * send-email endpoint, which falls back gracefully when the URL is
     * missing.
     */
    @Transactional
    public ConsultantApplication ermApproveAndSign(
            String applicationId,
            String ermName,
            String ermTitle,
            String ermSignatureBase64,
            HttpServletRequest request) {
        ConsultantApplication app = getByApplicationId(applicationId);
        if (!ConsultantApplication.Status.VERIFIED.name().equals(app.getStatus())) {
            throw new IllegalStateException(
                    "Only VERIFIED applications can be approved + signed "
                            + "(status=" + app.getStatus() + ").");
        }
        if (ermName == null || ermName.isBlank()
                || ermTitle == null || ermTitle.isBlank()) {
            throw new IllegalArgumentException(
                    "ERM name and title are required to countersign.");
        }
        if (ermSignatureBase64 == null || ermSignatureBase64.isBlank()
                || !ermSignatureBase64.startsWith("data:image/")) {
            throw new IllegalArgumentException(
                    "An ERM signature image is required (data:image/...).");
        }

        String ermSigUrl;
        try {
            ermSigUrl = uploadSignatureToCloudinary(
                    ermSignatureBase64, "signatures/erm-" + applicationId);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Couldn't store ERM signature: " + e.getMessage(), e);
        }

        LocalDateTime now = LocalDateTime.now();
        if (app.getEffectiveDate() == null) {
            app.setEffectiveDate(now.toLocalDate());
        }
        app.setErmName(ermName);
        app.setErmTitle(ermTitle);
        app.setErmSignatureUrl(ermSigUrl);
        app.setSignatureDate(now);
        app.setStatus(ConsultantApplication.Status.COMPLETED.name());
        applicationRepository.save(app);

        appendEvent(app.getId(),
                ConsultantApplicationEvent.EventType.APPROVED_AND_SIGNED,
                ConsultantApplicationEvent.ActorType.ERM,
                AGREEMENT_ERM_USER_ID,
                Map.of("ermName", ermName, "ermTitle", ermTitle),
                request);

        // PDF render -- best-effort; on failure the row stays COMPLETED
        // with a null finalPdfUrl that an operator can backfill via
        // send-email (which re-renders if missing).
        try {
            AgreementDocumentService.PdfUploadResult pdf =
                    agreementDocumentService.generateAgreementPdf(app);
            app.setFinalPdfUrl(pdf.secureUrl());
            app.setFinalPdfPublicId(pdf.publicId());
            applicationRepository.save(app);
            appendEvent(app.getId(),
                    ConsultantApplicationEvent.EventType.PDF_GENERATED,
                    ConsultantApplicationEvent.ActorType.SYSTEM, null,
                    Map.of("url", pdf.secureUrl(),
                            "publicId", pdf.publicId(),
                            "kind", "final"),
                    null);
            try {
                emailTemplateService.sendCompletedAgreementToParties(app);
                appendEvent(app.getId(),
                        ConsultantApplicationEvent.EventType.EMAIL_SENT,
                        ConsultantApplicationEvent.ActorType.SYSTEM, null,
                        Map.of("template", "completed_agreement_to_parties"),
                        null);
            } catch (Exception emailErr) {
                log.warn("Post-countersign email failed for {}: {}",
                        applicationId, emailErr.getMessage());
            }
        } catch (Exception pdfErr) {
            log.error("Final PDF generation failed for {}: {}",
                    applicationId, pdfErr.getMessage());
        }

        return app;
    }

    /**
     * Sends the final PDF to an arbitrary recipient (operator use:
     * forwarding to the consultant's manager, legal, payroll, etc.).
     * Requires COMPLETED state -- the final PDF must already exist.
     */
    @Transactional
    public void sendPdfToCustomRecipient(
            String applicationId, String recipientEmail, String note,
            HttpServletRequest request) {
        if (recipientEmail == null || recipientEmail.isBlank()
                || !recipientEmail.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new IllegalArgumentException(
                    "A valid recipient email is required.");
        }
        ConsultantApplication app = getByApplicationId(applicationId);
        if (!ConsultantApplication.Status.COMPLETED.name().equals(app.getStatus())
                || app.getFinalPdfUrl() == null) {
            throw new IllegalStateException(
                    "The final agreement PDF is not yet available.");
        }
        try {
            emailTemplateService.sendAgreementToCustomRecipient(
                    app, recipientEmail.trim(), note);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Couldn't email the agreement: " + e.getMessage(), e);
        }
        appendEvent(app.getId(),
                ConsultantApplicationEvent.EventType.EMAIL_SENT,
                ConsultantApplicationEvent.ActorType.ERM,
                AGREEMENT_ERM_USER_ID,
                Map.of("template", "agreement_to_custom_recipient",
                        "to", recipientEmail.trim()),
                request);
    }

    /**
     * Patch object for {@link #consultantFill}. Holds every nullable
     * field the consultant can edit; {@link #applyTo} copies non-null
     * values onto the entity and returns whether at least one value
     * changed (so we skip a no-op audit row).
     *
     * Kept as a public static inner class so the controller can build
     * one from its request DTO without leaking entity-side setters.
     */
    public static class ConsultantFillPatch {
        public String primaryPhone;
        public String workAuthorizationCategory;
        public String residenceAddress;
        public java.time.LocalDate effectiveDate;
        public String technologyTrack;
        public String customScopeNotes;
        public String employerPayrollEntity;
        public String implementationPartner;
        public String endClient;
        public String roleTitle;
        public java.time.LocalDate verifiedStartDate;
        public String payrollCycle;
        public String achAccountType;
        public String achBankName;
        public String achAccountHolderName;
        public String achRoutingNumber;
        public String achAccountNumber;
        public String achNoticeEmail;
        public String achDebitDates;
        public String achDebitAmounts;
        public String bgFullLegalName;
        public String bgOtherNamesUsed;
        public String bgCurrentAddress;
        public java.time.LocalDate bgDateOfBirth;
        public String bgFullSsn;
        public String bgDriverLicense;
        public String portalPlatform;
        public String portalUsername;
        public String portalAuthorizedActions;
        public java.time.LocalDate portalEffectiveDate;
        public String portalRevocationContact;
        public String securityCheckCount;
        public String securityCheckNumbers;
        public String securityCheckBank;
        public String securityCheckHolderName;
        public String securityCheckAmount;
        public String securityCheckDates;

        /** Returns true iff at least one non-null field was applied. */
        boolean applyTo(ConsultantApplication app) {
            boolean changed = false;
            if (primaryPhone != null)             { app.setPrimaryPhone(primaryPhone); changed = true; }
            if (workAuthorizationCategory != null){ app.setWorkAuthorizationCategory(workAuthorizationCategory); changed = true; }
            if (residenceAddress != null)         { app.setResidenceAddress(residenceAddress); changed = true; }
            if (effectiveDate != null)            { app.setEffectiveDate(effectiveDate); changed = true; }
            if (technologyTrack != null)          { app.setTechnologyTrack(technologyTrack); changed = true; }
            if (customScopeNotes != null)         { app.setCustomScopeNotes(customScopeNotes); changed = true; }
            if (employerPayrollEntity != null)    { app.setEmployerPayrollEntity(employerPayrollEntity); changed = true; }
            if (implementationPartner != null)    { app.setImplementationPartner(implementationPartner); changed = true; }
            if (endClient != null)                { app.setEndClient(endClient); changed = true; }
            if (roleTitle != null)                { app.setRoleTitle(roleTitle); changed = true; }
            if (verifiedStartDate != null)        { app.setVerifiedStartDate(verifiedStartDate); changed = true; }
            if (payrollCycle != null)             { app.setPayrollCycle(payrollCycle); changed = true; }
            if (achAccountType != null)           { app.setAchAccountType(achAccountType); changed = true; }
            if (achBankName != null)              { app.setAchBankName(achBankName); changed = true; }
            if (achAccountHolderName != null)     { app.setAchAccountHolderName(achAccountHolderName); changed = true; }
            if (achRoutingNumber != null)         { app.setAchRoutingNumber(achRoutingNumber); changed = true; }
            if (achAccountNumber != null)         { app.setAchAccountNumber(achAccountNumber); changed = true; }
            if (achNoticeEmail != null)           { app.setAchNoticeEmail(achNoticeEmail); changed = true; }
            if (achDebitDates != null)            { app.setAchDebitDates(achDebitDates); changed = true; }
            if (achDebitAmounts != null)          { app.setAchDebitAmounts(achDebitAmounts); changed = true; }
            if (bgFullLegalName != null)          { app.setBgFullLegalName(bgFullLegalName); changed = true; }
            if (bgOtherNamesUsed != null)         { app.setBgOtherNamesUsed(bgOtherNamesUsed); changed = true; }
            if (bgCurrentAddress != null)         { app.setBgCurrentAddress(bgCurrentAddress); changed = true; }
            if (bgDateOfBirth != null)            { app.setBgDateOfBirth(bgDateOfBirth); changed = true; }
            if (bgFullSsn != null)                { app.setBgFullSsn(bgFullSsn); changed = true; }
            if (bgDriverLicense != null)          { app.setBgDriverLicense(bgDriverLicense); changed = true; }
            if (portalPlatform != null)           { app.setPortalPlatform(portalPlatform); changed = true; }
            if (portalUsername != null)           { app.setPortalUsername(portalUsername); changed = true; }
            if (portalAuthorizedActions != null)  { app.setPortalAuthorizedActions(portalAuthorizedActions); changed = true; }
            if (portalEffectiveDate != null)      { app.setPortalEffectiveDate(portalEffectiveDate); changed = true; }
            if (portalRevocationContact != null)  { app.setPortalRevocationContact(portalRevocationContact); changed = true; }
            if (securityCheckCount != null)       { app.setSecurityCheckCount(securityCheckCount); changed = true; }
            if (securityCheckNumbers != null)     { app.setSecurityCheckNumbers(securityCheckNumbers); changed = true; }
            if (securityCheckBank != null)        { app.setSecurityCheckBank(securityCheckBank); changed = true; }
            if (securityCheckHolderName != null)  { app.setSecurityCheckHolderName(securityCheckHolderName); changed = true; }
            if (securityCheckAmount != null)      { app.setSecurityCheckAmount(securityCheckAmount); changed = true; }
            if (securityCheckDates != null)       { app.setSecurityCheckDates(securityCheckDates); changed = true; }
            return changed;
        }

        /** Names of every field the caller actually sent (non-null). */
        List<String> touchedFieldNames() {
            List<String> names = new java.util.ArrayList<>();
            if (primaryPhone != null) names.add("primaryPhone");
            if (workAuthorizationCategory != null) names.add("workAuthorizationCategory");
            if (residenceAddress != null) names.add("residenceAddress");
            if (effectiveDate != null) names.add("effectiveDate");
            if (technologyTrack != null) names.add("technologyTrack");
            if (customScopeNotes != null) names.add("customScopeNotes");
            if (employerPayrollEntity != null) names.add("employerPayrollEntity");
            if (implementationPartner != null) names.add("implementationPartner");
            if (endClient != null) names.add("endClient");
            if (roleTitle != null) names.add("roleTitle");
            if (verifiedStartDate != null) names.add("verifiedStartDate");
            if (payrollCycle != null) names.add("payrollCycle");
            if (achAccountType != null) names.add("achAccountType");
            if (achBankName != null) names.add("achBankName");
            if (achAccountHolderName != null) names.add("achAccountHolderName");
            if (achRoutingNumber != null) names.add("achRoutingNumber");
            if (achAccountNumber != null) names.add("achAccountNumber");
            if (achNoticeEmail != null) names.add("achNoticeEmail");
            if (achDebitDates != null) names.add("achDebitDates");
            if (achDebitAmounts != null) names.add("achDebitAmounts");
            if (bgFullLegalName != null) names.add("bgFullLegalName");
            if (bgOtherNamesUsed != null) names.add("bgOtherNamesUsed");
            if (bgCurrentAddress != null) names.add("bgCurrentAddress");
            if (bgDateOfBirth != null) names.add("bgDateOfBirth");
            if (bgFullSsn != null) names.add("bgFullSsn");
            if (bgDriverLicense != null) names.add("bgDriverLicense");
            if (portalPlatform != null) names.add("portalPlatform");
            if (portalUsername != null) names.add("portalUsername");
            if (portalAuthorizedActions != null) names.add("portalAuthorizedActions");
            if (portalEffectiveDate != null) names.add("portalEffectiveDate");
            if (portalRevocationContact != null) names.add("portalRevocationContact");
            if (securityCheckCount != null) names.add("securityCheckCount");
            if (securityCheckNumbers != null) names.add("securityCheckNumbers");
            if (securityCheckBank != null) names.add("securityCheckBank");
            if (securityCheckHolderName != null) names.add("securityCheckHolderName");
            if (securityCheckAmount != null) names.add("securityCheckAmount");
            if (securityCheckDates != null) names.add("securityCheckDates");
            return names;
        }
    }

    /**
     * Decodes a data:image/...;base64,... data URL and pushes the
     * bytes to Cloudinary under the given public_id. Returns the
     * Cloudinary {@code secure_url}.
     */
    private String uploadSignatureToCloudinary(String dataUrl, String publicId)
            throws java.io.IOException {
        int comma = dataUrl.indexOf(',');
        if (comma < 0) {
            throw new java.io.IOException("Malformed data URL (no comma).");
        }
        byte[] bytes = java.util.Base64.getDecoder()
                .decode(dataUrl.substring(comma + 1));
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>)
                cloudinary.uploader().upload(bytes,
                        com.cloudinary.utils.ObjectUtils.asMap(
                                "public_id", publicId,
                                "resource_type", "image",
                                "overwrite", true));
        Object url = result.get("secure_url");
        if (url == null) {
            throw new java.io.IOException(
                    "Cloudinary returned no secure_url for " + publicId);
        }
        return url.toString();
    }

    // ── Cron sweep ──────────────────────────────────────────────────

    /**
     * Flip every in-flight application whose {@code expiresAt} is in
     * the past to EXPIRED, append an audit event, and return how many
     * we touched. Called from {@link ConsultantExpiryJob} once daily.
     *
     * Idempotent — terminal statuses are filtered out by
     * {@link ConsultantApplicationRepository#findByStatusInAndExpiresAtBefore}
     * so re-running the sweep is safe.
     */
    @Transactional
    public int expireStaleApplications() {
        LocalDateTime now = LocalDateTime.now();
        List<String> inFlight = List.of(
                ConsultantApplication.Status.DRAFT.name(),
                ConsultantApplication.Status.SUBMITTED.name(),
                ConsultantApplication.Status.REVISION_REQUESTED.name(),
                ConsultantApplication.Status.UPDATED.name(),
                ConsultantApplication.Status.VERIFIED.name());
        List<ConsultantApplication> stale =
                applicationRepository.findByStatusInAndExpiresAtBefore(inFlight, now);
        if (stale.isEmpty()) return 0;

        for (ConsultantApplication app : stale) {
            String previousStatus = app.getStatus();
            app.setStatus(ConsultantApplication.Status.EXPIRED.name());
            applicationRepository.save(app);
            appendEvent(app.getId(),
                    ConsultantApplicationEvent.EventType.EXPIRED,
                    ConsultantApplicationEvent.ActorType.SYSTEM, null,
                    Map.of("expiredAt", now.toString(),
                            "previousStatus", previousStatus),
                    null);
        }
        return stale.size();
    }

    // ── Internal helpers ────────────────────────────────────────────

    private void saveRevision(Long applicationDbId, int version, String payloadJson,
                              String role, Long actorUserId) {
        ConsultantApplicationRevision rev = ConsultantApplicationRevision.builder()
                .applicationId(applicationDbId)
                .versionNumber(version)
                .payloadSnapshot(payloadJson)
                .createdByRole(role)
                .createdByUserId(actorUserId)
                .build();
        revisionRepository.save(rev);
    }

    private void appendEvent(Long applicationDbId,
                             ConsultantApplicationEvent.EventType type,
                             ConsultantApplicationEvent.ActorType actorType,
                             Long actorUserId,
                             Map<String, Object> metadata,
                             HttpServletRequest request) {
        String metaJson;
        try {
            metaJson = objectMapper.writeValueAsString(
                    metadata == null ? new LinkedHashMap<String, Object>() : metadata);
        } catch (Exception e) {
            metaJson = "{}";
        }
        String ip = request == null ? null : clientIp(request);
        String ua = request == null ? null : request.getHeader("User-Agent");

        ConsultantApplicationEvent event = ConsultantApplicationEvent.builder()
                .applicationId(applicationDbId)
                .eventType(type.name())
                .actorType(actorType.name())
                .actorUserId(actorUserId)
                .metadata(metaJson)
                .ipAddress(ip)
                .userAgent(ua)
                .build();
        eventRepository.save(event);
    }

    private String stringify(JsonNode payload) {
        if (payload == null) return "{}";
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{}";
        }
    }

    private void validateRequired(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required.");
        }
    }

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
