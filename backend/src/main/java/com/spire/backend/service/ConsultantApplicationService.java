package com.spire.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spire.backend.entity.ConsultantApplication;
import com.spire.backend.entity.ConsultantApplicationEvent;
import com.spire.backend.entity.ConsultantApplicationRevision;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.exception.UnauthorizedException;
import com.spire.backend.repository.ConsultantApplicationEventRepository;
import com.spire.backend.repository.ConsultantApplicationRepository;
import com.spire.backend.repository.ConsultantApplicationRevisionRepository;
import com.spire.backend.security.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Core service for the Consultant Agreement feature.
 *
 * State machine (see {@link ConsultantApplication.Status}):
 *   DRAFT -> SUBMITTED -> (REVISION_REQUESTED -> UPDATED)*
 *           -> VERIFIED -> SIGNED -> COMPLETED
 * Off-ramps: CANCELLED (ERM) / EXPIRED (cron).
 *
 * Foundation scope: create / read / list / update / cancel / OTP
 * request / OTP verify. Sign + PDF generation + revision-request
 * + ERM-side update emails ship in a follow-up batch once the PDF
 * template + structured field schema are locked.
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
    private static final int OTP_TTL_MINUTES = 10;
    private static final int OTP_MAX_ATTEMPTS = 5;
    private static final int OTP_LOCKOUT_HOURS = 1;

    private final ConsultantApplicationRepository applicationRepository;
    private final ConsultantApplicationEventRepository eventRepository;
    private final ConsultantApplicationRevisionRepository revisionRepository;
    private final JwtService jwtService;
    private final EmailTemplateService emailTemplateService;
    private final ConsultantPdfService consultantPdfService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();

    // ── ERM-side operations ─────────────────────────────────────────

    @Transactional
    public ConsultantApplication createApplication(
            Long ermUserId,
            String consultantEmail,
            String consultantName,
            String consultantPhone,
            JsonNode payload,
            HttpServletRequest request
    ) {
        validateRequired("consultantEmail", consultantEmail);

        String applicationId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        String payloadJson = stringify(payload);

        ConsultantApplication app = ConsultantApplication.builder()
                .applicationId(applicationId)
                .ermUserId(ermUserId)
                .consultantEmail(consultantEmail.trim().toLowerCase())
                .consultantName(consultantName)
                .consultantPhone(consultantPhone)
                .payload(payloadJson)
                .status(ConsultantApplication.Status.SUBMITTED.name())
                .expiresAt(now.plusDays(APPLICATION_TTL_DAYS))
                .build();
        app = applicationRepository.save(app);

        saveRevision(app.getId(), 1, payloadJson, "ERM", ermUserId);

        appendEvent(app.getId(),
                ConsultantApplicationEvent.EventType.CREATED,
                ConsultantApplicationEvent.ActorType.ERM,
                ermUserId,
                Map.of("applicationId", applicationId,
                        "consultantEmail", app.getConsultantEmail()),
                request);

        try {
            emailTemplateService.sendConsultantApplicationCreated(app);
            appendEvent(app.getId(),
                    ConsultantApplicationEvent.EventType.EMAIL_SENT,
                    ConsultantApplicationEvent.ActorType.SYSTEM, null,
                    Map.of("template", "application_created",
                            "to", app.getConsultantEmail()),
                    null);
        } catch (Exception e) {
            log.warn("Failed to send application-created email for {}: {}",
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
    public Page<ConsultantApplication> listForErm(Long ermUserId, String status, Pageable pageable) {
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) {
            return applicationRepository.findByErmUserId(ermUserId, pageable);
        }
        return applicationRepository.findByErmUserIdAndStatus(ermUserId, status, pageable);
    }

    @Transactional
    public ConsultantApplication updateApplication(
            String applicationId,
            Long ermUserId,
            String consultantEmail,
            String consultantName,
            String consultantPhone,
            JsonNode payload,
            HttpServletRequest request
    ) {
        ConsultantApplication app = requireOwned(applicationId, ermUserId);
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
            saveRevision(app.getId(), nextVersion, payloadJson, "ERM", ermUserId);
        }

        app.setStatus(ConsultantApplication.Status.UPDATED.name());
        applicationRepository.save(app);

        appendEvent(app.getId(),
                ConsultantApplicationEvent.EventType.REVISED,
                ConsultantApplicationEvent.ActorType.ERM, ermUserId,
                Map.of("status", app.getStatus()),
                request);

        return app;
    }

    @Transactional
    public ConsultantApplication cancel(
            String applicationId, Long ermUserId, HttpServletRequest request) {
        ConsultantApplication app = requireOwned(applicationId, ermUserId);
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
                ConsultantApplicationEvent.ActorType.ERM, ermUserId,
                Map.of(), request);
        return app;
    }

    @Transactional
    public void resendInvite(String applicationId, Long ermUserId,
                             HttpServletRequest request) {
        ConsultantApplication app = requireOwned(applicationId, ermUserId);
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
    public List<ConsultantApplicationEvent> listEvents(String applicationId, Long ermUserId) {
        ConsultantApplication app = requireOwned(applicationId, ermUserId);
        return eventRepository.findByApplicationIdOrderByCreatedAtDesc(app.getId());
    }

    @Transactional(readOnly = true)
    public List<ConsultantApplicationRevision> listRevisions(String applicationId, Long ermUserId) {
        ConsultantApplication app = requireOwned(applicationId, ermUserId);
        return revisionRepository.findByApplicationIdOrderByVersionNumberDesc(app.getId());
    }

    // ── Consultant-side OTP flow ────────────────────────────────────

    /**
     * Always returns silently regardless of whether the email matches
     * the application -- prevents enumeration of valid (applicationId,
     * email) pairs. The actual OTP send only happens on a match.
     */
    @Transactional
    public void requestOtp(String applicationId, String email,
                           HttpServletRequest request) {
        if (email == null || email.isBlank()) return;
        applicationRepository.findByApplicationId(applicationId).ifPresent(app -> {
            if (!app.getConsultantEmail().equalsIgnoreCase(email.trim())) {
                log.info("OTP request email mismatch for application {}", applicationId);
                return;
            }
            if (isTerminalStatus(app.getStatus())) {
                log.info("OTP request for terminal-status application {} ({})",
                        applicationId, app.getStatus());
                return;
            }

            String otp = generateOtp();
            app.setOtpHash(passwordEncoder.encode(otp));
            app.setOtpExpiresAt(LocalDateTime.now().plusMinutes(OTP_TTL_MINUTES));
            app.setOtpFailedAttempts(0);
            app.setOtpLockedUntil(null);
            applicationRepository.save(app);

            try {
                emailTemplateService.sendConsultantOtp(app, otp);
            } catch (Exception e) {
                log.warn("Failed to send consultant OTP for {}: {}",
                        applicationId, e.getMessage());
            }

            appendEvent(app.getId(),
                    ConsultantApplicationEvent.EventType.OTP_SENT,
                    ConsultantApplicationEvent.ActorType.CONSULTANT, null,
                    Map.of("to", app.getConsultantEmail()),
                    request);
        });
    }

    /**
     * Returns a short-lived consultant JWT on success. The token's
     * subject is the public applicationId and it carries
     * {@code purpose=consultant} so the regular {@link
     * com.spire.backend.security.JwtAuthFilter} ignores it.
     */
    @Transactional
    public String verifyOtp(String applicationId, String otp,
                            HttpServletRequest request) {
        ConsultantApplication app = getByApplicationId(applicationId);
        LocalDateTime now = LocalDateTime.now();

        if (app.getOtpLockedUntil() != null && app.getOtpLockedUntil().isAfter(now)) {
            throw new UnauthorizedException(
                    "Too many failed attempts. Try again after "
                            + app.getOtpLockedUntil().toString() + ".");
        }
        if (app.getOtpHash() == null || app.getOtpExpiresAt() == null
                || app.getOtpExpiresAt().isBefore(now)) {
            throw new UnauthorizedException(
                    "Code has expired. Request a new one.");
        }

        boolean ok = otp != null && passwordEncoder.matches(otp, app.getOtpHash());
        if (!ok) {
            int failed = app.getOtpFailedAttempts() + 1;
            app.setOtpFailedAttempts(failed);
            if (failed >= OTP_MAX_ATTEMPTS) {
                app.setOtpLockedUntil(now.plusHours(OTP_LOCKOUT_HOURS));
            }
            applicationRepository.save(app);

            appendEvent(app.getId(),
                    ConsultantApplicationEvent.EventType.OTP_FAILED,
                    ConsultantApplicationEvent.ActorType.CONSULTANT, null,
                    Map.of("failedAttempts", failed),
                    request);
            throw new UnauthorizedException("Invalid code.");
        }

        app.setOtpHash(null);
        app.setOtpExpiresAt(null);
        app.setOtpFailedAttempts(0);
        app.setOtpLockedUntil(null);
        applicationRepository.save(app);

        appendEvent(app.getId(),
                ConsultantApplicationEvent.EventType.OTP_VERIFIED,
                ConsultantApplicationEvent.ActorType.CONSULTANT, null,
                Map.of(), request);

        return jwtService.generateConsultantToken(applicationId);
    }

    // ── Consultant-side authenticated actions ───────────────────────
    //
    // All of these require the consultant JWT issued by verifyOtp and
    // enforce the per-application scope at the controller layer
    // (auth.getName() == applicationId from the URL path).

    @Transactional(readOnly = true)
    public ConsultantApplication getForConsultant(String applicationId) {
        ConsultantApplication app = getByApplicationId(applicationId);
        if (isTerminalCancellation(app.getStatus())) {
            throw new IllegalStateException(
                    "This application is no longer accepting consultant actions.");
        }
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
        // leave the application in SIGNED so an ERM can retry; the
        // signature row is already persisted and replayable.
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

    private ConsultantApplication requireOwned(String applicationId, Long ermUserId) {
        ConsultantApplication app = getByApplicationId(applicationId);
        if (!app.getErmUserId().equals(ermUserId)) {
            throw new UnauthorizedException(
                    "Application " + applicationId + " is owned by another ERM.");
        }
        return app;
    }

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

    private String generateOtp() {
        int code = 100_000 + random.nextInt(900_000);
        return String.valueOf(code);
    }

    private boolean isTerminalStatus(String status) {
        return ConsultantApplication.Status.SIGNED.name().equals(status)
                || ConsultantApplication.Status.COMPLETED.name().equals(status)
                || ConsultantApplication.Status.CANCELLED.name().equals(status)
                || ConsultantApplication.Status.EXPIRED.name().equals(status);
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
