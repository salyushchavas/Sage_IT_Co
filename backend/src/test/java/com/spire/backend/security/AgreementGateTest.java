package com.spire.backend.security;

import com.spire.backend.entity.Role;
import com.spire.backend.entity.User;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checklist 2.1: the Terms gate only applies to course-only student
 * accounts. Participants (with a Participant ID) and staff pass; their
 * access no longer depends on when the server last restarted.
 */
class AgreementGateTest {

    private static User user(String role, String participantId) {
        return User.builder().id(1L).role(Role.builder().name(role).build()).participantId(participantId).build();
    }

    @Test
    void onlyCourseOnlyStudentsAreGated() {
        assertTrue(AgreementGateFilter.appliesTo(user("STUDENT", null)));
        assertFalse(AgreementGateFilter.appliesTo(user("PARTICIPANT", "SAGE-2026-00001")));
        assertFalse(AgreementGateFilter.appliesTo(user("STUDENT", "SAGE-2026-00002")));
        for (String staff : new String[]{"ERM", "COACH", "TECHNICAL_ADVISOR", "FINANCE", "OPERATIONS_ADMIN",
                "SYSTEM_ADMIN", "ADMIN", "INSTRUCTOR", "TRAINER"}) {
            assertFalse(AgreementGateFilter.appliesTo(user(staff, null)), staff);
        }
    }
}
