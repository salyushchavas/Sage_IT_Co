package com.spire.backend.service;

import com.spire.backend.entity.AgreementAcceptance;
import com.spire.backend.entity.User;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.repository.AgreementAcceptanceRepository;
import com.spire.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Implements the email-reply-based Terms of Service acceptance flow.
 *
 *   1. {@link #requestAcceptance} — validates the user's typed legal
 *      name + checkbox state, persists an unverified row in
 *      {@code WAITING_REPLY}, and emails the user a "Reply YES"
 *      message whose subject contains a tracking marker
 *      {@code [AGREE-{userId}-{millis}]}. No OTP is generated here.
 *
 *   2. {@link #processReply} — called by the Vercel IMAP cron when
 *      it finds an unread reply matching a tracking marker and
 *      containing YES/agreed/etc. Verifies the row is still
 *      WAITING_REPLY and not expired (30-minute window),
 *      generates the 6-digit OTP, emails it, and flips the row to
 *      {@code CODE_SENT}.
 *
 *   3. {@link #verifyAcceptanceCode} — validates the OTP and flips
 *      the row to {@code VERIFIED}; from this point the row is
 *      treated as immutable legal evidence.
 *
 * Seven-layer evidence chain on a verified row:
 *   email-verified user → terms read (frontend tracks scroll) →
 *   typed legal name → checked consent boxes → replied YES from
 *   the user's own inbox → OTP entered correctly → IP / browser /
 *   timestamps stamped throughout.
 *
 * Immutability is enforced in code (we never mutate a verified row)
 * rather than at the DB layer; portable across MySQL / Postgres.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgreementService {

    public static final String CURRENT_VERSION = "v2.0";

    public static final String STATUS_WAITING_REPLY = "WAITING_REPLY";
    public static final String STATUS_CODE_SENT = "CODE_SENT";
    public static final String STATUS_VERIFIED = "VERIFIED";

    private static final int CODE_TTL_MINUTES = 10;
    private static final int AGREEMENT_EMAIL_TTL_MINUTES = 30;
    private static final int RESEND_COOLDOWN_SECONDS = 60;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AgreementAcceptanceRepository agreementRepository;
    private final UserRepository userRepository;
    private final EmailTemplateService emailTemplateService;
    private final RecordService recordService;
    private final AgreementPdfService agreementPdfService;

    // ─── Status read ────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> getStatus(Long userId) {
        Optional<AgreementAcceptance> row = agreementRepository.findByUserId(userId);
        Map<String, Object> out = new HashMap<>();
        out.put("version", CURRENT_VERSION);
        if (row.isEmpty()) {
            out.put("status", "NOT_STARTED");
            out.put("accepted", false);
            return out;
        }
        AgreementAcceptance a = row.get();
        out.put("status", a.getStatus());
        out.put("accepted", STATUS_VERIFIED.equals(a.getStatus()));
        if (a.getAgreementExpiresAt() != null) {
            out.put("agreementExpiresAt", a.getAgreementExpiresAt().toString());
        }
        if (a.getAcceptedAt() != null) {
            out.put("acceptedAt", a.getAcceptedAt().toString());
            out.put("legalName", a.getLegalName());
        }
        return out;
    }

    // ─── One-shot on-site signing (current PRD flow) ───────────────

    /**
     * Records acceptance immediately when the user signs on the
     * /agreement page — no email reply, no OTP. Persists the row
     * directly in VERIFIED state with the full audit trail, flips
     * {@code user.agreementAccepted}, generates + emails the signed
     * PDF, and dispatches the welcome email. Idempotent on rows
     * already VERIFIED.
     */
    @Transactional
    public Map<String, Object> signImmediate(
            Long userId, String legalName,
            String signatureImage, String signatureMethod,
            String ipAddress, String userAgent
    ) {
        if (legalName == null || countWords(legalName) < 2) {
            throw new IllegalArgumentException(
                    "Please enter your full legal name (first and last).");
        }
        if (signatureImage == null || signatureImage.isBlank()) {
            throw new IllegalArgumentException(
                    "Please add your digital signature before submitting.");
        }
        if (!signatureImage.startsWith("data:image/")) {
            throw new IllegalArgumentException(
                    "Signature must be an image (PNG / JPG).");
        }
        if (signatureImage.length() > 2_800_000) {
            throw new IllegalArgumentException(
                    "Signature image is too large (max 2 MB).");
        }
        String method = signatureMethod == null ? "draw" : signatureMethod.toLowerCase();
        if (!"draw".equals(method) && !"upload".equals(method)) {
            method = "draw";
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        Optional<AgreementAcceptance> existing = agreementRepository.findByUserId(userId);
        if (existing.isPresent() && STATUS_VERIFIED.equals(existing.get().getStatus())) {
            return Map.of(
                    "success", true,
                    "alreadyAccepted", true,
                    "status", STATUS_VERIFIED
            );
        }

        AgreementAcceptance row = existing.orElseGet(AgreementAcceptance::new);
        UserAgentInfo ua = parseUserAgent(userAgent);
        LocalDateTime now = LocalDateTime.now();

        row.setUser(user);
        row.setLegalName(legalName.trim());
        row.setAgreementVersion(CURRENT_VERSION);
        row.setStatus(STATUS_VERIFIED);
        row.setAcceptanceCode(null);
        row.setCodeVerified(true);
        row.setIpAddress(ipAddress);
        row.setUserAgent(userAgent);
        row.setBrowser(ua.browser);
        row.setOs(ua.os);
        row.setSignatureImage(signatureImage);
        row.setSignatureMethod(method);
        row.setAcceptedAt(now);
        row.setAgreementEmailSentAt(null);
        row.setAgreementExpiresAt(null);
        row.setLastResendAt(null);
        row.setUserReplyReceivedAt(null);
        row.setUserReplyContent(null);
        row.setVerificationCodeSentAt(null);
        row.setVerificationCodeVerifiedAt(now);
        row.setCodeExpiresAt(null);

        AgreementAcceptance saved = agreementRepository.save(row);

        user.setAgreementAccepted(true);
        userRepository.save(user);

        recordService.record(userId, "AGREEMENT_ACCEPTED",
                RecordService.Category.SECURITY,
                "Agreement signed on website",
                "User signed Terms of Service " + saved.getAgreementVersion(),
                Map.of(
                        "version", saved.getAgreementVersion(),
                        "legalName", saved.getLegalName(),
                        "ip", saved.getIpAddress() != null ? saved.getIpAddress() : "",
                        "browser", saved.getBrowser() != null ? saved.getBrowser() : "",
                        "os", saved.getOs() != null ? saved.getOs() : ""
                ));

        generateAndDeliverSignedPdf(saved);
        try { emailTemplateService.sendWelcomeEmail(user); } catch (Exception ignored) {}

        log.info("Agreement signed on-site for user {} (row {})", userId, saved.getId());
        return Map.of(
                "success", true,
                "alreadyAccepted", false,
                "status", STATUS_VERIFIED
        );
    }

    // ─── Request acceptance — sends "Reply YES" email ───────────────

    /**
     * Creates (or refreshes) the pending row in WAITING_REPLY and
     * emails the user a "Reply YES" prompt. No OTP yet — that
     * happens in {@link #processReply} after the inbox cron sees
     * the reply.
     *
     * Re-callable for users who already have a pending row, as long
     * as it hasn't been verified — each call resets the 30-minute
     * email window and sends a fresh request email.
     */
    @Transactional
    public Map<String, Object> requestAcceptance(
            Long userId, String legalName, boolean termsAccepted,
            boolean contentPolicyAccepted,
            String signatureImage, String signatureMethod,
            String ipAddress, String userAgent
    ) {
        if (legalName == null || countWords(legalName) < 2) {
            throw new IllegalArgumentException(
                    "Please enter your full legal name (first and last).");
        }
        if (!termsAccepted || !contentPolicyAccepted) {
            throw new IllegalArgumentException(
                    "You must agree to both the Terms of Service and the content policy.");
        }
        if (signatureImage == null || signatureImage.isBlank()) {
            throw new IllegalArgumentException(
                    "Please add your digital signature before submitting.");
        }
        if (!signatureImage.startsWith("data:image/")) {
            throw new IllegalArgumentException(
                    "Signature must be an image (PNG / JPG).");
        }
        // 2 MB cap on the encoded payload — base64 inflates the binary
        // by ~33%, so a true ~1.5 MB image produces a ~2 MB string.
        if (signatureImage.length() > 2_800_000) {
            throw new IllegalArgumentException(
                    "Signature image is too large (max 2 MB).");
        }
        String method = signatureMethod == null ? "draw" : signatureMethod.toLowerCase();
        if (!"draw".equals(method) && !"upload".equals(method)) {
            method = "draw";
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        Optional<AgreementAcceptance> existing = agreementRepository.findByUserId(userId);
        if (existing.isPresent() && STATUS_VERIFIED.equals(existing.get().getStatus())) {
            // Already accepted — return idempotently. A verified
            // row is immutable; never overwrite it.
            return Map.of(
                    "success", true,
                    "alreadyAccepted", true,
                    "status", STATUS_VERIFIED,
                    "message", "Agreement already accepted."
            );
        }

        AgreementAcceptance row = existing.orElseGet(AgreementAcceptance::new);
        long trackingTimestamp = System.currentTimeMillis() / 1000L;
        UserAgentInfo ua = parseUserAgent(userAgent);
        LocalDateTime now = LocalDateTime.now();

        row.setUser(user);
        row.setLegalName(legalName.trim());
        row.setAgreementVersion(CURRENT_VERSION);
        row.setStatus(STATUS_WAITING_REPLY);
        row.setAcceptanceCode(null);
        row.setCodeVerified(false);
        row.setIpAddress(ipAddress);
        row.setUserAgent(userAgent);
        row.setBrowser(ua.browser);
        row.setOs(ua.os);
        row.setSignatureImage(signatureImage);
        row.setSignatureMethod(method);
        row.setAgreementEmailSentAt(now);
        row.setAgreementExpiresAt(now.plusMinutes(AGREEMENT_EMAIL_TTL_MINUTES));
        row.setLastResendAt(now);
        // Clear any stale post-reply state from a previous attempt.
        row.setUserReplyReceivedAt(null);
        row.setUserReplyContent(null);
        row.setVerificationCodeSentAt(null);
        row.setVerificationCodeVerifiedAt(null);
        row.setCodeExpiresAt(null);

        // Render the pending agreement PDF up-front so the email
        // body and the attachment are dispatched in one relay call.
        // Best-effort — if the PDF render fails we still send the
        // email so the user can reply YES from the inbox; the signed
        // copy generated after OTP verification will land regardless.
        byte[] pendingPdf = null;
        try {
            pendingPdf = agreementPdfService.renderPendingBytes(row);
        } catch (Exception e) {
            log.warn("Pending agreement PDF render failed for user {}: {}", userId, e.getMessage());
        }

        // Send the request email and capture the subject so the
        // IMAP cron can match the reply back to this row.
        String subject = emailTemplateService.sendAgreementReplyRequestEmail(
                user, row.getLegalName(), ipAddress, userId, trackingTimestamp, pendingPdf);
        row.setAgreementEmailSubject(subject);

        AgreementAcceptance saved = agreementRepository.save(row);

        log.info("Agreement reply-request issued for user {} (row {}, expires {})",
                userId, saved.getId(), saved.getAgreementExpiresAt());

        Map<String, Object> out = new HashMap<>();
        out.put("success", true);
        out.put("alreadyAccepted", false);
        out.put("status", STATUS_WAITING_REPLY);
        out.put("expiresAt", saved.getAgreementExpiresAt().toString());
        out.put("message", "Agreement email sent. Reply YES from your inbox.");
        return out;
    }

    // ─── Sender-based pending-user lookup (cron support) ───────────

    /**
     * Returns {@code { hasPending, userId, email, expiresAt }} for the
     * given from-address. Used by the IMAP cron to drop subject-based
     * matching (which Gmail re-formats unpredictably) in favour of
     * sender-based matching: any unread email whose sender owns a
     * WAITING_REPLY row is a candidate for acceptance processing.
     *
     * Email comparison is case-insensitive; address-only (no
     * display name).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> checkPendingByEmail(String email) {
        Map<String, Object> out = new HashMap<>();
        out.put("hasPending", false);
        if (email == null || email.isBlank()) return out;

        String normalised = email.trim().toLowerCase();
        Optional<User> userOpt = userRepository.findByEmail(normalised);
        // findByEmail uses an exact match; some seed data may have
        // mixed-case addresses, so fall back to a scan if the
        // case-folded lookup misses.
        User user = userOpt.orElseGet(() -> userRepository.findAll().stream()
                .filter(u -> u.getEmail() != null
                        && u.getEmail().equalsIgnoreCase(normalised))
                .findFirst()
                .orElse(null));
        if (user == null) return out;

        AgreementAcceptance row = agreementRepository.findByUserId(user.getId()).orElse(null);
        if (row == null) return out;
        if (!STATUS_WAITING_REPLY.equals(row.getStatus())) return out;
        if (row.getAgreementExpiresAt() != null
                && row.getAgreementExpiresAt().isBefore(LocalDateTime.now())) {
            return out;
        }

        out.put("hasPending", true);
        out.put("userId", user.getId());
        out.put("email", user.getEmail());
        if (row.getAgreementExpiresAt() != null) {
            out.put("expiresAt", row.getAgreementExpiresAt().toString());
        }
        return out;
    }

    // ─── Process inbox reply (called by Vercel cron) ────────────────

    /**
     * Called by the Vercel IMAP cron when it finds a YES reply
     * matching a tracking marker. Idempotent — if the row is
     * already CODE_SENT or VERIFIED, returns silently. Verifies
     * the from-address matches the user's email so a forwarded
     * reply from a stranger can't be used to claim consent.
     *
     * Returns true when a code was actually generated (i.e. this
     * call moved the row from WAITING_REPLY → CODE_SENT) so the
     * cron can log meaningful counters.
     */
    @Transactional
    public boolean processReply(Long userId, String fromEmail, String replyContent) {
        AgreementAcceptance row = agreementRepository.findByUserId(userId).orElse(null);
        if (row == null) {
            log.warn("processReply: no agreement row for user {}", userId);
            return false;
        }
        if (!STATUS_WAITING_REPLY.equals(row.getStatus())) {
            log.info("processReply: user {} row already in status {}, ignoring", userId, row.getStatus());
            return false;
        }
        if (row.getAgreementExpiresAt() != null
                && row.getAgreementExpiresAt().isBefore(LocalDateTime.now())) {
            log.info("processReply: user {} agreement window expired, ignoring", userId);
            return false;
        }

        User user = row.getUser();
        // Defence in depth — the cron already filters by user id, but
        // ensure the reply address matches the on-record email so a
        // misrouted reply can't be misattributed.
        if (user == null || user.getEmail() == null
                || !user.getEmail().equalsIgnoreCase(fromEmail)) {
            log.warn("processReply: from-address {} does not match user {}", fromEmail, userId);
            return false;
        }

        String code = generateCode();
        LocalDateTime now = LocalDateTime.now();

        row.setStatus(STATUS_CODE_SENT);
        row.setUserReplyReceivedAt(now);
        row.setUserReplyContent(replyContent == null
                ? "" : replyContent.trim().substring(0, Math.min(64, replyContent.trim().length())));
        row.setAcceptanceCode(code);
        row.setCodeExpiresAt(now.plusMinutes(CODE_TTL_MINUTES));
        row.setVerificationCodeSentAt(now);
        agreementRepository.save(row);

        try {
            emailTemplateService.sendAgreementCodeEmail(user, row.getLegalName(), code);
        } catch (Exception ignored) {}

        log.info("Agreement code emailed to user {} after reply detection", userId);
        return true;
    }

    // ─── Verify OTP ─────────────────────────────────────────────────

    @Transactional
    public void verifyAcceptanceCode(Long userId, String code) {
        AgreementAcceptance row = agreementRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No agreement in progress. Please restart from the agreement page."));

        if (STATUS_VERIFIED.equals(row.getStatus())) {
            return; // idempotent — already accepted
        }
        if (!STATUS_CODE_SENT.equals(row.getStatus())) {
            throw new IllegalArgumentException(
                    "We haven't received your reply yet. Reply YES to the agreement email first.");
        }
        if (row.getCodeExpiresAt() == null
                || row.getCodeExpiresAt().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("Code expired. Please restart.");
        }

        String submitted = code == null ? "" : code.trim();
        if (row.getAcceptanceCode() == null || !row.getAcceptanceCode().equals(submitted)) {
            throw new IllegalArgumentException("Invalid code. Please try again.");
        }

        LocalDateTime now = LocalDateTime.now();
        row.setStatus(STATUS_VERIFIED);
        row.setCodeVerified(true);
        row.setVerificationCodeVerifiedAt(now);
        row.setAcceptedAt(now);
        agreementRepository.save(row);

        User user = row.getUser();
        user.setAgreementAccepted(true);
        userRepository.save(user);

        recordService.record(userId, "AGREEMENT_ACCEPTED",
                RecordService.Category.SECURITY,
                "Agreement accepted",
                "User accepted Terms of Service " + row.getAgreementVersion(),
                Map.of(
                        "version", row.getAgreementVersion(),
                        "legalName", row.getLegalName(),
                        "ip", row.getIpAddress() != null ? row.getIpAddress() : "",
                        "browser", row.getBrowser() != null ? row.getBrowser() : "",
                        "os", row.getOs() != null ? row.getOs() : "",
                        "replyContent", row.getUserReplyContent() != null ? row.getUserReplyContent() : ""
                ));

        // Generate the personalized signed PDF + email it as an
        // attachment. Best-effort — a PDF / SMTP outage must not roll
        // back the verification, since the legal evidence trail is
        // already complete in the row.
        generateAndDeliverSignedPdf(row);

        // Final onboarding step: now that they're fully set up
        // (email verified + agreement accepted), send the welcome
        // email. This is intentionally the LAST email — the welcome
        // would have been premature during signup since the user
        // couldn't actually use the platform until the agreement
        // was on file.
        try { emailTemplateService.sendWelcomeEmail(user); } catch (Exception ignored) {}
    }

    /**
     * Renders the personalized signed-agreement PDF for a verified
     * row, persists its public download URL, and emails the user a
     * copy as an attachment. All failures are swallowed and logged —
     * the row is already verified at this point and the PDF is a
     * post-acceptance artifact, not a precondition.
     */
    private void generateAndDeliverSignedPdf(AgreementAcceptance row) {
        String fileName = null;
        try {
            fileName = agreementPdfService.generate(row);
            row.setSignedAgreementPdfUrl(
                    "/api/agreement/signed-pdf/" + row.getUser().getId() + "/" + fileName);
            agreementRepository.save(row);
        } catch (Exception e) {
            log.error("Signed agreement PDF generation failed for user {}: {}",
                    row.getUser().getId(), e.getMessage());
        }

        // Email — read bytes off disk so the relay payload is the
        // exact file we'll later serve from the download endpoint.
        try {
            byte[] pdfBytes = null;
            if (fileName != null) {
                java.nio.file.Path p = java.nio.file.Paths.get(
                        "signed-agreements/" + fileName);
                if (java.nio.file.Files.exists(p)) {
                    pdfBytes = java.nio.file.Files.readAllBytes(p);
                }
            }
            String acceptedAt = row.getAcceptedAt() == null ? ""
                    : row.getAcceptedAt()
                            .atZone(java.time.ZoneId.of("Asia/Kolkata"))
                            .format(java.time.format.DateTimeFormatter
                                    .ofPattern("d MMMM yyyy, h:mm a 'IST'"));
            String recordId = String.format("AGR-%d-%05d",
                    row.getCreatedAt() == null
                            ? java.time.LocalDate.now().getYear()
                            : row.getCreatedAt().getYear(),
                    row.getId());

            emailTemplateService.sendSignedAgreementEmail(
                    row.getUser(), row.getLegalName(), acceptedAt,
                    row.getAgreementVersion(), recordId, pdfBytes);
        } catch (Exception e) {
            log.error("Signed agreement email failed for user {}: {}",
                    row.getUser().getId(), e.getMessage());
        }
    }

    // ─── Resend agreement-request email ─────────────────────────────

    /**
     * Re-emails the "Reply YES" prompt. 60-second cooldown
     * server-side. Refuses on already-verified rows. If the row is
     * already in CODE_SENT, the user should re-trigger the flow
     * (the OTP was already sent); we treat resend on CODE_SENT as
     * resending the code itself, not the request email.
     */
    @Transactional
    public void resend(Long userId) {
        AgreementAcceptance row = agreementRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No agreement in progress. Please restart from the agreement page."));

        if (STATUS_VERIFIED.equals(row.getStatus())) {
            throw new IllegalArgumentException("Agreement is already accepted.");
        }

        if (row.getLastResendAt() != null) {
            long secondsSince = Duration
                    .between(row.getLastResendAt(), LocalDateTime.now())
                    .getSeconds();
            if (secondsSince < RESEND_COOLDOWN_SECONDS) {
                long wait = RESEND_COOLDOWN_SECONDS - secondsSince;
                throw new IllegalArgumentException(
                        "Please wait " + wait + " more second" + (wait == 1 ? "" : "s")
                                + " before requesting another email.");
            }
        }

        if (STATUS_WAITING_REPLY.equals(row.getStatus())) {
            // Resend the "Reply YES" prompt. Bump the tracking
            // timestamp + the 30-minute window so a stale subject
            // doesn't outlive the new expiry.
            long trackingTimestamp = System.currentTimeMillis() / 1000L;
            LocalDateTime now = LocalDateTime.now();
            row.setAgreementEmailSentAt(now);
            row.setAgreementExpiresAt(now.plusMinutes(AGREEMENT_EMAIL_TTL_MINUTES));
            row.setLastResendAt(now);
            byte[] pendingPdf = null;
            try {
                pendingPdf = agreementPdfService.renderPendingBytes(row);
            } catch (Exception e) {
                log.warn("Pending agreement PDF render failed on resend for user {}: {}",
                        userId, e.getMessage());
            }
            String subject = emailTemplateService.sendAgreementReplyRequestEmail(
                    row.getUser(), row.getLegalName(), row.getIpAddress(),
                    userId, trackingTimestamp, pendingPdf);
            row.setAgreementEmailSubject(subject);
            agreementRepository.save(row);
            return;
        }

        if (STATUS_CODE_SENT.equals(row.getStatus())) {
            // Resend the OTP. Reuse the existing code so a stale
            // earlier email is still valid; just fire the email
            // again and bump the cooldown anchor.
            row.setLastResendAt(LocalDateTime.now());
            agreementRepository.save(row);
            try {
                emailTemplateService.sendAgreementCodeEmail(
                        row.getUser(), row.getLegalName(), row.getAcceptanceCode());
            } catch (Exception ignored) {}
        }
    }

    // ─── Internals ──────────────────────────────────────────────────

    private static String generateCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    private static int countWords(String s) {
        if (s == null) return 0;
        String trimmed = s.trim();
        if (trimmed.isEmpty()) return 0;
        return trimmed.split("\\s+").length;
    }

    /**
     * Tiny user-agent parser — enough to surface a readable browser
     * + OS for audit display. We don't pull in a dedicated UA
     * library because the audit row needs an approximation, not
     * perfect classification.
     */
    static UserAgentInfo parseUserAgent(String ua) {
        if (ua == null || ua.isBlank()) return new UserAgentInfo("Unknown", "Unknown");

        String browser;
        if (ua.contains("Edg/")) browser = "Edge";
        else if (ua.contains("OPR/") || ua.contains("Opera")) browser = "Opera";
        else if (ua.contains("Chrome/") && !ua.contains("Chromium")) browser = "Chrome";
        else if (ua.contains("Firefox/")) browser = "Firefox";
        else if (ua.contains("Safari/") && !ua.contains("Chrome/")) browser = "Safari";
        else browser = "Unknown";

        String os;
        if (ua.contains("Windows NT 10")) os = "Windows 10/11";
        else if (ua.contains("Windows")) os = "Windows";
        else if (ua.contains("Mac OS X") || ua.contains("Macintosh")) os = "macOS";
        else if (ua.contains("Android")) os = "Android";
        else if (ua.contains("iPhone") || ua.contains("iPad") || ua.contains("iOS")) os = "iOS";
        else if (ua.contains("Linux")) os = "Linux";
        else os = "Unknown";

        return new UserAgentInfo(browser, os);
    }

    record UserAgentInfo(String browser, String os) {}
}
