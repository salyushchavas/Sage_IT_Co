package com.spire.backend.entity;

/**
 * Roles for the consultant-agreement console ({@link AgreementUser}).
 *
 * SUPER_ADMIN  -- single bootstrapped account (env-driven, not
 *                 creatable through the UI). Manages ERM accounts.
 * ERM          -- created through the admin console; uses the existing
 *                 agreement-sending flow. The only role the console
 *                 can mint.
 *
 * Deliberately separate from the platform-wide LMS roles (the {@code
 * roles} table / {@link Role}); this surface is its own small user
 * directory.
 */
public enum AgreementUserRole {
    SUPER_ADMIN,
    ERM
}
