package com.spire.backend.service;

import com.spire.backend.dto.UserDTO;
import com.spire.backend.entity.Role;
import com.spire.backend.entity.User;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.repository.RoleRepository;
import com.spire.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Staff onboarding (asked for on 25 Sep): a System Admin adds a staff
 * member with a company login email (e.g. name@sageitco.com), a role and
 * their own (personal) email. The portal makes a temporary password and
 * emails the login details to the personal email; every later email for
 * that account goes there too, since the company address may not be a
 * mailbox. At first sign-in they must choose their own password.
 *
 * The agreement approvers (MANAGER, ACCOUNTS) follow the console instead
 * (AgreementAdminController:142-189, 214-241): no email; the temporary
 * password comes back once in the response, for the System Admin to share.
 * ERM, MANAGER and ACCOUNTS accounts need a title (printed on agreements),
 * kept in web_agreement_staff_titles.
 *
 * Participants aren't created here: they apply, an ERM confirms, and they
 * register from the emailed link (ParticipantApplicationService, which
 * also holds Operations' "Invite a participant").
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StaffOnboardingService {

    /** The roles a System Admin can add here, with their plain names. */
    public static final Map<String, String> STAFF_ROLES = new LinkedHashMap<>();
    static {
        STAFF_ROLES.put("ERM", "ERM (relationship manager)");
        // The console's labels (UserModals.tsx:315, 684-688).
        STAFF_ROLES.put("MANAGER", "Manager — approval gate (Phase 1 + 2)");
        STAFF_ROLES.put("ACCOUNTS", "Accounts — approval gate (Phase 2)");
        STAFF_ROLES.put("COACH", "Coach");
        STAFF_ROLES.put("TECHNICAL_ADVISOR", "Technical advisor");
        STAFF_ROLES.put("FINANCE", "Finance");
        STAFF_ROLES.put("OPERATIONS_ADMIN", "Operations admin");
        STAFF_ROLES.put("SYSTEM_ADMIN", "System admin");
    }

    /** The console's account roles: a title is required when one is added (AgreementAdminController:160-162). */
    static final Set<String> TITLE_REQUIRED_ROLES = Set.of("ERM", "MANAGER", "ACCOUNTS");
    /**
     * Roles the website offered before the title box. Its form sends no
     * title at all (the new one always sends the field, blank or not), so
     * such a request still adds the account without one, as it did then.
     */
    static final Set<String> ROLES_ADDED_BEFORE_TITLES = Set.of("ERM");
    /** New roles that follow the console: the temporary password is shown once on screen, never emailed. */
    static final Set<String> NO_EMAIL_ROLES = Set.of("MANAGER", "ACCOUNTS");

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    /** No look-alike characters (0/O, 1/l/I). */
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final RecordService recordService;
    private final EmailTemplateService emailTemplateService;
    private final WebAgreementStaffTitleService staffTitleService;

    /**
     * What happened: the account, and whether the login email went out (and
     * where). For MANAGER and ACCOUNTS nothing is emailed: emailSent is
     * false, sentTo null, and temporaryPassword holds the one-time password
     * to show on screen (null for every other role).
     */
    public record Result(UserDTO user, boolean emailSent, String sentTo, String temporaryPassword) {}

    @Transactional
    public Result createStaff(Long callerId, String fullName, String loginEmail, String personalEmail, String roleName,
                              String title) {
        User caller = requireRole(callerId, Set.of("SYSTEM_ADMIN"), "Only a System Admin can add staff accounts.");
        String name = PersonNames.clean(fullName);
        String login = email(loginEmail, "company (login) email");
        String personal = personalEmail == null || personalEmail.isBlank() ? null : email(personalEmail, "personal email");
        if (login.equals(personal)) personal = null;
        String role = roleName == null ? "" : roleName.trim().toUpperCase(Locale.ROOT);
        if (!STAFF_ROLES.containsKey(role)) {
            throw new IllegalArgumentException("Pick a staff role: " + String.join(", ", STAFF_ROLES.values()) + ".");
        }
        boolean titleRequired = TITLE_REQUIRED_ROLES.contains(role)
                && !(title == null && ROLES_ADDED_BEFORE_TITLES.contains(role));
        String cleanTitle = WebAgreementStaffTitleService.clean(title, titleRequired);
        if (userRepository.existsByEmailIgnoreCase(login)) {
            throw new IllegalStateException("There's already an account with " + login + ".");
        }
        Role r = roleRepository.findByName(role)
                .orElseThrow(() -> new IllegalStateException("The " + role + " role is missing on this server."));

        String temporary = temporaryPassword();
        User saved = userRepository.save(User.builder()
                .fullName(name)
                .email(login)
                .passwordHash(passwordEncoder.encode(temporary))
                .role(r)
                .isActive(true)
                // The admin vouches for the address; there's no code to type.
                .emailVerified(true)
                .agreementAccepted(true)
                .currentStatus("DASHBOARD_ENABLED")
                .personalEmail(personal)
                .mustChangePassword(true)
                .build());
        recordService.record(saved.getId(), "ACCOUNT_CREATED_BY_ADMIN", RecordService.Category.ACCOUNT,
                "Staff account created",
                STAFF_ROLES.get(role) + " account created by " + caller.getFullName() + " (user #" + callerId + ")",
                Map.of("role", role, "createdBy", callerId, "personalEmail", personal == null ? "" : personal));
        if (!cleanTitle.isEmpty()) staffTitleService.setTitle(saved.getId(), cleanTitle, callerId);
        if (NO_EMAIL_ROLES.contains(role)) {
            log.info("Staff account {} ({}) created by user {}; password shown on screen, no email", saved.getId(), role, callerId);
            return new Result(UserDTO.from(saved), false, null, temporary);
        }
        boolean sent = emailTemplateService.sendStaffLoginEmail(saved, STAFF_ROLES.get(role), temporary, false);
        log.info("Staff account {} ({}) created by user {}; login email sent: {}", saved.getId(), role, callerId, sent);
        return new Result(UserDTO.from(saved), sent, personal != null ? personal : login, null);
    }

    /**
     * A new temporary password, emailed like the first one (e.g. they lost
     * it, or the first email didn't arrive). They must change it again.
     * For MANAGER and ACCOUNTS it is the console's "Reset password": no
     * email, the password comes back once in the result.
     */
    @Transactional
    public Result sendNewLoginDetails(Long callerId, Long userId) {
        User caller = requireRole(callerId, Set.of("SYSTEM_ADMIN"), "Only a System Admin can send new login details.");
        if (userId.equals(callerId)) {
            throw new IllegalArgumentException("Use \"Change password\" for your own account.");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        String role = roleOf(user);
        if (!STAFF_ROLES.containsKey(role)) {
            throw new IllegalArgumentException("This is for staff accounts. Participants use \"Forgot password\" on the sign-in page.");
        }
        if (Boolean.FALSE.equals(user.getIsActive())) {
            throw new IllegalStateException("This account is deactivated. Reactivate it first.");
        }
        String temporary = temporaryPassword();
        user.setPasswordHash(passwordEncoder.encode(temporary));
        user.setMustChangePassword(true);
        user.endEarlierSessions();
        User saved = userRepository.save(user);
        if (NO_EMAIL_ROLES.contains(role)) {
            recordService.record(userId, "ACCOUNT_LOGIN_DETAILS_SENT", RecordService.Category.SECURITY,
                    "Password reset",
                    "A new temporary password was set by " + caller.getFullName() + " (user #" + callerId
                            + ") and shown on screen; no email was sent",
                    Map.of("sentBy", callerId));
            return new Result(UserDTO.from(saved), false, null, temporary);
        }
        recordService.record(userId, "ACCOUNT_LOGIN_DETAILS_SENT", RecordService.Category.SECURITY,
                "New login details sent",
                "A new temporary password was emailed by " + caller.getFullName() + " (user #" + callerId + ")",
                Map.of("sentBy", callerId));
        boolean sent = emailTemplateService.sendStaffLoginEmail(saved, STAFF_ROLES.get(role), temporary, true);
        String to = saved.getPersonalEmail() != null ? saved.getPersonalEmail() : saved.getEmail();
        return new Result(UserDTO.from(saved), sent, to, null);
    }

    /** "Kp7m-X2qd-9RtW-hn4c": four groups of four, no look-alike characters (about 94 bits). */
    static String temporaryPassword() {
        StringBuilder sb = new StringBuilder();
        for (int group = 0; group < 4; group++) {
            if (group > 0) sb.append('-');
            for (int i = 0; i < 4; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    private static String email(String value, String what) {
        String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!EMAIL.matcher(v).matches() || v.length() > 255) {
            throw new IllegalArgumentException("Enter a valid " + what + ".");
        }
        return v;
    }

    private User requireRole(Long callerId, Set<String> allowed, String message) {
        User caller = callerId == null ? null : userRepository.findById(callerId).orElse(null);
        if (caller == null || !allowed.contains(roleOf(caller))) throw new AccessDeniedException(message);
        return caller;
    }

    private static String roleOf(User u) {
        return u == null || u.getRole() == null || u.getRole().getName() == null
                ? "" : u.getRole().getName().toUpperCase(Locale.ROOT);
    }
}
