package com.spire.backend.service;

import com.spire.backend.entity.ParticipantDocument;
import com.spire.backend.entity.ProgramSelection;
import com.spire.backend.entity.Role;
import com.spire.backend.entity.User;
import com.spire.backend.repository.ParticipantDocumentRepository;
import com.spire.backend.repository.ProgramSelectionRepository;
import com.spire.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 30 Sep: after the documents and the program, an ERM verifies; only then
 * is the agreement emailed (and can it be signed, see RealProgressTest).
 */
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
        when(userRepo.findAllById(any())).thenAnswer(inv -> {
            List<User> found = new ArrayList<>();
            for (Object id : (Iterable<?>) inv.getArgument(0)) if (users.containsKey(id)) found.add(users.get(id));
            return found;
        });
        when(userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        ParticipantDocumentRepository docRepo = mock(ParticipantDocumentRepository.class);
        when(docRepo.findByUserIdOrderByUploadedAtDesc(anyLong())).thenAnswer(inv -> docs.stream()
                .filter(d -> d.getUserId().equals(inv.getArgument(0))).toList());
        when(docRepo.findById(anyLong())).thenAnswer(inv -> docs.stream()
                .filter(d -> d.getId().equals(inv.getArgument(0))).findFirst());
        ProgramSelectionRepository programs = mock(ProgramSelectionRepository.class);
        when(programs.findFirstByUserIdOrderBySelectionDateDesc(10L)).thenReturn(Optional.of(ProgramSelection.builder()
                .userId(10L).program("Career Development Program").skillset("Cloud & DevOps")
                .targetJobTitle("DevOps Engineer").selectionDate(LocalDateTime.now()).build()));

        documents = mock(DocumentService.class);
        when(documents.missingRequired(anyLong())).thenReturn(List.of());
        emails = mock(EmailTemplateService.class);
        when(emails.sendAgreementReadyEmail(any())).thenReturn(true);
        service = new DocumentVerificationService(userRepo, docRepo, programs, documents, emails,
                mock(RecordService.class), new PermissionService(null, null));

        person(1, "ERM");
        person(2, "OPERATIONS_ADMIN");
        person(3, "ERM").setIsActive(false);
        person(5, "FINANCE");
        person(6, "COACH");
        pat = person(10, "PARTICIPANT");
        pat.setParticipantId("SAGE-2026-00010");
        pat.setAcknowledgmentComplete(true);
        pat.setDocumentsComplete(true);
        pat.setProgramSelectionComplete(true);
        User early = person(11, "PARTICIPANT");       // documents not done yet: not in the queue
        early.setParticipantId("SAGE-2026-00011");
        early.setAcknowledgmentComplete(true);
    }

    @Test
    void theQueueHoldsParticipantsWithDocumentsAndProgramDone() {
        List<DocumentVerificationService.Row> waiting = service.queue(1L, null);
        assertEquals(List.of(10L), waiting.stream().map(DocumentVerificationService.Row::userId).toList());
        assertEquals("Career Development Program", waiting.get(0).program());
        assertTrue(service.queue(2L, "SENT").isEmpty());
    }

    @Test
    void onlyErmsAndOperationsVerify() {
        for (long notAllowed : List.of(5L, 6L, 10L)) {
            assertThrows(AccessDeniedException.class, () -> service.queue(notAllowed, null));
            assertThrows(AccessDeniedException.class, () -> service.verify(notAllowed, 10L));
        }
        assertNull(pat.getErmVerifiedAt());
    }

    @Test
    void verifyingApprovesWaitingUploadsAndEmailsTheAgreement() {
        ParticipantDocument license = doc(100, "DRIVERS_LICENSE", "PENDING");
        doc(101, "WORK_AUTHORIZATION", "APPROVED");
        ParticipantDocument resume = doc(102, "RESUME", "PENDING");

        assertTrue(service.verify(1L, 10L));
        verify(documents).review(license.getId(), 1L, "APPROVED", null);
        verify(documents).review(resume.getId(), 1L, "APPROVED", null);
        verify(documents, never()).review(eq(101L), anyLong(), anyString(), any());
        assertNotNull(pat.getErmVerifiedAt());
        assertEquals(1L, pat.getErmVerifiedBy());
        verify(emails).sendAgreementReadyEmail(pat);

        assertTrue(service.queue(1L, null).isEmpty(), "no longer waiting");
        assertEquals("Person 1", service.queue(1L, "SENT").get(0).verifiedByName());
        assertThrows(IllegalStateException.class, () -> service.verify(2L, 10L), "only once");
    }

    @Test
    void nothingIsVerifiedBeforeTheDocumentsAndProgramAreDone() {
        pat.setProgramSelectionComplete(false);
        assertThrows(IllegalStateException.class, () -> service.verify(1L, 10L));
        pat.setProgramSelectionComplete(true);
        when(documents.missingRequired(10L)).thenReturn(List.of("RESUME"));
        assertThrows(IllegalStateException.class, () -> service.verify(1L, 10L));
        pat.setAgreementComplete(true);
        assertThrows(IllegalStateException.class, () -> service.verify(1L, 10L));
        assertNull(pat.getErmVerifiedAt());
        verify(emails, never()).sendAgreementReadyEmail(any());
    }

    @Test
    void aDocumentCanBeSentBackWithAReason() {
        ParticipantDocument resume = doc(102, "RESUME", "PENDING");
        service.sendBack(1L, 10L, resume.getId(), "Please upload your latest resume");
        verify(documents).review(102L, 1L, "REJECTED", "Please upload your latest resume");

        ParticipantDocument someoneElses = ParticipantDocument.builder().id(200L).userId(11L).documentType("RESUME").build();
        docs.add(someoneElses);
        assertThrows(IllegalArgumentException.class, () -> service.sendBack(1L, 10L, 200L, "x"));
    }

    @Test
    void theErmNeverSeesTheSsnDocumentOperationsDoes() {
        doc(100, "DRIVERS_LICENSE", "PENDING");
        doc(103, "SSN_DOCUMENT", "PENDING");   // an old upload
        @SuppressWarnings("unchecked")
        List<DocumentVerificationService.Doc> forErm = (List<DocumentVerificationService.Doc>) service.detail(1L, 10L).get("documents");
        assertEquals(List.of("DRIVERS_LICENSE"), forErm.stream().map(DocumentVerificationService.Doc::documentType).toList());
        @SuppressWarnings("unchecked")
        List<DocumentVerificationService.Doc> forOps = (List<DocumentVerificationService.Doc>) service.detail(2L, 10L).get("documents");
        assertEquals(2, forOps.size());
        assertEquals("Driver's License", forErm.get(0).label());
    }

    @Test
    void activeErmsHearWhenSomeoneIsReadyAndOnlyThen() {
        service.notifyReady(11L);   // not ready
        verify(emails, never()).sendDocumentsToVerifyEmail(any(), any(), any());
        service.notifyReady(10L);
        verify(emails).sendDocumentsToVerifyEmail(users.get(1L), pat, "Career Development Program");
        verify(emails, times(1)).sendDocumentsToVerifyEmail(any(), any(), any());   // not the deactivated ERM
    }

    @Test
    void anErmMayOpenDocumentsOnlyBeforeTheAgreementIsSigned() {
        PermissionService permissions = new PermissionService(null, null);
        User erm = users.get(1L);
        assertTrue(permissions.canVerifyDocument(erm, pat, "RESUME"));
        assertFalse(permissions.canVerifyDocument(erm, pat, "SSN_DOCUMENT"));
        assertFalse(permissions.canVerifyDocument(users.get(6L), pat, "RESUME"), "not a coach");
        pat.setAgreementComplete(true);
        assertFalse(permissions.canVerifyDocument(erm, pat, "RESUME"), "after signing: their assigned ERM only");
    }
}
