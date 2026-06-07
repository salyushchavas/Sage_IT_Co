package com.spire.backend.security;

import com.spire.backend.entity.AgreementUserRole;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.AccessDeniedException;

/**
 * Small helpers for reading the authenticated agreement user's identity
 * off the request and enforcing the super-admin guard.
 *
 * {@link AgreementErmAuthFilter} stamps these request attributes from
 * the validated JWT; controllers read them via these accessors (or
 * {@code @RequestAttribute}). The super-admin check throws Spring
 * Security's {@link AccessDeniedException}, which {@code
 * GlobalExceptionHandler} maps to 403.
 */
public final class AgreementAuthz {

    public static final String ATTR_USER_ID = "agreementUserId";
    public static final String ATTR_ROLE = "agreementUserRole";
    public static final String ATTR_EMAIL = "agreementUserEmail";
    public static final String ATTR_FULL_NAME = "agreementUserFullName";
    public static final String ATTR_TITLE = "agreementUserTitle";

    public static final String ROLE_SUPER_ADMIN = "SUPER_ADMIN";

    private AgreementAuthz() {}

    public static String userId(HttpServletRequest request) {
        Object v = request.getAttribute(ATTR_USER_ID);
        return v == null ? null : v.toString();
    }

    public static String role(HttpServletRequest request) {
        Object v = request.getAttribute(ATTR_ROLE);
        return v == null ? null : v.toString();
    }

    /**
     * The caller's role as an enum, or null if absent/unrecognized
     * (e.g. a legacy pre-multi-user token). A null role is treated as
     * non-super-admin by the ownership checks.
     */
    public static AgreementUserRole roleEnum(HttpServletRequest request) {
        String r = role(request);
        if (r == null) return null;
        try {
            return AgreementUserRole.valueOf(r);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Throws 403 unless the caller is the SUPER_ADMIN. */
    public static void requireSuperAdmin(HttpServletRequest request) {
        if (!ROLE_SUPER_ADMIN.equals(role(request))) {
            throw new AccessDeniedException("Super-admin access required.");
        }
    }
}
