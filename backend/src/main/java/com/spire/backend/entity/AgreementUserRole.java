package com.spire.backend.entity;

/**
 * Roles for the consultant-agreement console ({@link AgreementUser}).
 *
 * SUPER_ADMIN  -- single bootstrapped account (env-driven, not
 *                 creatable through the UI). Manages console accounts;
 *                 implicitly holds every permission.
 * ERM          -- created through the admin console; uses the existing
 *                 agreement-sending flow + the final countersign.
 * MANAGER      -- 3A approver. Reviews the consultant-signed agreement
 *                 and Approves / Requests-Revision at the Manager gate
 *                 (Phase 1 + Phase 2).
 * ACCOUNTS     -- 3A approver. Same approver functionality as MANAGER
 *                 but fills the Accounts gate (Phase 2 only).
 *
 * One role per user (the permission set is derived from the role -- see
 * {@link com.spire.backend.security.AgreementAuthz#permissionsFor}).
 *
 * Deliberately separate from the platform-wide LMS roles (the {@code
 * roles} table / {@link Role}); this surface is its own small user
 * directory.
 */
public enum AgreementUserRole {
    SUPER_ADMIN,
    ERM,
    MANAGER,
    ACCOUNTS
}
