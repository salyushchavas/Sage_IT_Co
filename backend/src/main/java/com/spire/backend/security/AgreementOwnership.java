package com.spire.backend.security;

import com.spire.backend.entity.AgreementUserRole;
import com.spire.backend.entity.ConsultantApplication;
import com.spire.backend.exception.ResourceNotFoundException;

/**
 * Per-ERM data isolation (Phase B). Single source of truth for "can
 * this agreement user touch this application?".
 *
 * Rule: the SUPER_ADMIN sees/acts on everything; an ERM only on
 * applications they own ({@code owner_erm_id == their userId}).
 *
 * Non-owners are treated as if the application does not exist: {@link
 * #assertCanAccess} throws {@link ResourceNotFoundException} (→ 404 via
 * GlobalExceptionHandler), not 403 — standard tenant isolation that
 * avoids confirming an application's existence across ERMs.
 *
 * Every ERM-authenticated endpoint that takes an appId must call
 * {@link #assertCanAccess} immediately after loading the application and
 * before any read or mutation. NOTE: deliberately NOT enforced inside
 * {@code ConsultantApplicationService.getByApplicationId}, which is also
 * used by the public consultant-side flow that must stay open.
 */
public final class AgreementOwnership {

    private AgreementOwnership() {}

    public static boolean canView(ConsultantApplication app, String userId, AgreementUserRole role) {
        if (role == AgreementUserRole.SUPER_ADMIN) {
            return true;
        }
        return app.getOwnerErmId() != null && app.getOwnerErmId().equals(userId);
    }

    public static void assertCanAccess(ConsultantApplication app, String userId, AgreementUserRole role) {
        if (!canView(app, userId, role)) {
            // 404, not 403 — tenant isolation.
            throw new ResourceNotFoundException(
                    "ConsultantApplication", "applicationId", app.getApplicationId());
        }
    }
}
