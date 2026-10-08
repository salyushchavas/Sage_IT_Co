package com.spire.backend.service;

import com.spire.backend.entity.User;
import com.spire.backend.entity.WebAgreement;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.repository.UserRepository;
import com.spire.backend.repository.WebAgreementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Who may act on website agreements, shared by the staff, approval,
 * approver, countersign and System Admin services so none of them depends
 * on another. The staff checks are moved copies of
 * {@link WebAgreementStaffService}'s (same rules, same messages).
 *
 * Every check re-reads the caller from the database and refuses a
 * deactivated account.
 */
@Service
@RequiredArgsConstructor
public class WebAgreementAccess {

    /** Website roles that work on agreements. */
    public static final Set<String> STAFF = Set.of("ERM", "OPERATIONS_ADMIN", "SYSTEM_ADMIN");

    /** Website roles that see and act on every agreement (the console's super-admin). */
    public static final Set<String> ADMINS = Set.of("OPERATIONS_ADMIN", "SYSTEM_ADMIN");

    /**
     * Website roles that may use the approver API: the two gates, plus the
     * System Admin (the console's super-admin, who names the gate it acts on).
     */
    public static final Set<String> APPROVERS = Set.of("MANAGER", "ACCOUNTS", "SYSTEM_ADMIN");

    private final WebAgreementRepository agreementRepository;
    private final UserRepository userRepository;

    /** The caller, when they are an ERM or an admin; 403 otherwise. */
    public User requireStaff(Long callerId) {
        User caller = activeCaller(callerId);
        if (caller == null || !STAFF.contains(roleOf(caller))) {
            throw new AccessDeniedException("Only an ERM or an Operations admin can work on agreements.");
        }
        return caller;
    }

    /**
     * The agreement, when the caller may see it: the owner ERM or an admin.
     * Anyone else, and an archived agreement, gets 404 (never 403), so an
     * ERM can't learn that another ERM's agreement exists.
     */
    public WebAgreement requireAccess(String applicationId, Long callerId) {
        User caller = requireStaff(callerId);
        WebAgreement a = agreementRepository.findByApplicationId(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException("Agreement not found."));
        if (Boolean.TRUE.equals(a.getDeleted())) {
            throw new ResourceNotFoundException("Agreement not found.");
        }
        if (!isAdmin(caller) && !callerId.equals(a.getOwnerUserId())) {
            throw new ResourceNotFoundException("Agreement not found.");
        }
        return a;
    }

    /**
     * The caller, when they are a System Admin; 403 otherwise. Checked in
     * the service by role name because legacy ADMIN also holds ROLE_ADMIN
     * and the System Admin holds it too.
     */
    public User requireSystemAdmin(Long callerId) {
        User caller = activeCaller(callerId);
        if (caller == null || !"SYSTEM_ADMIN".equals(roleOf(caller))) {
            throw new AccessDeniedException("Only a System Admin can do this.");
        }
        return caller;
    }

    /**
     * The caller, when they are a Manager, an Accounts approver or the
     * System Admin; 403 "Approver role required." otherwise (the console's
     * approver gate). Which gate a System Admin acts on is resolved by the
     * approver service from its {@code role} parameter.
     */
    public User requireApprover(Long callerId) {
        User caller = activeCaller(callerId);
        if (caller == null || !APPROVERS.contains(roleOf(caller))) {
            throw new AccessDeniedException("Approver role required.");
        }
        return caller;
    }

    public static boolean isAdmin(User u) {
        return ADMINS.contains(roleOf(u));
    }

    /** The user's website role name, upper-cased; "" when none. */
    public static String roleOf(User u) {
        return u == null || u.getRole() == null || u.getRole().getName() == null
                ? "" : u.getRole().getName().toUpperCase();
    }

    /** The owning ERM's display name on each row (one query for the page). */
    public void populateOwnerNames(List<WebAgreement> agreements) {
        if (agreements == null || agreements.isEmpty()) return;
        Set<Long> ownerIds = agreements.stream()
                .map(WebAgreement::getOwnerUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ownerIds.isEmpty()) return;
        Map<Long, String> idToName = new HashMap<>();
        for (User u : userRepository.findAllById(ownerIds)) {
            idToName.put(u.getId(), u.getFullName());
        }
        for (WebAgreement a : agreements) {
            if (a.getOwnerUserId() != null) a.setOwnerName(idToName.get(a.getOwnerUserId()));
        }
    }

    /** The caller re-read from the database; null when unknown or deactivated. */
    private User activeCaller(Long callerId) {
        User caller = callerId == null ? null : userRepository.findById(callerId).orElse(null);
        if (caller == null || Boolean.FALSE.equals(caller.getIsActive())) return null;
        return caller;
    }
}
