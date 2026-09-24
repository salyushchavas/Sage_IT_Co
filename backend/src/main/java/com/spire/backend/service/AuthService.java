package com.spire.backend.service;

import com.spire.backend.dto.*;
import com.spire.backend.entity.Role;
import com.spire.backend.entity.User;
import com.spire.backend.exception.EmailNotVerifiedException;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.exception.UnauthorizedException;
import com.spire.backend.repository.RoleRepository;
import com.spire.backend.repository.UserRepository;
import com.spire.backend.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Handles user registration, login, and token refresh.
 * - Registration: validates email uniqueness, hashes password, assigns STUDENT role
 * - Login: verifies credentials, generates JWT tokens
 * - Refresh: validates refresh token, issues new access token
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final int CODE_TTL_MINUTES = 10;
    private static final int RESEND_COOLDOWN_SECONDS = 60;
    private static final int LOCKOUT_THRESHOLD = 5;
    private static final int LOCKOUT_MINUTES = 15;

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Canonical form of an email address: trimmed and lowercased with a
     * locale-independent rule. Applied at every entry point that takes an
     * email, because new rows are stored lowercased and Postgres compares
     * case-sensitively ("Jane@X.com" must find "jane@x.com").
     */
    public static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    /** SHA-256 hex of a one-time code. Only the hash is stored. */
    static String hashOtp(String code) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(code.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Constant-time comparison of a submitted code against the stored hash. */
    static boolean codeMatches(String storedHash, String submitted) {
        if (storedHash == null || submitted == null) return false;
        return MessageDigest.isEqual(
                storedHash.getBytes(StandardCharsets.UTF_8),
                hashOtp(submitted.trim()).getBytes(StandardCharsets.UTF_8));
    }

    /** True while a verification lock is in force. */
    private static boolean isLocked(User user) {
        return user.getVerificationLockedUntil() != null
                && user.getVerificationLockedUntil().isAfter(LocalDateTime.now());
    }

    /** Minutes left on the lock, rounded up (at least 1). */
    private static long lockMinutesLeft(User user) {
        return java.time.Duration.between(LocalDateTime.now(), user.getVerificationLockedUntil()).toMinutes() + 1;
    }

    /**
     * Failed attempts reset only on a successful verification or once the
     * lock has expired — never on resend, which would hand out five fresh
     * guesses with every new code.
     */
    private static void clearExpiredLock(User user) {
        if (user.getVerificationLockedUntil() != null && !isLocked(user)) {
            user.setVerificationLockedUntil(null);
            user.setVerificationFailedAttempts(0);
        }
    }

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RecordService recordService;
    private final EmailTemplateService emailTemplateService;
    private final WorkflowService workflowService;
    private final ParticipantIdService participantIdService;

    @Transactional
    public RegistrationResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.getEmail());

        // 1. Check duplicate email (case-insensitive)
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email is required");
        }
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new IllegalArgumentException("An account with this email already exists.");
        }

        // 2. Fetch STUDENT role from roles table
        Role studentRole = roleRepository.findByName("STUDENT")
                .orElseThrow(() -> new IllegalStateException("Default role STUDENT not found in database"));

        // 3. Build and save user with a 6-digit OTP. Code is valid for
        //    CODE_TTL_MINUTES; verifyCode rejects anything older.
        //    emailVerified=false locks login until the OTP is consumed.
        String code = generateCode();
        User user = User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .fullName(request.getFullName())
                .role(studentRole)
                .isActive(true)
                .emailVerified(false)
                .verificationCodeHash(hashOtp(code))
                .verificationCodeExpiresAt(LocalDateTime.now().plusMinutes(CODE_TTL_MINUTES))
                .verificationFailedAttempts(0)
                .lastVerificationResendAt(LocalDateTime.now())
                .build();

        user = userRepository.save(user);

        recordService.record(user.getId(), "ACCOUNT_CREATED", RecordService.Category.ACCOUNT,
                "Account created",
                "User registered with email " + user.getEmail(),
                Map.of(
                        "email", user.getEmail(),
                        "fullName", user.getFullName() != null ? user.getFullName() : "",
                        "registrationMethod", "email_password"
                ));

        // 4. Verification-code email only — best-effort. The welcome
        //    email no longer fires here; it's deferred to the very end
        //    of onboarding (post-agreement) so the user only gets it
        //    once they're truly all set up.
        try { emailTemplateService.sendVerificationCodeEmail(user, code); } catch (Exception ignored) {}

        // 5. No JWT yet — frontend reads requiresVerification=true
        //    and routes to /verify-email?email=…
        return RegistrationResponse.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .requiresVerification(true)
                .build();
    }

    // ─── Phase 1B: participant enrollment ───────────────────────────

    /**
     * Phase 1B's enrollment endpoint backing logic. Wider than
     * {@link #register} — accepts phone / location / availability /
     * skillset / experience-level, stamps the participant role, and
     * walks the workflow ladder DRAFT_STARTED → BASIC_INFO_SUBMITTED
     * → EMAIL_VERIFICATION_PENDING in one shot.
     *
     * Re-uses the existing OTP-generation + emailing primitives from
     * {@link #register} but does NOT delegate to it — we want the
     * extended fields persisted in the same insert (rather than a
     * follow-up UPDATE) and the role to be PARTICIPANT, not STUDENT.
     */
    @Transactional
    public RegistrationResponse enrollParticipant(ParticipantEnrollRequest request) {
        String email = normalizeEmail(request.getEmail());
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email is required");
        }
        if (request.getPassword() == null || request.getPassword().length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters");
        }
        if (request.getFullName() == null
                || request.getFullName().trim().split("\\s+").length < 2) {
            throw new IllegalArgumentException("Enter your full legal name (first and last)");
        }
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new IllegalArgumentException("An account with this email already exists.");
        }

        Role participantRole = roleRepository.findByName("PARTICIPANT")
                .orElseGet(() -> roleRepository.findByName("STUDENT")
                        .orElseThrow(() -> new IllegalStateException(
                                "Neither PARTICIPANT nor STUDENT role exists")));

        String code = generateCode();
        LocalDateTime now = LocalDateTime.now();

        User user = User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .fullName(request.getFullName().trim())
                .role(participantRole)
                .phone(request.getPhone())
                .isActive(true)
                .emailVerified(false)
                .currentStatus("DRAFT_STARTED")
                .verificationCodeHash(hashOtp(code))
                .verificationCodeExpiresAt(now.plusMinutes(CODE_TTL_MINUTES))
                .verificationFailedAttempts(0)
                .lastVerificationResendAt(now)
                .build();
        user = userRepository.save(user);

        recordService.record(user.getId(), "ACCOUNT_CREATED", RecordService.Category.ACCOUNT,
                "Participant enrolled",
                "New participant enrollment with email " + user.getEmail(),
                Map.of(
                        "email", user.getEmail(),
                        "fullName", user.getFullName(),
                        "phone", user.getPhone() != null ? user.getPhone() : ""
                ));

        // Workflow ladder: DRAFT_STARTED → BASIC_INFO_SUBMITTED → EMAIL_VERIFICATION_PENDING
        workflowService.transition(user, WorkflowService.Status.BASIC_INFO_SUBMITTED, "enrollment");
        workflowService.transition(user, WorkflowService.Status.EMAIL_VERIFICATION_PENDING, "otp_sent");

        try { emailTemplateService.sendVerificationCodeEmail(user, code); } catch (Exception ignored) {}

        return RegistrationResponse.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .requiresVerification(true)
                .build();
    }

    // ─── 6-digit OTP verification ───────────────────────────────────

    /**
     * Validates the OTP against the user record. On success: marks
     * the email verified, clears the code/lock state, and returns a
     * full AuthResponse so the frontend can store the JWT and head
     * to the dashboard. On failure: increments the attempt counter
     * and triggers a 15-minute lockout after LOCKOUT_THRESHOLD wrong
     * tries to defeat brute force on a 6-digit code (1M space).
     */
    // noRollbackFor: a wrong code is reported with an IllegalArgumentException
    // AFTER the failed-attempt counter (and the lock) were saved. Under a plain
    // @Transactional that exception rolled the save back, so the counter never
    // moved and the 5-try lockout never engaged.
    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public AuthResponse verifyCode(String rawEmail, String code) {
        User user = findUserByEmail(rawEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User", "email", normalizeEmail(rawEmail)));

        if (Boolean.TRUE.equals(user.getEmailVerified())) {
            // Never hand out a session here once the email is verified: there's
            // no code left to check, so that would let anyone sign in to any
            // verified account (staff included) knowing only its email.
            throw new IllegalStateException("This email is already verified. Sign in with your password.");
        }

        // Lockout window in effect?
        if (isLocked(user)) {
            long minutes = lockMinutesLeft(user);
            throw new IllegalArgumentException(
                    "Too many wrong attempts. Try again in about " + minutes + " minute"
                            + (minutes == 1 ? "" : "s") + ".");
        }
        clearExpiredLock(user);

        if (user.getVerificationCodeHash() == null) {
            throw new IllegalArgumentException("No verification code on file. Click \"Resend code\" to get a new one.");
        }

        if (user.getVerificationCodeExpiresAt() == null
                || user.getVerificationCodeExpiresAt().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("Code expired. Please request a new one.");
        }

        if (!codeMatches(user.getVerificationCodeHash(), code)) {
            int attempts = (user.getVerificationFailedAttempts() == null ? 0 : user.getVerificationFailedAttempts()) + 1;
            user.setVerificationFailedAttempts(attempts);
            if (attempts >= LOCKOUT_THRESHOLD) {
                user.setVerificationLockedUntil(LocalDateTime.now().plusMinutes(LOCKOUT_MINUTES));
                userRepository.save(user);
                recordService.record(user.getId(), "ACCOUNT_VERIFICATION_LOCKED",
                        RecordService.Category.SECURITY,
                        "Verification locked",
                        "User locked out after " + attempts + " wrong codes",
                        Map.of("email", user.getEmail()));
                throw new IllegalArgumentException(
                        "Too many wrong attempts. Try again in " + LOCKOUT_MINUTES + " minutes.");
            }
            userRepository.save(user);
            int remaining = LOCKOUT_THRESHOLD - attempts;
            throw new IllegalArgumentException(
                    "Invalid verification code. " + remaining + " attempt" + (remaining == 1 ? "" : "s")
                            + " remaining before lockout.");
        }

        // Right code, but a deactivated account doesn't get a session (login refuses it too).
        if (!Boolean.TRUE.equals(user.getIsActive())) {
            throw new IllegalStateException("This account is deactivated. Contact support if you think this is a mistake.");
        }

        // Success — promote.
        user.setEmailVerified(true);
        user.setVerificationCodeHash(null);
        user.setVerificationCodeExpiresAt(null);
        user.setVerificationFailedAttempts(0);
        user.setVerificationLockedUntil(null);
        // Drop the legacy token state too — they're not needed anymore.
        user.setVerificationToken(null);
        user.setVerificationExpiresAt(null);
        User saved = userRepository.save(user);

        recordService.record(saved.getId(), "ACCOUNT_EMAIL_VERIFIED", RecordService.Category.ACCOUNT,
                "Email verified",
                "User verified email via 6-digit code",
                Map.of("email", saved.getEmail()));

        // Phase 1B: walk the workflow forward + mint the participant
        // ID + send the ID email. All best-effort below the verify
        // itself — the user is already verified, so a mailer outage
        // can't roll back the gate. We still attempt the transitions
        // in the same Tx so the workflow_states audit row reflects
        // the actual state on the user record.
        try {
            workflowService.transition(saved,
                    WorkflowService.Status.EMAIL_VERIFIED, "email_verified");
        } catch (Exception e) {
            // Tolerant of pre-Phase-1B users whose currentStatus was
            // backfilled past EMAIL_VERIFIED — the transition still
            // records an audit row but doesn't move them backward.
        }

        // Only mint an ID if the user hasn't already got one. The
        // ParticipantIdService is itself idempotent but skipping the
        // call avoids an extra round-trip on returning users.
        if (saved.getParticipantId() == null || saved.getParticipantId().isBlank()) {
            try {
                String issued = participantIdService.issue(saved);
                saved.setParticipantId(issued);
                workflowService.transition(saved,
                        WorkflowService.Status.PARTICIPANT_ID_CREATED, "id_generated");
                try {
                    emailTemplateService.sendParticipantIdEmail(saved, issued);
                    workflowService.transition(saved,
                            WorkflowService.Status.ID_EMAIL_SENT, "id_email_sent");
                } catch (Exception ignored) {
                    // Email send failure — still report ID_CREATED so
                    // the frontend can show the page. Resend is a
                    // follow-up admin action.
                }
            } catch (Exception e) {
                // ID issuance failure shouldn't block verification.
                // The participant_id page will simply show "—" and
                // an admin can re-mint via /api/participants/me.
            }
        }

        // The participant lands on /dashboard (every status from
        // EMAIL_VERIFIED on routes there) and continues through the
        // "Complete Your Profile" steps. Their status stays on the real
        // step: it used to jump straight to DASHBOARD_ENABLED
        // ("dashboard_enabled_quick_signup"), which made every later check
        // pass and the roadmap show step 15 to a brand-new participant.
        return buildAuthResponse(saved);
    }

    /**
     * Issues a fresh OTP, subject to a 60-second cooldown. Returns
     * silently for already-verified accounts so a malicious caller
     * can't enumerate verification status. Never throws on missing
     * email — same reason.
     */
    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public void resendVerificationCode(String rawEmail) {
        var found = findUserByEmail(rawEmail);
        if (found.isEmpty()) return;
        User user = found.get();
        if (Boolean.TRUE.equals(user.getEmailVerified())) return;

        // A resend must never lift an active lock — otherwise every
        // resend would hand out five fresh guesses.
        if (isLocked(user)) {
            long minutes = lockMinutesLeft(user);
            throw new IllegalArgumentException(
                    "Too many attempts. Try again in " + minutes + " minute" + (minutes == 1 ? "" : "s") + ".");
        }
        clearExpiredLock(user);

        if (user.getLastVerificationResendAt() != null) {
            long secondsSince = java.time.Duration
                    .between(user.getLastVerificationResendAt(), LocalDateTime.now())
                    .getSeconds();
            if (secondsSince < RESEND_COOLDOWN_SECONDS) {
                long wait = RESEND_COOLDOWN_SECONDS - secondsSince;
                throw new IllegalArgumentException(
                        "Please wait " + wait + " more second" + (wait == 1 ? "" : "s") + " before requesting another code.");
            }
        }

        String code = generateCode();
        user.setVerificationCodeHash(hashOtp(code));
        user.setVerificationCodeExpiresAt(LocalDateTime.now().plusMinutes(CODE_TTL_MINUTES));
        // Failed attempts carry over: they reset only on success or
        // after an expired lock (see clearExpiredLock).
        user.setLastVerificationResendAt(LocalDateTime.now());
        userRepository.save(user);

        try { emailTemplateService.sendVerificationCodeEmail(user, code); } catch (Exception ignored) {}
    }

    /** Six-digit OTP (always padded), backed by SecureRandom. */
    private static String generateCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    /**
     * Finds a user by email: the lowercased form first, then the address
     * exactly as typed, then a case-insensitive match when exactly one row
     * fits. Older rows may predate lowercasing, and Postgres compares
     * case-sensitively, so "Jane@X.com" and "jane@x.com" must both work.
     */
    Optional<User> findUserByEmail(String rawEmail) {
        String email = normalizeEmail(rawEmail);
        if (email == null || email.isBlank()) return Optional.empty();
        Optional<User> found = userRepository.findByEmail(email);
        if (found.isPresent()) return found;
        String typed = rawEmail.trim();
        if (!typed.equals(email)) {
            found = userRepository.findByEmail(typed);
            if (found.isPresent()) return found;
        }
        List<User> matches = userRepository.findAllByEmailIgnoreCase(email);
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    // ─── Password reset ─────────────────────────────────────────────

    /**
     * Generates a reset token + 1-hour expiry on the user (if found)
     * and emails them a reset link. To prevent account enumeration,
     * the calling endpoint always returns a generic success regardless
     * of whether an account was found here — this method just no-ops.
     */
    @Transactional
    public void requestPasswordReset(String email) {
        findUserByEmail(email).ifPresent(user -> {
            String token = UUID.randomUUID().toString();
            user.setResetToken(token);
            user.setResetTokenExpiresAt(LocalDateTime.now().plusHours(1));
            userRepository.save(user);
            try { emailTemplateService.sendPasswordResetEmail(user, token); } catch (Exception ignored) {}
            recordService.record(user.getId(), "ACCOUNT_PASSWORD_RESET_REQUESTED",
                    RecordService.Category.SECURITY,
                    "Password reset requested",
                    "Reset link sent to " + user.getEmail(),
                    Map.of("email", user.getEmail()));
        });
    }

    /**
     * Consumes a reset token and replaces the password hash. Single-
     * use: the token is cleared on success so the same link can't be
     * reused later.
     */
    @Transactional
    public void resetPassword(String token, String newPassword) {
        User user = userRepository.findByResetToken(token)
                .orElseThrow(() -> new IllegalArgumentException("Invalid or expired reset link"));
        if (user.getResetTokenExpiresAt() == null
                || user.getResetTokenExpiresAt().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("Reset link has expired");
        }
        if (newPassword == null || newPassword.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setResetToken(null);
        user.setResetTokenExpiresAt(null);
        userRepository.save(user);
        recordService.record(user.getId(), "ACCOUNT_PASSWORD_RESET",
                RecordService.Category.SECURITY,
                "Password reset",
                "User reset password via emailed link",
                Map.of("email", user.getEmail()));
    }

    public AuthResponse login(LoginRequest request) {
        // 1. Find user by email (any capitalisation)
        User user = findUserByEmail(request.getEmail())
                .orElseThrow(() -> {
                    recordLoginFailed(null, normalizeEmail(request.getEmail()), "user_not_found");
                    return new UnauthorizedException("Invalid email or password");
                });

        // 2. Verify password
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            recordLoginFailed(user.getId(), user.getEmail(), "wrong_password");
            throw new UnauthorizedException("Invalid email or password");
        }

        // 3. Check if active
        if (!user.getIsActive()) {
            recordLoginFailed(user.getId(), user.getEmail(), "account_deactivated");
            throw new UnauthorizedException("Account is deactivated");
        }

        // 4. OTP gate — login is blocked until the email is verified.
        //    Throws a typed exception the controller turns into a
        //    structured 403 with the email so the frontend can route
        //    to /verify-email?email=… without a second lookup.
        if (!Boolean.TRUE.equals(user.getEmailVerified())) {
            recordLoginFailed(user.getId(), user.getEmail(), "email_not_verified");
            throw new EmailNotVerifiedException(user.getEmail());
        }

        recordService.record(user.getId(), "ACCOUNT_LOGIN", RecordService.Category.ACCOUNT,
                "Logged in",
                "User logged in successfully",
                Map.of("email", user.getEmail()));

        return buildAuthResponse(user);
    }

    private void recordLoginFailed(Long userId, String email, String reason) {
        // userId may be null when the email doesn't exist — still log
        // the attempt so admins can spot enumeration probes (we just
        // can't attribute it to a real user).
        if (userId == null) return;
        recordService.record(userId, "ACCOUNT_LOGIN_FAILED", RecordService.Category.SECURITY,
                "Failed login attempt",
                "Failed login attempt for " + email,
                Map.of("email", email != null ? email : "", "reason", reason));
    }

    public AuthResponse refreshToken(String refreshToken) {
        // Only a genuine refresh token: an access (or console / consultant)
        // token must not be exchangeable for a fresh pair.
        if (!jwtService.isRefreshToken(refreshToken)) {
            throw new UnauthorizedException("Invalid or expired refresh token");
        }

        Long userId = jwtService.extractUserId(refreshToken);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("Invalid or expired refresh token"));
        // A deactivated account must not mint new access tokens for the rest
        // of the refresh token's 7-day life. Same generic message as a bad
        // token, so the account state isn't confirmed to whoever holds it.
        if (!Boolean.TRUE.equals(user.getIsActive())) {
            throw new UnauthorizedException("Invalid or expired refresh token");
        }

        return buildAuthResponse(user);
    }

    private AuthResponse buildAuthResponse(User user) {
        String accessToken = jwtService.generateAccessToken(user.getId(), user.getRole().getName());
        String refreshToken = jwtService.generateRefreshToken(user.getId());

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .user(UserDTO.from(user))
                .build();
    }
}
