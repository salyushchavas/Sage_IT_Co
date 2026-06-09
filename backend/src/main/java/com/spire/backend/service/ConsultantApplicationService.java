package com.spire.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spire.backend.entity.AgreementUser;
import com.spire.backend.entity.AgreementUserRole;
import com.spire.backend.entity.ConsultantApplication;
import com.spire.backend.entity.ConsultantApplicationEvent;
import com.spire.backend.entity.ConsultantApplicationRevision;
import com.spire.backend.entity.ConsultantVerification;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.repository.AgreementUserRepository;
import com.spire.backend.repository.ConsultantApplicationEventRepository;
import com.spire.backend.repository.ConsultantApplicationRepository;
import com.spire.backend.repository.ConsultantApplicationRevisionRepository;
import com.spire.backend.repository.ConsultantVerificationRepository;
import com.spire.backend.security.AgreementAuthz;
import com.spire.backend.security.AgreementOwnership;
import com.spire.backend.security.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
     * Build L — invite stays valid for 15 days from {@code inviteSentAt}.
     * Past that, a SUBMITTED row is flipped to EXPIRED both lazily on
     * consultant access (so the dashboard reflects the state without
     * waiting for the cron) and by the daily sweep.
     */
    private static final int INVITE_VALIDITY_DAYS = 15;

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
    private final ConsultantVersionService consultantVersionService;
    private final AgreementUserRepository agreementUserRepository;
    private final ConsultantVerificationRepository verificationRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final com.cloudinary.Cloudinary cloudinary;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SecureRandom secureRandom = new SecureRandom();

    // Consultant OTP gate tunables.
    private static final int OTP_TTL_MINUTES = 10;
    private static final int OTP_MAX_ATTEMPTS = 5;
    private static final int OTP_RESEND_COOLDOWN_SECONDS = 60;
    private static final int OTP_MAX_PER_HOUR = 5;
    private static final String OTP_GENERIC_SENT_MSG =
            "If that email matches this agreement, a 6-digit code has been sent.";

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
            String visaStatus,
            Boolean requireAppendix1,
            Boolean requireAppendix2,
            Boolean requireAppendix3,
            Boolean requireAppendix4,
            Boolean requireAppendix5,
            Boolean requireSsn,
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
                // F-4: ERM-set visa is persisted on the existing
                // workAuthCategory column (no new column).
                .workAuthorizationCategory(
                        visaStatus == null || visaStatus.isBlank()
                                ? null
                                : visaStatus.trim())
                .requireAppendix1(Boolean.TRUE.equals(requireAppendix1))
                .requireAppendix2(Boolean.TRUE.equals(requireAppendix2))
                .requireAppendix3(Boolean.TRUE.equals(requireAppendix3))
                .requireAppendix4(Boolean.TRUE.equals(requireAppendix4))
                .requireAppendix5(Boolean.TRUE.equals(requireAppendix5))
                .requireSsn(Boolean.TRUE.equals(requireSsn))
                // Build G — effective date is the creation date. Was
                // formerly set at ermApproveAndSign; moving it here so
                // the consultant sees a stable "Effective: MM-DD-YYYY"
                // on the cover step and the PDF stamps it consistently.
                .effectiveDate(now.toLocalDate())
                // Build L — invite_sent_at tracks the LAST time the ERM
                // sent the fill invitation. Drives the 15-day expiry
                // sweep + the lazy access guard. Reset on every
                // {@link #resendInvite}.
                .inviteSentAt(now)
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

    // ── Consultant email-OTP gate (Phase D) ──────────────────────────

    private static boolean isConsultantActionable(ConsultantApplication app) {
        String s = app.getStatus();
        return ConsultantApplication.Status.SUBMITTED.name().equals(s)
                || ConsultantApplication.Status.REVISION_REQUESTED.name().equals(s);
    }

    private String generateOtp() {
        return String.format("%06d", secureRandom.nextInt(1_000_000));
    }

    /**
     * Portal request-otp. NON-ENUMERATING: the response copy is the
     * same whether the email is on record or not. A code is sent ONLY
     * when {@code email} matches the on-record {@code consultantEmail}
     * of at least one non-deleted, consultant-actionable agreement
     * (SUBMITTED or REVISION_REQUESTED), and only when the 60s cooldown
     * + hourly cap allow it. Any prior active challenge for the email
     * is superseded so just one code is ever live per consultant.
     *
     * Audit OTP_SENT is recorded against every matching agreement so
     * the per-ERM detail timeline still reflects the consultant's
     * activity.
     */
    @Transactional
    public String requestPortalOtp(String email, HttpServletRequest request) {
        String normalised = email == null ? "" : email.trim().toLowerCase();
        if (normalised.isEmpty()) return OTP_GENERIC_SENT_MSG;

        List<ConsultantApplication> matches = applicationRepository
                .findByConsultantEmailIgnoreCaseAndDeletedFalseOrderByCreatedAtDesc(
                        normalised);
        boolean anyActionable = matches.stream().anyMatch(
                ConsultantApplicationService::isConsultantActionable);
        if (matches.isEmpty() || !anyActionable) {
            return OTP_GENERIC_SENT_MSG;
        }

        LocalDateTime now = LocalDateTime.now();
        Optional<ConsultantVerification> latest =
                verificationRepository.findFirstByEmailOrderByCreatedAtDesc(normalised);
        if (latest.isPresent() && latest.get().getLastSentAt() != null
                && latest.get().getLastSentAt()
                        .isAfter(now.minusSeconds(OTP_RESEND_COOLDOWN_SECONDS))) {
            return OTP_GENERIC_SENT_MSG;
        }
        long lastHour = verificationRepository.countByEmailAndLastSentAtAfter(
                normalised, now.minusHours(1));
        if (lastHour >= OTP_MAX_PER_HOUR) {
            return OTP_GENERIC_SENT_MSG;
        }

        for (ConsultantVerification prior :
                verificationRepository.findByEmailAndConsumedAtIsNull(normalised)) {
            prior.setConsumedAt(now);
            verificationRepository.save(prior);
        }

        String code = generateOtp();
        String ip = clientIp(request);
        verificationRepository.save(ConsultantVerification.builder()
                .email(normalised)
                .otpHash(passwordEncoder.encode(code))
                .expiresAt(now.plusMinutes(OTP_TTL_MINUTES))
                .attempts(0)
                .lastSentAt(now)
                .resendCount((int) lastHour + 1)
                .requestIp(ip)
                .build());

        // Pick a representative actionable agreement to drive the email
        // template + audit event. Falls back to any match.
        ConsultantApplication primary = matches.stream()
                .filter(ConsultantApplicationService::isConsultantActionable)
                .findFirst()
                .orElse(matches.get(0));
        for (ConsultantApplication match : matches) {
            appendEvent(match.getId(),
                    ConsultantApplicationEvent.EventType.OTP_SENT,
                    ConsultantApplicationEvent.ActorType.CONSULTANT, null,
                    Map.of("ip", ip == null ? "" : ip,
                            "email", normalised),
                    request);
        }
        try {
            emailTemplateService.sendConsultantOtp(primary, code);
        } catch (Exception e) {
            log.warn("Failed to send consultant portal OTP to {}: {}",
                    normalised, e.getMessage());
        }
        return OTP_GENERIC_SENT_MSG;
    }

    /**
     * Portal verify-otp. On success: consume the challenge, audit on
     * every agreement for this email, mint an email-scoped consultant
     * token (no appId claim). On any failure: generic
     * IllegalArgumentException -> 400. The 5th wrong attempt
     * invalidates the challenge.
     */
    @Transactional
    public String verifyPortalOtp(String email, String otp, HttpServletRequest request) {
        String normalised = email == null ? "" : email.trim().toLowerCase();
        ConsultantVerification cv = verificationRepository
                .findFirstByEmailAndConsumedAtIsNullOrderByCreatedAtDesc(normalised)
                .orElse(null);

        if (cv == null
                || cv.getExpiresAt().isBefore(LocalDateTime.now())
                || cv.getAttempts() >= OTP_MAX_ATTEMPTS) {
            throw new IllegalArgumentException("Invalid or expired code.");
        }
        if (otp == null || !passwordEncoder.matches(otp.trim(), cv.getOtpHash())) {
            cv.setAttempts(cv.getAttempts() + 1);
            if (cv.getAttempts() >= OTP_MAX_ATTEMPTS) {
                cv.setConsumedAt(LocalDateTime.now());
            }
            verificationRepository.save(cv);
            // No app context on a wrong attempt -- audit OTP_FAILED on
            // every agreement for this email so the operator can see
            // the failed attempts in the timeline.
            List<ConsultantApplication> matches = applicationRepository
                    .findByConsultantEmailIgnoreCaseAndDeletedFalseOrderByCreatedAtDesc(
                            normalised);
            for (ConsultantApplication match : matches) {
                appendEvent(match.getId(),
                        ConsultantApplicationEvent.EventType.OTP_FAILED,
                        ConsultantApplicationEvent.ActorType.CONSULTANT, null,
                        Map.of("attempts", String.valueOf(cv.getAttempts())),
                        request);
            }
            throw new IllegalArgumentException("Invalid or expired code.");
        }

        LocalDateTime now = LocalDateTime.now();
        cv.setConsumedAt(now);
        verificationRepository.save(cv);

        String ip = clientIp(request);
        // Stamp access on every agreement this consultant can act on so
        // the ERM detail page reflects "consultant signed in" once,
        // regardless of which agreement they open first.
        List<ConsultantApplication> matches = applicationRepository
                .findByConsultantEmailIgnoreCaseAndDeletedFalseOrderByCreatedAtDesc(
                        normalised);
        for (ConsultantApplication match : matches) {
            if (match.getAccessAt() == null) {
                match.setAccessIp(ip);
                match.setAccessAt(now);
                applicationRepository.save(match);
            }
            appendEvent(match.getId(),
                    ConsultantApplicationEvent.EventType.OTP_VERIFIED,
                    ConsultantApplicationEvent.ActorType.CONSULTANT, null,
                    Map.of("ip", ip == null ? "" : ip),
                    request);
        }
        return jwtService.generateConsultantToken(normalised);
    }

    /**
     * Portal dashboard list. Returns every non-deleted agreement
     * addressed to {@code email}, EXCLUDING CANCELLED only. EXPIRED
     * rows STAY visible so the consultant can see the "invitation
     * expired -- please contact Sage IT" state on the dashboard
     * (Build L). Sorted: actionable first (SUBMITTED,
     * REVISION_REQUESTED), then VERIFIED, then COMPLETED, EXPIRED last.
     */
    @Transactional(readOnly = true)
    public List<ConsultantApplication> listForConsultant(String email) {
        String normalised = email == null ? "" : email.trim().toLowerCase();
        if (normalised.isEmpty()) return List.of();
        return applicationRepository
                .findByConsultantEmailIgnoreCaseAndDeletedFalseOrderByCreatedAtDesc(
                        normalised)
                .stream()
                .filter(app -> !ConsultantApplication.Status.CANCELLED.name()
                        .equals(app.getStatus()))
                .sorted((a, b) -> Integer.compare(
                        dashboardRank(a.getStatus()),
                        dashboardRank(b.getStatus())))
                .toList();
    }

    private static int dashboardRank(String status) {
        if (ConsultantApplication.Status.SUBMITTED.name().equals(status)) return 0;
        if (ConsultantApplication.Status.REVISION_REQUESTED.name().equals(status)) return 0;
        if (ConsultantApplication.Status.UPDATED.name().equals(status)) return 1;
        if (ConsultantApplication.Status.VERIFIED.name().equals(status)) return 1;
        if (ConsultantApplication.Status.SIGNED.name().equals(status)) return 2;
        if (ConsultantApplication.Status.COMPLETED.name().equals(status)) return 3;
        // Build L — EXPIRED is shown but ranked last so actionable
        // items always sit above it.
        if (ConsultantApplication.Status.EXPIRED.name().equals(status)) return 5;
        return 4;
    }

    /**
     * Phase B ownership guard for ERM-authenticated calls: the caller
     * must own the application (or be the super-admin), else 404. Reads
     * the authenticated identity off the request attributes stamped by
     * AgreementErmAuthFilter. Call immediately after loading, before any
     * read or mutation. NOT applied to the consultant-side flow (those
     * endpoints are appId-only and stay open).
     */
    public void assertErmCanAccess(ConsultantApplication app, HttpServletRequest request) {
        // Phase C — an archived (soft-deleted) application is gone from
        // the app's perspective: 404 on detail + every mutation, for the
        // owner AND the super-admin (recovery is DB-level only).
        if (Boolean.TRUE.equals(app.getDeleted())) {
            throw new ResourceNotFoundException(
                    "ConsultantApplication", "applicationId", app.getApplicationId());
        }
        AgreementOwnership.assertCanAccess(
                app, AgreementAuthz.userId(request), AgreementAuthz.roleEnum(request));
    }

    /**
     * Phase B — per-ERM data isolation. The super-admin sees every
     * application; an ERM sees only ones they own ({@code
     * owner_erm_id == userId}). The owner-scoped query keeps non-owned
     * rows in the DB, never serialized to the wrong ERM. Owner names are
     * resolved for the super-admin's oversight column.
     */
    @Transactional(readOnly = true)
    public Page<ConsultantApplication> listApplications(
            String status, Pageable pageable, String userId, AgreementUserRole role) {
        boolean all = status == null || status.isBlank() || "ALL".equalsIgnoreCase(status);
        Page<ConsultantApplication> page;
        if (role == AgreementUserRole.SUPER_ADMIN) {
            // Phase C — archived rows excluded everywhere.
            page = all
                    ? applicationRepository.findByDeletedFalse(pageable)
                    : applicationRepository.findByStatusAndDeletedFalse(status, pageable);
        } else {
            // ERM (or null role): only their own. A null/empty userId
            // matches nothing, so an unauthenticated/legacy token sees
            // an empty list rather than everyone's data.
            String owner = userId == null ? "" : userId;
            page = all
                    ? applicationRepository.findByOwnerErmIdAndDeletedFalse(owner, pageable)
                    : applicationRepository.findByOwnerErmIdAndStatusAndDeletedFalse(owner, status, pageable);
        }
        populateOwnerNames(page.getContent());
        return page;
    }

    /**
     * Batch-resolves the owning ERM's display name onto the transient
     * {@code ownerName} field for the super-admin's list column. One
     * query for the whole page rather than per-row lookups.
     */
    private void populateOwnerNames(List<ConsultantApplication> apps) {
        if (apps == null || apps.isEmpty()) return;
        java.util.Set<String> ownerIds = apps.stream()
                .map(ConsultantApplication::getOwnerErmId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        if (ownerIds.isEmpty()) return;
        Map<String, String> idToName = new java.util.HashMap<>();
        for (AgreementUser u : agreementUserRepository.findAllById(ownerIds)) {
            idToName.put(u.getId(), u.getFullName());
        }
        for (ConsultantApplication app : apps) {
            if (app.getOwnerErmId() != null) {
                app.setOwnerName(idToName.get(app.getOwnerErmId()));
            }
        }
    }

    /**
     * Phase C feature 3 — every live (non-archived) application for the
     * admin "Agreements by ERM" grouped view, newest first, with owner
     * names resolved. Super-admin-only (the controller enforces the role).
     */
    @Transactional(readOnly = true)
    public List<ConsultantApplication> listAllForAdmin() {
        List<ConsultantApplication> apps =
                applicationRepository.findByDeletedFalseOrderByCreatedAtDesc();
        populateOwnerNames(apps);
        return apps;
    }

    /**
     * Build L — super-admin soft-deletes ANY agreement, regardless of
     * status. The row stays in the DB (recoverable; audit history +
     * Cloudinary PDF preserved) but is hidden from every console list
     * (ERM, super-admin, consultant) and 404s on detail / mutation
     * for all three roles. Replaces the Phase C "only CANCELLED can be
     * archived" restriction.
     *
     * Audit event is still APPLICATION_ARCHIVED (kept for back-compat
     * with the existing event stream) -- the metadata carries the
     * previous status so an operator can tell what was wiped.
     */
    @Transactional
    public void deleteApplication(
            String applicationId, String adminUserId, HttpServletRequest request) {
        ConsultantApplication app = getByApplicationId(applicationId);
        if (Boolean.TRUE.equals(app.getDeleted())) {
            // Already deleted — treat as gone.
            throw new ResourceNotFoundException(
                    "ConsultantApplication", "applicationId", applicationId);
        }
        String previousStatus = app.getStatus();
        app.setDeleted(true);
        app.setDeletedAt(LocalDateTime.now());
        app.setDeletedBy(adminUserId);
        applicationRepository.save(app);

        appendEvent(app.getId(),
                ConsultantApplicationEvent.EventType.APPLICATION_ARCHIVED,
                ConsultantApplicationEvent.ActorType.ERM, AGREEMENT_ERM_USER_ID,
                Map.of("deletedBy", adminUserId == null ? "" : adminUserId,
                        "previousStatus", previousStatus == null ? "" : previousStatus),
                request);
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
        assertErmCanAccess(app, request);
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
        assertErmCanAccess(app, request);
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

    /**
     * Phase C — re-sends the consultant FILL invitation (the create-time
     * {@link EmailTemplateService#sendConsultantInitialFill}, pointing at
     * /consultant/{appId}/fill) to the CURRENT consultantEmail. The
     * manual recovery path when a typo'd email left a consultant stuck.
     * Only meaningful while the consultant still needs the form
     * (SUBMITTED or REVISION_REQUESTED) → else 409.
     */
    @Transactional
    public void resendInvite(String applicationId, HttpServletRequest request) {
        ConsultantApplication app = getByApplicationId(applicationId);
        assertErmCanAccess(app, request);
        String status = app.getStatus();
        if (!ConsultantApplication.Status.SUBMITTED.name().equals(status)
                && !ConsultantApplication.Status.REVISION_REQUESTED.name().equals(status)) {
            throw new IllegalStateException(
                    "The invitation can only be resent while the consultant still "
                            + "needs to complete the form (status=" + status + ").");
        }
        try {
            emailTemplateService.sendConsultantInitialFill(app);
            // Build L — resetting invite_sent_at to "now" restarts the
            // 15-day expiry clock, so an ERM resend gives the
            // consultant a fresh window without an operator having to
            // touch the row directly.
            app.setInviteSentAt(LocalDateTime.now());
            applicationRepository.save(app);
            appendEvent(app.getId(),
                    ConsultantApplicationEvent.EventType.EMAIL_SENT,
                    ConsultantApplicationEvent.ActorType.SYSTEM, null,
                    Map.of("template", "invite_resend",
                            "to", app.getConsultantEmail()),
                    request);
        } catch (Exception e) {
            throw new IllegalStateException("Couldn't resend invite: " + e.getMessage());
        }
    }

    /**
     * Phase C feature 1 — ERM (owner) fixes a wrong consultant email /
     * name. The new email flows into the ${primaryEmail} PDF placeholder
     * for any FUTURE generation; it does NOT alter an already-generated
     * COMPLETED PDF (immutable by design). Allowed while the contact is
     * still actionable (SUBMITTED, VERIFIED, REVISION_REQUESTED,
     * COMPLETED); rejected (409) otherwise.
     */
    @Transactional
    public ConsultantApplication updateConsultantContact(
            String applicationId, String consultantEmail, String consultantName,
            HttpServletRequest request) {
        ConsultantApplication app = getByApplicationId(applicationId);
        assertErmCanAccess(app, request);

        String status = app.getStatus();
        boolean editable =
                ConsultantApplication.Status.SUBMITTED.name().equals(status)
                || ConsultantApplication.Status.VERIFIED.name().equals(status)
                || ConsultantApplication.Status.REVISION_REQUESTED.name().equals(status)
                || ConsultantApplication.Status.COMPLETED.name().equals(status);
        if (!editable) {
            throw new IllegalStateException(
                    "Consultant contact can't be edited in status " + status + ".");
        }

        String newEmail = consultantEmail == null ? "" : consultantEmail.trim();
        if (newEmail.isBlank()
                || !newEmail.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new IllegalArgumentException("A valid consultant email is required.");
        }
        String newName = consultantName == null ? null : consultantName.trim();

        String oldEmail = app.getConsultantEmail();
        String oldName = app.getConsultantName();

        app.setConsultantEmail(newEmail.toLowerCase());
        if (newName != null && !newName.isBlank()) {
            app.setConsultantName(newName);
        }
        applicationRepository.save(app);

        appendEvent(app.getId(),
                ConsultantApplicationEvent.EventType.CONSULTANT_CONTACT_UPDATED,
                ConsultantApplicationEvent.ActorType.ERM, AGREEMENT_ERM_USER_ID,
                Map.of("oldEmail", oldEmail == null ? "" : oldEmail,
                        "newEmail", app.getConsultantEmail(),
                        "oldName", oldName == null ? "" : oldName,
                        "newName", app.getConsultantName() == null ? "" : app.getConsultantName()),
                request);
        return app;
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
        // Build L — lazy 15-day invite expiry. If a SUBMITTED row's
        // invite is older than 15 days, flip it to EXPIRED on the
        // spot so the dashboard / wizard render the expired state
        // even if the daily cron hasn't fired yet.
        app = applyInviteExpiryIfStale(app, request);
        // CANCELLED stays a hard block; EXPIRED is now readable so
        // the consultant can see the "invitation expired" status
        // screen rather than a generic error.
        if (ConsultantApplication.Status.CANCELLED.name().equals(app.getStatus())) {
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

    /**
     * Build L — flips a SUBMITTED row past its 15-day invite window
     * to EXPIRED in place. Returns the (possibly mutated) entity.
     * No-op for any other state, and for SUBMITTED rows whose invite
     * is still inside the window.
     */
    private ConsultantApplication applyInviteExpiryIfStale(
            ConsultantApplication app, HttpServletRequest request) {
        if (!ConsultantApplication.Status.SUBMITTED.name().equals(app.getStatus())) {
            return app;
        }
        LocalDateTime sentAt = app.getInviteSentAt();
        if (sentAt == null) sentAt = app.getCreatedAt();
        if (sentAt == null) return app;
        if (sentAt.plusDays(INVITE_VALIDITY_DAYS).isAfter(LocalDateTime.now())) {
            return app;
        }
        String previousStatus = app.getStatus();
        app.setStatus(ConsultantApplication.Status.EXPIRED.name());
        applicationRepository.save(app);
        appendEvent(app.getId(),
                ConsultantApplicationEvent.EventType.EXPIRED,
                ConsultantApplicationEvent.ActorType.SYSTEM, null,
                Map.of("reason", "invite-15-day-expiry",
                        "previousStatus", previousStatus,
                        "inviteSentAt", sentAt.toString()),
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
        String ip = clientIp(request);
        app.setSignedLegalName(legalName.trim());
        app.setSignatureImage(signatureImage);
        app.setSignedAt(now);
        app.setSignedIp(ip);
        app.setSignedUserAgent(request == null ? null : request.getHeader("User-Agent"));
        // Phase D — dedicated consultant signing record, surfaced to the ERM.
        app.setSigningIp(ip);
        app.setSigningAt(now);
        app.setStatus(ConsultantApplication.Status.SIGNED.name());
        applicationRepository.save(app);

        appendEvent(app.getId(),
                ConsultantApplicationEvent.EventType.SIGNED,
                ConsultantApplicationEvent.ActorType.CONSULTANT, null,
                Map.of("legalName", legalName.trim(),
                        "ip", ip == null ? "" : ip),
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
        // Build L — lazy 15-day expiry guard. A SUBMITTED row whose
        // invite is past 15 days flips to EXPIRED and the fill is
        // rejected with the consultant-visible "expired" state below.
        app = applyInviteExpiryIfStale(app, request);
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
            String finalSignatureBase64,
            String signedLegalName,
            HttpServletRequest request) {
        ConsultantApplication app = getByApplicationId(applicationId);
        // Build L — lazy 15-day expiry guard. Stops a stale invite
        // from sliding through submit between the daily cron sweeps.
        app = applyInviteExpiryIfStale(app, request);
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

        // F-4 effective-requirements gate. CORE fields/affirmations are
        // always required; appendices are gated by require_appendixN
        // (treated as required iff flagged OR optional-but-touched);
        // SSN is gated by require_ssn AND Appendix 3 being active. The
        // first-and-last signature model demands BOTH a main-agreement
        // draw and a final review-step draw -- one without the other
        // is rejected.
        boolean missingSig = signatureBase64 == null || signatureBase64.isBlank()
                || !signatureBase64.startsWith("data:image/");
        boolean missingFinalSig = finalSignatureBase64 == null || finalSignatureBase64.isBlank()
                || !finalSignatureBase64.startsWith("data:image/");
        java.util.List<String> missingFields = collectMissingConsultantFields(app);
        java.util.List<String> missingAffs = collectMissingAffirmations(app);
        if (!missingFields.isEmpty() || !missingAffs.isEmpty() || missingSig || missingFinalSig) {
            throw new com.spire.backend.exception.IncompleteSubmissionException(
                    missingFields, missingAffs, missingSig, missingFinalSig);
        }

        String signatureUrl;
        String finalSignatureUrl;
        try {
            signatureUrl = uploadSignatureToCloudinary(
                    signatureBase64, "signatures/consultant-" + applicationId);
            finalSignatureUrl = uploadSignatureToCloudinary(
                    finalSignatureBase64, "signatures/consultant-final-" + applicationId);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Couldn't store signature: " + e.getMessage(), e);
        }

        LocalDateTime now = LocalDateTime.now();
        String ip = clientIp(request);
        app.setSignedLegalName(signedLegalName.trim());
        app.setSignatureImage(signatureUrl);
        app.setSignedAt(now);
        app.setSignedIp(ip);
        app.setSignedUserAgent(request == null ? null : request.getHeader("User-Agent"));
        // Phase D — dedicated consultant signing record, surfaced to the ERM.
        app.setSigningIp(ip);
        app.setSigningAt(now);
        // F-4 — final (review-step) signature record.
        app.setFinalSignatureImage(finalSignatureUrl);
        app.setFinalSignedAt(now);
        app.setFinalSigningIp(ip);
        // Build Q — persist the signing date here so the
        // ${signatureDate} placeholder in every consultant signature
        // block ("Date / Email: ${signatureDate} / ${primaryEmail}")
        // shows the actual moment of submission. ermApproveAndSign
        // OVERWRITES this if/when the ERM countersigns later, which is
        // the right behaviour: the final PDF's signature date should
        // reflect the most recent signing action.
        app.setSignatureDate(now);
        app.setStatus(ConsultantApplication.Status.VERIFIED.name());
        applicationRepository.save(app);

        appendEvent(app.getId(),
                ConsultantApplicationEvent.EventType.SIGNED,
                ConsultantApplicationEvent.ActorType.CONSULTANT, null,
                Map.of("legalName", signedLegalName.trim(),
                        "from", fromStatus,
                        "to", app.getStatus(),
                        "ip", ip == null ? "" : ip),
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
        assertErmCanAccess(app, request);
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
        assertErmCanAccess(app, request);
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
        // Build G — effectiveDate is set at create. Legacy rows that
        // missed the create-time set still get a same-day backfill
        // here so the closing block isn't blank.
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
                // Pass the freshly-rendered bytes directly so the email
                // never re-fetches from Cloudinary. The pre-signed
                // {@code secure_url} returned at upload time 401s when
                // GET'd later (observed in prod: completion emails
                // shipped with attachments=0).
                emailTemplateService.sendCompletedAgreementToParties(app, pdf.bytes());
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

    // ── Build T: ERM releases the consultant-version PDF ─────────────

    /**
     * Build T — ERM "Approve consultant version" action. Allowed only
     * when status = VERIFIED (consultant submitted, ERM has not
     * countersigned yet). The action:
     *
     *   - Renders the consultant-version PDF: the agreement with the
     *     consultant's signatures present and the ERM signature
     *     ABSENT, plus an appended Certificate of Completion (audit
     *     trail, IPs, timestamps, SHA-256 hash).
     *   - Uploads the bytes under {@code agreements/{appId}-consultant}
     *     and persists {@code consultantPdfPublicId}, {@code documentHash},
     *     {@code consultantCopyReleased=true}, and the release stamp.
     *   - Audits CONSULTANT_VERSION_APPROVED with the ERM id.
     *   - Does NOT change the main state machine. The row stays
     *     VERIFIED until the ERM separately calls ermApproveAndSign,
     *     which transitions to COMPLETED and produces the ERM-signed
     *     PDF (which is NOT consultant-downloadable in this build).
     */
    @Transactional
    public ConsultantApplication ermApproveConsultantVersion(
            String applicationId, HttpServletRequest request) {
        ConsultantApplication app = getByApplicationId(applicationId);
        assertErmCanAccess(app, request);
        if (!ConsultantApplication.Status.VERIFIED.name().equals(app.getStatus())) {
            throw new IllegalStateException(
                    "Only VERIFIED applications can have a consultant version "
                            + "released (status=" + app.getStatus() + ").");
        }

        ConsultantVersionService.ReleaseResult release;
        try {
            release = consultantVersionService
                    .renderConsultantVersionWithCertificate(app);
        } catch (Exception e) {
            log.error("Consultant-version render failed for {}: {}",
                    applicationId, e.getMessage(), e);
            throw new IllegalStateException(
                    "Couldn't render consultant-version PDF: " + e.getMessage(), e);
        }

        String publicId = "agreements/" + applicationId + "-consultant";
        try {
            agreementDocumentService.uploadPdfBytes(release.bytes(), publicId);
        } catch (Exception e) {
            log.error("Cloudinary upload of consultant-version failed for {}: {}",
                    applicationId, e.getMessage(), e);
            throw new IllegalStateException(
                    "Couldn't store consultant-version PDF: " + e.getMessage(), e);
        }

        LocalDateTime now = LocalDateTime.now();
        app.setConsultantPdfPublicId(publicId);
        app.setDocumentHash(release.sha256Hex());
        app.setConsultantCopyReleased(true);
        app.setConsultantCopyReleasedAt(now);
        // released_by uses the same sentinel/marker as the rest of the
        // ERM flow; resolveActorErmId returns the auth'd user id when
        // present, falling back to the global agreement-erm sentinel.
        app.setConsultantCopyReleasedBy(resolveActorErmId(request));
        applicationRepository.save(app);

        appendEvent(app.getId(),
                ConsultantApplicationEvent.EventType.CONSULTANT_VERSION_APPROVED,
                ConsultantApplicationEvent.ActorType.ERM,
                AGREEMENT_ERM_USER_ID,
                Map.of(
                        "publicId", publicId,
                        "documentHash", release.sha256Hex(),
                        "bytes", String.valueOf(release.bytes().length)),
                request);

        try {
            emailTemplateService.sendConsultantVersionReleased(app);
            appendEvent(app.getId(),
                    ConsultantApplicationEvent.EventType.EMAIL_SENT,
                    ConsultantApplicationEvent.ActorType.SYSTEM, null,
                    Map.of("template", "consultant_version_released"),
                    null);
        } catch (Exception e) {
            log.warn("Couldn't notify consultant of released copy for {}: {}",
                    applicationId, e.getMessage());
        }

        return app;
    }

    /**
     * Build T — best-effort actor id resolution for release_by.
     * Falls back to the global agreement-erm sentinel when the
     * request carries no authenticated AgreementUser.
     */
    private String resolveActorErmId(HttpServletRequest request) {
        try {
            org.springframework.security.core.Authentication auth =
                    org.springframework.security.core.context.SecurityContextHolder
                            .getContext().getAuthentication();
            if (auth != null && auth.getName() != null && !auth.getName().isBlank()) {
                return auth.getName();
            }
        } catch (Exception ignored) {
            // fall through
        }
        return null;
    }

    // ── Build T: e-sign consent capture ──────────────────────────────

    /**
     * Build T — records the consultant's e-sign consent at the consent
     * gate (after OTP login, before the wizard). Idempotent: a second
     * call with consent already present is a no-op (we don't overwrite
     * the original timestamp/IP). Audits CONSENT_GIVEN once.
     */
    @Transactional
    public ConsultantApplication recordConsent(
            String applicationId, HttpServletRequest request) {
        ConsultantApplication app = getByApplicationId(applicationId);
        // Idempotent — preserve the original record. Returning the
        // unchanged entity is fine; the UI gates off consentGivenAt.
        if (app.getConsentGivenAt() != null) {
            return app;
        }
        LocalDateTime now = LocalDateTime.now();
        String ip = clientIp(request);
        app.setConsentGivenAt(now);
        app.setConsentIp(ip);
        app.setConsentVersion(ConsultantVersionService.CONSENT_VERSION);
        applicationRepository.save(app);
        appendEvent(app.getId(),
                ConsultantApplicationEvent.EventType.CONSENT_GIVEN,
                ConsultantApplicationEvent.ActorType.CONSULTANT, null,
                Map.of(
                        "version", ConsultantVersionService.CONSENT_VERSION,
                        "ip", ip == null ? "" : ip),
                request);
        return app;
    }

    // ── Build T: consultant OTP-gated download of the released copy ──

    /**
     * Build T — issues a fresh OTP for the consultant to download
     * their released consultant-version PDF. Reuses the consultant
     * verification table + the same hash / cooldown / lockout rules
     * as the portal OTP. Gated on consultantCopyReleased so the
     * action is only callable in the released state.
     *
     * Returns the same generic message whether the OTP was actually
     * issued (cooldown, hourly cap) or not — same non-enumeration
     * stance as the portal request-otp.
     */
    @Transactional
    public String requestDownloadOtp(
            String applicationId, HttpServletRequest request) {
        ConsultantApplication app = getByApplicationId(applicationId);
        if (!Boolean.TRUE.equals(app.getConsultantCopyReleased())) {
            // Generic — doesn't leak release state to a token holder
            // poking at a not-yet-released agreement.
            return OTP_GENERIC_SENT_MSG;
        }
        String normalised = app.getConsultantEmail() == null
                ? "" : app.getConsultantEmail().trim().toLowerCase();
        if (normalised.isEmpty()) return OTP_GENERIC_SENT_MSG;

        LocalDateTime now = LocalDateTime.now();
        Optional<ConsultantVerification> latest =
                verificationRepository.findFirstByEmailOrderByCreatedAtDesc(normalised);
        if (latest.isPresent() && latest.get().getLastSentAt() != null
                && latest.get().getLastSentAt()
                        .isAfter(now.minusSeconds(OTP_RESEND_COOLDOWN_SECONDS))) {
            return OTP_GENERIC_SENT_MSG;
        }
        long lastHour = verificationRepository.countByEmailAndLastSentAtAfter(
                normalised, now.minusHours(1));
        if (lastHour >= OTP_MAX_PER_HOUR) {
            return OTP_GENERIC_SENT_MSG;
        }

        for (ConsultantVerification prior :
                verificationRepository.findByEmailAndConsumedAtIsNull(normalised)) {
            prior.setConsumedAt(now);
            verificationRepository.save(prior);
        }

        String code = generateOtp();
        String ip = clientIp(request);
        verificationRepository.save(ConsultantVerification.builder()
                .email(normalised)
                .otpHash(passwordEncoder.encode(code))
                .expiresAt(now.plusMinutes(OTP_TTL_MINUTES))
                .attempts(0)
                .lastSentAt(now)
                .resendCount((int) lastHour + 1)
                .requestIp(ip)
                .build());

        appendEvent(app.getId(),
                ConsultantApplicationEvent.EventType.DOWNLOAD_OTP_SENT,
                ConsultantApplicationEvent.ActorType.CONSULTANT, null,
                Map.of("ip", ip == null ? "" : ip,
                        "email", normalised),
                request);
        try {
            emailTemplateService.sendConsultantDownloadOtp(app, code);
        } catch (Exception e) {
            log.warn("Failed to send consultant download OTP to {}: {}",
                    normalised, e.getMessage());
        }
        return OTP_GENERIC_SENT_MSG;
    }

    /**
     * Build T — verifies the fresh download OTP, marks it consumed,
     * returns the released consultant-version PDF bytes. Throws
     * IllegalStateException when the row hasn't been released, and
     * IllegalArgumentException on any OTP failure (consumed by the
     * controller as 400).
     */
    @Transactional
    public byte[] downloadConsultantCopy(
            String applicationId, String otp, HttpServletRequest request) {
        ConsultantApplication app = getByApplicationId(applicationId);
        if (!Boolean.TRUE.equals(app.getConsultantCopyReleased())
                || app.getConsultantPdfPublicId() == null
                || app.getConsultantPdfPublicId().isBlank()) {
            throw new IllegalStateException(
                    "Your copy is not available yet.");
        }
        String normalised = app.getConsultantEmail() == null
                ? "" : app.getConsultantEmail().trim().toLowerCase();
        ConsultantVerification cv = verificationRepository
                .findFirstByEmailAndConsumedAtIsNullOrderByCreatedAtDesc(normalised)
                .orElse(null);
        if (cv == null
                || cv.getExpiresAt().isBefore(LocalDateTime.now())
                || cv.getAttempts() >= OTP_MAX_ATTEMPTS) {
            throw new IllegalArgumentException("Invalid or expired code.");
        }
        if (otp == null || !passwordEncoder.matches(otp.trim(), cv.getOtpHash())) {
            cv.setAttempts(cv.getAttempts() + 1);
            if (cv.getAttempts() >= OTP_MAX_ATTEMPTS) {
                cv.setConsumedAt(LocalDateTime.now());
            }
            verificationRepository.save(cv);
            appendEvent(app.getId(),
                    ConsultantApplicationEvent.EventType.OTP_FAILED,
                    ConsultantApplicationEvent.ActorType.CONSULTANT, null,
                    Map.of("attempts", String.valueOf(cv.getAttempts()),
                            "purpose", "download"),
                    request);
            throw new IllegalArgumentException("Invalid or expired code.");
        }
        cv.setConsumedAt(LocalDateTime.now());
        verificationRepository.save(cv);

        // Fetch the bytes through a short-lived signed URL.
        String url = agreementDocumentService.signedPdfUrl(
                app.getConsultantPdfPublicId(),
                java.time.Duration.ofMinutes(5));
        byte[] bytes;
        try {
            java.net.URLConnection conn = new java.net.URL(url).openConnection();
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(30_000);
            try (java.io.InputStream in = conn.getInputStream()) {
                bytes = in.readAllBytes();
            }
        } catch (Exception e) {
            log.error("Failed to fetch released consultant copy for {}: {}",
                    applicationId, e.getMessage());
            throw new IllegalStateException(
                    "Couldn't fetch your copy. Please try again.", e);
        }

        String ip = clientIp(request);
        appendEvent(app.getId(),
                ConsultantApplicationEvent.EventType.CONSULTANT_DOWNLOAD,
                ConsultantApplicationEvent.ActorType.CONSULTANT, null,
                Map.of("ip", ip == null ? "" : ip,
                        "bytes", String.valueOf(bytes.length),
                        "documentHash", app.getDocumentHash() == null
                                ? "" : app.getDocumentHash()),
                request);
        return bytes;
    }

    // ── Build M: advance Phase-1 COMPLETED to Phase 2 ────────────────

    /**
     * Build M — promotion map carried in the advance-to-phase-2 body.
     * Each non-null Boolean targets one of the per-agreement
     * requirement flags; the service treats {@code true} as "promote
     * this section to required for Phase 2". {@code false}/null leave
     * the existing value untouched. The default empty body promotes
     * EVERY currently-optional section the consultant skipped in
     * Phase 1.
     */
    public static class Phase2Promotion {
        public Boolean appendix1;
        public Boolean appendix2;
        public Boolean appendix3;
        public Boolean appendix4;
        public Boolean appendix5;
        public Boolean ssn;
    }

    /**
     * Build M — ERM reopens a Phase-1 COMPLETED agreement on the SAME
     * document. The action:
     *
     *   - flips selected previously-optional require_* flags to TRUE
     *     (default: every section the consultant skipped in Phase 1);
     *   - PRESERVES every filled field -- nothing is wiped;
     *   - clears BOTH consultant signatures + their audit timestamps,
     *     plus every section affirmation, because the consultant must
     *     re-sign / re-affirm the now-final document at Phase 2;
     *   - clears the Phase-1 ERM countersignature artifacts (name /
     *     title / signature url / signatureDate) and the final PDF
     *     pointers; the Phase-2 countersign regenerates everything;
     *   - resets {@code inviteSentAt} so the 15-day expiry window
     *     restarts;
     *   - transitions COMPLETED -> SUBMITTED so the consultant flow
     *     reopens the wizard;
     *   - sets {@code phase} to 2 and audits
     *     {@code ADVANCED_TO_PHASE_2} with the promoted sections;
     *   - sends the consultant a no-PDF "Phase 2 -- please complete
     *     the remaining sections" email.
     */
    @Transactional
    public ConsultantApplication advanceToPhase2(
            String applicationId,
            Phase2Promotion promotion,
            HttpServletRequest request) {
        ConsultantApplication app = getByApplicationId(applicationId);
        assertErmCanAccess(app, request);
        if (!ConsultantApplication.Status.COMPLETED.name().equals(app.getStatus())) {
            throw new IllegalStateException(
                    "Only a COMPLETED agreement can be advanced to Phase 2 "
                            + "(status=" + app.getStatus() + ").");
        }
        Integer currentPhase = app.getPhase();
        if (currentPhase != null && currentPhase >= 2) {
            throw new IllegalStateException(
                    "This agreement is already at Phase " + currentPhase + ".");
        }

        // Resolve the promotion map. A null / empty body means "promote
        // every currently-optional section the consultant skipped" --
        // the typical operator path. Non-null entries in the body are
        // honoured verbatim (true => promote, false => leave alone).
        boolean p1 = promotion != null && Boolean.TRUE.equals(promotion.appendix1);
        boolean p2 = promotion != null && Boolean.TRUE.equals(promotion.appendix2);
        boolean p3 = promotion != null && Boolean.TRUE.equals(promotion.appendix3);
        boolean p4 = promotion != null && Boolean.TRUE.equals(promotion.appendix4);
        boolean p5 = promotion != null && Boolean.TRUE.equals(promotion.appendix5);
        boolean pSsn = promotion != null && Boolean.TRUE.equals(promotion.ssn);
        boolean explicitSelection = promotion != null && (
                promotion.appendix1 != null || promotion.appendix2 != null
                || promotion.appendix3 != null || promotion.appendix4 != null
                || promotion.appendix5 != null || promotion.ssn != null);
        if (!explicitSelection) {
            // Default selection: every appendix currently flagged
            // optional gets promoted. ssn follows the same rule but
            // only if Appendix 3 ends up required (it's meaningless
            // outside that section).
            p1 = !Boolean.TRUE.equals(app.getRequireAppendix1());
            p2 = !Boolean.TRUE.equals(app.getRequireAppendix2());
            p3 = !Boolean.TRUE.equals(app.getRequireAppendix3());
            p4 = !Boolean.TRUE.equals(app.getRequireAppendix4());
            p5 = !Boolean.TRUE.equals(app.getRequireAppendix5());
            // Leave SSN unchanged in the default path -- the ERM
            // toggles it explicitly when they want it.
        }

        java.util.List<String> promoted = new java.util.ArrayList<>();
        if (p1) { app.setRequireAppendix1(true); promoted.add("appendix1"); }
        if (p2) { app.setRequireAppendix2(true); promoted.add("appendix2"); }
        if (p3) { app.setRequireAppendix3(true); promoted.add("appendix3"); }
        if (p4) { app.setRequireAppendix4(true); promoted.add("appendix4"); }
        if (p5) { app.setRequireAppendix5(true); promoted.add("appendix5"); }
        if (pSsn) { app.setRequireSsn(true); promoted.add("ssn"); }

        // Clear consultant signatures + every audit timestamp + every
        // section affirmation so the consultant has to re-sign and
        // re-affirm the now-final document. Field data stays put.
        app.setSignatureImage(null);
        app.setSignedAt(null);
        app.setSignedIp(null);
        app.setSignedUserAgent(null);
        app.setSigningAt(null);
        app.setSigningIp(null);
        app.setFinalSignatureImage(null);
        app.setFinalSignedAt(null);
        app.setFinalSigningIp(null);
        app.setSignedLegalName(null);
        app.setAffirmedMainAgreement(false);
        app.setAffirmedExhibitA(false);
        app.setAffirmedExhibitB(false);
        app.setAffirmedAppendix1(false);
        app.setAffirmedAppendix2(false);
        app.setAffirmedAppendix3(false);
        app.setAffirmedAppendix4(false);
        app.setAffirmedAppendix5(false);

        // Clear the Phase-1 ERM countersignature record + the final
        // PDF pointers. ermApproveAndSign regenerates both at Phase 2
        // countersign.
        app.setErmName(null);
        app.setErmTitle(null);
        app.setErmSignatureUrl(null);
        app.setSignatureDate(null);
        app.setFinalPdfUrl(null);
        app.setFinalPdfPublicId(null);

        LocalDateTime now = LocalDateTime.now();
        app.setPhase(2);
        app.setStatus(ConsultantApplication.Status.SUBMITTED.name());
        app.setInviteSentAt(now);
        applicationRepository.save(app);

        appendEvent(app.getId(),
                ConsultantApplicationEvent.EventType.ADVANCED_TO_PHASE_2,
                ConsultantApplicationEvent.ActorType.ERM,
                AGREEMENT_ERM_USER_ID,
                Map.of("ermUserId", AgreementAuthz.userId(request) == null
                                ? "" : AgreementAuthz.userId(request),
                        "promoted", promoted),
                request);

        try {
            emailTemplateService.sendConsultantPhase2Notification(app);
            appendEvent(app.getId(),
                    ConsultantApplicationEvent.EventType.EMAIL_SENT,
                    ConsultantApplicationEvent.ActorType.SYSTEM, null,
                    Map.of("template", "consultant_phase2_notification"),
                    null);
        } catch (Exception e) {
            log.warn("Phase 2 notification email failed for {}: {}",
                    applicationId, e.getMessage());
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
        // Load + ownership check first: a non-owner ERM must get 404
        // before any input validation reveals anything.
        ConsultantApplication app = getByApplicationId(applicationId);
        assertErmCanAccess(app, request);
        if (recipientEmail == null || recipientEmail.isBlank()
                || !recipientEmail.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new IllegalArgumentException(
                    "A valid recipient email is required.");
        }
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

        // Build G: Appendix 3 ID type toggle. Values: "DL" | "STATE_ID".
        public String idType;

        // F-1 (guided signing foundation): per-section affirmations.
        // Boolean (object) so null = "not sent in this partial save"
        // and false = "explicitly unchecked" -- only non-null values
        // are written through applyTo, preserving partial-save semantics.
        public Boolean affirmedMainAgreement;
        public Boolean affirmedExhibitA;
        public Boolean affirmedExhibitB;
        public Boolean affirmedAppendix1;
        public Boolean affirmedAppendix2;
        public Boolean affirmedAppendix3;
        public Boolean affirmedAppendix4;
        public Boolean affirmedAppendix5;

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
            if (idType != null)                   { app.setIdType(idType); changed = true; }
            // F-1 affirmation flags. Null skips, anything else writes through.
            if (affirmedMainAgreement != null)    { app.setAffirmedMainAgreement(affirmedMainAgreement); changed = true; }
            if (affirmedExhibitA != null)         { app.setAffirmedExhibitA(affirmedExhibitA); changed = true; }
            if (affirmedExhibitB != null)         { app.setAffirmedExhibitB(affirmedExhibitB); changed = true; }
            if (affirmedAppendix1 != null)        { app.setAffirmedAppendix1(affirmedAppendix1); changed = true; }
            if (affirmedAppendix2 != null)        { app.setAffirmedAppendix2(affirmedAppendix2); changed = true; }
            if (affirmedAppendix3 != null)        { app.setAffirmedAppendix3(affirmedAppendix3); changed = true; }
            if (affirmedAppendix4 != null)        { app.setAffirmedAppendix4(affirmedAppendix4); changed = true; }
            if (affirmedAppendix5 != null)        { app.setAffirmedAppendix5(affirmedAppendix5); changed = true; }
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
            if (idType != null) names.add("idType");
            if (affirmedMainAgreement != null) names.add("affirmedMainAgreement");
            if (affirmedExhibitA != null) names.add("affirmedExhibitA");
            if (affirmedExhibitB != null) names.add("affirmedExhibitB");
            if (affirmedAppendix1 != null) names.add("affirmedAppendix1");
            if (affirmedAppendix2 != null) names.add("affirmedAppendix2");
            if (affirmedAppendix3 != null) names.add("affirmedAppendix3");
            if (affirmedAppendix4 != null) names.add("affirmedAppendix4");
            if (affirmedAppendix5 != null) names.add("affirmedAppendix5");
            return names;
        }
    }

    // ── Build G: Appendix 5 security cheque upload ───────────────────

    /** Hard upper bound; matches Spring's {@code spring.servlet.multipart.max-file-size}. */
    private static final long MAX_CHEQUE_BYTES = 10L * 1024L * 1024L;

    /**
     * Persists the consultant's Appendix 5 security cheque. Uploads
     * the bytes to Cloudinary at {@code cheques/<appId>}
     * (type=authenticated; resource_type=auto so PDFs and JPEGs both
     * land cleanly) and records the public_id + content type on the
     * row. Subsequent uploads overwrite. Audit CHEQUE_UPLOADED.
     */
    @Transactional
    public ConsultantApplication uploadCheque(
            String applicationId,
            byte[] bytes,
            String contentType,
            HttpServletRequest request) {
        ConsultantApplication app = getByApplicationId(applicationId);
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Cheque file is empty.");
        }
        if (bytes.length > MAX_CHEQUE_BYTES) {
            throw new IllegalArgumentException(
                    "Cheque file is too large (>10 MB).");
        }
        String normalisedType = contentType == null ? "" : contentType.toLowerCase();
        boolean isImage = normalisedType.startsWith("image/");
        boolean isPdf = normalisedType.equals("application/pdf");
        if (!isImage && !isPdf) {
            throw new IllegalArgumentException(
                    "Cheque must be an image (JPG/PNG/HEIC) or PDF.");
        }
        String publicId = "cheques/" + applicationId;
        try {
            cloudinary.uploader().upload(bytes,
                    com.cloudinary.utils.ObjectUtils.asMap(
                            "public_id", publicId,
                            // 'auto' picks image vs raw based on content;
                            // both flavours can be re-fetched via signedUrl
                            // by passing the right resourceType.
                            "resource_type", isPdf ? "raw" : "image",
                            "type", "authenticated",
                            "overwrite", true));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(
                    "Couldn't store cheque: " + e.getMessage(), e);
        }
        app.setChequePublicId(publicId);
        app.setChequeContentType(normalisedType);
        app.setChequeUploadedAt(LocalDateTime.now());
        applicationRepository.save(app);

        appendEvent(app.getId(),
                ConsultantApplicationEvent.EventType.CHEQUE_UPLOADED,
                ConsultantApplicationEvent.ActorType.CONSULTANT, null,
                Map.of("publicId", publicId,
                        "contentType", normalisedType,
                        "bytes", bytes.length),
                request);
        return app;
    }

    /**
     * Streams the bytes of a previously-uploaded cheque. Re-signs the
     * delivery URL on every call (the per-upload stored URL 401s
     * later -- same pattern as the final PDF). Returns null when no
     * cheque has been uploaded yet.
     */
    public ChequeBytes fetchChequeBytes(String applicationId) throws java.io.IOException {
        ConsultantApplication app = getByApplicationId(applicationId);
        String publicId = app.getChequePublicId();
        if (publicId == null || publicId.isBlank()) return null;
        boolean isPdf = "application/pdf".equalsIgnoreCase(app.getChequeContentType());
        String url = cloudinary.url()
                .resourceType(isPdf ? "raw" : "image")
                .type("authenticated")
                .signed(true)
                .secure(true)
                .generate(publicId);
        java.net.URLConnection conn = new java.net.URL(url).openConnection();
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(30_000);
        try (java.io.InputStream in = conn.getInputStream()) {
            byte[] bytes = in.readAllBytes();
            return new ChequeBytes(bytes, app.getChequeContentType());
        }
    }

    /** Carries the cheque bytes + their original content-type for the controller's stream. */
    public record ChequeBytes(byte[] bytes, String contentType) {}

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
        // Build L — sweep only SUBMITTED rows whose invite is past 15
        // days. REVISION_REQUESTED, VERIFIED, UPDATED, COMPLETED are
        // exempt: the consultant has already engaged or the ERM is
        // mid-review, so they shouldn't time out under the consultant
        // -invite rule.
        LocalDateTime cutoff = now.minusDays(INVITE_VALIDITY_DAYS);
        List<String> inFlight = List.of(
                ConsultantApplication.Status.SUBMITTED.name());
        List<ConsultantApplication> stale =
                applicationRepository.findByStatusInAndInviteSentAtBefore(inFlight, cutoff);
        if (stale.isEmpty()) return 0;

        for (ConsultantApplication app : stale) {
            String previousStatus = app.getStatus();
            app.setStatus(ConsultantApplication.Status.EXPIRED.name());
            applicationRepository.save(app);
            appendEvent(app.getId(),
                    ConsultantApplicationEvent.EventType.EXPIRED,
                    ConsultantApplicationEvent.ActorType.SYSTEM, null,
                    Map.of("expiredAt", now.toString(),
                            "previousStatus", previousStatus,
                            "reason", "invite-15-day-expiry",
                            "inviteSentAt", String.valueOf(app.getInviteSentAt())),
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

    // ── F-4 effective-requirements gate ──────────────────────────────
    //
    // Replaces the F-1 blanket "everything required" model. The ERM
    // picks which appendices THIS consultant must complete at create
    // time (require_appendix1..5 flags) and whether SSN is mandatory
    // inside Appendix 3 (require_ssn). The validator now distinguishes:
    //
    //   CORE (always required, every agreement):
    //     - cover: consultantName, consultantEmail, primaryPhone,
    //       residenceAddress  (workAuthorizationCategory is ERM-set,
    //       not consultant-edited, so omitted here)
    //     - Exhibit A: technologyTrack, customScopeNotes
    //     - Affirmations: affirmedMainAgreement, affirmedExhibitA,
    //       affirmedExhibitB
    //     - implementationPartner is NEVER required (per spec)
    //
    //   APPENDIX 1..5 (per require_appendixN flag):
    //     - Required: every appendix field must be non-blank AND the
    //       affirmation must be ticked.
    //     - Optional and untouched (no field non-blank, affirmation
    //       not ticked): skip; no validation.
    //     - Optional and touched (any field non-blank OR affirmation
    //       ticked): treated as required (all-or-nothing).
    //
    //   SSN (bgFullSsn) inside Appendix 3:
    //     - Required only when require_ssn AND Appendix 3 is being
    //       completed (either required, or optional-touched).
    //
    // Keys returned are entity field / affirmation names; the wizard
    // UI keys its sections off the same names via
    // src/lib/agreement-sections.ts, so a missing key routes directly
    // to the right step.

    /** True if {@code s} has any non-whitespace character. */
    private static boolean nonBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    /** Fields whose presence makes Appendix 1 "touched" (excludes implementationPartner, never required). */
    private static boolean isAppendix1Touched(ConsultantApplication app) {
        return nonBlank(app.getEmployerPayrollEntity())
                || nonBlank(app.getEndClient())
                || nonBlank(app.getRoleTitle())
                || app.getVerifiedStartDate() != null
                || nonBlank(app.getPayrollCycle())
                || Boolean.TRUE.equals(app.getAffirmedAppendix1());
    }

    private static boolean isAppendix2Touched(ConsultantApplication app) {
        return nonBlank(app.getAchAccountType())
                || nonBlank(app.getAchBankName())
                || nonBlank(app.getAchAccountHolderName())
                || nonBlank(app.getAchRoutingNumber())
                || nonBlank(app.getAchAccountNumber())
                || nonBlank(app.getAchNoticeEmail())
                || nonBlank(app.getAchDebitDates())
                || nonBlank(app.getAchDebitAmounts())
                || Boolean.TRUE.equals(app.getAffirmedAppendix2());
    }

    private static boolean isAppendix3Touched(ConsultantApplication app) {
        // bgFullSsn is gated separately (require_ssn) — including it
        // here would mean a stray SSN entry forces all of Appendix 3.
        // That's the right behaviour: if the consultant typed an SSN,
        // they engaged with the section and should finish it.
        return nonBlank(app.getBgFullLegalName())
                || nonBlank(app.getBgOtherNamesUsed())
                || nonBlank(app.getBgCurrentAddress())
                || app.getBgDateOfBirth() != null
                || nonBlank(app.getBgFullSsn())
                || nonBlank(app.getBgDriverLicense())
                || Boolean.TRUE.equals(app.getAffirmedAppendix3());
    }

    private static boolean isAppendix4Touched(ConsultantApplication app) {
        return nonBlank(app.getPortalPlatform())
                || nonBlank(app.getPortalUsername())
                || nonBlank(app.getPortalAuthorizedActions())
                || app.getPortalEffectiveDate() != null
                || nonBlank(app.getPortalRevocationContact())
                || Boolean.TRUE.equals(app.getAffirmedAppendix4());
    }

    private static boolean isAppendix5Touched(ConsultantApplication app) {
        return nonBlank(app.getSecurityCheckCount())
                || nonBlank(app.getSecurityCheckNumbers())
                || nonBlank(app.getSecurityCheckBank())
                || nonBlank(app.getSecurityCheckHolderName())
                || nonBlank(app.getSecurityCheckAmount())
                || nonBlank(app.getSecurityCheckDates())
                || Boolean.TRUE.equals(app.getAffirmedAppendix5());
    }

    /** Returns the keys of every effectively-required consultant field that's blank. */
    private static java.util.List<String> collectMissingConsultantFields(
            ConsultantApplication app) {
        java.util.List<String> missing = new java.util.ArrayList<>();
        // CORE (always required).
        addIfBlank(missing, "consultantName", app.getConsultantName());
        addIfBlank(missing, "consultantEmail", app.getConsultantEmail());
        addIfBlank(missing, "primaryPhone", app.getPrimaryPhone());
        addIfBlank(missing, "residenceAddress", app.getResidenceAddress());
        addIfBlank(missing, "technologyTrack", app.getTechnologyTrack());
        addIfBlank(missing, "customScopeNotes", app.getCustomScopeNotes());

        // Appendix 1 -- employment (per require_appendix1; all-or-nothing
        // if optional but touched). implementationPartner is never required.
        boolean app1Required = Boolean.TRUE.equals(app.getRequireAppendix1());
        if (app1Required || isAppendix1Touched(app)) {
            addIfBlank(missing, "employerPayrollEntity", app.getEmployerPayrollEntity());
            addIfBlank(missing, "endClient", app.getEndClient());
            addIfBlank(missing, "roleTitle", app.getRoleTitle());
            if (app.getVerifiedStartDate() == null) missing.add("verifiedStartDate");
            addIfBlank(missing, "payrollCycle", app.getPayrollCycle());
        }

        // Appendix 2 -- ACH.
        boolean app2Required = Boolean.TRUE.equals(app.getRequireAppendix2());
        if (app2Required || isAppendix2Touched(app)) {
            addIfBlank(missing, "achAccountType", app.getAchAccountType());
            addIfBlank(missing, "achBankName", app.getAchBankName());
            addIfBlank(missing, "achAccountHolderName", app.getAchAccountHolderName());
            addIfBlank(missing, "achRoutingNumber", app.getAchRoutingNumber());
            addIfBlank(missing, "achAccountNumber", app.getAchAccountNumber());
            addIfBlank(missing, "achNoticeEmail", app.getAchNoticeEmail());
            addIfBlank(missing, "achDebitDates", app.getAchDebitDates());
            addIfBlank(missing, "achDebitAmounts", app.getAchDebitAmounts());
        }

        // Appendix 3 -- background check (SSN gated by require_ssn,
        // idType + ID number always required when the section is active).
        boolean app3Required = Boolean.TRUE.equals(app.getRequireAppendix3());
        boolean app3Active = app3Required || isAppendix3Touched(app);
        if (app3Active) {
            addIfBlank(missing, "bgFullLegalName", app.getBgFullLegalName());
            addIfBlank(missing, "bgOtherNamesUsed", app.getBgOtherNamesUsed());
            addIfBlank(missing, "bgCurrentAddress", app.getBgCurrentAddress());
            if (app.getBgDateOfBirth() == null) missing.add("bgDateOfBirth");
            String t = app.getIdType();
            if (t == null || (!"DL".equals(t) && !"STATE_ID".equals(t))) {
                missing.add("idType");
            }
            addIfBlank(missing, "bgDriverLicense", app.getBgDriverLicense());
            if (Boolean.TRUE.equals(app.getRequireSsn())) {
                addIfBlank(missing, "bgFullSsn", app.getBgFullSsn());
            }
        }

        // Appendix 4 -- portal access.
        boolean app4Required = Boolean.TRUE.equals(app.getRequireAppendix4());
        if (app4Required || isAppendix4Touched(app)) {
            addIfBlank(missing, "portalPlatform", app.getPortalPlatform());
            addIfBlank(missing, "portalUsername", app.getPortalUsername());
            addIfBlank(missing, "portalAuthorizedActions", app.getPortalAuthorizedActions());
            if (app.getPortalEffectiveDate() == null) missing.add("portalEffectiveDate");
            addIfBlank(missing, "portalRevocationContact", app.getPortalRevocationContact());
        }

        // Appendix 5 -- security check (cheque upload required when active).
        boolean app5Required = Boolean.TRUE.equals(app.getRequireAppendix5());
        if (app5Required || isAppendix5Touched(app)) {
            addIfBlank(missing, "securityCheckCount", app.getSecurityCheckCount());
            addIfBlank(missing, "securityCheckNumbers", app.getSecurityCheckNumbers());
            addIfBlank(missing, "securityCheckBank", app.getSecurityCheckBank());
            addIfBlank(missing, "securityCheckHolderName", app.getSecurityCheckHolderName());
            addIfBlank(missing, "securityCheckAmount", app.getSecurityCheckAmount());
            addIfBlank(missing, "securityCheckDates", app.getSecurityCheckDates());
            // Cheque file is required when Appendix 5 applies. The
            // wizard uploads it via POST /cheque before submit; the
            // entity holds the public_id and content type.
            if (app.getChequePublicId() == null || app.getChequePublicId().isBlank()) {
                missing.add("chequeUpload");
            }
        }

        // Build G strict format checks. Only enforced WHEN the field is
        // effectively required (i.e. already in `missing`-checking scope);
        // an unfilled optional field doesn't need a format check.
        if (app2Required || isAppendix2Touched(app)) {
            if (nonBlank(app.getAchRoutingNumber())
                    && !app.getAchRoutingNumber().replaceAll("\\D", "").matches("\\d{9}")) {
                missing.add("achRoutingNumber");
            }
            if (nonBlank(app.getAchAccountNumber())
                    && !app.getAchAccountNumber().replaceAll("\\D", "").matches("\\d{10}")) {
                missing.add("achAccountNumber");
            }
        }
        if (app3Active && Boolean.TRUE.equals(app.getRequireSsn())) {
            if (nonBlank(app.getBgFullSsn())
                    && !app.getBgFullSsn().matches("\\d{3}-\\d{2}-\\d{4}")) {
                missing.add("bgFullSsn");
            }
        }

        return missing;
    }

    /** Returns the keys of every effectively-required affirmation flag still false / null. */
    private static java.util.List<String> collectMissingAffirmations(
            ConsultantApplication app) {
        java.util.List<String> missing = new java.util.ArrayList<>();
        // Always-required affirmations (main agreement + exhibits).
        if (!Boolean.TRUE.equals(app.getAffirmedMainAgreement())) missing.add("affirmedMainAgreement");
        if (!Boolean.TRUE.equals(app.getAffirmedExhibitA())) missing.add("affirmedExhibitA");
        if (!Boolean.TRUE.equals(app.getAffirmedExhibitB())) missing.add("affirmedExhibitB");
        // Per-appendix: required directly, OR optional-but-touched (all-or-nothing).
        if ((Boolean.TRUE.equals(app.getRequireAppendix1()) || isAppendix1Touched(app))
                && !Boolean.TRUE.equals(app.getAffirmedAppendix1())) {
            missing.add("affirmedAppendix1");
        }
        if ((Boolean.TRUE.equals(app.getRequireAppendix2()) || isAppendix2Touched(app))
                && !Boolean.TRUE.equals(app.getAffirmedAppendix2())) {
            missing.add("affirmedAppendix2");
        }
        if ((Boolean.TRUE.equals(app.getRequireAppendix3()) || isAppendix3Touched(app))
                && !Boolean.TRUE.equals(app.getAffirmedAppendix3())) {
            missing.add("affirmedAppendix3");
        }
        if ((Boolean.TRUE.equals(app.getRequireAppendix4()) || isAppendix4Touched(app))
                && !Boolean.TRUE.equals(app.getAffirmedAppendix4())) {
            missing.add("affirmedAppendix4");
        }
        if ((Boolean.TRUE.equals(app.getRequireAppendix5()) || isAppendix5Touched(app))
                && !Boolean.TRUE.equals(app.getAffirmedAppendix5())) {
            missing.add("affirmedAppendix5");
        }
        return missing;
    }

    private static void addIfBlank(java.util.List<String> out, String key, String value) {
        if (value == null || value.trim().isEmpty()) out.add(key);
    }
}
