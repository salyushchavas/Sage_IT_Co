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
import java.util.ArrayList;
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
     * Build Q — the consultant ACCESS LINK is valid for 7 days from the
     * last invite send ({@code inviteSentAt}; falls back to createdAt).
     * Past that the link is treated as expired: the consultant is BLOCKED
     * from accessing the agreement (shown a "link expired — ask Sage IT to
     * resend" message), but the AGREEMENT itself is NEVER mutated — it
     * keeps its lifecycle status and stays visible in every dashboard. The
     * ERM resends to issue a fresh 7-day window. Replaces the Build L rule
     * that flipped SUBMITTED rows to the terminal EXPIRED status after 15
     * days; link expiry is now a DERIVED, non-mutating indicator.
     */
    private static final int CONSULTANT_LINK_TTL_DAYS = 7;

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
    private final com.spire.backend.repository.AgreementApprovalRepository approvalRepository;
    private final com.spire.backend.repository.ConsultantAgreementVersionRepository agreementVersionRepository;
    private final AgreementAssignmentService assignmentService;
    private final ConsultantVerificationRepository verificationRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final com.cloudinary.Cloudinary cloudinary;
    /** Phase 5 (S3) — agreements consultant-upload + signature storage (S3StorageService). */
    private final DocumentStorage documentStorage;

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
            String firstName,
            String middleName,
            String lastName,
            String consultantPhone,
            String ratePeriod1,
            String rateAmount1,
            String ratePeriod2,
            String rateAmount2,
            String phase2DeliverablePeriod,
            String visaStatus,
            String visaStatusOther,
            Boolean requireAppendix1,
            Boolean requireAppendix2,
            Boolean requireAppendix3,
            Boolean requireAppendix4,
            Boolean requireAppendix5,
            Boolean requireSsn,
            String achDebitDates,
            String achDebitAmounts,
            String technologyTrack,
            String customScopeNotes,
            String portalAuthorizedActions,
            String portalRevocationContact,
            String emailPretext,
            JsonNode payload,
            String ownerErmId,
            HttpServletRequest request
    ) {
        validateRequired("consultantEmail", consultantEmail);

        String applicationId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        String payloadJson = stringify(payload);

        // Build Y — ERM-filled ACH debit schedule: single free-text date(s)
        // + amount(s) (e.g. "15th of every month" / "$416.67"). Stored on
        // the existing ${achDebitDates}/${achDebitAmounts} columns; read-
        // only to the consultant.
        String achDatesValue = blankToNull(achDebitDates);
        String achAmountsValue = blankToNull(achDebitAmounts);

        // Build W — structured name. Compose consultant_name from
        // First + Middle? + Last; fall back to the single legacy field.
        String fn = blankToNull(firstName);
        String mn = blankToNull(middleName);
        String ln = blankToNull(lastName);
        String composed = composeName(fn, mn, ln);
        String effectiveName = composed != null
                ? composed
                : blankToNull(consultantName);

        // Build W — work-authorization custom value only for "Others".
        String cat = blankToNull(visaStatus);
        String catOther = "Others".equalsIgnoreCase(cat == null ? "" : cat)
                ? blankToNull(visaStatusOther)
                : null;

        ConsultantApplication app = ConsultantApplication.builder()
                .applicationId(applicationId)
                .ermUserId(AGREEMENT_ERM_USER_ID)
                // Stamp the authenticated agreement user as the owner.
                // Not yet used to filter reads (next phase).
                .ownerErmId(ownerErmId)
                .consultantEmail(consultantEmail.trim().toLowerCase())
                .consultantName(effectiveName)
                .firstName(fn)
                .middleName(mn)
                .lastName(ln)
                .consultantPhone(consultantPhone)
                .ratePeriod1(ratePeriod1)
                .rateAmount1(rateAmount1)
                .ratePeriod2(ratePeriod2)
                .rateAmount2(rateAmount2)
                .phase2DeliverablePeriod(blankToNull(phase2DeliverablePeriod))
                // F-4: ERM-set visa on workAuthCategory. Build W adds the
                // custom "Others" free-text on work_authorization_other.
                .workAuthorizationCategory(cat)
                .workAuthorizationOther(catOther)
                .requireAppendix1(Boolean.TRUE.equals(requireAppendix1))
                .requireAppendix2(Boolean.TRUE.equals(requireAppendix2))
                .requireAppendix3(Boolean.TRUE.equals(requireAppendix3))
                .requireAppendix4(Boolean.TRUE.equals(requireAppendix4))
                .requireAppendix5(Boolean.TRUE.equals(requireAppendix5))
                .requireSsn(Boolean.TRUE.equals(requireSsn))
                // Build Y — ERM-filled single ACH debit date(s)/amount(s).
                .achDebitDates(achDatesValue)
                .achDebitAmounts(achAmountsValue)
                // Build I — ERM-set Service Track (Exhibit A); read-only to
                // the consultant; rendered into the agreement.
                .technologyTrack(blankToNull(technologyTrack))
                .customScopeNotes(blankToNull(customScopeNotes))
                // Build Z — ERM-set Appendix 4 Authorized Actions + Revocation
                // Contact; read-only to the consultant; stamped into Appendix 4
                // (${portalAuthorizedActions} / ${portalRevocationContact}).
                .portalAuthorizedActions(blankToNull(portalAuthorizedActions))
                .portalRevocationContact(blankToNull(portalRevocationContact))
                // Build O — optional ERM-authored invitation email pre-text
                // (blank → the default copy at send time).
                .emailPretext(blankToNull(emailPretext))
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
     * Build V — appId-bound portal request-otp. The consultant arrives
     * via the per-agreement invitation link; we look up the row,
     * derive the ERM-set consultantEmail server-side, and send the OTP
     * THERE. The consultant never types an email anywhere — the only
     * route for a code to reach a non-ERM-set address is for the
     * ERM to have set the wrong one in the first place.
     *
     * Non-enumerating: the response copy is the same whether the row
     * exists, is soft-deleted, or has no consultant email on file.
     * Cooldown + hourly cap apply per email (so spamming this with
     * different appIds for the same consultant still throttles).
     */
    @Transactional
    public String requestPortalOtpForApp(String applicationId, HttpServletRequest request) {
        ConsultantApplication app = applicationRepository
                .findByApplicationId(applicationId)
                .orElse(null);
        if (app == null
                || Boolean.TRUE.equals(app.getDeleted())
                || ConsultantApplication.Status.CANCELLED.name().equals(app.getStatus())) {
            return OTP_GENERIC_SENT_MSG;
        }
        String email = app.getConsultantEmail();
        if (email == null || email.isBlank()) return OTP_GENERIC_SENT_MSG;
        String normalised = email.trim().toLowerCase();

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
                ConsultantApplicationEvent.EventType.OTP_SENT,
                ConsultantApplicationEvent.ActorType.CONSULTANT, null,
                Map.of("ip", ip == null ? "" : ip,
                        "email", normalised,
                        "appId", applicationId),
                request);
        try {
            emailTemplateService.sendConsultantOtp(app, code);
        } catch (Exception e) {
            log.warn("Failed to send consultant portal OTP for {}: {}",
                    applicationId, e.getMessage());
        }
        return OTP_GENERIC_SENT_MSG;
    }

    /**
     * Build V — appId-bound verify-otp. Resolves the email from the
     * row, validates the supplied code, returns an email-scoped
     * session token (so the consultant dashboard still lists every
     * agreement addressed to them). The consultant never supplies an
     * email here.
     */
    @Transactional
    public String verifyPortalOtpForApp(
            String applicationId, String otp, HttpServletRequest request) {
        ConsultantApplication app = applicationRepository
                .findByApplicationId(applicationId)
                .orElse(null);
        if (app == null
                || Boolean.TRUE.equals(app.getDeleted())
                || ConsultantApplication.Status.CANCELLED.name().equals(app.getStatus())
                || app.getConsultantEmail() == null
                || app.getConsultantEmail().isBlank()) {
            throw new IllegalArgumentException("Invalid or expired code.");
        }
        // Build Q — block verify through an expired access link (no status
        // mutation; the request endpoint already refuses these, this covers
        // the rare request→verify boundary).
        if (isConsultantLinkExpired(app)) {
            throw new IllegalStateException(
                    "This invitation link has expired. Please ask your Sage IT "
                            + "contact to resend it.");
        }
        String normalised = app.getConsultantEmail().trim().toLowerCase();
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
                    Map.of("attempts", String.valueOf(cv.getAttempts())),
                    request);
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
     * Build V — masked email for the login page's "we'll send a code
     * to" reassurance line. Format: first char + "•••" + first char of
     * the local part after the @, e.g. "a•••@g•••.com". Returns a
     * neutral "—" mask when the row is missing or has no email so the
     * surface still doesn't leak existence.
     */
    @Transactional(readOnly = true)
    public String maskedConsultantEmail(String applicationId) {
        ConsultantApplication app = applicationRepository
                .findByApplicationId(applicationId)
                .orElse(null);
        if (app == null
                || Boolean.TRUE.equals(app.getDeleted())
                || ConsultantApplication.Status.CANCELLED.name().equals(app.getStatus())
                || app.getConsultantEmail() == null
                || app.getConsultantEmail().isBlank()) {
            return "—";
        }
        return maskEmail(app.getConsultantEmail().trim());
    }

    private static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at < 1 || at == email.length() - 1) return "•••";
        String local = email.substring(0, at);
        String domain = email.substring(at + 1);
        int dot = domain.indexOf('.');
        String dHead = dot > 0 ? domain.substring(0, dot) : domain;
        String dTail = dot > 0 ? domain.substring(dot) : "";
        String lMask = local.charAt(0) + "•••";
        String dMask = (dHead.isEmpty() ? "•" : String.valueOf(dHead.charAt(0))) + "•••";
        return lMask + "@" + dMask + dTail;
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
        List<ConsultantApplication> out = applicationRepository
                .findByConsultantEmailIgnoreCaseAndDeletedFalseOrderByCreatedAtDesc(
                        normalised)
                .stream()
                .filter(app -> !ConsultantApplication.Status.CANCELLED.name()
                        .equals(app.getStatus()))
                .sorted((a, b) -> Integer.compare(
                        dashboardRank(a.getStatus()),
                        dashboardRank(b.getStatus())))
                .toList();
        // Build Q — derived link-expiry so the dashboard renders the
        // "link expired — contact Sage IT to resend" state (no status flip).
        out.forEach(app -> app.setLinkExpired(isConsultantLinkExpired(app)));
        return out;
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
        populateApprovalSummary(page.getContent());
        // Build Q — derived "link expired — resend" indicator for the ERM
        // dashboard; never hides or mutates the agreement.
        page.getContent().forEach(app -> app.setLinkExpired(isConsultantLinkExpired(app)));
        return page;
    }

    /**
     * Build O — batch-resolves the Manager/Accounts gate status and the
     * "sent for approval" timestamp onto the transient summary fields for
     * the ERM "All" list. Two queries for the whole page (gate rows +
     * SENT_FOR_APPROVAL events) rather than per-row lookups.
     *
     * managerStatus/accountsStatus carry the LATEST gate decision per role
     * (highest round wins); null when no gate exists for that role (e.g.
     * Phase 1 never has an Accounts gate → the UI renders "N/A").
     * sentForApprovalAt is the EARLIEST SENT_FOR_APPROVAL event (when it
     * was first routed to approvers), ISO-formatted; null if never sent.
     */
    private void populateApprovalSummary(List<ConsultantApplication> apps) {
        if (apps == null || apps.isEmpty()) return;
        List<Long> ids = apps.stream()
                .map(ConsultantApplication::getId)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (ids.isEmpty()) return;

        // Latest gate row per (applicationId, role): highest round wins.
        java.util.Map<Long, java.util.Map<
                com.spire.backend.entity.AgreementApproval.ApproverRole,
                com.spire.backend.entity.AgreementApproval>> byApp = new java.util.HashMap<>();
        for (var a : approvalRepository.findByApplicationIdIn(ids)) {
            byApp.computeIfAbsent(a.getApplicationId(), k -> new java.util.EnumMap<>(
                            com.spire.backend.entity.AgreementApproval.ApproverRole.class))
                    .merge(a.getRole(), a, (cur, cand) -> {
                        int curRound = cur.getRound() == null ? -1 : cur.getRound();
                        int candRound = cand.getRound() == null ? -1 : cand.getRound();
                        return candRound >= curRound ? cand : cur;
                    });
        }

        // Earliest SENT_FOR_APPROVAL event per application.
        java.util.Map<Long, LocalDateTime> sentAt = new java.util.HashMap<>();
        for (var e : eventRepository.findByApplicationIdInAndEventType(ids,
                ConsultantApplicationEvent.EventType.SENT_FOR_APPROVAL.name())) {
            if (e.getCreatedAt() == null) continue;
            sentAt.merge(e.getApplicationId(), e.getCreatedAt(),
                    (cur, cand) -> cand.isBefore(cur) ? cand : cur);
        }

        for (ConsultantApplication app : apps) {
            var roleMap = byApp.get(app.getId());
            if (roleMap != null) {
                var mgr = roleMap.get(
                        com.spire.backend.entity.AgreementApproval.ApproverRole.MANAGER);
                if (mgr != null && mgr.getStatus() != null) {
                    app.setManagerStatus(mgr.getStatus().name());
                }
                var acc = roleMap.get(
                        com.spire.backend.entity.AgreementApproval.ApproverRole.ACCOUNTS);
                if (acc != null && acc.getStatus() != null) {
                    app.setAccountsStatus(acc.getStatus().name());
                }
            }
            LocalDateTime sent = sentAt.get(app.getId());
            if (sent != null) {
                app.setSentForApprovalAt(sent.toString());
            }
        }
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
     * Build AA — DESTRUCTIVE operator backfill. Re-renders every executed
     * (COMPLETED) agreement from the CURRENT template and overwrites its stored
     * copies, then recomputes the SHA-256. Used to propagate a template
     * correction into already-signed records when product explicitly accepts
     * that the as-signed bytes + signing-time hash are replaced (super-admin
     * decision).
     *
     * <p>The live record will no longer match the bytes the consultant signed,
     * and {@code documentHash} is recomputed. The OLD S3 objects are NOT
     * deleted — renders write new timestamped keys, so the originals remain
     * retrievable from the bucket as a safety net. {@code dryRun=true} reports
     * the affected count WITHOUT rendering or writing anything.
     *
     * <p>Not method-transactional: each record is saved independently so one
     * render failure (LibreOffice/S3) doesn't roll back the rest. The immutable
     * ConsultantAgreementVersion snapshots (Build V) are intentionally left
     * untouched — they remain the historical record of what was approved.
     */
    public java.util.Map<String, Object> regenerateCompletedAgreements(
            boolean dryRun, HttpServletRequest request) {
        java.util.List<ConsultantApplication> completed = applicationRepository
                .findByStatusAndDeletedFalse(
                        ConsultantApplication.Status.COMPLETED.name(),
                        org.springframework.data.domain.Pageable.unpaged())
                .getContent();
        int processed = 0, regenerated = 0, failed = 0;
        java.util.List<String> errors = new java.util.ArrayList<>();
        for (ConsultantApplication app : completed) {
            processed++;
            if (dryRun) continue;
            try {
                regenerateOneCompleted(app, request);
                regenerated++;
            } catch (Exception e) {
                failed++;
                errors.add(app.getApplicationId() + ": " + e.getMessage());
                log.error("Build AA regenerate failed for {}: {}",
                        app.getApplicationId(), e.getMessage(), e);
            }
        }
        java.util.Map<String, Object> summary = new java.util.LinkedHashMap<>();
        summary.put("dryRun", dryRun);
        summary.put("status", "COMPLETED");
        summary.put("matched", completed.size());
        summary.put("processed", processed);
        summary.put("regenerated", regenerated);
        summary.put("failed", failed);
        summary.put("errors", errors);
        return summary;
    }

    /**
     * Build AA — re-render + overwrite one COMPLETED agreement's stored copies.
     * Plain helper (not {@code @Transactional} — the per-item {@code save}
     * carries its own transaction; a class-internal {@code @Transactional} call
     * would be a proxy no-op anyway).
     */
    private void regenerateOneCompleted(ConsultantApplication app, HttpServletRequest request)
            throws Exception {
        String oldS3Key = app.getS3Key();
        String oldHash = app.getDocumentHash();
        // 1. Re-render the FINAL ERM-signed PDF (all stored signatures) from the
        //    corrected template → new timestamped key (old object retained).
        AgreementDocumentService.PdfUploadResult finalPdf =
                agreementDocumentService.generateAgreementPdf(app);
        app.setS3Key(finalPdf.publicId());
        Integer phase = app.getPhase();
        if (phase == null || phase == 1) {
            app.setPhase1FinalPdfS3Key(finalPdf.publicId());
        }
        // 2. If a consultant-version copy was released, re-render it + the
        //    Certificate of Completion, recompute SHA-256, repoint the key.
        if (Boolean.TRUE.equals(app.getConsultantCopyReleased())) {
            ConsultantVersionService.ReleaseResult release =
                    consultantVersionService.renderConsultantVersionWithCertificate(app);
            String cvKey = agreementDocumentService.storeConsultantVersionPdf(app, release.bytes());
            app.setConsultantPdfS3Key(cvKey);
            app.setDocumentHash(release.sha256Hex());
        }
        applicationRepository.save(app);
        appendEvent(app.getId(),
                ConsultantApplicationEvent.EventType.PDF_GENERATED,
                ConsultantApplicationEvent.ActorType.ERM,
                AGREEMENT_ERM_USER_ID,
                Map.of("kind", "regenerated",
                        "reason", "template-correction-build-AA",
                        "oldS3Key", oldS3Key == null ? "" : oldS3Key,
                        "newS3Key", app.getS3Key() == null ? "" : app.getS3Key(),
                        "oldDocumentHash", oldHash == null ? "" : oldHash,
                        "newDocumentHash", app.getDocumentHash() == null ? "" : app.getDocumentHash()),
                request);
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
        if (consultantName != null) {
            app.setConsultantName(consultantName);
            // Build W — keep the structured name parts coherent so the
            // PDF/clauses (which compose from first/middle/last) match.
            syncNameParts(app, consultantName);
        }
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
            // Build W — sync structured parts (see updateApplication).
            syncNameParts(app, newName);
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
        // CANCELLED stays a hard block.
        if (ConsultantApplication.Status.CANCELLED.name().equals(app.getStatus())) {
            throw new IllegalStateException(
                    "This application is no longer accepting consultant actions.");
        }
        // Build Q — link expiry is a DERIVED, non-mutating signal: the
        // agreement keeps its status, but the consultant view renders the
        // "link expired — ask Sage IT to resend" screen when it's set.
        app.setLinkExpired(isConsultantLinkExpired(app));
        // Build AP — hand the wizard the gate it will actually be judged
        // against, so it never hides a section the submit will demand.
        app.setEffectiveRequirements(resolveEffectiveRequirements(app));
        appendEvent(app.getId(),
                ConsultantApplicationEvent.EventType.ACCESSED,
                ConsultantApplicationEvent.ActorType.CONSULTANT, null,
                Map.of("status", app.getStatus()),
                request);
        return app;
    }

    /**
     * Build Q — DERIVED consultant-link expiry. True when an awaiting
     * (SUBMITTED) agreement's access link is older than the 7-day TTL
     * (measured from inviteSentAt, falling back to createdAt). NEVER
     * mutates the agreement — callers use it to block consultant access
     * and to render the "link expired — resend" indicator. Returns false
     * for every other status (so COMPLETED and all post-submission states
     * are never link-expired) and when the timestamp is missing.
     */
    private boolean isConsultantLinkExpired(ConsultantApplication app) {
        if (app == null) return false;
        if (!ConsultantApplication.Status.SUBMITTED.name().equals(app.getStatus())) {
            return false;
        }
        LocalDateTime sentAt = app.getInviteSentAt();
        if (sentAt == null) sentAt = app.getCreatedAt();
        if (sentAt == null) return false;
        return sentAt.plusDays(CONSULTANT_LINK_TTL_DAYS).isBefore(LocalDateTime.now());
    }

    /** Build Q — public overload: resolve the row, then derive link expiry (false if missing/deleted). */
    @Transactional(readOnly = true)
    public boolean isConsultantLinkExpired(String applicationId) {
        ConsultantApplication app = applicationRepository
                .findByApplicationId(applicationId).orElse(null);
        if (app == null || Boolean.TRUE.equals(app.getDeleted())) return false;
        return isConsultantLinkExpired(app);
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
        // Build Q — block this consultant transition through an expired link.
        if (isConsultantLinkExpired(app)) {
            throw new IllegalStateException(
                    "This invitation link has expired. Please ask your Sage IT "
                            + "contact to resend it.");
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
        String status = app.getStatus();
        if (!ConsultantApplication.Status.SUBMITTED.name().equals(status)
                && !ConsultantApplication.Status.REVISION_REQUESTED.name().equals(status)) {
            throw new IllegalStateException(
                    "Application is in status " + status
                            + " and cannot be edited by the consultant.");
        }
        // Build Q — the agreement is never mutated by expiry, but an
        // expired access link blocks the write (the consultant must get a
        // fresh link from the ERM). No status change.
        if (isConsultantLinkExpired(app)) {
            throw new IllegalStateException(
                    "This invitation link has expired. Please ask your Sage IT "
                            + "contact to resend it.");
        }
        // Build Y (B5) / Build P — during a section-restricted round, ONLY
        // the in-scope section(s) may change. Covers a Build Y revision
        // round (REVISION_REQUESTED) AND a Phase-2 fill (phase ≥ 2): in
        // Phase 2 only the ERM-reopened sections are writable, so every
        // completed Phase-1 field/affirmation stays immutable.
        java.util.Optional<java.util.Set<String>> writeScope = consultantWriteScope(app);
        if (writeScope.isPresent()) {
            java.util.Set<String> allowed = writeScope.get();
            for (String touched : patch.touchedFieldNames()) {
                String section = FIELD_SECTION.get(touched);
                if (section != null && !allowed.contains(section)) {
                    throw new IllegalArgumentException(
                            "This change is outside the section(s) currently open for "
                                    + "editing. The field '" + touched + "' is locked.");
                }
            }
        }
        // Build J — enforce the Portal Access soft cap (10) server-side too,
        // so a direct API call can't persist an unbounded list.
        if (patch.portalEntries != null && !patch.portalEntries.isBlank()) {
            try {
                JsonNode arr = objectMapper.readTree(patch.portalEntries);
                if (arr.isArray() && arr.size() > 10) {
                    throw new IllegalArgumentException(
                            "Portal access is limited to 10 entries.");
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
                // malformed JSON — syncPortalLegacyColumns will no-op safely
            }
        }
        boolean changed = patch.applyTo(app);
        if (!changed) {
            return app;
        }
        // Build W — keep the composed consultant_name in sync whenever
        // the consultant edits any part of their structured name.
        if (patch.firstName != null || patch.middleName != null
                || patch.lastName != null) {
            String composed = composeName(
                    blankToNull(app.getFirstName()),
                    blankToNull(app.getMiddleName()),
                    blankToNull(app.getLastName()));
            if (composed != null) app.setConsultantName(composed);
        }
        // Build J — mirror the ACH JSON+legacy pattern: keep the flattened
        // legacy columns in sync so the ERM read-back + template render off
        // simple values while the JSON/structured fields stay the source.
        if (patch.portalEntries != null) {
            syncPortalLegacyColumns(app);
        }
        boolean currentAddrTouched = patch.bgCurrentAddressLine1 != null
                || patch.bgCurrentAddressLine2 != null || patch.bgCurrentAddressCity != null
                || patch.bgCurrentAddressState != null || patch.bgCurrentAddressZip != null
                || patch.bgCurrentSameAsResidence != null;
        // When "Same as residence" is on, a residence edit also changes the
        // resolved current address — keep the legacy column fresh.
        boolean residenceTouchedWhileSameAs =
                Boolean.TRUE.equals(app.getBgCurrentSameAsResidence())
                && (patch.addressLine1 != null || patch.addressLine2 != null
                        || patch.addressCity != null || patch.addressState != null
                        || patch.addressZip != null);
        if (currentAddrTouched || residenceTouchedWhileSameAs) {
            app.setBgCurrentAddress(AgreementDocumentService.assembledCurrentAddress(app));
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
        String fromStatus = app.getStatus();
        // Build AB-2 — submit reached the service. Pairs with the [submit]
        // received/REJECTED/SUCCESS lines to pinpoint where a submit dies.
        log.info("[submit] start appId={} email={} fromStatus={}",
                applicationId, app.getConsultantEmail(), fromStatus);
        if (!ConsultantApplication.Status.SUBMITTED.name().equals(fromStatus)
                && !ConsultantApplication.Status.REVISION_REQUESTED.name().equals(fromStatus)) {
            throw new IllegalStateException(
                    "Application is in status " + fromStatus
                            + " and cannot be submitted by the consultant.");
        }
        // Build Q — block a submit through an expired access link (no
        // status mutation; the ERM resends to reopen the 7-day window).
        if (isConsultantLinkExpired(app)) {
            throw new IllegalStateException(
                    "This invitation link has expired. Please ask your Sage IT "
                            + "contact to resend it.");
        }
        // Build AB-2 — require a NON-BLANK legal name, not two+ words. The old
        // "first and last" (>=2 tokens) rule made submit a hard 400 for any
        // consultant with a single-word legal name (mononym) — they could never
        // submit (the client didn't pre-check this, so it surfaced only as a
        // swallowed 400). Trust the consultant's typed legal name.
        if (signedLegalName == null || signedLegalName.trim().isEmpty()) {
            throw new IllegalArgumentException("Please enter your full legal name.");
        }

        // F-4 effective-requirements gate. CORE fields/affirmations are
        // always required; appendices are gated by require_appendixN
        // (treated as required iff flagged OR optional-but-touched);
        // SSN is gated by require_ssn AND Appendix 3 being active. The
        // first-and-last signature model demands BOTH a main-agreement
        // draw and a final review-step draw -- one without the other
        // is rejected.
        // Build Y — the primary (main-agreement) signature may be REUSED
        // on a section-restricted revision where the main-agreement step
        // isn't in scope (the consultant only re-draws the final
        // execution signature). It's missing only when neither a fresh
        // draw nor a persisted primary exists. The final signature is
        // always re-captured.
        boolean hasNewPrimary = signatureBase64 != null
                && signatureBase64.startsWith("data:image/");
        boolean hasExistingPrimary = (app.getSignatureImage() != null
                && !app.getSignatureImage().isBlank())
                || (app.getSignatureS3Key() != null
                && !app.getSignatureS3Key().isBlank());
        boolean missingSig = !hasNewPrimary && !hasExistingPrimary;
        boolean missingFinalSig = finalSignatureBase64 == null || finalSignatureBase64.isBlank()
                || !finalSignatureBase64.startsWith("data:image/");
        java.util.List<String> missingFields = collectMissingConsultantFields(app);
        java.util.List<String> missingAffs = collectMissingAffirmations(app);
        if (!missingFields.isEmpty() || !missingAffs.isEmpty() || missingSig || missingFinalSig) {
            // Build AB-2 — the exact reason submit was rejected for this
            // consultant (resolves "validation thinks X is incomplete").
            log.warn("[submit] REJECTED appId={} fromStatus={} missingFields={} missingAffirmations={} "
                            + "missingPrimarySig={} missingFinalSig={} hasNewPrimary={} hasExistingPrimary={}",
                    applicationId, fromStatus, missingFields, missingAffs,
                    missingSig, missingFinalSig, hasNewPrimary, hasExistingPrimary);
            throw new com.spire.backend.exception.IncompleteSubmissionException(
                    missingFields, missingAffs, missingSig, missingFinalSig);
        }

        // Phase 5 — signatures store to S3 (signature_s3_key); the Cloudinary
        // *_image columns stay null for new records. When there's no new
        // primary draw (a section-restricted revision reusing the existing
        // primary), the existing pointer — S3 key or legacy Cloudinary URL —
        // is left untouched.
        try {
            if (hasNewPrimary) {
                app.setSignatureS3Key(storeSignatureToS3(signatureBase64, "consultant", app));
                app.setSignatureImage(null);
            }
            app.setFinalSignatureS3Key(
                    storeSignatureToS3(finalSignatureBase64, "consultant-final", app));
            app.setFinalSignatureImage(null);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Couldn't store signature: " + e.getMessage(), e);
        }

        LocalDateTime now = LocalDateTime.now();
        String ip = clientIp(request);
        app.setSignedLegalName(signedLegalName.trim());
        app.setSignedAt(now);
        app.setSignedIp(ip);
        app.setSignedUserAgent(request == null ? null : request.getHeader("User-Agent"));
        // Phase D — dedicated consultant signing record, surfaced to the ERM.
        app.setSigningIp(ip);
        app.setSigningAt(now);
        // F-4 — final (review-step) signature record (stored on S3 above).
        app.setFinalSignedAt(now);
        app.setFinalSigningIp(ip);
        // Build AL — record the per-section signing dates BEFORE overwriting
        // the global date, so only the section(s) actually (re)signed this
        // round move. A targeted appendix/phase revision no longer bumps the
        // untouched appendices' dates. Must run before setSignatureDate(now)
        // below (it reads the PRIOR global date to backfill legacy rows).
        stampSectionSignatureDates(app, now, hasNewPrimary);
        // Build Q — persist the signing date here so the
        // ${signatureDate} placeholder in every consultant signature
        // block ("Date / Email: ${signatureDate} / ${primaryEmail}")
        // shows the actual moment of submission. ermApproveAndSign
        // OVERWRITES this if/when the ERM countersigns later, which is
        // the right behaviour: the final PDF's signature date should
        // reflect the most recent signing action. Retained as the global
        // fallback (legacy rows) + the execution-trace date.
        app.setSignatureDate(now);
        // Build Y — the revision round is complete; clear the scope so a
        // fresh VERIFIED row is no longer restricted (the next
        // ermRequestRevision sets a new scope).
        app.setRevisionSections(null);
        // Build AH — a (re)submission supersedes any previously released
        // consultant-version copy. Clear the release flag so the ERM must
        // re-approve the consultant version — which mints the NEXT numbered
        // version (V2, V3, …) — before re-sending for approval. This re-enables
        // the "Approve consultant version" button and re-gates "Send for
        // approval" until the revised version is released. No-op on a first
        // submit, where the copy was never released.
        app.setConsultantCopyReleased(false);
        app.setConsultantCopyReleasedAt(null);
        // Build AO — drop any stale over-clicked cheque entries beyond the
        // declared count so the stored list matches what the consultant signed
        // (the render + ERM view already cap on read; this cleans storage so a
        // later count increase doesn't resurface old numbers).
        pruneChequesToDeclaredCount(app);
        app.setStatus(ConsultantApplication.Status.VERIFIED.name());
        applicationRepository.save(app);
        log.info("[submit] SUCCESS appId={} {} -> VERIFIED", applicationId, fromStatus);

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

    // ── Build AL — per-section consultant signature dates ─────────────

    /**
     * Record the per-section consultant signing dates so a targeted revision
     * only re-stamps the section(s) actually (re)signed this round. The primary
     * (main-agreement) date moves ONLY when a fresh primary was drawn; each
     * appendix date moves only when that appendix is affirmed AND either this is
     * an unrestricted (first/full) submit OR the appendix is in the current
     * revision / Phase-2 scope. Untouched appendices keep their prior dates.
     *
     * <p>Must be called BEFORE {@code setSignatureDate(now)} — it reads the
     * prior global date to backfill any already-signed section that predates
     * this feature, so legacy rows don't collapse to a single "now" date.
     */
    private void stampSectionSignatureDates(
            ConsultantApplication app, LocalDateTime now, boolean primaryReSigned) {
        java.util.Optional<java.util.Set<String>> scopeOpt = consultantWriteScope(app);
        boolean restricted = scopeOpt.isPresent();
        java.util.Set<String> scope = scopeOpt.orElseGet(java.util.Collections::emptySet);

        java.util.Map<String, String> dates =
                parseSectionSignatureDates(app.getSectionSignatureDates());

        // Backfill: an already-signed section with no per-section date inherits
        // the PRIOR global signing date (else it would fall back to the freshly
        // updated global date and read "now" after this feature ships).
        LocalDateTime prevGlobal = app.getSignatureDate();
        if (prevGlobal != null) {
            String prev = prevGlobal.toString();
            boolean hasPrimary = primaryReSigned
                    || nonBlank(app.getSignatureS3Key()) || nonBlank(app.getSignatureImage());
            if (hasPrimary) dates.putIfAbsent("main-agreement", prev);
            for (int n = 1; n <= 5; n++) {
                if (appendixAffirmed(app, n)) dates.putIfAbsent("appendix" + n, prev);
            }
        }

        // (Re)stamp ONLY what was signed this round.
        String nowStr = now.toString();
        if (primaryReSigned) dates.put("main-agreement", nowStr);
        for (int n = 1; n <= 5; n++) {
            if (appendixAffirmed(app, n) && (!restricted || scope.contains("appendix" + n))) {
                dates.put("appendix" + n, nowStr);
            }
        }
        app.setSectionSignatureDates(writeSectionSignatureDates(dates));
    }

    private static boolean appendixAffirmed(ConsultantApplication app, int n) {
        return switch (n) {
            case 1 -> Boolean.TRUE.equals(app.getAffirmedAppendix1());
            case 2 -> Boolean.TRUE.equals(app.getAffirmedAppendix2());
            case 3 -> Boolean.TRUE.equals(app.getAffirmedAppendix3());
            case 4 -> Boolean.TRUE.equals(app.getAffirmedAppendix4());
            case 5 -> Boolean.TRUE.equals(app.getAffirmedAppendix5());
            default -> false;
        };
    }

    private java.util.Map<String, String> parseSectionSignatureDates(String json) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        if (json != null && !json.isBlank()) {
            try {
                JsonNode node = objectMapper.readTree(json);
                if (node != null && node.isObject()) {
                    node.fields().forEachRemaining(e -> {
                        if (e.getValue() != null && e.getValue().isTextual()) {
                            out.put(e.getKey(), e.getValue().asText());
                        }
                    });
                }
            } catch (Exception ignored) { /* treat as no per-section dates */ }
        }
        return out;
    }

    private String writeSectionSignatureDates(java.util.Map<String, String> dates) {
        try {
            return objectMapper.writeValueAsString(dates);
        } catch (Exception e) {
            log.warn("Failed to serialise section signature dates: {}", e.getMessage());
            return null;
        }
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
            String applicationId, JsonNode sections,
            String achDebitDates, String achDebitAmounts,
            String ratePeriod1, String rateAmount1,
            String ratePeriod2, String rateAmount2,
            String phase2DeliverablePeriod,
            HttpServletRequest request) {
        ConsultantApplication app = getByApplicationId(applicationId);
        assertErmCanAccess(app, request);
        // 3B — the ERM can bounce to the CONSULTANT from the consultant-
        // signed state OR from any approval stage (e.g. an approver's note
        // needs a consultant fix). The consultant resubmit returns the row
        // to VERIFIED, after which the ERM re-releases + re-sends for
        // approval (a fresh round).
        String st = app.getStatus();
        boolean revisable =
                ConsultantApplication.Status.VERIFIED.name().equals(st)
                || ConsultantApplication.Status.AWAITING_APPROVALS.name().equals(st)
                || ConsultantApplication.Status.APPROVAL_REVISION_REQUESTED.name().equals(st)
                || ConsultantApplication.Status.READY_TO_SIGN.name().equals(st);
        if (!revisable) {
            throw new IllegalStateException(
                    "This application can't be sent back for revision "
                            + "(status=" + st + ").");
        }

        // Build Y — SECTION PICKER. The ERM selects the section(s) to
        // revise (optional per-section note, never required). The
        // consultant is then restricted to ONLY these sections.
        java.util.List<String> selectedKeys = new java.util.ArrayList<>();
        java.util.LinkedHashMap<String, String> notes = new java.util.LinkedHashMap<>();
        if (sections != null && sections.isArray()) {
            for (JsonNode row : sections) {
                String key = blankToNull(row.path("key").asText(""));
                if (key == null || !REVISABLE_SECTION_IDS.contains(key)) continue;
                if (!selectedKeys.contains(key)) selectedKeys.add(key);
                String note = blankToNull(row.path("note").asText(""));
                if (note != null) notes.put(key, note);
            }
        }

        // Build U — apply an ERM ACH debit-schedule correction sent with the
        // revision. Only a CHANGED value is persisted; when it changes, scope
        // appendix2 in so the consultant re-reviews + re-affirms the corrected
        // schedule (this also makes an "ACH-only" revision valid below).
        boolean achChanged = false;
        if (achDebitDates != null && !java.util.Objects.equals(
                blankToNull(achDebitDates), blankToNull(app.getAchDebitDates()))) {
            app.setAchDebitDates(blankToNull(achDebitDates));
            achChanged = true;
        }
        if (achDebitAmounts != null && !java.util.Objects.equals(
                blankToNull(achDebitAmounts), blankToNull(app.getAchDebitAmounts()))) {
            app.setAchDebitAmounts(blankToNull(achDebitAmounts));
            achChanged = true;
        }
        if (achChanged && !selectedKeys.contains("appendix2")) {
            selectedKeys.add("appendix2");
        }

        // Build W — apply an ERM Phase-2 Rate Schedule correction sent with the
        // revision (Rate period 1/2 + Amount 1/2). Only CHANGED values persist;
        // when any changes, scope the MAIN AGREEMENT in (Section 11 carries the
        // rate card) so the consultant re-reviews + re-signs the corrected
        // schedule. The values stay read-only to the consultant and stamp into
        // the agreement PDF (buildContext already maps the rate keys).
        boolean rateChanged = false;
        if (ratePeriod1 != null && !java.util.Objects.equals(
                blankToNull(ratePeriod1), blankToNull(app.getRatePeriod1()))) {
            app.setRatePeriod1(blankToNull(ratePeriod1));
            rateChanged = true;
        }
        if (rateAmount1 != null && !java.util.Objects.equals(
                blankToNull(rateAmount1), blankToNull(app.getRateAmount1()))) {
            app.setRateAmount1(blankToNull(rateAmount1));
            rateChanged = true;
        }
        if (ratePeriod2 != null && !java.util.Objects.equals(
                blankToNull(ratePeriod2), blankToNull(app.getRatePeriod2()))) {
            app.setRatePeriod2(blankToNull(ratePeriod2));
            rateChanged = true;
        }
        if (rateAmount2 != null && !java.util.Objects.equals(
                blankToNull(rateAmount2), blankToNull(app.getRateAmount2()))) {
            app.setRateAmount2(blankToNull(rateAmount2));
            rateChanged = true;
        }
        if (rateChanged && !selectedKeys.contains("main-agreement")) {
            selectedKeys.add("main-agreement");
        }

        // Appendix 1 Schedule 1 — apply an ERM Phase-2 deliverables-period
        // correction sent with the revision. Only a CHANGED value persists;
        // when it changes, scope APPENDIX 1 in (where the Monthly Deliverables
        // table lives) so the consultant re-reviews + re-signs the corrected
        // schedule. Read-only to the consultant; stamps into the agreement PDF.
        if (phase2DeliverablePeriod != null && !java.util.Objects.equals(
                blankToNull(phase2DeliverablePeriod),
                blankToNull(app.getPhase2DeliverablePeriod()))) {
            app.setPhase2DeliverablePeriod(blankToNull(phase2DeliverablePeriod));
            if (!selectedKeys.contains("appendix1")) {
                selectedKeys.add("appendix1");
            }
        }

        if (selectedKeys.isEmpty()) {
            throw new IllegalArgumentException(
                    "Select at least one section to revise.");
        }

        // Persist the revision scope (JSON: [{"key":…,"note":…}, …]).
        com.fasterxml.jackson.databind.node.ArrayNode arr = objectMapper.createArrayNode();
        for (String k : selectedKeys) {
            com.fasterxml.jackson.databind.node.ObjectNode o = objectMapper.createObjectNode();
            o.put("key", k);
            if (notes.containsKey(k)) o.put("note", notes.get(k));
            arr.add(o);
        }
        app.setRevisionSections(arr.toString());

        // Human-readable summary (drives the consultant email + the
        // REVISION_REQUESTED banner). Built from the section labels + any
        // notes, so a zero-text revision still reads clearly.
        String summary = buildRevisionSummary(selectedKeys, notes);
        app.setCurrentRevisionRemarks(summary);

        // Re-arm the affirmation for each selected section so the
        // consultant must re-read + re-affirm it (scroll-gate re-arms in
        // the wizard automatically per section).
        for (String k : selectedKeys) clearAffirmationForSection(app, k);

        Integer prevCount = app.getRevisionCount();
        app.setRevisionCount((prevCount == null ? 0 : prevCount) + 1);
        app.setStatus(ConsultantApplication.Status.REVISION_REQUESTED.name());
        applicationRepository.save(app);

        appendEvent(app.getId(),
                ConsultantApplicationEvent.EventType.REVISION_REQUESTED,
                ConsultantApplicationEvent.ActorType.ERM,
                AGREEMENT_ERM_USER_ID,
                Map.of("selectedSections", selectedKeys,
                        "revisionCount", app.getRevisionCount()),
                request);

        try {
            emailTemplateService.sendConsultantRevisionRequest(
                    app, summary == null ? "" : summary);
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
     * Build AJ — SIGNATURE-ONLY revision. The consultant re-signs the agreement
     * with a fresh signature; content (fields + affirmations) is left intact and
     * read-only. Used when the consultant's drawn/uploaded signature is wrong or
     * inappropriate. Clears BOTH stored consultant signatures so a fresh draw is
     * required (hasExistingPrimary would otherwise let them reuse the primary),
     * scopes the wizard to the two signing steps via the dedicated "signature"
     * key, and bounces to REVISION_REQUESTED with a signature-specific note.
     * Valid from the same states as {@link #ermRequestRevision}.
     */
    @Transactional
    public ConsultantApplication ermRequestSignatureRevision(
            String applicationId, String note, HttpServletRequest request) {
        ConsultantApplication app = getByApplicationId(applicationId);
        assertErmCanAccess(app, request);
        String st = app.getStatus();
        boolean revisable =
                ConsultantApplication.Status.VERIFIED.name().equals(st)
                || ConsultantApplication.Status.AWAITING_APPROVALS.name().equals(st)
                || ConsultantApplication.Status.APPROVAL_REVISION_REQUESTED.name().equals(st)
                || ConsultantApplication.Status.READY_TO_SIGN.name().equals(st);
        if (!revisable) {
            throw new IllegalStateException(
                    "A signature re-sign can't be requested from status " + st + ".");
        }

        // Clear BOTH consultant signatures so a fresh re-draw is required. The
        // primary MUST be cleared explicitly (else hasExistingPrimary lets the
        // consultant reuse it); the final is always re-captured but is cleared
        // here too so the "signed" record is unambiguously reset. Content fields
        // + affirmations are untouched — this is signature-only.
        app.setSignatureS3Key(null);
        app.setSignatureImage(null);
        app.setFinalSignatureS3Key(null);
        app.setFinalSignatureImage(null);
        app.setSigningAt(null);
        app.setSigningIp(null);
        app.setFinalSignedAt(null);
        app.setFinalSigningIp(null);
        app.setSignatureDate(null);

        // Scope the wizard to the signing steps only (dedicated key; the
        // consultant fill flow maps "signature" to main-agreement + review).
        com.fasterxml.jackson.databind.node.ArrayNode arr = objectMapper.createArrayNode();
        com.fasterxml.jackson.databind.node.ObjectNode o = objectMapper.createObjectNode();
        o.put("key", "signature");
        String trimmedNote = note == null ? null : note.trim();
        if (trimmedNote != null && !trimmedNote.isEmpty()) o.put("note", trimmedNote);
        arr.add(o);
        app.setRevisionSections(arr.toString());

        String summary = "Please re-sign the agreement with a clear, valid signature."
                + (trimmedNote != null && !trimmedNote.isEmpty() ? " " + trimmedNote : "");
        app.setCurrentRevisionRemarks(summary);

        Integer prevCount = app.getRevisionCount();
        app.setRevisionCount((prevCount == null ? 0 : prevCount) + 1);
        app.setStatus(ConsultantApplication.Status.REVISION_REQUESTED.name());
        applicationRepository.save(app);

        appendEvent(app.getId(),
                ConsultantApplicationEvent.EventType.REVISION_REQUESTED,
                ConsultantApplicationEvent.ActorType.ERM,
                AGREEMENT_ERM_USER_ID,
                Map.of("signatureRevision", true,
                        "revisionCount", app.getRevisionCount()),
                request);

        try {
            emailTemplateService.sendConsultantRevisionRequest(app, summary);
            appendEvent(app.getId(),
                    ConsultantApplicationEvent.EventType.EMAIL_SENT,
                    ConsultantApplicationEvent.ActorType.SYSTEM, null,
                    Map.of("template", "consultant_revision_request"),
                    null);
        } catch (Exception e) {
            log.warn("Failed to notify consultant of signature revision for {}: {}",
                    applicationId, e.getMessage());
        }

        return app;
    }

    // ── Build AK — per-document re-upload revision ───────────────────

    /**
     * Dedicated document re-upload scope keys → the wizard section that hosts
     * that upload tile. Used both to un-gate the upload server-side
     * ({@link #assertSectionWritable}) and, on the consultant, to surface the
     * hosting section. These keys are deliberately NOT in REVISABLE_SECTION_IDS
     * (mirrors how "signature" is a dedicated non-section key).
     */
    // The SSN document is normally optional, but a re-upload request makes it
    // required again for that round (see collectMissingConsultantFields), so it
    // can't be resubmitted until replaced.
    private static final java.util.Map<String, String> DOC_REVISION_SECTION = java.util.Map.of(
            "doc:workauth", "cover",
            "doc:offer-letter", "appendix1",
            "doc:dl-doc", "appendix3",
            "doc:state-id", "appendix3",
            "doc:ssn-doc", "appendix3",
            "doc:cheque", "appendix5");

    private static final java.util.Map<String, String> DOC_REVISION_LABELS = java.util.Map.of(
            "doc:workauth", "work-authorization document",
            "doc:offer-letter", "offer letter",
            "doc:dl-doc", "Driver's License document",
            "doc:state-id", "State ID document",
            "doc:ssn-doc", "SSN document",
            "doc:cheque", "security cheque(s)");

    /**
     * Build AK — ERM "Request document re-upload": a per-document revision. For
     * each requested document key the stored file pointer(s) are CLEARED so a
     * fresh upload is required (the backend submit-gate then blocks resubmit
     * until it's replaced), the wizard is scoped to the hosting section(s) via
     * the dedicated {@code doc:*} keys, and the agreement bounces to
     * REVISION_REQUESTED. Content/affirmations/signatures are untouched — this
     * is document-only. Valid from the same states as
     * {@link #ermRequestRevision}. Used when the consultant uploaded a wrong or
     * inappropriate document.
     */
    @Transactional
    public ConsultantApplication ermRequestDocumentRevision(
            String applicationId, java.util.List<String> docKeys, String note,
            HttpServletRequest request) {
        ConsultantApplication app = getByApplicationId(applicationId);
        assertErmCanAccess(app, request);
        String st = app.getStatus();
        boolean revisable =
                ConsultantApplication.Status.VERIFIED.name().equals(st)
                || ConsultantApplication.Status.AWAITING_APPROVALS.name().equals(st)
                || ConsultantApplication.Status.APPROVAL_REVISION_REQUESTED.name().equals(st)
                || ConsultantApplication.Status.READY_TO_SIGN.name().equals(st);
        if (!revisable) {
            throw new IllegalStateException(
                    "A document re-upload can't be requested from status " + st + ".");
        }

        // Validate + de-dupe the requested keys (drop anything not a known doc key).
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
        if (docKeys != null) {
            for (String k : docKeys) {
                if (k != null && DOC_REVISION_SECTION.containsKey(k)) keys.add(k);
            }
        }
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("Select at least one document to re-upload.");
        }

        // Clear each requested document so a fresh upload is required. The
        // submit-gate (collectMissingConsultantFields) then flags the cleared
        // doc as missing until the consultant re-uploads it.
        for (String k : keys) clearDocument(app, k);

        // Scope the wizard to the document keys (each maps to its hosting section
        // on the consultant; field writes to that section stay locked).
        com.fasterxml.jackson.databind.node.ArrayNode arr = objectMapper.createArrayNode();
        String trimmedNote = note == null ? null : note.trim();
        boolean hasNote = trimmedNote != null && !trimmedNote.isEmpty();
        for (String k : keys) {
            com.fasterxml.jackson.databind.node.ObjectNode o = objectMapper.createObjectNode();
            o.put("key", k);
            if (hasNote) o.put("note", trimmedNote);
            arr.add(o);
        }
        app.setRevisionSections(arr.toString());

        StringBuilder sb = new StringBuilder("Please re-upload the following document(s): ");
        int i = 0;
        for (String k : keys) {
            if (i++ > 0) sb.append(", ");
            sb.append(DOC_REVISION_LABELS.getOrDefault(k, k));
        }
        sb.append('.');
        if (hasNote) sb.append(' ').append(trimmedNote);
        String summary = sb.toString();
        app.setCurrentRevisionRemarks(summary);

        Integer prevCount = app.getRevisionCount();
        app.setRevisionCount((prevCount == null ? 0 : prevCount) + 1);
        app.setStatus(ConsultantApplication.Status.REVISION_REQUESTED.name());
        applicationRepository.save(app);

        appendEvent(app.getId(),
                ConsultantApplicationEvent.EventType.REVISION_REQUESTED,
                ConsultantApplicationEvent.ActorType.ERM,
                AGREEMENT_ERM_USER_ID,
                Map.of("documentRevision", true,
                        "documents", String.join(",", keys),
                        "revisionCount", app.getRevisionCount()),
                request);

        try {
            emailTemplateService.sendConsultantRevisionRequest(app, summary);
            appendEvent(app.getId(),
                    ConsultantApplicationEvent.EventType.EMAIL_SENT,
                    ConsultantApplicationEvent.ActorType.SYSTEM, null,
                    Map.of("template", "consultant_revision_request"),
                    null);
        } catch (Exception e) {
            log.warn("Failed to notify consultant of document revision for {}: {}",
                    applicationId, e.getMessage());
        }

        return app;
    }

    /**
     * Build AK — null the stored pointer(s) for one requested document key so a
     * fresh upload is required. A legacy Cloudinary object is destroyed first
     * (best-effort) so nulling the pointer doesn't orphan it — the re-upload's
     * own {@code destroyCloudinaryDoc} could no longer see it once the pointer
     * is gone. New S3-stored objects are left to the standard re-upload path.
     */
    private void clearDocument(ConsultantApplication app, String docKey) {
        switch (docKey) {
            case "doc:workauth" -> {
                destroyCloudinaryDoc(app.getWorkAuthDocPublicId(), app.getWorkAuthDocContentType());
                app.setWorkAuthDocS3Key(null);
                app.setWorkAuthDocPublicId(null);
                app.setWorkAuthDocContentType(null);
                app.setWorkAuthDocUploadedAt(null);
            }
            case "doc:offer-letter" -> {
                destroyCloudinaryDoc(app.getOfferLetterPublicId(), app.getOfferLetterContentType());
                app.setOfferLetterS3Key(null);
                app.setOfferLetterPublicId(null);
                app.setOfferLetterContentType(null);
                app.setOfferLetterUploadedAt(null);
            }
            case "doc:dl-doc" -> {
                destroyCloudinaryDoc(app.getDlDocPublicId(), app.getDlDocContentType());
                app.setDlDocS3Key(null);
                app.setDlDocPublicId(null);
                app.setDlDocContentType(null);
                app.setDlDocUploadedAt(null);
            }
            case "doc:state-id" -> {
                destroyCloudinaryDoc(app.getStateIdDocPublicId(), app.getStateIdDocContentType());
                app.setStateIdDocS3Key(null);
                app.setStateIdDocPublicId(null);
                app.setStateIdDocContentType(null);
                app.setStateIdDocUploadedAt(null);
            }
            case "doc:ssn-doc" -> {
                destroyCloudinaryDoc(app.getSsnDocPublicId(), app.getSsnDocContentType());
                app.setSsnDocS3Key(null);
                app.setSsnDocPublicId(null);
                app.setSsnDocContentType(null);
                app.setSsnDocUploadedAt(null);
            }
            case "doc:cheque" -> clearChequeFiles(app);
            default -> { /* unknown key — ignore */ }
        }
    }

    /**
     * Build AK — clear the uploaded FILE of every cheque while preserving its
     * number/date, so the consultant only re-attaches files (not re-enters the
     * schedule). The per-cheque completeness gate (number + upload) then blocks
     * resubmit until each file is replaced.
     */
    /**
     * Build AO — drop cheque entries whose index is at/beyond the declared
     * {@code securityCheckCount}. When the consultant over-clicks the cheque
     * count, enters numbers, then reduces the count, the extra entries linger
     * in the JSON. No-op when the count isn't a positive number (so a non-cheque
     * agreement's data is never touched).
     */
    private void pruneChequesToDeclaredCount(ConsultantApplication app) {
        int count = parseChequeCountSafe(app.getSecurityCheckCount());
        if (count <= 0) return;
        String json = app.getCheques();
        if (json == null || json.isBlank()) return;
        java.util.List<ChequeEntry> entries = parseCheques(app);
        java.util.List<ChequeEntry> kept = new java.util.ArrayList<>();
        for (ChequeEntry e : entries) {
            if (e.index() >= 0 && e.index() < count) kept.add(e);
        }
        if (kept.size() != entries.size()) {
            app.setCheques(serialiseCheques(kept));
        }
    }

    private void clearChequeFiles(ConsultantApplication app) {
        java.util.List<ChequeEntry> entries = parseCheques(app);
        if (!entries.isEmpty()) {
            java.util.List<ChequeEntry> cleared = new java.util.ArrayList<>(entries.size());
            for (ChequeEntry e : entries) {
                // Destroy any legacy Cloudinary object first (best-effort) so it
                // isn't orphaned once its pointer is dropped.
                destroyCloudinaryDoc(e.publicId(), e.contentType());
                cleared.add(new ChequeEntry(
                        e.index(), e.number(), e.date(), "", "", "", ""));
            }
            app.setCheques(serialiseCheques(cleared));
        }
        // Legacy single-cheque pointers (index 0 fallback).
        destroyCloudinaryDoc(app.getChequePublicId(), app.getChequeContentType());
        app.setChequeS3Key(null);
        app.setChequePublicId(null);
        app.setChequeContentType(null);
        app.setChequeUploadedAt(null);
    }

    // ── Build Y — section-picker revision support ────────────────────

    /** Sections the ERM may select in the revision picker (review/sign excluded). */
    private static final java.util.List<String> REVISABLE_SECTION_IDS = java.util.List.of(
            "cover", "main-agreement", "exhibit-a", "exhibit-b",
            "appendix1", "appendix2", "appendix3", "appendix4", "appendix5");

    private static final java.util.Map<String, String> SECTION_LABELS = java.util.Map.ofEntries(
            java.util.Map.entry("cover", "Your Information"),
            java.util.Map.entry("main-agreement", "The Agreement"),
            java.util.Map.entry("exhibit-a", "Exhibit A"),
            java.util.Map.entry("exhibit-b", "Exhibit B"),
            java.util.Map.entry("appendix1", "Appendix 1 — Employment"),
            java.util.Map.entry("appendix2", "Appendix 2 — ACH Authorization"),
            java.util.Map.entry("appendix3", "Appendix 3 — Background Check"),
            java.util.Map.entry("appendix4", "Appendix 4 — Portal Access"),
            java.util.Map.entry("appendix5", "Appendix 5 — Security Cheque"));

    /** field/affirmation key → owning section id (for B5 write-scope enforcement). */
    private static final java.util.Map<String, String> FIELD_SECTION = buildFieldSectionMap();

    private static java.util.Map<String, String> buildFieldSectionMap() {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        for (String f : new String[]{"firstName", "middleName", "lastName",
                "consultantName", "primaryPhone", "addressLine1", "addressLine2",
                "addressCity", "addressState", "addressZip",
                // ERM-set / read-only cover fields that still exist on the
                // fill patch — mapped so they can't bypass the B5 scope.
                "residenceAddress", "workAuthorizationCategory", "effectiveDate"}) {
            m.put(f, "cover");
        }
        m.put("affirmedMainAgreement", "main-agreement");
        // Build W — the Phase-2 rate card (Section 11) lives in the main
        // agreement; ERM-set + read-only to the consultant, but mapped so a
        // rate revision scopes the main agreement for re-review/re-signature.
        for (String f : new String[]{"ratePeriod1", "rateAmount1",
                "ratePeriod2", "rateAmount2"}) m.put(f, "main-agreement");
        for (String f : new String[]{"technologyTrack", "customScopeNotes",
                "affirmedExhibitA"}) m.put(f, "exhibit-a");
        m.put("affirmedExhibitB", "exhibit-b");
        for (String f : new String[]{"employerPayrollEntity", "implementationPartner",
                "endClient", "roleTitle", "verifiedStartDate", "payrollCycle",
                "phase2DeliverablePeriod",
                "affirmedAppendix1"}) m.put(f, "appendix1");
        for (String f : new String[]{"achAccountType", "achBankName",
                "achAccountHolderName", "achRoutingNumber", "achAccountNumber",
                "achNoticeEmail", "achDebitDates", "achDebitAmounts",
                "affirmedAppendix2"}) m.put(f, "appendix2");
        for (String f : new String[]{"bgFullLegalName", "bgOtherNamesUsed",
                "bgCurrentAddress", "bgCurrentAddressLine1", "bgCurrentAddressLine2",
                "bgCurrentAddressCity", "bgCurrentAddressState", "bgCurrentAddressZip",
                "bgCurrentSameAsResidence", "bgDateOfBirth", "bgFullSsn", "idType",
                "bgDriverLicense", "bgStateId", "affirmedAppendix3"}) m.put(f, "appendix3");
        // Build Z — portalAuthorizedActions + portalRevocationContact are now
        // ERM-set/read-only (not consultant-writable), so they are omitted here
        // (mirrors ACH debit being ERM-only).
        for (String f : new String[]{"portalPlatform", "portalUsername",
                "portalEntries", "portalEffectiveDate",
                "affirmedAppendix4"}) m.put(f, "appendix4");
        for (String f : new String[]{"securityCheckCount", "securityCheckBank",
                "securityCheckHolderName", "securityCheckAmount",
                "securityCheckNumbers", "securityCheckDates",
                "affirmedAppendix5"}) m.put(f, "appendix5");
        return m;
    }

    private static String buildRevisionSummary(
            java.util.List<String> keys, java.util.Map<String, String> notes) {
        StringBuilder sb = new StringBuilder("Please revise: ");
        for (int i = 0; i < keys.size(); i++) {
            String k = keys.get(i);
            if (i > 0) sb.append("; ");
            sb.append(SECTION_LABELS.getOrDefault(k, k));
            String note = notes.get(k);
            if (note != null && !note.isBlank()) sb.append(" (").append(note).append(")");
        }
        sb.append('.');
        return sb.toString();
    }

    private static void clearAffirmationForSection(ConsultantApplication app, String key) {
        switch (key) {
            case "main-agreement" -> app.setAffirmedMainAgreement(false);
            case "exhibit-a" -> app.setAffirmedExhibitA(false);
            case "exhibit-b" -> app.setAffirmedExhibitB(false);
            case "appendix1" -> app.setAffirmedAppendix1(false);
            case "appendix2" -> app.setAffirmedAppendix2(false);
            case "appendix3" -> app.setAffirmedAppendix3(false);
            case "appendix4" -> app.setAffirmedAppendix4(false);
            case "appendix5" -> app.setAffirmedAppendix5(false);
            default -> { /* cover / review carry no affirmation */ }
        }
    }

    /**
     * Build Y (B5) / Build P — reject a write to {@code sectionId} when a
     * section-restricted round is active and that section isn't in scope.
     * Covers BOTH a Build Y revision round (REVISION_REQUESTED) and a
     * Phase-2 fill (phase ≥ 2, status SUBMITTED) — in Phase 2 only the
     * ERM-reopened sections are writable, so a completed Phase-1 upload
     * (cheques, work-auth, DL/SSN) is rejected here even though the
     * out-of-band upload endpoints don't pass through {@code consultantFill}.
     * No-op when unrestricted.
     */
    private void assertSectionWritable(ConsultantApplication app, String sectionId) {
        assertUploadWritable(app, sectionId, null);
    }

    /**
     * Build AK — upload write-gate. Like {@link #assertSectionWritable} but a
     * per-document re-upload round ({@code doc:<x>} scope keys) unlocks ONLY the
     * upload whose exact {@code docKey} was requested — not every upload sharing
     * the same host section. A full-section revision (scope contains
     * {@code sectionId}) still unlocks all of that section's uploads.
     */
    private void assertUploadWritable(
            ConsultantApplication app, String sectionId, String docKey) {
        // Build P — the out-of-band upload endpoints (cheque, work-auth,
        // DL/SSN, offer-letter) carry no status guard of their own, so gate
        // them here: a consultant may only write while actively filling
        // (SUBMITTED) or revising (REVISION_REQUESTED). Reject in any other
        // status so a still-valid consultant token can't overwrite a locked
        // Phase-1 upload after submit (VERIFIED) or once finalized.
        String st = app.getStatus();
        if (!ConsultantApplication.Status.SUBMITTED.name().equals(st)
                && !ConsultantApplication.Status.REVISION_REQUESTED.name().equals(st)) {
            throw new IllegalStateException(
                    "This agreement is no longer open for consultant edits.");
        }
        // Build Q — an expired access link blocks EVERY consultant write,
        // including the out-of-band uploads (cheque/work-auth/DL/SSN/offer-
        // letter) that don't pass through consultantFill/consultantSubmit.
        // No status mutation; the ERM resends to reopen the 7-day window.
        if (isConsultantLinkExpired(app)) {
            throw new IllegalStateException(
                    "This invitation link has expired. Please ask your Sage IT "
                            + "contact to resend it.");
        }
        java.util.Optional<java.util.Set<String>> scope = consultantWriteScope(app);
        if (scope.isEmpty()) return;
        java.util.Set<String> allowed = scope.get();
        // Full-section revision (or Phase-2 reopened): the whole host section is
        // writable, so every upload it hosts is allowed.
        if (allowed.contains(sectionId)) return;
        // Build AK — a per-document re-upload round scopes on dedicated
        // "doc:<x>" keys and unlocks ONLY the exact requested document. Two
        // docs sharing a host section (e.g. appendix3's DL / State-ID / SSN)
        // therefore stay independent: requesting a DL re-upload does NOT let
        // the consultant overwrite the State-ID or SSN document. Field/
        // affirmation edits go through consultantFill's own FIELD_SECTION
        // check, so they stay locked regardless.
        if (docKey != null && allowed.contains(docKey)) return;
        throw new IllegalArgumentException(
                "This change is outside the section(s) currently open for editing.");
    }

    /** The section keys currently in the consultant's revision scope (empty = unrestricted). */
    private java.util.Set<String> parseRevisionSectionKeys(ConsultantApplication app) {
        return parseSectionScopeKeys(app.getRevisionSections());
    }

    /** Build P — the Phase 2 reopened-section keys (same JSON shape as the revision scope). */
    private java.util.Set<String> parsePhase2ReopenedKeys(ConsultantApplication app) {
        return parseSectionScopeKeys(app.getPhase2ReopenedSections());
    }

    /** Parse a {@code [{"key":"…"}, …]} section-scope JSON into its keys (empty on null/blank/garbage). */
    private java.util.Set<String> parseSectionScopeKeys(String json) {
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        if (json != null && !json.isBlank()) {
            try {
                JsonNode arr = objectMapper.readTree(json);
                if (arr.isArray()) {
                    for (JsonNode n : arr) {
                        String k = n.path("key").asText("");
                        if (!k.isBlank()) keys.add(k);
                    }
                }
            } catch (Exception ignored) { /* treat as unrestricted */ }
        }
        return keys;
    }

    /** Build P — serialize section keys into the {@code [{"key":"…"}, …]} scope JSON (appendix keys only). */
    private String buildSectionScopeJson(java.util.List<String> keys) {
        com.fasterxml.jackson.databind.node.ArrayNode arr = objectMapper.createArrayNode();
        if (keys != null) {
            for (String k : keys) {
                if (k != null && k.startsWith("appendix")) {
                    arr.add(objectMapper.createObjectNode().put("key", k));
                }
            }
        }
        return arr.toString();
    }

    /**
     * Build P — the section keys the consultant may currently WRITE, or an
     * empty {@link java.util.Optional} when unrestricted. A Build Y revision
     * round (status = REVISION_REQUESTED) takes precedence; otherwise a
     * Phase-2 fill (phase ≥ 2, status = SUBMITTED, scope persisted)
     * restricts writes to the ERM-reopened sections. Present-but-empty ⇒
     * nothing is writable (everything was completed in Phase 1).
     */
    private java.util.Optional<java.util.Set<String>> consultantWriteScope(ConsultantApplication app) {
        if (ConsultantApplication.Status.REVISION_REQUESTED.name().equals(app.getStatus())
                && app.getRevisionSections() != null && !app.getRevisionSections().isBlank()) {
            return java.util.Optional.of(parseRevisionSectionKeys(app));
        }
        Integer phase = app.getPhase();
        if (phase != null && phase >= 2
                && ConsultantApplication.Status.SUBMITTED.name().equals(app.getStatus())
                && app.getPhase2ReopenedSections() != null
                && !app.getPhase2ReopenedSections().isBlank()) {
            return java.util.Optional.of(parsePhase2ReopenedKeys(app));
        }
        return java.util.Optional.empty();
    }

    // ── 3B — role-based approval workflow ────────────────────────────

    /** Required approver gates for a coaching phase: P1={MANAGER}; P2={MANAGER,ACCOUNTS}. */
    private static java.util.List<com.spire.backend.entity.AgreementApproval.ApproverRole>
            requiredApprovers(int phase) {
        if (phase >= 2) {
            return java.util.List.of(
                    com.spire.backend.entity.AgreementApproval.ApproverRole.MANAGER,
                    com.spire.backend.entity.AgreementApproval.ApproverRole.ACCOUNTS);
        }
        return java.util.List.of(
                com.spire.backend.entity.AgreementApproval.ApproverRole.MANAGER);
    }

    /** All approval rows for an application (oldest first) — detail + certificate. */
    @Transactional(readOnly = true)
    public java.util.List<com.spire.backend.entity.AgreementApproval> listApprovals(
            String applicationId) {
        ConsultantApplication app = getByApplicationId(applicationId);
        return approvalRepository.findByApplicationIdOrderByCreatedAtAsc(app.getId());
    }

    /**
     * ERM "Send for Approval" (and re-send after a revision). Routes a
     * consultant-signed agreement to the phase's required approvers.
     * First send requires VERIFIED + the consultant version already
     * released; re-send fires from APPROVAL_REVISION_REQUESTED and RESETS
     * every required approver to PENDING for a fresh round.
     */
    @Transactional
    public ConsultantApplication sendForApproval(
            String applicationId,
            String managerUserId,
            String accountsUserId,
            Integer versionNumber,
            HttpServletRequest request) {
        ConsultantApplication app = getByApplicationId(applicationId);
        assertErmCanAccess(app, request);
        String st = app.getStatus();
        boolean firstSend = ConsultantApplication.Status.VERIFIED.name().equals(st);
        boolean resend = ConsultantApplication.Status.APPROVAL_REVISION_REQUESTED.name().equals(st);
        if (!firstSend && !resend) {
            throw new IllegalStateException(
                    "Send for Approval is only available from VERIFIED (after the "
                            + "consultant version is released) or APPROVAL_REVISION_REQUESTED "
                            + "(status=" + st + ").");
        }
        if (firstSend && !Boolean.TRUE.equals(app.getConsultantCopyReleased())) {
            throw new IllegalStateException(
                    "Release the consultant version (Approve consultant version) "
                            + "before sending for approval.");
        }
        int phase = app.getPhase() == null ? 1 : app.getPhase();
        Integer prevRound = approvalRepository.maxRound(app.getId());
        int round = (prevRound == null ? 0 : prevRound) + 1;
        var approvers = requiredApprovers(phase);

        // Build K — directed routing: resolve + validate the chosen approver
        // for each required role from the OWNER ERM's assigned set.
        String ownerErmId = app.getOwnerErmId();
        java.util.Map<com.spire.backend.entity.AgreementApproval.ApproverRole, AgreementUser> chosen =
                new java.util.HashMap<>();
        for (var role : approvers) {
            AgreementUserRole urole =
                    role == com.spire.backend.entity.AgreementApproval.ApproverRole.MANAGER
                            ? AgreementUserRole.MANAGER : AgreementUserRole.ACCOUNTS;
            String picked = urole == AgreementUserRole.MANAGER ? managerUserId : accountsUserId;
            java.util.List<AgreementUser> assigned =
                    assignmentService.assignedApprovers(ownerErmId, urole);
            if (assigned.isEmpty()) {
                throw new IllegalStateException(
                        "No " + urole.name().toLowerCase() + " assigned — ask an admin to assign one.");
            }
            if (picked == null || picked.isBlank()) {
                throw new IllegalArgumentException(
                        "Select a " + urole.name().toLowerCase() + " to send for approval.");
            }
            AgreementUser match = assigned.stream()
                    .filter(u -> u.getId().equals(picked)).findFirst().orElse(null);
            if (match == null) {
                // Distinguish "assigned but deactivated" from "never assigned"
                // (assigned* lists are active-only) so the message is actionable.
                if (assignmentService.assignedApproverIds(ownerErmId, urole).contains(picked)) {
                    throw new IllegalArgumentException(
                            "The selected " + urole.name().toLowerCase()
                                    + " is no longer active. Ask an admin to reactivate or assign another.");
                }
                throw new IllegalArgumentException(
                        "The selected " + urole.name().toLowerCase()
                                + " is not assigned to this ERM.");
            }
            chosen.put(role, match);
        }

        for (var role : approvers) {
            AgreementUser appr = chosen.get(role);
            approvalRepository.save(
                    com.spire.backend.entity.AgreementApproval.builder()
                            .applicationId(app.getId())
                            .role(role)
                            .status(com.spire.backend.entity.AgreementApproval.Decision.PENDING)
                            .phase(phase)
                            .round(round)
                            .approverUserId(appr.getId())
                            .approverName(appr.getFullName())
                            .build());
        }
        // Build V — resolve + record the consultant-version the ERM is sending
        // for approval (defaults to the latest). The approver reviews THIS
        // version's frozen snapshot. The consultant version is (re-)created
        // EXPLICITLY by the ERM via "Approve consultant version"
        // (ermApproveConsultantVersion); a revision resubmit clears
        // consultantCopyReleased (Build AH) so the ERM must re-approve — which
        // mints the next numbered version — before this send. So "the latest"
        // here is the just-released revised version.
        Integer selectedVersion = versionNumber;
        if (selectedVersion == null) {
            selectedVersion = agreementVersionRepository
                    .findTopByApplicationIdOrderByVersionNumberDesc(app.getId())
                    .map(com.spire.backend.entity.ConsultantAgreementVersion::getVersionNumber)
                    .orElse(null);
        } else if (agreementVersionRepository
                .findByApplicationIdAndVersionNumber(app.getId(), selectedVersion).isEmpty()) {
            throw new IllegalArgumentException(
                    "Selected version V" + selectedVersion + " was not found for this agreement.");
        }
        app.setApprovalVersionNumber(selectedVersion);
        app.setStatus(ConsultantApplication.Status.AWAITING_APPROVALS.name());
        applicationRepository.save(app);

        // Build T — email each ROUTED approver (Phase 1: the selected Manager;
        // Phase 2: the selected Manager + Accounts) that an agreement awaits
        // their approval. A re-send after a revision reuses this method, so it
        // re-notifies the relevant approver(s) for free. Best-effort per
        // recipient: a send failure is logged and never blocks the action, and
        // only the routed approver(s) — never the whole role pool — are emailed.
        String ermDisplayName = (ownerErmId == null) ? "your Sage IT contact"
                : agreementUserRepository.findById(ownerErmId)
                        .map(AgreementUser::getFullName)
                        .filter(n -> n != null && !n.isBlank())
                        .orElse("your Sage IT contact");
        for (var entry : chosen.entrySet()) {
            AgreementUser approver = entry.getValue();
            try {
                emailTemplateService.sendApprovalRequestNotification(
                        approver.getEmail(), approver.getFullName(),
                        app.getConsultantName(), ermDisplayName, phase);
            } catch (Exception e) {
                log.warn("Build T — approval-request email to the {} failed "
                        + "(send-for-approval unaffected): {}",
                        entry.getKey(), e.getMessage());
            }
        }

        appendEvent(app.getId(),
                ConsultantApplicationEvent.EventType.SENT_FOR_APPROVAL,
                ConsultantApplicationEvent.ActorType.ERM,
                AGREEMENT_ERM_USER_ID,
                Map.of("phase", phase, "round", round,
                        "approvers", approvers.stream().map(Enum::name).toList(),
                        "routedTo", chosen.values().stream()
                                .map(AgreementUser::getFullName).toList(),
                        "resend", resend,
                        "version", String.valueOf(selectedVersion)),
                request);
        return app;
    }

    /**
     * Build V — all immutable approved-consultant-version snapshots (V1, V2, …)
     * for an agreement, oldest → newest. ERM-gated (per-ERM isolation). Drives
     * the version history + the version selector at send-for-approval.
     */
    @Transactional(readOnly = true)
    public java.util.List<com.spire.backend.entity.ConsultantAgreementVersion> listAgreementVersions(
            String applicationId, HttpServletRequest request) {
        ConsultantApplication app = getByApplicationId(applicationId);
        assertErmCanAccess(app, request);
        return agreementVersionRepository.findByApplicationIdOrderByVersionNumberAsc(app.getId());
    }

    /**
     * Build V — the stored PDF bytes of a numbered consultant-version snapshot,
     * for the ERM's view-only preview. ERM-gated. The bytes come straight from
     * the version's immutable S3 object (no re-render).
     */
    @Transactional(readOnly = true)
    public byte[] agreementVersionPdf(
            String applicationId, int versionNumber, HttpServletRequest request) {
        ConsultantApplication app = getByApplicationId(applicationId);
        assertErmCanAccess(app, request);
        com.spire.backend.entity.ConsultantAgreementVersion v = agreementVersionRepository
                .findByApplicationIdAndVersionNumber(app.getId(), versionNumber)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "ConsultantAgreementVersion", "versionNumber", String.valueOf(versionNumber)));
        return agreementDocumentService.readStoredPdfBytes(v.getS3Key());
    }

    /**
     * Build V — the frozen snapshot bytes of the version routed for THIS
     * approval round ({@code app.approvalVersionNumber}), or {@code null} when
     * no version was routed (legacy rounds) so the caller can fall back to a
     * live render. Takes the already-authorized application (the approver gate
     * is enforced by the controller via {@code getForApprover}).
     */
    @Transactional(readOnly = true)
    public byte[] versionSnapshotBytes(ConsultantApplication app) {
        Integer vn = app.getApprovalVersionNumber();
        if (vn == null) return null;
        return agreementVersionRepository
                .findByApplicationIdAndVersionNumber(app.getId(), vn)
                .map(v -> agreementDocumentService.readStoredPdfBytes(v.getS3Key()))
                .orElse(null);
    }

    /**
     * Build AC — DESTRUCTIVE operator backfill. Revokes the ERM
     * countersignature on EVERY executed (COMPLETED) agreement and reverts it
     * to VERIFIED, so the FULL approval + countersign gate re-runs: the owning
     * ERM re-sends for approval (a fresh approver round), the approvers
     * re-approve, and the ERM re-countersigns — all via the normal pipeline, no
     * fabricated state. The consultant signature + the approval HISTORY are
     * preserved; only the ERM signature image + date are cleared.
     *
     * <p>This is consultant-visible: a COMPLETED agreement reverts to "awaiting
     * signature", so any "completed" copy already delivered is stale until the
     * ERM re-signs. The old ERM-signed PDFs are NOT deleted (they keep their old
     * timestamped S3 keys); the live final PDF is overwritten at re-sign.
     * {@code dryRun=true} reports the affected count without changing anything.
     *
     * <p>Not method-transactional: each record saves independently so one
     * failure doesn't roll back the rest.
     */
    public java.util.Map<String, Object> revokeErmSignaturesOnCompleted(
            boolean dryRun, HttpServletRequest request) {
        java.util.List<ConsultantApplication> completed = applicationRepository
                .findByStatusAndDeletedFalse(
                        ConsultantApplication.Status.COMPLETED.name(),
                        org.springframework.data.domain.Pageable.unpaged())
                .getContent();
        int processed = 0, reverted = 0, failed = 0;
        java.util.List<String> errors = new java.util.ArrayList<>();
        for (ConsultantApplication app : completed) {
            processed++;
            if (dryRun) continue;
            try {
                revokeOneErmSignature(app, request);
                reverted++;
            } catch (Exception e) {
                failed++;
                errors.add(app.getApplicationId() + ": " + e.getMessage());
                log.error("Build AC revoke-ERM-signature failed for {}: {}",
                        app.getApplicationId(), e.getMessage(), e);
            }
        }
        java.util.Map<String, Object> summary = new java.util.LinkedHashMap<>();
        summary.put("dryRun", dryRun);
        summary.put("status", "COMPLETED");
        summary.put("matched", completed.size());
        summary.put("processed", processed);
        summary.put("reverted", reverted);
        summary.put("failed", failed);
        summary.put("errors", errors);
        return summary;
    }

    /** Build AC — clear one agreement's ERM countersignature + revert to VERIFIED. */
    private void revokeOneErmSignature(ConsultantApplication app, HttpServletRequest request) {
        String fromStatus = app.getStatus();
        String oldSigKey = app.getErmSignatureS3Key();
        // Clear the ERM countersignature (image refs + date). ermName/ermTitle
        // stay as the re-sign prefill; the ${ermSignature*} placeholders render
        // blank again until the ERM re-countersigns.
        app.setErmSignatureS3Key(null);
        app.setErmSignatureUrl(null);
        app.setErmSignatureDate(null);
        // Revert so the whole gate re-runs (ERM re-sends → approvers re-approve
        // → ERM re-signs). The consultant signature + approval history are kept.
        app.setStatus(ConsultantApplication.Status.VERIFIED.name());
        applicationRepository.save(app);
        appendEvent(app.getId(),
                ConsultantApplicationEvent.EventType.ERM_SIGNATURE_REVOKED,
                ConsultantApplicationEvent.ActorType.ERM,
                AGREEMENT_ERM_USER_ID,
                Map.of("fromStatus", fromStatus == null ? "" : fromStatus,
                        "toStatus", ConsultantApplication.Status.VERIFIED.name(),
                        "clearedErmSignatureKey", oldSigKey == null ? "" : oldSigKey,
                        "reason", "re-sign-after-template-correction"),
                request);
    }

    /**
     * Build K — the approvers an ERM may route this agreement to: their
     * assigned, active MANAGER (always) + ACCOUNTS (Phase 2). Used to drive
     * the send-for-approval pickers.
     */
    @Transactional(readOnly = true)
    public java.util.Map<String, Object> eligibleApprovers(
            String applicationId, HttpServletRequest request) {
        ConsultantApplication app = getByApplicationId(applicationId);
        assertErmCanAccess(app, request);
        int phase = app.getPhase() == null ? 1 : app.getPhase();
        String ownerErmId = app.getOwnerErmId();
        java.util.function.Function<AgreementUser, java.util.Map<String, Object>> slim =
                u -> java.util.Map.of("id", u.getId(),
                        "name", u.getFullName() == null ? "" : u.getFullName(),
                        "email", u.getEmail() == null ? "" : u.getEmail());
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("phase", phase);
        out.put("managers", assignmentService.assignedApprovers(ownerErmId, AgreementUserRole.MANAGER)
                .stream().map(slim).toList());
        out.put("accounts", phase >= 2
                ? assignmentService.assignedApprovers(ownerErmId, AgreementUserRole.ACCOUNTS)
                        .stream().map(slim).toList()
                : java.util.List.of());
        return out;
    }

    /**
     * An approver (MANAGER / ACCOUNTS) decides on their gate.
     * {@code approve=true} → APPROVED (→ READY_TO_SIGN once ALL required
     * gates for the round are approved). Otherwise a revision request
     * (note required) → APPROVAL_REVISION_REQUESTED, surfacing the note to
     * the ERM via {@code currentRevisionRemarks}.
     */
    @Transactional
    public ConsultantApplication approverDecision(
            String applicationId,
            com.spire.backend.entity.AgreementApproval.ApproverRole role,
            boolean approve,
            String note,
            HttpServletRequest request) {
        ConsultantApplication app = getByApplicationId(applicationId);
        if (Boolean.TRUE.equals(app.getDeleted())) {
            throw new com.spire.backend.exception.ResourceNotFoundException(
                    "ConsultantApplication", "applicationId", applicationId);
        }
        if (!ConsultantApplication.Status.AWAITING_APPROVALS.name().equals(app.getStatus())) {
            throw new IllegalStateException(
                    "This agreement is not awaiting approvals (status="
                            + app.getStatus() + ").");
        }
        Integer round = approvalRepository.maxRound(app.getId());
        if (round == null) {
            throw new IllegalStateException("No approval round is open.");
        }
        com.spire.backend.entity.AgreementApproval row = approvalRepository
                .findFirstByApplicationIdAndRoleAndRound(app.getId(), role, round)
                // Build K — 404 (not 409) so it's indistinguishable from a gate
                // routed to a different approver (no gate-existence probing).
                .orElseThrow(() -> new com.spire.backend.exception.ResourceNotFoundException(
                        "ConsultantApplication", "applicationId", applicationId));
        if (row.getStatus() != com.spire.backend.entity.AgreementApproval.Decision.PENDING) {
            throw new IllegalStateException("This gate has already been decided.");
        }
        // Build K — when the gate is routed to a specific approver, only that
        // approver may decide it (null-approver rows stay role-wide).
        String deciderId = com.spire.backend.security.AgreementAuthz.userId(request);
        if (row.getApproverUserId() != null && !row.getApproverUserId().equals(deciderId)) {
            throw new com.spire.backend.exception.ResourceNotFoundException(
                    "ConsultantApplication", "applicationId", applicationId);
        }
        if (!approve && (note == null || note.isBlank())) {
            throw new IllegalArgumentException(
                    "A note is required when requesting a revision.");
        }

        String approverId = com.spire.backend.security.AgreementAuthz.userId(request);
        String approverName = approverId == null ? null
                : agreementUserRepository.findById(approverId)
                        .map(AgreementUser::getFullName).orElse(null);
        String ip = clientIp(request);
        row.setDecidedBy(approverId);
        row.setDecidedByName(approverName);
        row.setDecidedAt(LocalDateTime.now());
        row.setDecidedIp(ip);
        row.setNote(note);

        if (approve) {
            row.setStatus(com.spire.backend.entity.AgreementApproval.Decision.APPROVED);
            approvalRepository.save(row);
            appendEvent(app.getId(),
                    ConsultantApplicationEvent.EventType.APPROVAL_APPROVED,
                    ConsultantApplicationEvent.ActorType.ERM, null,
                    Map.of("role", role.name(), "round", round,
                            "approver", approverName == null ? "" : approverName,
                            "ip", ip == null ? "" : ip),
                    request);
            var rows = approvalRepository.findByApplicationIdAndRound(app.getId(), round);
            boolean allApproved = !rows.isEmpty() && rows.stream().allMatch(
                    r -> r.getStatus()
                            == com.spire.backend.entity.AgreementApproval.Decision.APPROVED);
            if (allApproved) {
                app.setStatus(ConsultantApplication.Status.READY_TO_SIGN.name());
                applicationRepository.save(app);
            }
        } else {
            row.setStatus(com.spire.backend.entity.AgreementApproval.Decision.REVISION_REQUESTED);
            approvalRepository.save(row);
            app.setStatus(ConsultantApplication.Status.APPROVAL_REVISION_REQUESTED.name());
            app.setCurrentRevisionRemarks("[" + role.name() + "] " + note);
            applicationRepository.save(app);
            appendEvent(app.getId(),
                    ConsultantApplicationEvent.EventType.APPROVAL_REVISION_REQUESTED,
                    ConsultantApplicationEvent.ActorType.ERM, null,
                    Map.of("role", role.name(), "round", round,
                            "approver", approverName == null ? "" : approverName,
                            "note", note),
                    request);
        }
        return app;
    }

    /**
     * The apps currently awaiting THIS approver role's gate (current round).
     * Build K — when {@code approverUserId} is non-null, the queue is
     * filtered to agreements routed specifically to that user; rows with a
     * null {@code approverUserId} (legacy/pre-Build-K) stay role-wide so
     * in-flight approvals are never stranded.
     */
    @Transactional(readOnly = true)
    public java.util.List<ConsultantApplication> approverQueue(
            com.spire.backend.entity.AgreementApproval.ApproverRole role,
            String approverUserId) {
        var pending = approvalRepository.findByStatusAndRole(
                com.spire.backend.entity.AgreementApproval.Decision.PENDING, role);
        java.util.List<ConsultantApplication> out = new java.util.ArrayList<>();
        for (var row : pending) {
            // Build K — directed routing: a routed row is visible only to its
            // chosen approver; a null-approver row is role-wide (fallback).
            if (row.getApproverUserId() != null
                    && !row.getApproverUserId().equals(approverUserId)) {
                continue;
            }
            ConsultantApplication app =
                    applicationRepository.findById(row.getApplicationId()).orElse(null);
            if (app == null || Boolean.TRUE.equals(app.getDeleted())) continue;
            if (!ConsultantApplication.Status.AWAITING_APPROVALS.name().equals(app.getStatus())) {
                continue;
            }
            Integer maxR = approvalRepository.maxRound(app.getId());
            if (maxR != null && !maxR.equals(row.getRound())) continue;
            out.add(app);
        }
        out.sort(java.util.Comparator.comparing(
                ConsultantApplication::getUpdatedAt,
                java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())));
        return out;
    }

    /**
     * Build AI — every distinct agreement ever routed to THIS approver in a
     * role, across all lifecycle statuses. Drives the approver dashboard's
     * "All agreements" status table (the read-only counterpart to the ERM's
     * owner-scoped list). Scope = the approver's own gate rows, so it exposes
     * nothing that wasn't sent to them; drafts/submissions never routed to them
     * stay hidden. The transient summary fields (managerStatus/accountsStatus/
     * sentForApprovalAt) + owner name are populated exactly as the ERM list, so
     * the frontend reuses the same row rendering. Newest activity first.
     */
    @Transactional(readOnly = true)
    public java.util.List<ConsultantApplication> approverApplications(
            com.spire.backend.entity.AgreementApproval.ApproverRole role,
            String approverUserId) {
        if (approverUserId == null || approverUserId.isBlank()) {
            return java.util.List.of();
        }
        // Distinct application ids this approver holds a gate for (any round).
        java.util.LinkedHashSet<Long> appIds = new java.util.LinkedHashSet<>();
        for (var row : approvalRepository.findByRoleAndApproverUserId(role, approverUserId)) {
            if (row.getApplicationId() != null) appIds.add(row.getApplicationId());
        }
        if (appIds.isEmpty()) return java.util.List.of();
        java.util.List<ConsultantApplication> apps = new java.util.ArrayList<>();
        for (Long id : appIds) {
            ConsultantApplication app = applicationRepository.findById(id).orElse(null);
            if (app == null || Boolean.TRUE.equals(app.getDeleted())) continue;
            apps.add(app);
        }
        populateOwnerNames(apps);
        populateApprovalSummary(apps);
        apps.sort(java.util.Comparator.comparing(
                ConsultantApplication::getUpdatedAt,
                java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())));
        return apps;
    }

    /**
     * Fetch an application for an approver's read-only preview/detail.
     * The approver may only see agreements where they hold a gate in the
     * current round; anything else 404s (so a token can't probe IDs).
     */
    @Transactional(readOnly = true)
    public ConsultantApplication getForApprover(
            String applicationId,
            com.spire.backend.entity.AgreementApproval.ApproverRole role,
            String approverUserId) {
        ConsultantApplication app = getByApplicationId(applicationId);
        if (Boolean.TRUE.equals(app.getDeleted())) {
            throw new com.spire.backend.exception.ResourceNotFoundException(
                    "ConsultantApplication", "applicationId", applicationId);
        }
        Integer round = approvalRepository.maxRound(app.getId());
        var gate = round == null ? java.util.Optional
                .<com.spire.backend.entity.AgreementApproval>empty()
                : approvalRepository.findFirstByApplicationIdAndRoleAndRound(app.getId(), role, round);
        // Build K — the routed approver (or any holder of the role when the
        // row is unrouted/legacy) may view; otherwise 404 (no ID probing).
        boolean canView = gate.isPresent()
                && (gate.get().getApproverUserId() == null
                    || gate.get().getApproverUserId().equals(approverUserId));
        if (!canView) {
            throw new com.spire.backend.exception.ResourceNotFoundException(
                    "ConsultantApplication", "applicationId", applicationId);
        }
        return app;
    }

    /**
     * Build AJ — gate for the approver "All agreements" latest-version preview.
     * Admits any approver who has EVER held a gate for this application in this
     * role (any round/status) — matching the scope of {@link #approverApplications}
     * (the status list the preview button lives on), which is broader than
     * {@link #getForApprover}'s current-round check. 404 (no ID probing) when the
     * caller was never routed this agreement.
     */
    @Transactional(readOnly = true)
    public ConsultantApplication getForApproverAnyRound(
            String applicationId,
            com.spire.backend.entity.AgreementApproval.ApproverRole role,
            String approverUserId) {
        ConsultantApplication app = getByApplicationId(applicationId);
        if (Boolean.TRUE.equals(app.getDeleted())) {
            throw new com.spire.backend.exception.ResourceNotFoundException(
                    "ConsultantApplication", "applicationId", applicationId);
        }
        boolean routed = approverUserId != null && !approverUserId.isBlank()
                && approvalRepository.existsByApplicationIdAndRoleAndApproverUserId(
                        app.getId(), role, approverUserId);
        if (!routed) {
            throw new com.spire.backend.exception.ResourceNotFoundException(
                    "ConsultantApplication", "applicationId", applicationId);
        }
        return app;
    }

    /**
     * Build AJ — bytes of the LATEST approved consultant-version snapshot (the
     * highest V#), or {@code null} when the agreement has no version yet (legacy
     * rounds), so the caller falls back to a live pre-sign render. Unlike
     * {@link #versionSnapshotBytes} (the version routed for THIS round), this
     * always returns the newest, so the approver preview shows the current
     * agreement regardless of which version a past round reviewed.
     */
    @Transactional(readOnly = true)
    public byte[] latestVersionSnapshotBytes(ConsultantApplication app) {
        return agreementVersionRepository
                .findTopByApplicationIdOrderByVersionNumberDesc(app.getId())
                .map(v -> agreementDocumentService.readStoredPdfBytes(v.getS3Key()))
                .orElse(null);
    }

    /**
     * Build S — gate for the durable Phase-1 signed-agreement preview. Unlike
     * {@link #getForApprover} (which gates on the CURRENT round's routed
     * approver), this admits any MANAGER who APPROVED this agreement (any
     * round) — so the original Phase-1 Manager keeps Phase-1 view rights even
     * when a different Manager is routed for Phase 2. Matches exactly when the
     * "Phase 1 signed" button is shown (the app is in their Approved Documents).
     * 404 (no ID probing) when the caller never approved it as Manager.
     */
    @Transactional(readOnly = true)
    public ConsultantApplication getForManagerPhase1Preview(
            String applicationId, String managerUserId) {
        return getForApproverWhoApproved(
                applicationId,
                com.spire.backend.entity.AgreementApproval.ApproverRole.MANAGER,
                managerUserId);
    }

    /**
     * Build AK — gate for an approver's own copy of an agreement: admits
     * exactly the approvers whose "Approved agreements" record contains it,
     * i.e. anyone holding an APPROVED decision on it in this role, any round.
     * That is the same set {@link #approverApprovedRecords} lists, so every row
     * in the record stays actionable — including one approved in an earlier
     * round the approver is no longer routed for (the reasoning
     * {@link #getForManagerPhase1Preview} already made for Phase 1, generalized
     * to either gate). 404 (no ID probing) when the caller never approved it.
     */
    @Transactional(readOnly = true)
    public ConsultantApplication getForApproverWhoApproved(
            String applicationId,
            com.spire.backend.entity.AgreementApproval.ApproverRole role,
            String approverUserId) {
        ConsultantApplication app = getByApplicationId(applicationId);
        if (Boolean.TRUE.equals(app.getDeleted())) {
            throw new com.spire.backend.exception.ResourceNotFoundException(
                    "ConsultantApplication", "applicationId", applicationId);
        }
        boolean approved = approverUserId != null && !approverUserId.isBlank()
                && approvalRepository.existsByApplicationIdAndRoleAndStatusAndDecidedBy(
                        app.getId(),
                        role,
                        com.spire.backend.entity.AgreementApproval.Decision.APPROVED,
                        approverUserId);
        if (!approved) {
            throw new com.spire.backend.exception.ResourceNotFoundException(
                    "ConsultantApplication", "applicationId", applicationId);
        }
        return app;
    }

    /**
     * Build L — read-only record of agreements THIS approver has approved
     * (one row per agreement, the latest APPROVED decision), with the
     * originating ERM resolved. The Accounts dashboard groups these by ERM;
     * the Manager dashboard shows a flat list. Deleted agreements are
     * dropped; the "status" reflects the agreement's CURRENT state.
     */
    @Transactional(readOnly = true)
    public java.util.List<java.util.Map<String, Object>> approverApprovedRecords(
            com.spire.backend.entity.AgreementApproval.ApproverRole role,
            String approverUserId) {
        if (approverUserId == null || approverUserId.isBlank()) {
            return java.util.List.of();
        }
        var approved = approvalRepository
                .findByStatusAndRoleAndDecidedByOrderByDecidedAtDesc(
                        com.spire.backend.entity.AgreementApproval.Decision.APPROVED,
                        role, approverUserId);
        java.util.Set<Long> seen = new java.util.HashSet<>();
        java.util.Map<String, String> ermNameCache = new java.util.HashMap<>();
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        for (var row : approved) {
            if (!seen.add(row.getApplicationId())) continue; // latest only (ordered desc)
            ConsultantApplication app =
                    applicationRepository.findById(row.getApplicationId()).orElse(null);
            if (app == null || Boolean.TRUE.equals(app.getDeleted())) continue;
            String ermId = app.getOwnerErmId();
            String ermName = ermId == null ? null : ermNameCache.computeIfAbsent(ermId,
                    id -> agreementUserRepository.findById(id)
                            .map(AgreementUser::getFullName).orElse(null));
            java.util.Map<String, Object> rec = new java.util.LinkedHashMap<>();
            rec.put("appId", app.getApplicationId());
            rec.put("consultantName", app.getConsultantName());
            rec.put("consultantEmail", app.getConsultantEmail());
            rec.put("ermId", ermId);
            rec.put("ermName", ermName == null || ermName.isBlank()
                    ? "(unassigned ERM)" : ermName);
            rec.put("phase", row.getPhase());
            rec.put("decidedAt", row.getDecidedAt() == null ? null : row.getDecidedAt().toString());
            rec.put("status", app.getStatus());
            // Build S — does a durable Phase-1 signed agreement exist to preview?
            // MANAGER only (Accounts approves Phase 2 only and never sees it).
            rec.put("hasPhase1Signed",
                    role == com.spire.backend.entity.AgreementApproval.ApproverRole.MANAGER
                    && app.getPhase1FinalPdfS3Key() != null
                    && !app.getPhase1FinalPdfS3Key().isBlank());
            out.add(rec);
        }
        return out;
    }

    /**
     * 3B — ERM status board: every agreement currently in an approval
     * state ({@code AWAITING_APPROVALS} / {@code APPROVAL_REVISION_REQUESTED}
     * / {@code READY_TO_SIGN}), each with its full approval history. The
     * super-admin sees all; an ERM sees only their own (per-ERM isolation).
     * The frontend splits these into Phase 1 / Phase 2 boards.
     */
    @Transactional(readOnly = true)
    public java.util.List<java.util.Map<String, Object>> approvalBoard(
            String ownerErmId, com.spire.backend.entity.AgreementUserRole role) {
        java.util.List<String> states = java.util.List.of(
                ConsultantApplication.Status.AWAITING_APPROVALS.name(),
                ConsultantApplication.Status.APPROVAL_REVISION_REQUESTED.name(),
                ConsultantApplication.Status.READY_TO_SIGN.name());
        java.util.List<ConsultantApplication> apps =
                (role == com.spire.backend.entity.AgreementUserRole.SUPER_ADMIN)
                        ? applicationRepository
                                .findByStatusInAndDeletedFalseOrderByUpdatedAtDesc(states)
                        : applicationRepository
                                .findByOwnerErmIdAndStatusInAndDeletedFalseOrderByUpdatedAtDesc(
                                        ownerErmId, states);
        // The board groups its rows under the owning ERM, and ownerName is a
        // @Transient field nobody fills unless asked -- without this every row
        // would serialize ownerName:null and the grouping would collapse into
        // one "Unassigned" pile. One batched lookup, same as listAllForAdmin
        // and approverApplications. Harmless on the ERM-scoped path (a single
        // owner id resolves to a single row).
        populateOwnerNames(apps);
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        for (ConsultantApplication app : apps) {
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("application", app);
            row.put("approvals",
                    approvalRepository.findByApplicationIdOrderByCreatedAtAsc(app.getId()));
            out.add(row);
        }
        return out;
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
        // 3B — the ERM countersign is now gated behind approvals. The
        // agreement must be READY_TO_SIGN (every required approver for the
        // current phase has approved).
        if (!ConsultantApplication.Status.READY_TO_SIGN.name().equals(app.getStatus())) {
            throw new IllegalStateException(
                    "Only READY_TO_SIGN applications can be countersigned "
                            + "(status=" + app.getStatus() + "). All required "
                            + "approvals must be in first.");
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

        String ermSigS3Key;
        try {
            ermSigS3Key = storeSignatureToS3(ermSignatureBase64, "erm", app);
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
        app.setErmSignatureS3Key(ermSigS3Key);
        app.setErmSignatureUrl(null);   // Phase 5 — new ERM signature on S3
        // Build W — stamp the ERM's OWN countersign date (no longer
        // overwrites the consultant's signatureDate). The ERM "Date:"
        // line stays blank until exactly this moment.
        app.setErmSignatureDate(now);
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
            // Phase 1 (S3) — persist the S3 key; the Cloudinary fields stay
            // null for new records (download/email paths dual-read on s3_key).
            app.setS3Key(pdf.publicId());
            // Build S — at the PHASE-1 countersign, ALSO snapshot this final PDF
            // under a durable key the Phase-2 countersign won't overwrite (it
            // rewrites only s3Key) and advanceToPhase2 won't clear, so the
            // Manager can still preview the Phase-1 signed agreement after the
            // advance to Phase 2. buildAgreementPdfKey already embeds the phase,
            // so this is the distinct .../phase-1/final-{ts}.pdf object.
            Integer ermSignPhase = app.getPhase();
            if (ermSignPhase == null || ermSignPhase == 1) {
                app.setPhase1FinalPdfS3Key(pdf.publicId());
            }
            applicationRepository.save(app);
            appendEvent(app.getId(),
                    ConsultantApplicationEvent.EventType.PDF_GENERATED,
                    ConsultantApplicationEvent.ActorType.SYSTEM, null,
                    Map.of("s3Key", pdf.publicId() == null ? "" : pdf.publicId(),
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

        String s3Key;
        try {
            // Phase 1 (S3) — store the consultant-version PDF in S3. Cloudinary
            // uploadPdfBytes is kept intact for old records / Phase 2 migration.
            s3Key = agreementDocumentService.storeConsultantVersionPdf(app, release.bytes());
        } catch (Exception e) {
            log.error("S3 upload of consultant-version failed for {}: {}",
                    applicationId, e.getMessage(), e);
            throw new IllegalStateException(
                    "Couldn't store consultant-version PDF: " + e.getMessage(), e);
        }

        LocalDateTime now = LocalDateTime.now();
        // Phase 1 (S3) — persist the S3 key; consultantPdfPublicId (Cloudinary)
        // stays null for new records.
        app.setConsultantPdfS3Key(s3Key);
        app.setDocumentHash(release.sha256Hex());
        app.setConsultantCopyReleased(true);
        app.setConsultantCopyReleasedAt(now);
        // released_by uses the same sentinel/marker as the rest of the
        // ERM flow; resolveActorErmId returns the auth'd user id when
        // present, falling back to the global agreement-erm sentinel.
        app.setConsultantCopyReleasedBy(resolveActorErmId(request));
        applicationRepository.save(app);

        // Build V — snapshot this approval as the next immutable numbered
        // version (V1, V2, …). The consultant-version PDF already lives under a
        // unique, timestamped S3 key, so prior versions are never overwritten.
        int nextVersion = agreementVersionRepository
                .findTopByApplicationIdOrderByVersionNumberDesc(app.getId())
                .map(v -> v.getVersionNumber() + 1)
                .orElse(1);
        agreementVersionRepository.save(
                com.spire.backend.entity.ConsultantAgreementVersion.builder()
                        .applicationId(app.getId())
                        .versionNumber(nextVersion)
                        .s3Key(s3Key)
                        .documentHash(release.sha256Hex())
                        .phase(app.getPhase())
                        .build());

        appendEvent(app.getId(),
                ConsultantApplicationEvent.EventType.CONSULTANT_VERSION_APPROVED,
                ConsultantApplicationEvent.ActorType.ERM,
                AGREEMENT_ERM_USER_ID,
                Map.of(
                        "s3Key", s3Key,
                        "documentHash", release.sha256Hex(),
                        "version", String.valueOf(nextVersion),
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
        // Phase 1 (S3) — released copy lives in S3 (consultantPdfS3Key) for new
        // records, or Cloudinary (consultantPdfPublicId) for old ones.
        boolean copyStored = (app.getConsultantPdfS3Key() != null
                        && !app.getConsultantPdfS3Key().isBlank())
                || (app.getConsultantPdfPublicId() != null
                        && !app.getConsultantPdfPublicId().isBlank());
        if (!Boolean.TRUE.equals(app.getConsultantCopyReleased()) || !copyStored) {
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

        // Fetch the bytes through a short-lived URL — S3 presigned (new
        // records) or Cloudinary signed (old records).
        String url = agreementDocumentService.consultantPdfSourceUrl(
                app, java.time.Duration.ofMinutes(5));
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
        // Build U — quick stat the ERM checks at a glance + audit row
        // with timestamp + IP for the activity timeline.
        int previousCount = app.getConsultantDownloadCount() == null
                ? 0 : app.getConsultantDownloadCount();
        LocalDateTime downloadedAt = LocalDateTime.now();
        app.setConsultantDownloadCount(previousCount + 1);
        app.setConsultantLastDownloadedAt(downloadedAt);
        applicationRepository.save(app);
        appendEvent(app.getId(),
                ConsultantApplicationEvent.EventType.CONSULTANT_DOWNLOAD,
                ConsultantApplicationEvent.ActorType.CONSULTANT, null,
                Map.of("ip", ip == null ? "" : ip,
                        "bytes", String.valueOf(bytes.length),
                        "downloadCount", String.valueOf(previousCount + 1),
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

        // Build P — Phase 2 lock. Completed Phase 1 content stays
        // IMMUTABLE: preserve the PRIMARY signature + legal name and every
        // already-given affirmation (main agreement, exhibits, and any
        // appendix NOT being reopened). The consultant re-signs only the
        // FINAL execution block and re-affirms only the reopened sections.
        // (Mirrors the Build Y restricted-revision signing model, which
        // reuses the persisted primary signature and re-captures the final
        // — see consultantSubmit's hasExistingPrimary handling.)
        app.setFinalSignatureImage(null);
        app.setFinalSignatureS3Key(null);   // Phase 5 — clear the S3 pointer too
        app.setFinalSignedAt(null);
        app.setFinalSigningIp(null);
        // Clear affirmations ONLY for the reopened (newly-required)
        // appendices, so the consultant re-affirms exactly those; locked
        // Phase-1 sections keep their original affirmation.
        if (p1) app.setAffirmedAppendix1(false);
        if (p2) app.setAffirmedAppendix2(false);
        if (p3) app.setAffirmedAppendix3(false);
        if (p4) app.setAffirmedAppendix4(false);
        if (p5) app.setAffirmedAppendix5(false);

        // Build P — persist the reopened-section scope (the promoted
        // appendices). The consultant wizard + the backend write-gate
        // restrict Phase 2 to exactly these sections + the final sign
        // step; everything else completed in Phase 1 is read-only/hidden.
        app.setPhase2ReopenedSections(buildSectionScopeJson(promoted));

        // Clear the Phase-1 ERM countersignature record + the final
        // PDF pointers. ermApproveAndSign regenerates both at Phase 2
        // countersign.
        app.setErmName(null);
        app.setErmTitle(null);
        app.setErmSignatureUrl(null);
        app.setErmSignatureS3Key(null);   // Phase 5 — clear the S3 pointer too
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
        // Phase 1 (S3) — the final PDF is "available" when stored in S3
        // (s3Key) OR Cloudinary (finalPdfPublicId/finalPdfUrl).
        boolean finalPdfStored = (app.getS3Key() != null && !app.getS3Key().isBlank())
                || app.getFinalPdfPublicId() != null
                || (app.getFinalPdfUrl() != null && !app.getFinalPdfUrl().isBlank());
        if (!ConsultantApplication.Status.COMPLETED.name().equals(app.getStatus())
                || !finalPdfStored) {
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
        // Build W — structured name (consultant may correct the spelling
        // on the cover step). consultant_name is recomposed in
        // consultantFill from these.
        public String firstName;
        public String middleName;
        public String lastName;
        public String primaryPhone;
        public String workAuthorizationCategory;
        public String residenceAddress;
        // Build W — structured US billing address.
        public String addressLine1;
        public String addressLine2;
        public String addressCity;
        public String addressState;
        public String addressZip;
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
        // Build U — ERM-only (consultant-read-only). Still deserialized for
        // shape stability, but NEVER applied or counted as touched from a
        // consultant patch (see applyTo + touchedFieldNames), so a crafted
        // /fill cannot overwrite the ERM-set ACH debit schedule.
        public String achDebitDates;
        public String achDebitAmounts;
        public String bgFullLegalName;
        public String bgOtherNamesUsed;
        public String bgCurrentAddress;
        // Build J — structured current address + same-as-residence toggle.
        public String bgCurrentAddressLine1;
        public String bgCurrentAddressLine2;
        public String bgCurrentAddressCity;
        public String bgCurrentAddressState;
        public String bgCurrentAddressZip;
        public Boolean bgCurrentSameAsResidence;
        public java.time.LocalDate bgDateOfBirth;
        public String bgFullSsn;
        public String bgDriverLicense;
        // Build AK — Appendix 3 State-ID number (DL number + State-ID number).
        public String bgStateId;
        public String portalPlatform;
        public String portalUsername;
        // Build J — repeatable platform+username entries (JSON-in-TEXT).
        public String portalEntries;
        // Build Z — portalAuthorizedActions + portalRevocationContact are now
        // ERM-set (create-time), read-only to the consultant, so they are NOT
        // accepted on the consultant fill patch (mirrors ACH debit).
        public java.time.LocalDate portalEffectiveDate;
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
            if (firstName != null)                { app.setFirstName(firstName); changed = true; }
            if (middleName != null)               { app.setMiddleName(middleName); changed = true; }
            if (lastName != null)                 { app.setLastName(lastName); changed = true; }
            if (primaryPhone != null)             { app.setPrimaryPhone(primaryPhone); changed = true; }
            if (workAuthorizationCategory != null){ app.setWorkAuthorizationCategory(workAuthorizationCategory); changed = true; }
            if (residenceAddress != null)         { app.setResidenceAddress(residenceAddress); changed = true; }
            if (addressLine1 != null)             { app.setAddressLine1(addressLine1); changed = true; }
            if (addressLine2 != null)             { app.setAddressLine2(addressLine2); changed = true; }
            if (addressCity != null)              { app.setAddressCity(addressCity); changed = true; }
            if (addressState != null)             { app.setAddressState(addressState); changed = true; }
            if (addressZip != null)               { app.setAddressZip(addressZip); changed = true; }
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
            // Build U — achDebitDates/achDebitAmounts are ERM-only; deliberately
            // NOT applied from a consultant patch (read-only enforced server-side;
            // the ERM corrects them via the revision-request action instead).
            if (bgFullLegalName != null)          { app.setBgFullLegalName(bgFullLegalName); changed = true; }
            if (bgOtherNamesUsed != null)         { app.setBgOtherNamesUsed(bgOtherNamesUsed); changed = true; }
            if (bgCurrentAddress != null)         { app.setBgCurrentAddress(bgCurrentAddress); changed = true; }
            if (bgCurrentAddressLine1 != null)    { app.setBgCurrentAddressLine1(bgCurrentAddressLine1); changed = true; }
            if (bgCurrentAddressLine2 != null)    { app.setBgCurrentAddressLine2(bgCurrentAddressLine2); changed = true; }
            if (bgCurrentAddressCity != null)     { app.setBgCurrentAddressCity(bgCurrentAddressCity); changed = true; }
            if (bgCurrentAddressState != null)    { app.setBgCurrentAddressState(bgCurrentAddressState); changed = true; }
            if (bgCurrentAddressZip != null)      { app.setBgCurrentAddressZip(bgCurrentAddressZip); changed = true; }
            if (bgCurrentSameAsResidence != null) { app.setBgCurrentSameAsResidence(bgCurrentSameAsResidence); changed = true; }
            if (bgDateOfBirth != null)            { app.setBgDateOfBirth(bgDateOfBirth); changed = true; }
            if (bgFullSsn != null)                { app.setBgFullSsn(bgFullSsn); changed = true; }
            if (bgDriverLicense != null)          { app.setBgDriverLicense(bgDriverLicense); changed = true; }
            if (bgStateId != null)                { app.setBgStateId(bgStateId); changed = true; }
            if (portalPlatform != null)           { app.setPortalPlatform(portalPlatform); changed = true; }
            if (portalUsername != null)           { app.setPortalUsername(portalUsername); changed = true; }
            if (portalEntries != null)            { app.setPortalEntries(portalEntries); changed = true; }
            if (portalEffectiveDate != null)      { app.setPortalEffectiveDate(portalEffectiveDate); changed = true; }
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
            if (firstName != null) names.add("firstName");
            if (middleName != null) names.add("middleName");
            if (lastName != null) names.add("lastName");
            if (primaryPhone != null) names.add("primaryPhone");
            if (workAuthorizationCategory != null) names.add("workAuthorizationCategory");
            if (residenceAddress != null) names.add("residenceAddress");
            if (addressLine1 != null) names.add("addressLine1");
            if (addressLine2 != null) names.add("addressLine2");
            if (addressCity != null) names.add("addressCity");
            if (addressState != null) names.add("addressState");
            if (addressZip != null) names.add("addressZip");
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
            // Build U — ACH debit fields are ERM-only; not consultant-touchable.
            if (bgFullLegalName != null) names.add("bgFullLegalName");
            if (bgOtherNamesUsed != null) names.add("bgOtherNamesUsed");
            if (bgCurrentAddress != null) names.add("bgCurrentAddress");
            if (bgCurrentAddressLine1 != null) names.add("bgCurrentAddressLine1");
            if (bgCurrentAddressLine2 != null) names.add("bgCurrentAddressLine2");
            if (bgCurrentAddressCity != null) names.add("bgCurrentAddressCity");
            if (bgCurrentAddressState != null) names.add("bgCurrentAddressState");
            if (bgCurrentAddressZip != null) names.add("bgCurrentAddressZip");
            if (bgCurrentSameAsResidence != null) names.add("bgCurrentSameAsResidence");
            if (bgDateOfBirth != null) names.add("bgDateOfBirth");
            if (bgFullSsn != null) names.add("bgFullSsn");
            if (bgDriverLicense != null) names.add("bgDriverLicense");
            if (bgStateId != null) names.add("bgStateId");
            if (portalPlatform != null) names.add("portalPlatform");
            if (portalUsername != null) names.add("portalUsername");
            if (portalEntries != null) names.add("portalEntries");
            if (portalEffectiveDate != null) names.add("portalEffectiveDate");
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
        assertUploadWritable(app, "appendix5", "doc:cheque"); // Build Y (B5) / AK
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
        // Phase 5 — store to S3; leave the Cloudinary cheque id null for new
        // records (reads dual-branch on cheque_s3_key). isImage/isPdf above
        // are retained for validation only.
        String oldPublicId = app.getChequePublicId();
        String oldContentType = app.getChequeContentType();
        String s3Key = agreementUploadKey(app, "cheque", normalisedType);
        try {
            documentStorage.store(bytes, s3Key, normalisedType);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Couldn't store cheque: " + e.getMessage(), e);
        }
        app.setChequeS3Key(s3Key);
        app.setChequePublicId(null);
        app.setChequeContentType(normalisedType);
        app.setChequeUploadedAt(LocalDateTime.now());
        applicationRepository.save(app);
        destroyCloudinaryDoc(oldPublicId, oldContentType);

        appendEvent(app.getId(),
                ConsultantApplicationEvent.EventType.CHEQUE_UPLOADED,
                ConsultantApplicationEvent.ActorType.CONSULTANT, null,
                Map.of("s3Key", s3Key,
                        "contentType", normalisedType,
                        "bytes", bytes.length),
                request);
        return app;
    }

    /** Build N — best-effort delete of a superseded authenticated Cloudinary doc. */
    private void destroyCloudinaryDoc(String publicId, String contentType) {
        if (publicId == null || publicId.isBlank()) return;
        boolean isPdf = "application/pdf".equalsIgnoreCase(contentType);
        try {
            cloudinary.uploader().destroy(publicId,
                    com.cloudinary.utils.ObjectUtils.asMap(
                            "resource_type", isPdf ? "raw" : "image",
                            "type", "authenticated",
                            "invalidate", true));
        } catch (Exception ignored) {
            // Orphan cleanup is best-effort; never block an upload on it.
        }
    }

    // ── Phase 5 (S3) — agreements upload + signature storage helpers ──
    //
    // New consultant uploads and signatures go to S3 via documentStorage;
    // reads dual-branch on the new *_s3_key column (present → S3 bytes; else
    // the untouched authenticated-Cloudinary path). Scoped to these
    // agreements-only methods — the shared Cloudinary bean is untouched for
    // vault/media/video/thumbnail callers.

    private static final java.time.format.DateTimeFormatter S3_KEY_TS =
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** File extension for an S3 key, derived from the upload's content type. */
    private static String extFor(String contentType) {
        if (contentType == null) return "bin";
        String t = contentType.toLowerCase();
        if (t.equals("application/pdf")) return "pdf";
        if (t.equals("image/jpeg") || t.equals("image/jpg")) return "jpg";
        if (t.equals("image/png")) return "png";
        if (t.equals("image/heic") || t.equals("image/heif")) return "heic";
        if (t.startsWith("image/")) {
            String sub = t.substring("image/".length()).replaceAll("[^a-z0-9]", "");
            return sub.isEmpty() ? "img" : sub;
        }
        return "bin";
    }

    /** ERM-id path segment for S3 keys (falls back to "system" for un-owned rows). */
    private static String ermSegment(ConsultantApplication app) {
        String erm = app.getOwnerErmId();
        return (erm == null || erm.isBlank()) ? "system" : erm;
    }

    /**
     * Per-APPLICATION key segment. One ERM owns many applications, so the key
     * MUST include the application id — otherwise two consultants under the
     * same ERM uploading the same doc-type in the same second would collide
     * and overwrite each other's object.
     */
    private static String appSegment(ConsultantApplication app) {
        if (app.getId() != null) return String.valueOf(app.getId());
        String aid = app.getApplicationId();
        return (aid == null || aid.isBlank()) ? "app" : aid;
    }

    /** Short random suffix so re-uploads within the same second never collide. */
    private static String rand() {
        return Long.toHexString(
                java.util.concurrent.ThreadLocalRandom.current().nextLong() & 0xffffffffL);
    }

    /** S3 key for a consultant upload: agreements/{ermId}/uploads/{appId}-{docType}-{ts}-{rand}.{ext}. */
    private String agreementUploadKey(ConsultantApplication app, String docType, String contentType) {
        return "agreements/" + ermSegment(app) + "/uploads/" + appSegment(app) + "-" + docType + "-"
                + LocalDateTime.now().format(S3_KEY_TS) + "-" + rand() + "." + extFor(contentType);
    }

    /** S3 key for a signature image (always normalised PNG): agreements/{ermId}/signatures/{appId}-{role}-{ts}-{rand}.png. */
    private String agreementSignatureKey(ConsultantApplication app, String role) {
        return "agreements/" + ermSegment(app) + "/signatures/" + appSegment(app) + "-" + role + "-"
                + LocalDateTime.now().format(S3_KEY_TS) + "-" + rand() + ".png";
    }

    /**
     * Authenticated Cloudinary fetch of an agreements upload by public_id —
     * the legacy read path, reused by the dual-read fallback and the upload
     * migration. Re-signs on every call (the per-upload URL 401s later).
     */
    byte[] cloudinaryAuthBytes(String publicId, String contentType) throws java.io.IOException {
        boolean isPdf = "application/pdf".equalsIgnoreCase(contentType);
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
            return in.readAllBytes();
        }
    }

    /**
     * Dual-read for a consultant upload: S3 when {@code s3Key} is set (Phase 5
     * records), else the untouched authenticated-Cloudinary path. Returns null
     * when neither pointer is present (no upload on file).
     */
    private ChequeBytes readAgreementDoc(String s3Key, String cloudinaryPublicId, String contentType)
            throws java.io.IOException {
        if (s3Key != null && !s3Key.isBlank()) {
            try {
                return new ChequeBytes(documentStorage.get(s3Key), contentType);
            } catch (software.amazon.awssdk.services.s3.model.NoSuchKeyException e) {
                return null; // missing object → 404 via the controller's null guard
            } catch (software.amazon.awssdk.core.exception.SdkException e) {
                // The S3 SDK throws UNCHECKED exceptions; without this the
                // controller's IOException catch is bypassed → HTTP 500. Map to
                // IOException to preserve the existing 502 (BAD_GATEWAY) path.
                throw new java.io.IOException("S3 read failed", e);
            }
        }
        if (cloudinaryPublicId == null || cloudinaryPublicId.isBlank()) return null;
        return new ChequeBytes(cloudinaryAuthBytes(cloudinaryPublicId, contentType), contentType);
    }

    /**
     * Build W — Appendix 1 work-authorization document upload. Validates
     * type/size, stores the bytes in Cloudinary (type=authenticated) under a
     * Build-N unique public_id, persists the public_id + content type +
     * timestamp, and cleans up the prior asset.
     */
    @Transactional
    public ConsultantApplication uploadWorkAuthDoc(
            String applicationId,
            byte[] bytes,
            String contentType,
            HttpServletRequest request) {
        ConsultantApplication app = getByApplicationId(applicationId);
        // Build I — the work-auth doc now lives in Personal Information (cover).
        assertUploadWritable(app, "cover", "doc:workauth"); // Build Y (B5) / AK
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Work-authorization file is empty.");
        }
        if (bytes.length > MAX_CHEQUE_BYTES) {
            throw new IllegalArgumentException(
                    "Work-authorization file is too large (>10 MB).");
        }
        String normalisedType = contentType == null ? "" : contentType.toLowerCase();
        boolean isImage = normalisedType.startsWith("image/");
        boolean isPdf = normalisedType.equals("application/pdf");
        if (!isImage && !isPdf) {
            throw new IllegalArgumentException(
                    "Work-authorization document must be an image (JPG/PNG/HEIC) or PDF.");
        }
        String oldPublicId = app.getWorkAuthDocPublicId();
        String oldContentType = app.getWorkAuthDocContentType();
        String s3Key = agreementUploadKey(app, "workauth", normalisedType);
        try {
            documentStorage.store(bytes, s3Key, normalisedType);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Couldn't store work-authorization document: " + e.getMessage(), e);
        }
        app.setWorkAuthDocS3Key(s3Key);
        app.setWorkAuthDocPublicId(null);
        app.setWorkAuthDocContentType(normalisedType);
        app.setWorkAuthDocUploadedAt(LocalDateTime.now());
        applicationRepository.save(app);
        destroyCloudinaryDoc(oldPublicId, oldContentType);

        appendEvent(app.getId(),
                ConsultantApplicationEvent.EventType.WORK_AUTH_UPLOADED,
                ConsultantApplicationEvent.ActorType.CONSULTANT, null,
                Map.of("s3Key", s3Key,
                        "contentType", normalisedType,
                        "bytes", bytes.length),
                request);
        return app;
    }

    /**
     * Build W — streams the bytes of the uploaded work-authorization
     * document, re-signing the delivery URL on every call (same pattern
     * as the cheque / final PDF). Returns null when none uploaded.
     */
    public ChequeBytes fetchWorkAuthDocBytes(String applicationId) throws java.io.IOException {
        ConsultantApplication app = getByApplicationId(applicationId);
        return readAgreementDoc(app.getWorkAuthDocS3Key(), app.getWorkAuthDocPublicId(),
                app.getWorkAuthDocContentType());
    }

    /**
     * Build I — Phase 2 Employment offer-letter upload. Mirrors
     * {@link #uploadWorkAuthDoc}: validates type/size, stores at
     * {@code agreements/{appId}-offerletter} (type=authenticated), persists
     * the public_id + content type + timestamp. Latest replaces prior.
     */
    @Transactional
    public ConsultantApplication uploadOfferLetter(
            String applicationId,
            byte[] bytes,
            String contentType,
            HttpServletRequest request) {
        ConsultantApplication app = getByApplicationId(applicationId);
        assertUploadWritable(app, "appendix1", "doc:offer-letter"); // Build Y (B5) / AK
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Offer letter file is empty.");
        }
        if (bytes.length > MAX_CHEQUE_BYTES) {
            throw new IllegalArgumentException("Offer letter file is too large (>10 MB).");
        }
        String normalisedType = contentType == null ? "" : contentType.toLowerCase();
        boolean isImage = normalisedType.startsWith("image/");
        boolean isPdf = normalisedType.equals("application/pdf");
        if (!isImage && !isPdf) {
            throw new IllegalArgumentException(
                    "Offer letter must be an image (JPG/PNG/HEIC) or PDF.");
        }
        String oldPublicId = app.getOfferLetterPublicId();
        String oldContentType = app.getOfferLetterContentType();
        String s3Key = agreementUploadKey(app, "offerletter", normalisedType);
        try {
            documentStorage.store(bytes, s3Key, normalisedType);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Couldn't store offer letter: " + e.getMessage(), e);
        }
        app.setOfferLetterS3Key(s3Key);
        app.setOfferLetterPublicId(null);
        app.setOfferLetterContentType(normalisedType);
        app.setOfferLetterUploadedAt(LocalDateTime.now());
        applicationRepository.save(app);
        destroyCloudinaryDoc(oldPublicId, oldContentType);

        appendEvent(app.getId(),
                ConsultantApplicationEvent.EventType.OFFER_LETTER_UPLOADED,
                ConsultantApplicationEvent.ActorType.CONSULTANT, null,
                Map.of("s3Key", s3Key,
                        "contentType", normalisedType,
                        "bytes", bytes.length),
                request);
        return app;
    }

    /** Build I — streams the uploaded offer-letter bytes (re-signed URL). */
    public ChequeBytes fetchOfferLetterBytes(String applicationId) throws java.io.IOException {
        ConsultantApplication app = getByApplicationId(applicationId);
        return readAgreementDoc(app.getOfferLetterS3Key(), app.getOfferLetterPublicId(),
                app.getOfferLetterContentType());
    }

    /**
     * Build J — shared validate + store for a Background Check document
     * upload. Mirrors {@link #uploadOfferLetter}; the caller persists the
     * returned public_id/content-type on the right columns.
     */
    /** Build J — validate a Background Check upload; returns the normalised content type. */
    private String validateBgDoc(byte[] bytes, String contentType, String label) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException(label + " file is empty.");
        }
        if (bytes.length > MAX_CHEQUE_BYTES) {
            throw new IllegalArgumentException(label + " file is too large (>10 MB).");
        }
        String normalisedType = contentType == null ? "" : contentType.toLowerCase();
        boolean isImage = normalisedType.startsWith("image/");
        boolean isPdf = normalisedType.equals("application/pdf");
        if (!isImage && !isPdf) {
            throw new IllegalArgumentException(
                    label + " must be an image (JPG/PNG/HEIC) or PDF.");
        }
        return normalisedType;
    }

    /** Build J — dual-read a Background Check upload (S3 key → S3; else Cloudinary). */
    private ChequeBytes fetchBgDoc(String s3Key, String publicId, String contentType)
            throws java.io.IOException {
        return readAgreementDoc(s3Key, publicId, contentType);
    }

    /** Build J — Driver's License document upload (Appendix 3). */
    @Transactional
    public ConsultantApplication uploadDlDoc(
            String applicationId, byte[] bytes, String contentType, HttpServletRequest request) {
        ConsultantApplication app = getByApplicationId(applicationId);
        assertUploadWritable(app, "appendix3", "doc:dl-doc"); // Build Y (B5) / AK
        String oldPublicId = app.getDlDocPublicId();
        String oldContentType = app.getDlDocContentType();
        String normalisedType = validateBgDoc(bytes, contentType, "Driver's License document");
        String s3Key = agreementUploadKey(app, "dldoc", normalisedType);
        documentStorage.store(bytes, s3Key, normalisedType);
        app.setDlDocS3Key(s3Key);
        app.setDlDocPublicId(null);
        app.setDlDocContentType(normalisedType);
        app.setDlDocUploadedAt(LocalDateTime.now());
        applicationRepository.save(app);
        destroyCloudinaryDoc(oldPublicId, oldContentType);
        appendEvent(app.getId(),
                ConsultantApplicationEvent.EventType.DL_DOC_UPLOADED,
                ConsultantApplicationEvent.ActorType.CONSULTANT, null,
                Map.of("s3Key", s3Key, "contentType", normalisedType, "bytes", bytes.length),
                request);
        return app;
    }

    /** Build J — streams the uploaded Driver's License document (S3 → S3; else Cloudinary). */
    public ChequeBytes fetchDlDocBytes(String applicationId) throws java.io.IOException {
        ConsultantApplication app = getByApplicationId(applicationId);
        return fetchBgDoc(app.getDlDocS3Key(), app.getDlDocPublicId(), app.getDlDocContentType());
    }

    /** Build AK — State-ID document upload (Appendix 3). Mirrors {@link #uploadDlDoc}. */
    @Transactional
    public ConsultantApplication uploadStateIdDoc(
            String applicationId, byte[] bytes, String contentType, HttpServletRequest request) {
        ConsultantApplication app = getByApplicationId(applicationId);
        assertUploadWritable(app, "appendix3", "doc:state-id"); // Build Y (B5) / AK
        String oldPublicId = app.getStateIdDocPublicId();
        String oldContentType = app.getStateIdDocContentType();
        String normalisedType = validateBgDoc(bytes, contentType, "State ID document");
        String s3Key = agreementUploadKey(app, "stateiddoc", normalisedType);
        documentStorage.store(bytes, s3Key, normalisedType);
        app.setStateIdDocS3Key(s3Key);
        app.setStateIdDocPublicId(null);
        app.setStateIdDocContentType(normalisedType);
        app.setStateIdDocUploadedAt(LocalDateTime.now());
        applicationRepository.save(app);
        destroyCloudinaryDoc(oldPublicId, oldContentType);
        appendEvent(app.getId(),
                ConsultantApplicationEvent.EventType.STATE_ID_DOC_UPLOADED,
                ConsultantApplicationEvent.ActorType.CONSULTANT, null,
                Map.of("s3Key", s3Key, "contentType", normalisedType, "bytes", bytes.length),
                request);
        return app;
    }

    /** Build AK — streams the uploaded State-ID document (S3 → S3; else Cloudinary). */
    public ChequeBytes fetchStateIdDocBytes(String applicationId) throws java.io.IOException {
        ConsultantApplication app = getByApplicationId(applicationId);
        return fetchBgDoc(app.getStateIdDocS3Key(), app.getStateIdDocPublicId(),
                app.getStateIdDocContentType());
    }

    /** Build J — SSN document upload (Appendix 3). ALWAYS optional. */
    @Transactional
    public ConsultantApplication uploadSsnDoc(
            String applicationId, byte[] bytes, String contentType, HttpServletRequest request) {
        ConsultantApplication app = getByApplicationId(applicationId);
        assertUploadWritable(app, "appendix3", "doc:ssn-doc"); // Build Y (B5) / AK
        String oldPublicId = app.getSsnDocPublicId();
        String oldContentType = app.getSsnDocContentType();
        String normalisedType = validateBgDoc(bytes, contentType, "SSN document");
        String s3Key = agreementUploadKey(app, "ssndoc", normalisedType);
        documentStorage.store(bytes, s3Key, normalisedType);
        app.setSsnDocS3Key(s3Key);
        app.setSsnDocPublicId(null);
        app.setSsnDocContentType(normalisedType);
        app.setSsnDocUploadedAt(LocalDateTime.now());
        applicationRepository.save(app);
        destroyCloudinaryDoc(oldPublicId, oldContentType);
        appendEvent(app.getId(),
                ConsultantApplicationEvent.EventType.SSN_DOC_UPLOADED,
                ConsultantApplicationEvent.ActorType.CONSULTANT, null,
                Map.of("s3Key", s3Key, "contentType", normalisedType, "bytes", bytes.length),
                request);
        return app;
    }

    /** Build J — streams the uploaded SSN document (S3 → S3; else Cloudinary). */
    public ChequeBytes fetchSsnDocBytes(String applicationId) throws java.io.IOException {
        ConsultantApplication app = getByApplicationId(applicationId);
        return fetchBgDoc(app.getSsnDocS3Key(), app.getSsnDocPublicId(), app.getSsnDocContentType());
    }

    // ── Build W — small name helpers ──────────────────────────────────

    /** Trim a string, returning null when null/blank. */
    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }


    /**
     * Compose "First Middle? Last" from already-trimmed (or null) parts.
     * Returns null when both first and last are absent.
     */
    private static String composeName(String first, String middle, String last) {
        if ((first == null || first.isEmpty())
                && (last == null || last.isEmpty())) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (first != null && !first.isEmpty()) sb.append(first);
        if (middle != null && !middle.isEmpty()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(middle);
        }
        if (last != null && !last.isEmpty()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(last);
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? null : out;
    }

    /**
     * Build W — decompose a single full name into First / Middle / Last
     * and write the structured columns, so any path that edits the
     * composed {@code consultantName} keeps the parts (which the PDF
     * composes from) coherent. One token → first only; two → first +
     * last; three+ → first, middle (the span between), last.
     */
    private static void syncNameParts(ConsultantApplication app, String fullName) {
        String t = blankToNull(fullName);
        if (t == null) return;
        String[] parts = t.split("\\s+");
        if (parts.length == 1) {
            app.setFirstName(parts[0]);
            app.setMiddleName(null);
            app.setLastName(null);
        } else if (parts.length == 2) {
            app.setFirstName(parts[0]);
            app.setMiddleName(null);
            app.setLastName(parts[1]);
        } else {
            app.setFirstName(parts[0]);
            app.setLastName(parts[parts.length - 1]);
            app.setMiddleName(String.join(" ",
                    java.util.Arrays.copyOfRange(parts, 1, parts.length - 1)));
        }
    }

    /**
     * Streams the bytes of a previously-uploaded cheque. Re-signs the
     * delivery URL on every call (the per-upload stored URL 401s
     * later -- same pattern as the final PDF). Returns null when no
     * cheque has been uploaded yet.
     */
    public ChequeBytes fetchChequeBytes(String applicationId) throws java.io.IOException {
        ConsultantApplication app = getByApplicationId(applicationId);
        return readAgreementDoc(app.getChequeS3Key(), app.getChequePublicId(),
                app.getChequeContentType());
    }

    /** Carries the cheque bytes + their original content-type for the controller's stream. */
    public record ChequeBytes(byte[] bytes, String contentType) {}

    // ── Build U: multi-cheque support ────────────────────────────────

    /**
     * One entry in the {@code cheques} JSON list. Phase 5 adds {@code s3Key}:
     * new cheque uploads store the S3 key here (Cloudinary {@code publicId}
     * left blank); reads dual-branch on it, so pre-Phase-5 entries that only
     * carry a {@code publicId} keep rendering from Cloudinary.
     */
    public record ChequeEntry(
            int index,
            String number,
            String date,
            String publicId,
            String s3Key,
            String contentType,
            String uploadedAt) {}

    /**
     * Decode the {@code cheques} JSON column into a sorted-by-index
     * list. Falls back to {@code [{index:0, publicId:<legacy>}]} when
     * the column is null but the legacy {@code chequePublicId} is set,
     * so pre-Build-U rows keep rendering.
     */
    public List<ChequeEntry> parseCheques(ConsultantApplication app) {
        String json = app.getCheques();
        if (json != null && !json.isBlank()) {
            try {
                JsonNode root = objectMapper.readTree(json);
                if (root.isArray()) {
                    List<ChequeEntry> out = new ArrayList<>(root.size());
                    for (JsonNode n : root) {
                        out.add(new ChequeEntry(
                                n.path("index").asInt(0),
                                n.path("number").asText(""),
                                n.path("date").asText(""),
                                n.path("publicId").asText(""),
                                n.path("s3Key").asText(""),
                                n.path("contentType").asText(""),
                                n.path("uploadedAt").asText("")));
                    }
                    out.sort(java.util.Comparator.comparingInt(ChequeEntry::index));
                    return out;
                }
            } catch (Exception e) {
                log.warn("Failed to parse cheques JSON for {}: {}",
                        app.getApplicationId(), e.getMessage());
            }
        }
        // Legacy single-cheque fallback (index 0). Phase 5: the single cheque
        // may now be on S3 (chequeS3Key) with the Cloudinary id null, so the
        // fallback triggers on EITHER pointer.
        String legacyPublicId = app.getChequePublicId();
        String legacyS3Key = app.getChequeS3Key();
        if ((legacyS3Key != null && !legacyS3Key.isBlank())
                || (legacyPublicId != null && !legacyPublicId.isBlank())) {
            return List.of(new ChequeEntry(
                    0, "", "",
                    legacyPublicId == null ? "" : legacyPublicId,
                    legacyS3Key == null ? "" : legacyS3Key,
                    app.getChequeContentType() == null ? "" : app.getChequeContentType(),
                    app.getChequeUploadedAt() == null ? "" : app.getChequeUploadedAt().toString()));
        }
        return List.of();
    }

    /** Patch payload for the per-cheque metadata setter. */
    public static class ChequeMetadataPatch {
        public String number;
        public String date;
    }

    /**
     * Build U — sets/updates the metadata (number, date) for one
     * cheque entry without touching its uploaded bytes. The consultant
     * /fill page calls this each time they edit a per-cheque input.
     */
    @Transactional
    public ConsultantApplication setChequeMetadata(
            String applicationId,
            int index,
            ChequeMetadataPatch patch,
            HttpServletRequest request) {
        ConsultantApplication app = getByApplicationId(applicationId);
        assertUploadWritable(app, "appendix5", "doc:cheque"); // Build Y (B5) / AK
        if (index < 0 || index > 50) {
            throw new IllegalArgumentException("Cheque index out of range.");
        }
        List<ChequeEntry> entries = new ArrayList<>(parseCheques(app));
        ChequeEntry existing = findEntry(entries, index);
        String number = patch == null || patch.number == null ? "" : patch.number.trim();
        String date = patch == null || patch.date == null ? "" : patch.date.trim();
        ChequeEntry replacement = new ChequeEntry(
                index,
                number,
                date,
                existing == null ? "" : existing.publicId(),
                existing == null ? "" : existing.s3Key(),
                existing == null ? "" : existing.contentType(),
                existing == null ? "" : existing.uploadedAt());
        upsertEntry(entries, replacement);
        app.setCheques(serialiseCheques(entries));
        applicationRepository.save(app);
        return app;
    }

    /**
     * Build U — upload bytes for cheque #{@code index}. Stored under
     * {@code agreements/{appId}-cheque-{index}} (authenticated; type
     * varies by content). Updates only that entry in the JSON list;
     * other entries (incl. metadata) untouched. CHEQUE_UPLOADED audit.
     */
    @Transactional
    public ConsultantApplication uploadChequeAt(
            String applicationId,
            int index,
            byte[] bytes,
            String contentType,
            HttpServletRequest request) {
        ConsultantApplication app = getByApplicationId(applicationId);
        assertUploadWritable(app, "appendix5", "doc:cheque"); // Build Y (B5) / AK
        if (index < 0 || index > 50) {
            throw new IllegalArgumentException("Cheque index out of range.");
        }
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
        // Phase 5 — store to S3 (unique timestamped key); Cloudinary id stays
        // blank for new entries. isImage/isPdf above are validation-only now.
        String s3Key = agreementUploadKey(app, "cheque-" + index, normalisedType);
        try {
            documentStorage.store(bytes, s3Key, normalisedType);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Couldn't store cheque: " + e.getMessage(), e);
        }

        List<ChequeEntry> entries = new ArrayList<>(parseCheques(app));
        ChequeEntry existing = findEntry(entries, index);
        String oldChequePublicId = existing == null ? null : existing.publicId();
        String oldChequeContentType = existing == null ? null : existing.contentType();
        ChequeEntry replacement = new ChequeEntry(
                index,
                existing == null ? "" : existing.number(),
                existing == null ? "" : existing.date(),
                "",                       // Phase 5 — Cloudinary id null for new entries
                s3Key,
                normalisedType,
                LocalDateTime.now().toString());
        upsertEntry(entries, replacement);
        app.setCheques(serialiseCheques(entries));
        // Keep the legacy single-cheque fields current too so the ERM's
        // existing "cheque uploaded" pill in the wizard's review step
        // and the older fetchChequeBytes path keep working — now on S3.
        if (index == 0) {
            app.setChequeS3Key(s3Key);
            app.setChequePublicId(null);
            app.setChequeContentType(normalisedType);
            app.setChequeUploadedAt(LocalDateTime.now());
        }
        applicationRepository.save(app);
        destroyCloudinaryDoc(oldChequePublicId, oldChequeContentType);

        appendEvent(app.getId(),
                ConsultantApplicationEvent.EventType.CHEQUE_UPLOADED,
                ConsultantApplicationEvent.ActorType.CONSULTANT, null,
                Map.of("s3Key", s3Key,
                        "index", index,
                        "contentType", normalisedType,
                        "bytes", bytes.length),
                request);
        return app;
    }

    /**
     * Build U — streams the bytes for cheque #{@code index}. Re-signs
     * the URL on every call (per-upload URL 401s later). Returns null
     * when the index has no upload on file.
     */
    public ChequeBytes fetchChequeBytesAt(String applicationId, int index)
            throws java.io.IOException {
        ConsultantApplication app = getByApplicationId(applicationId);
        List<ChequeEntry> entries = parseCheques(app);
        ChequeEntry entry = findEntry(new ArrayList<>(entries), index);
        if (entry == null) return null;
        return readAgreementDoc(entry.s3Key(), entry.publicId(), entry.contentType());
    }

    private static ChequeEntry findEntry(List<ChequeEntry> entries, int index) {
        for (ChequeEntry e : entries) {
            if (e.index() == index) return e;
        }
        return null;
    }

    private static void upsertEntry(List<ChequeEntry> entries, ChequeEntry replacement) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).index() == replacement.index()) {
                entries.set(i, replacement);
                return;
            }
        }
        entries.add(replacement);
    }

    private String serialiseCheques(List<ChequeEntry> entries) {
        entries.sort(java.util.Comparator.comparingInt(ChequeEntry::index));
        try {
            return objectMapper.writeValueAsString(entries);
        } catch (Exception e) {
            throw new IllegalStateException("Couldn't serialise cheques.", e);
        }
    }

    /** Parse the consultant-entered cheque count, capped at 50 (sanity). */
    private static int parseChequeCountSafe(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        try {
            int n = Integer.parseInt(raw.trim());
            if (n < 0) return 0;
            return Math.min(50, n);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Phase 5 — decodes a {@code data:image/...;base64,...} signature and
     * stores the bytes in S3 under
     * {@code agreements/{ermId}/signatures/{role}-{ts}.png}. Returns the S3
     * key (persisted on the relevant {@code *_s3_key} column; the Cloudinary
     * {@code *_image}/{@code *_url} column stays null for new records). The
     * stored bytes are the raw signature image; the PDF render
     * ({@link AgreementDocumentService#buildImage}) re-normalises to PNG on
     * read, so the object content-type is informational only.
     */
    private String storeSignatureToS3(String dataUrl, String role, ConsultantApplication app)
            throws java.io.IOException {
        if (dataUrl == null) {
            throw new java.io.IOException("Missing signature data URL.");
        }
        int comma = dataUrl.indexOf(',');
        if (comma < 0) {
            throw new java.io.IOException("Malformed data URL (no comma).");
        }
        byte[] bytes = java.util.Base64.getDecoder()
                .decode(dataUrl.substring(comma + 1));
        String mime = "image/png";
        if (dataUrl.startsWith("data:")) {
            int semi = dataUrl.indexOf(';');
            int end = (semi >= 0 && semi < comma) ? semi : comma;
            if (end > 5) mime = dataUrl.substring(5, end);
        }
        String key = agreementSignatureKey(app, role);
        documentStorage.store(bytes, key, mime);
        return key;
    }

    // ── Cron sweep ──────────────────────────────────────────────────

    /**
     * Build Q — NO-OP. Agreements no longer expire: the 7-day TTL applies
     * only to the consultant ACCESS LINK, surfaced as a derived,
     * non-mutating indicator ({@link #isConsultantLinkExpired}). This
     * method is retained (returns 0) so the daily {@link ConsultantExpiryJob}
     * — itself now unscheduled — and any operations caller keep compiling,
     * but it never changes an agreement's status. Executed (COMPLETED)
     * agreements and all other states are permanent records.
     */
    @Transactional(readOnly = true)
    public int expireStaleApplications() {
        return 0;
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
        // "Touched" = the CONSULTANT engaged with Appendix 2. Build AP —
        // achDebitDates + achDebitAmounts are ERM-FILLED (Build Y, at create
        // and again via Request Revision) and read-only to the consultant, so
        // they were never theirs to enter. Counting them flipped Appendix 2 to
        // "active/required" on every agreement where the ERM typed a debit
        // schedule WITHOUT ticking require_appendix2 -- and since the wizard
        // hides a not-required appendix entirely, that demanded six ACH fields
        // and an affirmation the consultant had no way to reach. A permanently
        // unsubmittable agreement. Same bug, same fix as Build AB-2 for
        // Appendix 4; they are already excluded from the required-field gate
        // below, so they must not decide the gate applies either.
        //
        // Invariant: an ERM-set read-only field NEVER makes a section touched.
        // The wizard's isAppendixTouched enforces it by skipping field.readOnly;
        // these per-appendix checks are the server-side half of that contract.
        return nonBlank(app.getAchAccountType())
                || nonBlank(app.getAchBankName())
                || nonBlank(app.getAchAccountHolderName())
                || nonBlank(app.getAchRoutingNumber())
                || nonBlank(app.getAchAccountNumber())
                || nonBlank(app.getAchNoticeEmail())
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
                // Build J — structured current address parts also count
                // (incl. the optional line 2) + the same-as toggle.
                || nonBlank(app.getBgCurrentAddressLine1())
                || nonBlank(app.getBgCurrentAddressLine2())
                || nonBlank(app.getBgCurrentAddressCity())
                || nonBlank(app.getBgCurrentAddressState())
                || nonBlank(app.getBgCurrentAddressZip())
                || Boolean.TRUE.equals(app.getBgCurrentSameAsResidence())
                || app.getBgDateOfBirth() != null
                || nonBlank(app.getBgFullSsn())
                || nonBlank(app.getBgDriverLicense())
                || nonBlank(app.getBgStateId()) // Build AK — State-ID number
                // Document uploads do NOT mark the section touched (consistent
                // with the work-auth / offer-letter uploads) — only entered
                // form data + the affirmation do.
                || Boolean.TRUE.equals(app.getAffirmedAppendix3());
    }

    private static boolean isAppendix4Touched(ConsultantApplication app) {
        // "Touched" = the CONSULTANT engaged with Appendix 4. Build AB-2 —
        // portalAuthorizedActions + portalRevocationContact are ERM-SET (Build
        // Z), and Authorized Actions carries a DEFAULT at create, so they are
        // non-blank on every agreement. Counting them here flipped Appendix 4 to
        // "active/required" for EVERY consultant — forcing portalEntries +
        // effective date + the affirmation even when the ERM never required it
        // (a silent backend 400). Only consultant-entered fields count now.
        return nonBlank(app.getPortalPlatform())
                || nonBlank(app.getPortalUsername())
                // Build J — repeatable entries also count as touched.
                || nonBlank(app.getPortalEntries())
                || app.getPortalEffectiveDate() != null
                || Boolean.TRUE.equals(app.getAffirmedAppendix4());
    }

    /**
     * Build J — true when {@code portal_entries} JSON contains at least one
     * entry with BOTH a non-blank platform AND username. Falls back to the
     * legacy single portal_platform/portal_username pair for old rows.
     */
    /**
     * Build J — flatten the portal_entries JSON into the legacy
     * portal_platform / portal_username columns (only complete rows,
     * position-aligned, comma-joined) so the ERM read-back + template
     * render off simple values. Mirrors the ACH JSON→legacy sync.
     */
    private void syncPortalLegacyColumns(ConsultantApplication app) {
        String json = app.getPortalEntries();
        java.util.List<String> platforms = new java.util.ArrayList<>();
        java.util.List<String> usernames = new java.util.ArrayList<>();
        if (json != null && !json.isBlank()) {
            try {
                JsonNode arr = objectMapper.readTree(json);
                if (arr.isArray()) {
                    for (JsonNode e : arr) {
                        String p = e.path("platform").asText("").trim();
                        String u = e.path("username").asText("").trim();
                        if (p.isEmpty() || u.isEmpty()) continue;
                        platforms.add(p);
                        usernames.add(u);
                    }
                }
            } catch (Exception ignored) {
                return; // leave legacy columns untouched on malformed JSON
            }
        }
        app.setPortalPlatform(platforms.isEmpty() ? null : String.join(", ", platforms));
        app.setPortalUsername(usernames.isEmpty() ? null : String.join(", ", usernames));
    }

    private boolean hasCompletePortalEntry(ConsultantApplication app) {
        String json = app.getPortalEntries();
        if (json != null && !json.isBlank()) {
            try {
                JsonNode arr = objectMapper.readTree(json);
                if (arr.isArray()) {
                    for (JsonNode e : arr) {
                        if (nonBlank(e.path("platform").asText(""))
                                && nonBlank(e.path("username").asText(""))) {
                            return true;
                        }
                    }
                }
            } catch (Exception ignored) {
                // fall through to legacy check
            }
        }
        return nonBlank(app.getPortalPlatform()) && nonBlank(app.getPortalUsername());
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

    /**
     * Build Y — sections the ERM explicitly selected in a (restricted)
     * revision round. They become REQUIRED for that round even if they
     * were optional/untouched, so the consultant must actually complete +
     * affirm what was asked. Empty outside a restricted revision.
     */
    private java.util.Set<String> revisionForcedSections(ConsultantApplication app) {
        if (ConsultantApplication.Status.REVISION_REQUESTED.name().equals(app.getStatus())
                && app.getRevisionSections() != null
                && !app.getRevisionSections().isBlank()) {
            return parseRevisionSectionKeys(app);
        }
        return java.util.Collections.emptySet();
    }

    /**
     * Build AP — THE resolution of "which sections does the submit gate
     * apply to for this agreement". Everything that needs the answer reads
     * it from here: the two collectMissing* validators below, and the
     * consultant view payload (via {@code getForConsultant}), which hands
     * it to the wizard so the wizard renders the server's answer instead of
     * re-deriving its own.
     *
     * That re-derivation is what kept breaking. The rule lived twice, once
     * in Java and once in TypeScript, and nothing kept them in step: when
     * they disagreed the server demanded fields the wizard had hidden, and
     * the consultant got an agreement they could not submit and could not
     * fix. Appendix 2 (an ERM-set read-only field marking the section
     * touched) and Appendix 4 before it (Build AB-2) were both that bug.
     * There is now one answer, computed here, shipped to the client.
     *
     * Keys match the wizard's EffectiveRequirements shape exactly.
     */
    public java.util.Map<String, Boolean> resolveEffectiveRequirements(
            ConsultantApplication app) {
        return resolveEffectiveRequirements(app, revisionForcedSections(app));
    }

    /**
     * The pure half — no instance state, so
     * {@code ConsultantApplicationRequirementsTest} can pin the rule
     * directly with an explicit {@code forced} scope.
     */
    static java.util.Map<String, Boolean> resolveEffectiveRequirements(
            ConsultantApplication app, java.util.Set<String> forced) {
        // Active = the ERM required it, OR the consultant engaged with it
        // (optional-but-touched is all-or-nothing), OR an ERM revision round
        // forced it back into scope.
        java.util.Map<String, Boolean> out = new java.util.LinkedHashMap<>();
        out.put("appendix1", Boolean.TRUE.equals(app.getRequireAppendix1())
                || isAppendix1Touched(app) || forced.contains("appendix1"));
        out.put("appendix2", Boolean.TRUE.equals(app.getRequireAppendix2())
                || isAppendix2Touched(app) || forced.contains("appendix2"));
        out.put("appendix3", Boolean.TRUE.equals(app.getRequireAppendix3())
                || isAppendix3Touched(app) || forced.contains("appendix3"));
        out.put("appendix4", Boolean.TRUE.equals(app.getRequireAppendix4())
                || isAppendix4Touched(app) || forced.contains("appendix4"));
        out.put("appendix5", Boolean.TRUE.equals(app.getRequireAppendix5())
                || isAppendix5Touched(app) || forced.contains("appendix5"));
        out.put("ssn", Boolean.TRUE.equals(app.getRequireSsn()));
        // Build AK — only an ERM re-upload request re-requires the SSN doc.
        out.put("ssnDocRequired", forced.contains("doc:ssn-doc"));
        return out;
    }

    /** Returns the keys of every effectively-required consultant field that's blank. */
    private java.util.List<String> collectMissingConsultantFields(
            ConsultantApplication app) {
        java.util.List<String> missing = new java.util.ArrayList<>();
        java.util.Set<String> forced = revisionForcedSections(app);
        // Build AP — the gate reads the SAME resolution the wizard is given,
        // so "what the server enforces" and "what the wizard shows" cannot
        // drift apart.
        java.util.Map<String, Boolean> active =
                resolveEffectiveRequirements(app, forced);
        // CORE (always required).
        // Build W — structured name: first + last required, middle optional.
        addIfBlank(missing, "firstName", app.getFirstName());
        addIfBlank(missing, "lastName", app.getLastName());
        addIfBlank(missing, "consultantEmail", app.getConsultantEmail());
        addIfBlank(missing, "primaryPhone", app.getPrimaryPhone());
        // Build W — structured US billing address (line2 optional).
        addIfBlank(missing, "addressLine1", app.getAddressLine1());
        addIfBlank(missing, "addressCity", app.getAddressCity());
        addIfBlank(missing, "addressState", app.getAddressState());
        addIfBlank(missing, "addressZip", app.getAddressZip());
        if (nonBlank(app.getAddressZip())
                && !app.getAddressZip().trim().matches("\\d{5}(-\\d{4})?")) {
            missing.add("addressZip");
        }
        // Build I — work-authorization document now lives in Personal
        // Information and is required for EVERY work-auth type (CORE).
        // Phase 5 — dual-read: present when EITHER the S3 key or the legacy
        // Cloudinary id is set (new uploads store the key + null the id).
        if (!nonBlank(app.getWorkAuthDocS3Key()) && !nonBlank(app.getWorkAuthDocPublicId())) {
            missing.add("workAuthDoc");
        }
        // Build I — Service Track is ERM-set at create (read-only to the
        // consultant), so it's no longer part of the consultant gate.

        // Appendix 1 -- employment (per require_appendix1; all-or-nothing
        // if optional but touched). implementationPartner is never required.
        if (Boolean.TRUE.equals(active.get("appendix1"))) {
            addIfBlank(missing, "employerPayrollEntity", app.getEmployerPayrollEntity());
            addIfBlank(missing, "endClient", app.getEndClient());
            addIfBlank(missing, "roleTitle", app.getRoleTitle());
            if (app.getVerifiedStartDate() == null) missing.add("verifiedStartDate");
            addIfBlank(missing, "payrollCycle", app.getPayrollCycle());
            // Build I — Offer Letter upload is required whenever Appendix 1
            // (Phase 2 Employment) applies.
            if (!nonBlank(app.getOfferLetterS3Key()) && !nonBlank(app.getOfferLetterPublicId())) {
                missing.add("offerLetter");
            }
        }

        // Appendix 2 -- ACH.
        boolean app2Active = Boolean.TRUE.equals(active.get("appendix2"));
        if (app2Active) {
            addIfBlank(missing, "achAccountType", app.getAchAccountType());
            addIfBlank(missing, "achBankName", app.getAchBankName());
            addIfBlank(missing, "achAccountHolderName", app.getAchAccountHolderName());
            addIfBlank(missing, "achRoutingNumber", app.getAchRoutingNumber());
            addIfBlank(missing, "achAccountNumber", app.getAchAccountNumber());
            addIfBlank(missing, "achNoticeEmail", app.getAchNoticeEmail());
            // Build Y — debit date(s)/amount(s) are now ERM-filled at
            // create (read-only to the consultant), so they are no longer
            // part of the consultant's required-field gate.
        }

        // Appendix 3 -- background check (SSN gated by require_ssn,
        // idType + ID number always required when the section is active).
        boolean app3Active = Boolean.TRUE.equals(active.get("appendix3"));
        if (app3Active) {
            addIfBlank(missing, "bgFullLegalName", app.getBgFullLegalName());
            addIfBlank(missing, "bgOtherNamesUsed", app.getBgOtherNamesUsed());
            // Build J — structured current address (mirrors residence:
            // line1 + city + state + zip required, line2 optional). When
            // "Same as residence" is on, the residence address (validated as
            // CORE above) is authoritative, so the structured current-address
            // fields are NOT separately required.
            if (!Boolean.TRUE.equals(app.getBgCurrentSameAsResidence())) {
                addIfBlank(missing, "bgCurrentAddressLine1", app.getBgCurrentAddressLine1());
                addIfBlank(missing, "bgCurrentAddressCity", app.getBgCurrentAddressCity());
                addIfBlank(missing, "bgCurrentAddressState", app.getBgCurrentAddressState());
                addIfBlank(missing, "bgCurrentAddressZip", app.getBgCurrentAddressZip());
            }
            if (app.getBgDateOfBirth() == null) missing.add("bgDateOfBirth");
            // Build AK — the consultant may provide a Driver's License AND/OR a
            // State ID. Rules: at least ONE must be provided; and any ID they
            // START (a number OR a document) must be COMPLETE (number + doc).
            boolean dlNum = nonBlank(app.getBgDriverLicense());
            boolean dlDoc = nonBlank(app.getDlDocS3Key()) || nonBlank(app.getDlDocPublicId());
            boolean stateNum = nonBlank(app.getBgStateId());
            boolean stateDoc = nonBlank(app.getStateIdDocS3Key())
                    || nonBlank(app.getStateIdDocPublicId());
            boolean dlProvided = dlNum || dlDoc;
            boolean stateProvided = stateNum || stateDoc;
            if (!dlProvided && !stateProvided) {
                // Nothing provided — require at least one ID document.
                missing.add("dlDoc");
            } else {
                if (dlProvided) {
                    if (!dlNum) missing.add("bgDriverLicense");
                    if (!dlDoc) missing.add("dlDoc");
                }
                if (stateProvided) {
                    if (!stateNum) missing.add("bgStateId");
                    if (!stateDoc) missing.add("stateIdDoc");
                }
            }
            // Build J — SSN document is ALWAYS optional at first fill; never
            // validated here. (An ERM re-upload request re-requires it below.)
            if (Boolean.TRUE.equals(app.getRequireSsn())) {
                addIfBlank(missing, "bgFullSsn", app.getBgFullSsn());
            }
        }

        // Build AK — the SSN document is optional at first fill, but an ERM
        // "Request re-upload" (doc:ssn-doc in the revision scope) makes it
        // required again until the consultant uploads a replacement. Independent
        // of app3Active so it holds even for a doc-only SSN revision round.
        if (forced.contains("doc:ssn-doc")
                && !nonBlank(app.getSsnDocS3Key())
                && !nonBlank(app.getSsnDocPublicId())) {
            missing.add("ssnDoc");
        }

        // Appendix 4 -- portal access.
        if (Boolean.TRUE.equals(active.get("appendix4"))) {
            // Build J — at least one COMPLETE platform+username entry is
            // required (the repeatable list replaces the single pair).
            if (!hasCompletePortalEntry(app)) {
                missing.add("portalEntries");
            }
            // Build Z — portalAuthorizedActions + portalRevocationContact are
            // now ERM-set (read-only to the consultant) and may legitimately be
            // blank, so they are NO LONGER validated as consultant-required here.
            // (Validating them deadlocked appendix-4 revision submits: the
            // consultant cannot fill a field that is now ERM-only, so a blank
            // one made the submit permanently fail.)
            if (app.getPortalEffectiveDate() == null) missing.add("portalEffectiveDate");
        }

        // Appendix 5 -- security cheque(s) required when active.
        // Build U — multi-cheque: each entry 0..count-1 must have a
        // number AND an upload. The legacy single chequePublicId is
        // honoured via parseCheques (treated as index 0) for pre-Build-U
        // rows that haven't migrated their data.
        if (Boolean.TRUE.equals(active.get("appendix5"))) {
            addIfBlank(missing, "securityCheckCount", app.getSecurityCheckCount());
            addIfBlank(missing, "securityCheckBank", app.getSecurityCheckBank());
            addIfBlank(missing, "securityCheckHolderName", app.getSecurityCheckHolderName());
            addIfBlank(missing, "securityCheckAmount", app.getSecurityCheckAmount());
            int requiredCount = parseChequeCountSafe(app.getSecurityCheckCount());
            if (requiredCount <= 0) {
                // Adding "cheques" as a single missing token covers both
                // "count not set" and "no cheques uploaded".
                missing.add("cheques");
            } else {
                List<ChequeEntry> entries = parseCheques(app);
                for (int i = 0; i < requiredCount; i++) {
                    ChequeEntry e = findEntry(new java.util.ArrayList<>(entries), i);
                    // Phase 5 — uploaded when EITHER pointer is present.
                    boolean noUpload = e == null
                            || (!nonBlank(e.s3Key()) && !nonBlank(e.publicId()));
                    if (e == null
                            || e.number() == null || e.number().isBlank()
                            || noUpload) {
                        missing.add("cheques");
                        break;
                    }
                }
            }
        }

        // Build G strict format checks. Only enforced WHEN the field is
        // effectively required (i.e. already in `missing`-checking scope);
        // an unfilled optional field doesn't need a format check.
        if (app2Active) {
            if (nonBlank(app.getAchRoutingNumber())
                    && !app.getAchRoutingNumber().replaceAll("\\D", "").matches("\\d{9}")) {
                missing.add("achRoutingNumber");
            }
            // Build AD — account number is free-type (no fixed-length rule):
            // bank account numbers vary in length, so the old exactly-10-digits
            // check rejected valid accounts. Presence is still enforced (the
            // addIfBlank above), only the format/length constraint is dropped.
        }
        if (app3Active && Boolean.TRUE.equals(app.getRequireSsn())) {
            // Build W — SSN is strictly alphanumeric (A-Z, a-z, 0-9);
            // no hyphens, spaces, or symbols.
            if (nonBlank(app.getBgFullSsn())
                    && !app.getBgFullSsn().trim().matches("[A-Za-z0-9]+")) {
                missing.add("bgFullSsn");
            }
        }

        return missing;
    }

    /** Returns the keys of every effectively-required affirmation flag still false / null. */
    private java.util.List<String> collectMissingAffirmations(
            ConsultantApplication app) {
        java.util.List<String> missing = new java.util.ArrayList<>();
        // Build AP — same single resolution the field gate and the wizard use.
        // Build Y — ERM-selected sections in a restricted revision are
        // forced required (so the consultant must re-affirm them), which
        // resolveEffectiveRequirements folds in.
        java.util.Map<String, Boolean> active = resolveEffectiveRequirements(app);
        // Always-required affirmations (main agreement + exhibits).
        if (!Boolean.TRUE.equals(app.getAffirmedMainAgreement())) missing.add("affirmedMainAgreement");
        if (!Boolean.TRUE.equals(app.getAffirmedExhibitA())) missing.add("affirmedExhibitA");
        if (!Boolean.TRUE.equals(app.getAffirmedExhibitB())) missing.add("affirmedExhibitB");
        // Per-appendix: affirmation required exactly when the section is active.
        if (Boolean.TRUE.equals(active.get("appendix1"))
                && !Boolean.TRUE.equals(app.getAffirmedAppendix1())) {
            missing.add("affirmedAppendix1");
        }
        if (Boolean.TRUE.equals(active.get("appendix2"))
                && !Boolean.TRUE.equals(app.getAffirmedAppendix2())) {
            missing.add("affirmedAppendix2");
        }
        if (Boolean.TRUE.equals(active.get("appendix3"))
                && !Boolean.TRUE.equals(app.getAffirmedAppendix3())) {
            missing.add("affirmedAppendix3");
        }
        if (Boolean.TRUE.equals(active.get("appendix4"))
                && !Boolean.TRUE.equals(app.getAffirmedAppendix4())) {
            missing.add("affirmedAppendix4");
        }
        if (Boolean.TRUE.equals(active.get("appendix5"))
                && !Boolean.TRUE.equals(app.getAffirmedAppendix5())) {
            missing.add("affirmedAppendix5");
        }
        return missing;
    }

    private static void addIfBlank(java.util.List<String> out, String key, String value) {
        if (value == null || value.trim().isEmpty()) out.add(key);
    }
}
