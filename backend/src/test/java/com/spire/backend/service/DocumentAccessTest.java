package com.spire.backend.service;

import com.spire.backend.entity.ErmAssignment;
import com.spire.backend.entity.Role;
import com.spire.backend.entity.User;
import com.spire.backend.repository.CoachAssignmentRepository;
import com.spire.backend.repository.ErmAssignmentRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Roadmap §13: who may open a participant's identity documents. */
class DocumentAccessTest {

    private static User user(long id, String role) {
        return User.builder().id(id).role(Role.builder().name(role).build()).isActive(true).build();
    }

    private final ErmAssignmentRepository erms = mock(ErmAssignmentRepository.class);
    private final PermissionService permissions = new PermissionService(erms, mock(CoachAssignmentRepository.class));

    @Test
    void theAssignedErmSeesIdsButNeverTheSsnDocument() {
        when(erms.findFirstByUserIdOrderByAssignedDateDesc(10L))
                .thenReturn(Optional.of(ErmAssignment.builder().userId(10L).ermUserId(2L).build()));
        User erm = user(2, "ERM");
        assertTrue(permissions.canViewDocument(erm, 10L, "GOVERNMENT_ID"));
        assertFalse(permissions.canViewDocument(erm, 10L, "SSN_DOCUMENT"));
        assertFalse(permissions.canViewDocument(user(3, "ERM"), 10L, "GOVERNMENT_ID"), "not their participant");
    }

    @Test
    void operationsAndTheOwnerSeeEverythingTheOldLmsAdminNothing() {
        assertTrue(permissions.canViewDocument(user(10, "PARTICIPANT"), 10L, "SSN_DOCUMENT"));
        assertTrue(permissions.canViewDocument(user(5, "OPERATIONS_ADMIN"), 10L, "SSN_DOCUMENT"));
        assertTrue(permissions.canViewDocument(user(6, "SYSTEM_ADMIN"), 10L, "SSN_DOCUMENT"));
        assertFalse(permissions.canViewDocument(user(7, "ADMIN"), 10L, "GOVERNMENT_ID"));
        assertFalse(permissions.canViewDocument(user(8, "COACH"), 10L, "RESUME"));
        assertFalse(permissions.canViewDocument(user(9, "FINANCE"), 10L, "GOVERNMENT_ID"));
    }
}
