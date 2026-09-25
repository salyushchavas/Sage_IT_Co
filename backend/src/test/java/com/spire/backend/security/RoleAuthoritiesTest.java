package com.spire.backend.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checklist 6.1: System Admin opens the admin pages, so it must pass the
 * endpoints that only name the legacy ADMIN role. Other roles are unchanged.
 */
class RoleAuthoritiesTest {

    @Test
    void systemAdminAlsoCarriesTheAdminAuthority() {
        assertEquals(List.of(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN"), new SimpleGrantedAuthority("ROLE_ADMIN")),
                JwtAuthFilter.authoritiesFor("SYSTEM_ADMIN"));
    }

    @Test
    void everyOtherRoleKeepsOnlyItsOwnAuthority() {
        for (String role : List.of("ADMIN", "OPERATIONS_ADMIN", "ERM", "COACH", "FINANCE", "PARTICIPANT", "INSTRUCTOR")) {
            assertEquals(List.of(new SimpleGrantedAuthority("ROLE_" + role)), JwtAuthFilter.authoritiesFor(role), role);
        }
    }
}
