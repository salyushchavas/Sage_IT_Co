package com.spire.backend.config;

import com.spire.backend.entity.Role;
import com.spire.backend.entity.User;
import com.spire.backend.entity.WebAgreementErmAssignment;
import com.spire.backend.entity.WebAgreementStaffTitle;
import com.spire.backend.repository.RoleRepository;
import com.spire.backend.repository.UserRepository;
import com.spire.backend.repository.WebAgreementErmAssignmentRepository;
import com.spire.backend.repository.WebAgreementStaffTitleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fake approver accounts for testing the website agreement's approval
 * chain, like the fake ERMs DataSeeder created (every @sageitco.com staff
 * account seeded by the code is a test account). Runs once the app is
 * ready (after DataSeeder has made the MANAGER and ACCOUNTS roles), in a
 * transaction of its own, and never stops the server from starting.
 *
 * <p>Each account is created only when its email is free; an existing
 * account is never changed. Only the bcrypt hashes are here; the passwords
 * are in the owner's private notes. Each new approver joins the approver
 * team of the fake ERMs once (a link an admin later removes is not added
 * again, and an approver an admin moved to another role is left alone),
 * and the fake ERMs get a title for the countersign prefill only when they
 * have none. No email is sent.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebAgreementTestApprovers {

    /** Columns: email, full name, role, title, bcrypt hash of the private password. */
    static final String[][] ACCOUNTS = {
            {"manager.test@sageitco.com", "Test Manager", "MANAGER", "Manager",
                    "$2a$10$Ayh19K/SnmlSh2WYMiJRZOX1KhDUeoMAEH66ncTA0Z3aWYJN2Jlye"},
            {"accounts.test@sageitco.com", "Test Accounts", "ACCOUNTS", "Accounts",
                    "$2a$10$Dk1A.Kh9NEd6kgSEMn1Tg.oUxe54TZ/ObxYV57VGTVDHU8q9Oa0t6"},
    };

    /** The fake ERMs whose approver team the test approvers join. */
    static final String[] TEST_ERMS = {"erm@sageitco.com", "deepthi.erm@sageitco.com"};

    /** Title for the fake ERMs' countersign prefill, set only when they have none. */
    static final String TEST_ERM_TITLE = "Employee Relationship Manager";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final WebAgreementErmAssignmentRepository assignmentRepository;
    private final WebAgreementStaffTitleRepository staffTitleRepository;
    private final PlatformTransactionManager transactionManager;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tx.executeWithoutResult(status -> ensure());
        } catch (Exception e) {
            log.warn("Test approver accounts skipped: {}", e.getMessage());
        }
    }

    void ensure() {
        for (String[] row : ACCOUNTS) {
            Role role = roleRepository.findByName(row[2]).orElse(null);
            if (role == null) {
                log.warn("{} role missing — {} not seeded", row[2], row[0]);
                continue;
            }
            User approver = userRepository.findByEmail(row[0]).orElse(null);
            if (approver == null) {
                approver = userRepository.save(User.builder()
                        .email(row[0])
                        .passwordHash(row[4])
                        .fullName(row[1])
                        .role(role)
                        .isActive(true)
                        .emailVerified(true)
                        .agreementAccepted(true)
                        .currentStatus("DASHBOARD_ENABLED")
                        .build());
                log.info("Seeded test approver {} ({}) with role {}", row[0], row[1], row[2]);
            }
            if (staffTitleRepository.findByUserId(approver.getId()).isEmpty()) {
                staffTitleRepository.save(WebAgreementStaffTitle.builder()
                        .userId(approver.getId()).title(row[3]).build());
            }
            if (!hasRole(approver, row[2])) continue;
            for (String ermEmail : TEST_ERMS) {
                User erm = userRepository.findByEmail(ermEmail).orElse(null);
                if (!hasRole(erm, "ERM")) continue;
                if (assignmentRepository
                        .findByErmUserIdAndApproverUserIdAndRole(erm.getId(), approver.getId(), row[2]).isEmpty()) {
                    assignmentRepository.save(WebAgreementErmAssignment.builder()
                            .ermUserId(erm.getId()).approverUserId(approver.getId()).role(row[2]).build());
                    log.info("Added test approver {} to {}'s approver team", row[0], ermEmail);
                }
            }
        }
        for (String ermEmail : TEST_ERMS) {
            User erm = userRepository.findByEmail(ermEmail).orElse(null);
            if (!hasRole(erm, "ERM")) continue;
            if (staffTitleRepository.findByUserId(erm.getId()).isEmpty()) {
                staffTitleRepository.save(WebAgreementStaffTitle.builder()
                        .userId(erm.getId()).title(TEST_ERM_TITLE).build());
            }
        }
    }

    private static boolean hasRole(User user, String role) {
        return user != null && user.getRole() != null && role.equals(user.getRole().getName());
    }
}
