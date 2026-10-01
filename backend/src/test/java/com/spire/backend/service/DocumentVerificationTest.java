package com.spire.backend.service;

import com.spire.backend.entity.ParticipantDocument;
import com.spire.backend.entity.Role;
import com.spire.backend.entity.User;
import com.spire.backend.repository.ParticipantDocumentRepository;
import com.spire.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** An ERM checks the documents (opening each) and confirms, or sends one back; then the program step opens. */
class DocumentVerificationTest {

    private final Map<Long, User> users = new LinkedHashMap<>();
    private final List<ParticipantDocument> docs = new ArrayList<>();
    private DocumentService documents;
    private EmailTemplateService emails;
    private DocumentVerificationService service;
    private User pat;

    private User person(long id, String role) {
        User u = User.builder().id(id).email("u" + id + "@x.com").fullName("Person " + id)
                .role(Role.builder().name(role).build()).isActive(true).emailVerified(true).build();
        users.put(id, u);
        return u;
    }

    private ParticipantDocument doc(long id, String type, String status) {
        ParticipantDocument d = ParticipantDocument.builder().id(id).userId(10L).documentType(type)
                .fileName(type + ".pdf").reviewStatus(status).notApplicable(false).build();
        docs.add(d);
        return d;
    }

    @BeforeEach
    void setUp() {
        UserRepository userRepo = mock(UserRepository.class);
        when(userRepo.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(users.get((Long) inv.getArgument(0))));
        when(userRepo.findAll()).thenAnswer(inv -> new ArrayList<>(users.values()));
        ParticipantDocumentRepository docRepo = mock(ParticipantDocumentRepository.class);
        when(docRepo.findByUserIdOrderByUploadedAtDesc(anyLong())).thenAnswer(inv -> docs.stream()
                .filter(d -> d.getUserId().equals(inv.getArgument(0))).toList());
        when(docRepo.findById(anyLong())).thenAnswer(inv -> docs.stream()
                .filter(d -> d.getId().equals(inv.getArgument(0))).findFirst());
        documents = mock(DocumentService.class);
        when(documents.missingRequired(anyLong())).thenReturn(List.of());
        emails = mock(EmailTemplateService.class);
        when(emails.sendDocumentsVerifiedEmail(any())).thenReturn(true);
        service = new DocumentVerificationService(userRepo, docRepo, documents, emails,
                mock(RecordService.class), new PermissionService(null, null));

        person(1, "ERM");
        person(2, "OPERATIONS_ADMIN");
        person(3, "ERM").setIsActive(false);
        person(5, "FINANCE");
        person(6, "COACH");
        pat = person(10, "PARTICIPANT");
        pat.setParticipantId("SAGE-2026-00010");
        pat.setDocumentsComplete(true);
        User early = person(11, "PARTICIPANT");   // documents not submitted yet
        early.setParticipantId("SAGE-2026-00011");
    }

    @Test
    void theQueueHoldsParticipantsWhoseDocumentsAreWaiting() {
        assertEquals(List.of(10L), service.queue(1L, null).stream().map(DocumentVerificationService.Row::userId).toList());
        assertTrue(service.queue(2L, "VERIFIED").isEmpty());
        when(documents.documentsVerified(10L)).thenReturn(true);
        assertTrue(service.queue(1L, null).isEmpty());
        assertEquals(1, service.queue(1L, "VERIFIED").size());
        pat.setProgramSelectionComplete(true);
        assertTrue(service.queue(1L, "VERIFIED").isEmpty(), "gone once they chose their program");
    }

    @Test
    void onlyErmsAndOperationsCheckDocuments() {
        for (long notAllowed : List.of(5L, 6L, 10L)) {
            assertThrows(AccessDeniedException.class, () -> service.queue(notAllowed, null));
            assertThrows(AccessDeniedException.class, () -> service.confirm(notAllowed, 10L));
        }
    }

    @Test
    void confirmingApprovesEveryFileStillWaitingAndEmailsTheParticipant() {
        doc(100, "DRIVERS_LICENSE", "PENDING");
        doc(101, "GOVERNMENT_ID", "PENDING");
        doc(102, "WORK_AUTHORIZATION", "APPROVED");
        doc(103, "OTHER", "PENDING");
        assertTrue(service.confirm(1L, 10L));
        verify(documents).review(100L, 1L, "APPROVED", null);
        verify(documents).review(101L, 1L, "APPROVED", null);
        verify(documents).review(103L, 1L, "APPROVED", null);
        verify(documents, never()).review(eq(102L), anyLong(), anyString(), any());
        verify(emails).sendDocumentsVerifiedEmail(pat);
    }

    @Test
    void nothingIsConfirmedTooEarlyOrTwice() {
        users.get(11L).setDocumentsComplete(false);
        assertThrows(IllegalStateException.class, () -> service.confirm(1L, 11L), "not submitted");
        when(documents.missingRequired(10L)).thenReturn(List.of("RESUME"));
        assertThrows(IllegalStateException.class, () -> service.confirm(1L, 10L), "something missing");
        when(documents.missingRequired(10L)).thenReturn(List.of());
        when(documents.sentBackCount(10L)).thenReturn(1);
        assertThrows(IllegalStateException.class, () -> service.confirm(1L, 10L), "a document was sent back");
        when(documents.sentBackCount(10L)).thenReturn(0);
        when(documents.documentsVerified(10L)).thenReturn(true);
        assertThrows(IllegalStateException.class, () -> service.confirm(1L, 10L), "already confirmed");
        verify(emails, never()).sendDocumentsVerifiedEmail(any());
    }

    @Test
    void aDocumentCanBeSentBackWithAReason() {
        doc(102, "RESUME", "PENDING");
        service.sendBack(1L, 10L, 102L, "Please upload your latest resume");
        verify(documents).review(102L, 1L, "REJECTED", "Please upload your latest resume");
        docs.add(ParticipantDocument.builder().id(200L).userId(11L).documentType("RESUME").build());
        assertThrows(IllegalArgumentException.class, () -> service.sendBack(1L, 10L, 200L, "x"));
    }

    @Test
    void theErmNeverSeesTheSsnDocument() {
        doc(100, "DRIVERS_LICENSE", "PENDING");
        doc(104, "SSN_DOCUMENT", "PENDING");   // an old upload
        @SuppressWarnings("unchecked")
        List<DocumentVerificationService.Doc> forErm = (List<DocumentVerificationService.Doc>) service.detail(1L, 10L).get("documents");
        assertEquals(List.of("DRIVERS_LICENSE"), forErm.stream().map(DocumentVerificationService.Doc::documentType).toList());
        @SuppressWarnings("unchecked")
        List<DocumentVerificationService.Doc> forOps = (List<DocumentVerificationService.Doc>) service.detail(2L, 10L).get("documents");
        assertEquals(2, forOps.size());
    }

    @Test
    void activeErmsAreToldWhenDocumentsAreWaiting() {
        service.notifyErms(11L);   // nothing submitted
        verify(emails, never()).sendDocumentsToVerifyEmail(any(), any());
        service.notifyErms(10L);
        verify(emails).sendDocumentsToVerifyEmail(users.get(1L), pat);
        verify(emails, times(1)).sendDocumentsToVerifyEmail(any(), any());   // not the deactivated ERM
    }

    @Test
    void anErmMayOpenDocumentsOnlyBeforeTheAgreementIsSigned() {
        PermissionService permissions = new PermissionService(null, null);
        User erm = users.get(1L);
        assertTrue(permissions.canVerifyDocument(erm, pat, "RESUME"));
        assertFalse(permissions.canVerifyDocument(erm, pat, "SSN_DOCUMENT"));
        assertFalse(permissions.canVerifyDocument(users.get(6L), pat, "RESUME"), "not a coach");
        pat.setAgreementComplete(true);
        assertFalse(permissions.canVerifyDocument(erm, pat, "RESUME"));
    }
}
