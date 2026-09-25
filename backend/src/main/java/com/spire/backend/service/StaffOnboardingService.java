package com.spire.backend.service;

import com.spire.backend.dto.UserDTO;
import com.spire.backend.entity.Role;
import com.spire.backend.entity.User;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.repository.RoleRepository;
import com.spire.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
 * Participants aren't created here: they enroll themselves (roadmap steps
 * 1–3 verify their email and give them a Participant ID). An admin can
 * email them an invitation to enroll instead.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StaffOnboardingService {

    /** The roles a System Admin can add here, with their plain names. */
    public static final Map<String, String> STAFF_ROLES = new LinkedHashMap<>();
    static {
        STAFF_ROLES.put("ERM", "ERM (relationship manager)");
        STAFF_ROLES.put("COACH", "Coach");
        STAFF_ROLES.put("TECHNICAL_ADVISOR", "Technical advisor");
        STAFF_ROLES.put("FINANCE", "Finance");
        STAFF_ROLES.put("OPERATIONS_ADMIN", "Operations admin");
        STAFF_ROLES.put("SYSTEM_ADMIN", "System admin");
    }

    /** Who may invite a participant to enroll. */
    private static final Set<String> INVITERS = Set.of("SYSTEM_ADMIN", "OPERATIONS_ADMIN");

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    /** No look-alike characters (0/O, 1/l/I). */
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final RecordService recordService;
    private final EmailTemplateService emailTemplateService;

    @Value("${app.url:https://sageitco.com}")
    private String appUrl;

    /** What happened: the account, and whether the login email went out (and where). */
    public record Result(UserDTO user, boolean emailSent, String sentTo) {}

    @Transactional
    public Result createStaff(Long callerId, String fullName, String loginEmail, String personalEmail, String roleName) {
        User caller = requireRole(callerId, Set.of("SYSTEM_ADMIN"), "Only a System Admin can add staff accounts.");
        String name = PersonNames.clean(fullName);
        String login = email(loginEmail, "company (login) email");
        String personal = personalEmail == null || personalEmail.isBlank() ? null : email(personalEmail, "personal email");
        if (login.equals(personal)) personal = null;
        String role = roleName == null ? "" : roleName.trim().toUpperCase(Locale.ROOT);
        if (!STAFF_ROLES.containsKey(role)) {
            throw new IllegalArgumentException("Pick a staff role: " + String.join(", ", STAFF_ROLES.values()) + ".");
        }
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
        boolean sent = emailTemplateService.sendStaffLoginEmail(saved, STAFF_ROLES.get(role), temporary, false);
        log.info("Staff account {} ({}) created by user {}; login email sent: {}", saved.getId(), role, callerId, sent);
        return new Result(UserDTO.from(saved), sent, personal != null ? personal : login);
    }

    /**
     * A new temporary password, emailed like the first one (e.g. they lost
     * it, or the first email didn't arrive). They must change it again.
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
        User saved = userRepository.save(user);
        recordService.record(userId, "ACCOUNT_LOGIN_DETAILS_SENT", RecordService.Category.SECURITY,
                "New login details sent",
                "A new temporary password was emailed by " + caller.getFullName() + " (user #" + callerId + ")",
                Map.of("sentBy", callerId));
        boolean sent = emailTemplateService.sendStaffLoginEmail(saved, STAFF_ROLES.get(role), temporary, true);
        String to = saved.getPersonalEmail() != null ? saved.getPersonalEmail() : saved.getEmail();
        return new Result(UserDTO.from(saved), sent, to);
    }

    /**
     * An emailed invitation to enroll, with the enrollment page's link
     * (their name and email filled in). They verify their email and get
     * their Participant ID there, as every participant does.
     */
    public boolean inviteParticipant(Long callerId, String fullName, String emailAddress) {
        User caller = requireRole(callerId, INVITERS, "Only a System Admin or Operations admin can invite participants.");
        String name = PersonNames.clean(fullName);
        String address = email(emailAddress, "email");
        if (userRepository.existsByEmailIgnoreCase(address)) {
            throw new IllegalStateException("There's already an account with " + address + ".");
        }
        String link = appUrl + "/enroll?email=" + URLEncoder.encode(address, StandardCharsets.UTF_8)
                + "&name=" + URLEncoder.encode(name, StandardCharsets.UTF_8);
        boolean sent = emailTemplateService.sendParticipantInviteEmail(address, name, link);
        recordService.record(callerId, "PARTICIPANT_INVITED", RecordService.Category.ACCOUNT,
                "Invited a participant to enroll", name + " <" + address + ">",
                Map.of("email", address, "emailSent", sent));
        log.info("User {} invited {} to enroll; email sent: {}", caller.getId(), address, sent);
        return sent;
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
