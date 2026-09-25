package com.spire.backend.service;

import com.spire.backend.entity.ErmAssignment;
import com.spire.backend.entity.ParticipantDocument;
import com.spire.backend.entity.Role;
import com.spire.backend.entity.User;
import com.spire.backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Checklist 0.4 — permission holes:
 * identity documents only for the owner, their assigned ERM or an admin
 * (with every staff view recorded); roles changed only by a System Admin;
 * staff accounts managed only by a System Admin.
 */
class PermissionRulesTest {

    private static final long PARTICIPANT = 10L, ERM_A = 20L, ERM_B = 21L, OPS = 30L, SYS = 40L, COACH = 50L,
            LEGACY_ADMIN = 60L, FINANCE = 70L;

    private UserRepository userRepository;
    private ErmAssignmentRepository ermAssignmentRepository;
    private RecordService recordService;
    private PermissionService permissionService;

    private static User user(long id, String role) {
        return User.builder().id(id).email("u" + id + "@x.com").fullName("User " + id)
                .role(Role.builder().name(role).build()).isActive(true).build();
    }

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        ermAssignmentRepository = mock(ErmAssignmentRepository.class);
        recordService = mock(RecordService.class);
        permissionService = new PermissionService(ermAssignmentRepository, mock(CoachAssignmentRepository.class));
        for (User u : new User[]{user(PARTICIPANT, "PARTICIPANT"), user(ERM_A, "ERM"), user(ERM_B, "ERM"),
                user(OPS, "OPERATIONS_ADMIN"), user(SYS, "SYSTEM_ADMIN"), user(COACH, "COACH"),
                user(LEGACY_ADMIN, "ADMIN"), user(FINANCE, "FINANCE")}) {
            when(userRepository.findById(u.getId())).thenReturn(Optional.of(u));
        }
        when(ermAssignmentRepository.findFirstByUserIdOrderByAssignedDateDesc(PARTICIPANT))
                .thenReturn(Optional.of(ErmAssignment.builder().userId(PARTICIPANT).ermUserId(ERM_A).build()));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ── Identity documents ────────────────────────────────────────

    private DocumentService documentService() {
        ParticipantDocumentRepository docs = mock(ParticipantDocumentRepository.class);
        when(docs.findById(99L)).thenReturn(Optional.of(ParticipantDocument.builder()
                .id(99L).userId(PARTICIPANT).documentType("SSN_CARD").fileUrl("x").build()));
        return new DocumentService(docs, userRepository, mock(DocumentStorageService.class),
                mock(WorkflowService.class), recordService, mock(ProfileCompletionService.class), permissionService,
                mock(EmailTemplateService.class));
    }

    @Test
    void onlyOwnerAssignedErmAndAdminsOpenIdentityDocuments() {
        DocumentService documents = documentService();
        assertNotNull(documents.get(99L, PARTICIPANT));
        assertNotNull(documents.get(99L, ERM_A));
        assertNotNull(documents.get(99L, OPS));
        assertNotNull(documents.get(99L, SYS));
        for (long refused : new long[]{ERM_B, COACH, FINANCE}) {
            assertThrows(AccessDeniedException.class, () -> documents.get(99L, refused), "user " + refused);
        }
    }

    @Test
    void staffViewsAreRecordedButOwnerViewsAreNot() {
        DocumentService documents = documentService();
        documents.get(99L, PARTICIPANT);
        verify(recordService, never()).record(anyLong(), eq("DOCUMENT_VIEWED"), anyString(), anyString(), anyString(), anyMap());

        documents.get(99L, ERM_A);
        verify(recordService).record(eq(PARTICIPANT), eq("DOCUMENT_VIEWED"), eq(RecordService.Category.DOCUMENT),
                anyString(), contains("ERM user #" + ERM_A),
                eq(Map.of("documentId", 99L, "documentType", "SSN_CARD", "viewerId", ERM_A, "viewerRole", "ERM")));
    }

    @Test
    void anErmRowForANonErmUserGrantsNothing() {
        // A stale assignment row pointing at someone who is no longer an ERM.
        when(ermAssignmentRepository.findFirstByUserIdOrderByAssignedDateDesc(PARTICIPANT))
                .thenReturn(Optional.of(ErmAssignment.builder().userId(PARTICIPANT).ermUserId(COACH).build()));
        assertFalse(permissionService.canViewDocumentsOf(user(COACH, "COACH"), PARTICIPANT));
    }

    // ── Roles and staff accounts ──────────────────────────────────

    private AdminService adminService() {
        RoleRepository roles = mock(RoleRepository.class);
        when(roles.findByName(anyString())).thenAnswer(inv -> Optional.of(Role.builder().name(inv.getArgument(0)).build()));
        return new AdminService(userRepository, roles, mock(CourseRepository.class), mock(EnrollmentRepository.class),
                mock(LessonRepository.class), mock(ProgressRepository.class), mock(CertificateRepository.class),
                mock(SessionRequestRepository.class), mock(MentorAssignmentRepository.class), recordService,
                mock(AdminRevenueService.class), mock(PaymentLedgerRepository.class));
    }

    @Test
    void operationsAdminCannotChangeAnyRoleIncludingItsOwn() {
        AdminService admin = adminService();
        assertThrows(AccessDeniedException.class, () -> admin.updateUserRole(OPS, "SYSTEM_ADMIN", OPS));
        assertThrows(AccessDeniedException.class, () -> admin.updateUserRole(PARTICIPANT, "COACH", OPS));
        assertEquals("OPERATIONS_ADMIN", userRepository.findById(OPS).orElseThrow().getRole().getName());
    }

    @Test
    void systemAdminChangesRolesButNotItsOwn() {
        AdminService admin = adminService();
        assertEquals("COACH", admin.updateUserRole(PARTICIPANT, "COACH", SYS).getRole());
        assertEquals("SYSTEM_ADMIN", admin.updateUserRole(OPS, "SYSTEM_ADMIN", SYS).getRole());
        assertThrows(IllegalArgumentException.class, () -> admin.updateUserRole(SYS, "COACH", SYS));
    }

    @Test
    void legacyAdminCannotGrantOrRemoveTopAdminRoles() {
        AdminService admin = adminService();
        assertThrows(AccessDeniedException.class, () -> admin.updateUserRole(PARTICIPANT, "SYSTEM_ADMIN", LEGACY_ADMIN));
        assertThrows(AccessDeniedException.class, () -> admin.updateUserRole(SYS, "COACH", LEGACY_ADMIN));
        assertEquals("FINANCE", admin.updateUserRole(PARTICIPANT, "FINANCE", LEGACY_ADMIN).getRole());
    }

    @Test
    void operationsAdminManagesParticipantsOnly() {
        AdminService admin = adminService();
        assertFalse(admin.updateUserStatus(PARTICIPANT, OPS, false).getIsActive());
        assertThrows(AccessDeniedException.class, () -> admin.updateUserStatus(SYS, OPS, false));
        assertThrows(AccessDeniedException.class, () -> admin.updateUserStatus(ERM_A, OPS, false));
        assertThrows(AccessDeniedException.class, () -> admin.softDeleteUser(ERM_A, OPS));
        assertTrue(userRepository.findById(SYS).orElseThrow().getIsActive());
        assertTrue(userRepository.findById(ERM_A).orElseThrow().getIsActive());
    }

    @Test
    void systemAdminManagesStaffButTopAdminsCannotBeSoftDeleted() {
        AdminService admin = adminService();
        assertFalse(admin.updateUserStatus(ERM_A, SYS, false).getIsActive());
        assertThrows(IllegalArgumentException.class, () -> admin.softDeleteUser(LEGACY_ADMIN, SYS));
        assertThrows(IllegalArgumentException.class, () -> admin.updateUserStatus(SYS, SYS, false));
    }
}
