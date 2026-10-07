package com.spire.backend.service;

import com.spire.backend.dto.AuthResponse;
import com.spire.backend.entity.ParticipantApplication;
import com.spire.backend.entity.User;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.repository.ParticipantApplicationRepository;
import com.spire.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import static com.spire.backend.entity.ParticipantApplication.APPROVED;
import static com.spire.backend.entity.ParticipantApplication.DECLINED;
import static com.spire.backend.entity.ParticipantApplication.PENDING;
import static com.spire.backend.entity.ParticipantApplication.REGISTERED;

/**
 * Roadmap step 1 as asked for on 30 Sep: a visitor sees the courses and
 * applies with their basic details; an ERM confirms the application,
 * which emails a one-time link; the applicant registers from that link
 * and enters the roadmap (Participant ID, then the profile steps).
 *
 * Nobody gets an account without a confirmed application. Operations'
 * "Invite a participant" is the same thing already confirmed.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ParticipantApplicationService {

    /** How long a registration link works. */
    static final int LINK_DAYS = 7;
    /** At most one registration email a minute per application. */
    static final int RESEND_COOLDOWN_SECONDS = 60;
    static final int MAX_TECHNOLOGY = 100;
    static final int MAX_REASON = 500;

    /** Who may confirm, decline or resend. */
    static final Set<String> REVIEWERS = Set.of("ERM", "OPERATIONS_ADMIN", "SYSTEM_ADMIN");
    /** Who may invite (an application that is confirmed from the start). */
    static final Set<String> INVITERS = Set.of("OPERATIONS_ADMIN", "SYSTEM_ADMIN");
    private static final List<String> OPEN = List.of(PENDING, APPROVED);

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s,;<>\"']+@[^@\\s,;<>\"']+\\.[^@\\s,;<>\"']+$");
    /** Course names only: nothing that could turn an email into markup or a link. */
    private static final Pattern TECHNOLOGY = Pattern.compile("^[\\p{L}\\p{N}][\\p{L}\\p{N} .,&/+#()'-]*$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ParticipantApplicationRepository applicationRepository;
    private final UserRepository userRepository;
    private final AuthService authService;
    private final EmailTemplateService emailTemplateService;
    private final RecordService recordService;
    private final PermissionService permissionService;

    @Value("${app.url:https://sageitco.com}")
    private String appUrl;

    /** Where a new-application notice goes when there is no active ERM. */
    @Value("${program.operations.email:}")
    private String operationsEmail;

    /** What a staff action did: the application, and whether its email went out. */
    public record Result(ParticipantApplication application, boolean emailSent) {}

    // ── Step 1: the visitor applies ──────────────────────────────

    @Transactional
    public ParticipantApplication apply(String fullName, String rawEmail, String phone,
                                        String technology, String ipAddress) {
        String name = PersonNames.clean(fullName);
        if (name.split("\\s+").length < 2) {
            throw new IllegalArgumentException("Enter your full legal name (first and last)");
        }
        String email = email(rawEmail);
        if (phone == null || phone.isBlank()) {
            throw new IllegalArgumentException("Phone number is required");
        }
        String course = technology(technology, true);
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new IllegalStateException("There's already an account with this email. Sign in instead.");
        }
        String phoneNormalized = PhoneNumbers.requireAvailable(userRepository, phone, null);
        // Two open applications with one number would leave the second
        // applicant unable to register once the first one has.
        if (applicationRepository.existsByPhoneNormalizedAndStatusInAndEmailNot(phoneNormalized, OPEN, email)) {
            throw new IllegalArgumentException(
                    "This phone number is already used on another application. "
                            + "Use a different number, or apply with the email you used before.");
        }

        ParticipantApplication open = applicationRepository
                .findFirstByEmailAndStatusInOrderByCreatedAtDesc(email, OPEN).orElse(null);
        if (open != null && APPROVED.equals(open.getStatus())) {
            throw new IllegalStateException(
                    "Your application is already confirmed. Check your email for the registration link.");
        }
        if (open != null) {
            // Applied again before anyone looked at it: the latest details count.
            LocalDateTime lastSaved = open.getUpdatedAt() != null ? open.getUpdatedAt() : open.getCreatedAt();
            open.setFullName(name);
            open.setPhone(phone.trim());
            open.setPhoneNormalized(phoneNormalized);
            open.setSelectedTechnology(course);
            ParticipantApplication saved = applicationRepository.save(open);
            // The page says a confirmation was sent: send it again, at most
            // once a minute so a repeated click can't flood the inbox.
            if (lastSaved == null
                    || Duration.between(lastSaved, LocalDateTime.now()).getSeconds() >= RESEND_COOLDOWN_SECONDS) {
                emailTemplateService.sendApplicationReceivedEmail(email, name, course);
            }
            return saved;
        }

        ParticipantApplication saved = applicationRepository.save(ParticipantApplication.builder()
                .fullName(name)
                .email(email)
                .phone(phone.trim())
                .phoneNormalized(phoneNormalized)
                .selectedTechnology(course)
                .status(PENDING)
                .source("WEBSITE")
                .ipAddress(ipAddress)
                .build());
        log.info("New application {} for {}", saved.getId(), course);

        emailTemplateService.sendApplicationReceivedEmail(email, name, course);
        notifyReviewers(saved);
        return saved;
    }

    /** Every active ERM hears about a new application (Operations' inbox when there is none). */
    private void notifyReviewers(ParticipantApplication a) {
        List<User> erms = userRepository.findAll().stream()
                .filter(u -> !Boolean.FALSE.equals(u.getIsActive()))
                .filter(u -> "ERM".equals(permissionService.roleOf(u)))
                .toList();
        for (User erm : erms) {
            try {
                emailTemplateService.sendApplicationToConfirmEmail(erm, a.getFullName(), a.getEmail(),
                        a.getPhone(), a.getSelectedTechnology());
            } catch (Exception e) {
                log.warn("Couldn't notify ERM {} about application {}: {}", erm.getId(), a.getId(), e.getMessage());
            }
        }
        if (erms.isEmpty() && operationsEmail != null && !operationsEmail.isBlank()) {
            User inbox = User.builder().email(operationsEmail.trim()).fullName("Operations").build();
            emailTemplateService.sendApplicationToConfirmEmail(inbox, a.getFullName(), a.getEmail(),
                    a.getPhone(), a.getSelectedTechnology());
        }
    }

    // ── Step 2: an ERM confirms (or declines) ────────────────────

    /** One row of the staff applications screen (never the link's hash or the applicant's IP). */
    public record Row(Long id, String fullName, String email, String phone, String selectedTechnology,
                      String status, String source, LocalDateTime createdAt, LocalDateTime reviewedAt,
                      String reviewedByName, String declineReason, LocalDateTime registrationEmailSentAt,
                      LocalDateTime linkExpiresAt, LocalDateTime registeredAt, Long userId) {}

    @Transactional(readOnly = true)
    public List<Row> list(Long callerId, String status) {
        requireRole(callerId, REVIEWERS);
        String s = status == null || status.isBlank() ? PENDING : status.trim().toUpperCase();
        List<ParticipantApplication> found;
        if ("ALL".equals(s)) {
            found = applicationRepository.findTop500ByOrderByCreatedAtDesc();
        } else if (Set.of(PENDING, APPROVED, REGISTERED, DECLINED).contains(s)) {
            found = applicationRepository.findTop500ByStatusOrderByCreatedAtAsc(s);
        } else {
            throw new IllegalArgumentException("Unknown status: " + status);
        }
        Map<Long, String> reviewers = new HashMap<>();
        userRepository.findAllById(found.stream().map(ParticipantApplication::getReviewedBy)
                        .filter(Objects::nonNull).distinct().toList())
                .forEach(u -> reviewers.put(u.getId(), u.getFullName()));
        return found.stream().map(a -> row(a, reviewers.get(a.getReviewedBy()))).toList();
    }

    public Row row(ParticipantApplication a) {
        String reviewer = a.getReviewedBy() == null ? null
                : userRepository.findById(a.getReviewedBy()).map(User::getFullName).orElse(null);
        return row(a, reviewer);
    }

    private static Row row(ParticipantApplication a, String reviewerName) {
        return new Row(a.getId(), a.getFullName(), a.getEmail(), a.getPhone(), a.getSelectedTechnology(),
                a.getStatus(), a.getSource(), a.getCreatedAt(), a.getReviewedAt(), reviewerName,
                a.getDeclineReason(), a.getRegistrationEmailSentAt(), a.getRegistrationTokenExpiresAt(),
                a.getRegisteredAt(), a.getUserId());
    }

    @Transactional(readOnly = true)
    public Map<String, Long> counts(Long callerId) {
        requireRole(callerId, REVIEWERS);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String s : List.of(PENDING, APPROVED, REGISTERED, DECLINED)) {
            counts.put(s, applicationRepository.countByStatus(s));
        }
        return counts;
    }

    /** Confirms a waiting (or earlier declined) application and emails the registration link. */
    @Transactional
    public Result confirm(Long applicationId, Long reviewerId) {
        User reviewer = requireRole(reviewerId, REVIEWERS);
        ParticipantApplication a = find(applicationId);
        if (REGISTERED.equals(a.getStatus())) {
            throw new IllegalStateException("This applicant has already registered.");
        }
        if (APPROVED.equals(a.getStatus())) {
            throw new IllegalStateException("Already confirmed. Use \"Send link again\" to email a new link.");
        }
        if (userRepository.existsByEmailIgnoreCase(a.getEmail())) {
            throw new IllegalStateException("There's already an account with " + a.getEmail() + ".");
        }
        a.setStatus(APPROVED);
        a.setReviewedBy(reviewerId);
        a.setReviewedAt(LocalDateTime.now());
        a.setDeclineReason(null);
        boolean sent = sendLink(a, false);
        recordService.record(reviewerId, "APPLICATION_CONFIRMED", RecordService.Category.ACCOUNT,
                "Confirmed an application", a.getFullName() + " <" + a.getEmail() + ">",
                Map.of("applicationId", a.getId(), "email", a.getEmail(), "emailSent", sent));
        log.info("User {} ({}) confirmed application {}; email sent: {}",
                reviewerId, permissionService.roleOf(reviewer), a.getId(), sent);
        return new Result(a, sent);
    }

    /** A new link for a confirmed applicant who hasn't registered (the old link stops working). */
    @Transactional
    public Result resend(Long applicationId, Long reviewerId) {
        requireRole(reviewerId, REVIEWERS);
        ParticipantApplication a = find(applicationId);
        if (!APPROVED.equals(a.getStatus())) {
            throw new IllegalStateException(REGISTERED.equals(a.getStatus())
                    ? "This applicant has already registered."
                    : "Confirm the application first.");
        }
        if (a.getRegistrationEmailSentAt() != null) {
            long since = Duration.between(a.getRegistrationEmailSentAt(), LocalDateTime.now()).getSeconds();
            if (since < RESEND_COOLDOWN_SECONDS) {
                throw new IllegalStateException("A link was emailed less than a minute ago. Try again shortly.");
            }
        }
        boolean sent = sendLink(a, "INVITE".equals(a.getSource()));
        recordService.record(reviewerId, "APPLICATION_LINK_RESENT", RecordService.Category.ACCOUNT,
                "Sent a registration link again", a.getFullName() + " <" + a.getEmail() + ">",
                Map.of("applicationId", a.getId(), "email", a.getEmail(), "emailSent", sent));
        return new Result(a, sent);
    }

    @Transactional
    public ParticipantApplication decline(Long applicationId, Long reviewerId, String reason) {
        requireRole(reviewerId, REVIEWERS);
        ParticipantApplication a = find(applicationId);
        if (REGISTERED.equals(a.getStatus())) {
            throw new IllegalStateException("This applicant has already registered.");
        }
        String why = reason == null ? "" : reason.trim();
        if (why.length() > MAX_REASON) {
            throw new IllegalArgumentException("Please keep the reason under " + MAX_REASON + " characters.");
        }
        a.setStatus(DECLINED);
        a.setReviewedBy(reviewerId);
        a.setReviewedAt(LocalDateTime.now());
        a.setDeclineReason(why.isEmpty() ? null : why);
        a.setRegistrationTokenHash(null);      // a link already sent stops working
        a.setRegistrationTokenExpiresAt(null);
        ParticipantApplication saved = applicationRepository.save(a);
        recordService.record(reviewerId, "APPLICATION_DECLINED", RecordService.Category.ACCOUNT,
                "Declined an application", a.getFullName() + " <" + a.getEmail() + ">",
                Map.of("applicationId", a.getId(), "email", a.getEmail(), "reason", why));
        return saved;
    }

    /**
     * Operations invites someone: an application that is confirmed from
     * the start, so the registration link goes out straight away.
     */
    @Transactional
    public boolean invite(Long callerId, String fullName, String rawEmail) {
        User caller = userRepository.findById(callerId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", callerId));
        if (!INVITERS.contains(permissionService.roleOf(caller))) {
            throw new AccessDeniedException("Only a System Admin or Operations admin can invite participants.");
        }
        String name = PersonNames.clean(fullName);
        String email = email(rawEmail);
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new IllegalStateException("There's already an account with " + email + ".");
        }
        ParticipantApplication a = applicationRepository
                .findFirstByEmailAndStatusInOrderByCreatedAtDesc(email, OPEN)
                .orElseGet(() -> ParticipantApplication.builder().email(email).fullName(name).source("INVITE").build());
        // An open application keeps the name its applicant gave; a link sent
        // moments ago isn't sent again (the same rule as "Send link again").
        if (APPROVED.equals(a.getStatus()) && a.getRegistrationEmailSentAt() != null
                && Duration.between(a.getRegistrationEmailSentAt(), LocalDateTime.now()).getSeconds()
                        < RESEND_COOLDOWN_SECONDS) {
            throw new IllegalStateException("A link was emailed less than a minute ago. Try again shortly.");
        }
        a.setStatus(APPROVED);
        a.setReviewedBy(callerId);
        a.setReviewedAt(LocalDateTime.now());
        boolean sent = sendLink(a, true);
        recordService.record(callerId, "PARTICIPANT_INVITED", RecordService.Category.ACCOUNT,
                "Invited a participant to register", a.getFullName() + " <" + email + ">",
                Map.of("applicationId", a.getId(), "email", email, "emailSent", sent));
        return sent;
    }

    /** Gives the application a fresh one-time link and emails it. */
    private boolean sendLink(ParticipantApplication a, boolean invited) {
        String token = newToken();
        a.setRegistrationTokenHash(AuthService.hashOtp(token));
        a.setRegistrationTokenExpiresAt(LocalDateTime.now().plusDays(LINK_DAYS));
        ParticipantApplication saved = applicationRepository.save(a);
        if (a.getId() == null) a.setId(saved.getId());
        String link = appUrl + "/register?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        boolean sent = false;
        try {
            sent = emailTemplateService.sendRegistrationEmail(a.getEmail(), a.getFullName(),
                    a.getSelectedTechnology(), link, LINK_DAYS, invited);
        } catch (Exception e) {
            log.warn("Registration email for application {} failed: {}", a.getId(), e.getMessage());
        }
        if (sent) {
            a.setRegistrationEmailSentAt(LocalDateTime.now());
            applicationRepository.save(a);
        }
        return sent;
    }

    // ── Step 3: the applicant registers from the link ────────────

    /** What the registration page shows for a link that still works. */
    @Transactional(readOnly = true)
    public Map<String, Object> registrationDetails(String token) {
        ParticipantApplication a = byToken(token);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("fullName", a.getFullName());
        details.put("email", a.getEmail());
        details.put("selectedTechnology", a.getSelectedTechnology() == null ? "" : a.getSelectedTechnology());
        details.put("needsPhone", needsPhone(a));
        return details;
    }

    /**
     * The applicant types a phone number when they gave none (an invite) or
     * when the one they gave now belongs to another account.
     */
    private boolean needsPhone(ParticipantApplication a) {
        if (a.getPhone() == null || a.getPhone().isBlank()) return true;
        try {
            PhoneNumbers.requireAvailable(userRepository, a.getPhone(), null);
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    /**
     * Uses the link once: creates the participant account (email already
     * verified by the link), gives it a Participant ID and signs the
     * person in. A failure leaves the link usable.
     */
    @Transactional
    public AuthResponse register(String token, String password, String phone) {
        if (token == null || token.isBlank() || token.length() > 200) {
            throw new IllegalArgumentException(LINK_GONE);
        }
        // Locked until this finishes, so a double click can't register twice.
        ParticipantApplication a = applicationRepository
                .findByRegistrationTokenHashForUpdate(AuthService.hashOtp(token.trim()))
                .orElseThrow(() -> new IllegalArgumentException(LINK_GONE));
        requireUsable(a);
        boolean typedPhone = needsPhone(a);
        String usePhone = typedPhone ? phone : a.getPhone();
        AuthResponse auth = authService.registerConfirmedParticipant(
                a.getFullName(), a.getEmail(), usePhone, a.getSelectedTechnology(), password);
        a.setStatus(REGISTERED);
        a.setRegisteredAt(LocalDateTime.now());
        a.setUserId(auth.getUser().getId());
        a.setRegistrationTokenHash(null);
        a.setRegistrationTokenExpiresAt(null);
        if (typedPhone && usePhone != null) {
            a.setPhone(usePhone.trim());
            a.setPhoneNormalized(PhoneNumbers.normalizeOrNull(usePhone));
        }
        applicationRepository.save(a);
        recordService.record(auth.getUser().getId(), "APPLICATION_REGISTERED", RecordService.Category.ACCOUNT,
                "Registered from a confirmed application",
                "Application #" + a.getId() + (a.getReviewedBy() == null ? "" : ", confirmed by user #" + a.getReviewedBy()),
                Map.of("applicationId", a.getId(),
                        "confirmedBy", a.getReviewedBy() == null ? "" : a.getReviewedBy()));
        return auth;
    }

    static final String LINK_GONE =
            "This registration link has expired or was already used. Ask us to send you a new one.";

    private ParticipantApplication byToken(String token) {
        if (token == null || token.isBlank() || token.length() > 200) {
            throw new IllegalArgumentException(LINK_GONE);
        }
        ParticipantApplication a = applicationRepository
                .findByRegistrationTokenHash(AuthService.hashOtp(token.trim()))
                .orElseThrow(() -> new IllegalArgumentException(LINK_GONE));
        requireUsable(a);
        return a;
    }

    private static void requireUsable(ParticipantApplication a) {
        if (!APPROVED.equals(a.getStatus()) || a.getRegistrationTokenExpiresAt() == null
                || a.getRegistrationTokenExpiresAt().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException(LINK_GONE);
        }
    }

    // ── Internals ────────────────────────────────────────────────

    private ParticipantApplication find(Long id) {
        return applicationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Application", "id", id));
    }

    private User requireRole(Long callerId, Set<String> roles) {
        User caller = callerId == null ? null : userRepository.findById(callerId).orElse(null);
        if (caller == null || !roles.contains(permissionService.roleOf(caller))) {
            throw new AccessDeniedException("Only an ERM or an Operations admin can manage applications.");
        }
        return caller;
    }

    private static String email(String raw) {
        String email = AuthService.normalizeEmail(raw);
        if (email == null || email.isBlank()) throw new IllegalArgumentException("Email is required");
        if (email.length() > 254 || !EMAIL.matcher(email).matches()) {
            throw new IllegalArgumentException("Enter a valid email address.");
        }
        return email;
    }

    private static String technology(String raw, boolean required) {
        String t = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        if (t.isEmpty()) {
            if (required) throw new IllegalArgumentException("Choose the course you're applying for.");
            return null;
        }
        if (t.length() > MAX_TECHNOLOGY || !TECHNOLOGY.matcher(t).matches()) {
            throw new IllegalArgumentException("That course name isn't valid. Choose one from the list.");
        }
        return t;
    }

    /** 32 random bytes, URL-safe: only its hash is stored. */
    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
