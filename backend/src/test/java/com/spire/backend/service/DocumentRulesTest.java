package com.spire.backend.service;

import com.spire.backend.entity.ParticipantDocument;
import com.spire.backend.entity.Role;
import com.spire.backend.entity.User;
import com.spire.backend.repository.ParticipantDocumentRepository;
import com.spire.backend.repository.UserRepository;
import com.spire.backend.repository.WorkflowStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Checklist 1.3 (roadmap step 5): required documents need an upload or an
 * exception Operations approves; Operations can send a document back only
 * with a reason, which is emailed; while the agreement isn't signed that
 * reopens the documents step.
 */
class DocumentRulesTest {

    private final List<ParticipantDocument> rows = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(100);
    private UserRepository users;
    private EmailTemplateService emails;
    private DocumentService service;
    private User user;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        user = User.builder().id(10L).email("p@x.com").fullName("Pat Doe")
                .role(Role.builder().name("PARTICIPANT").build())
                .isActive(true).emailVerified(true).participantId("SIT-2026-00001")
                .basicInfoComplete(true).acknowledgmentComplete(true)
                .currentStatus("ACKNOWLEDGMENT_ACCEPTED").build();
        when(users.findById(10L)).thenReturn(Optional.of(user));
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(users.findAllById(any())).thenReturn(List.of(user));

        ParticipantDocumentRepository repo = mock(ParticipantDocumentRepository.class);
        when(repo.save(any(ParticipantDocument.class))).thenAnswer(inv -> {
            ParticipantDocument d = inv.getArgument(0);
            if (d.getId() == null) {
                d.setId(ids.incrementAndGet());
                rows.add(d);
            }
            return d;
        });
        doAnswer(inv -> rows.remove((ParticipantDocument) inv.getArgument(0))).when(repo).delete(any());
        when(repo.findById(anyLong())).thenAnswer(inv -> rows.stream()
                .filter(d -> d.getId().equals(inv.getArgument(0))).findFirst());
        when(repo.findByUserIdOrderByUploadedAtDesc(anyLong())).thenAnswer(inv -> rows.stream()
                .filter(d -> d.getUserId().equals(inv.getArgument(0))).toList());
        when(repo.findByUserIdAndDocumentType(anyLong(), anyString())).thenAnswer(inv -> rows.stream()
                .filter(d -> d.getUserId().equals(inv.getArgument(0))
                        && d.getDocumentType().equals(inv.getArgument(1))).toList());
        when(repo.findByReviewStatusInOrderByUploadedAtAsc(any())).thenAnswer(inv -> rows.stream()
                .filter(d -> ((java.util.Collection<?>) inv.getArgument(0)).contains(d.getReviewStatus())).toList());

        DocumentStorageService storage = mock(DocumentStorageService.class);
        when(storage.upload(anyLong(), anyString(), any(), any()))
                .thenReturn(new DocumentStorageService.StoredFile("/tmp/x.pdf", "/tmp/x.pdf"));
        RecordService records = mock(RecordService.class);
        WorkflowService workflow = new WorkflowService(users, mock(WorkflowStateRepository.class), records);
        ProfileCompletionService profile = new ProfileCompletionService(users, records, mock(OnboardingService.class));
        emails = mock(EmailTemplateService.class);
        service = new DocumentService(repo, users, storage, workflow, records, profile,
                mock(PermissionService.class), emails);
    }

    private ParticipantDocument upload(String type) {
        return service.upload(10L, type, new MockMultipartFile("file", type + ".pdf",
                "application/pdf", "%PDF-1.4".getBytes()));
    }

    private void uploadAllRequired() {
        upload("GOVERNMENT_ID");
        upload("WORK_AUTHORIZATION");
        upload("RESUME");
    }

    // ── "Not applicable" ──────────────────────────────────────────

    @Test
    void anOptionalDocumentCanSimplyBeMarkedNotApplicable() {
        ParticipantDocument na = service.markNotApplicable(10L, "SSN_DOCUMENT", null);
        assertEquals("NOT_APPLICABLE", na.getReviewStatus());
        assertThrows(IllegalArgumentException.class, () -> service.markNotApplicable(10L, "OTHER", null));
    }

    @Test
    void aRequiredDocumentNeedsAReasonAndOnlyCountsOnceOperationsApproves() {
        IllegalArgumentException noReason = assertThrows(IllegalArgumentException.class,
                () -> service.markNotApplicable(10L, "WORK_AUTHORIZATION", " "));
        assertTrue(noReason.getMessage().contains("Tell Operations why"));

        upload("GOVERNMENT_ID");
        upload("RESUME");
        ParticipantDocument req = service.markNotApplicable(10L, "WORK_AUTHORIZATION", "US citizen, no visa needed");
        assertEquals("EXCEPTION_REQUESTED", req.getReviewStatus());
        assertEquals("US citizen, no visa needed", req.getExceptionReason());
        assertEquals(List.of("WORK_AUTHORIZATION"), service.missingRequired(10L), "a pending request doesn't count");

        Map<String, Object> blocked = service.complete(10L);
        assertEquals(false, blocked.get("success"));
        assertTrue(String.valueOf(blocked.get("message")).contains("still has to approve"));
        assertNotEquals(Boolean.TRUE, user.getDocumentsComplete());

        service.review(req.getId(), 1L, "APPROVED", null);
        assertEquals("EXCEPTION_APPROVED", req.getReviewStatus());
        verify(emails).sendDocumentExceptionApprovedEmail(user, "Work Authorization / Visa");
        assertTrue(service.missingRequired(10L).isEmpty());
        assertEquals(true, service.complete(10L).get("success"));
        assertEquals(Boolean.TRUE, user.getDocumentsComplete());
    }

    @Test
    void aDeclinedExceptionMustBeUploadedAfterAll() {
        ParticipantDocument req = service.markNotApplicable(10L, "RESUME", "I don't have one yet");
        assertThrows(IllegalArgumentException.class, () -> service.review(req.getId(), 1L, "REJECTED", ""),
                "declining needs a reason");
        service.review(req.getId(), 1L, "REJECTED", "Please upload any draft resume");
        assertEquals("EXCEPTION_DECLINED", req.getReviewStatus());
        verify(emails).sendDocumentResubmitEmail(user, "Resume / CV", "Please upload any draft resume", true);
        assertTrue(service.missingRequired(10L).contains("RESUME"));
    }

    @Test
    void anApprovedDocumentCantBeTurnedIntoNotApplicable() {
        ParticipantDocument id = upload("GOVERNMENT_ID");
        service.review(id.getId(), 1L, "APPROVED", null);
        assertThrows(IllegalArgumentException.class,
                () -> service.markNotApplicable(10L, "GOVERNMENT_ID", "changed my mind"));
    }

    @Test
    void anOptionalMarkerHasNothingToReview() {
        ParticipantDocument na = service.markNotApplicable(10L, "DRIVERS_LICENSE", null);
        assertThrows(IllegalArgumentException.class, () -> service.review(na.getId(), 1L, "APPROVED", null));
    }

    @Test
    void anOldUnreviewedNotApplicableOnARequiredDocumentNoLongerCounts() {
        upload("GOVERNMENT_ID");
        upload("RESUME");
        rows.add(ParticipantDocument.builder().id(ids.incrementAndGet()).userId(10L)
                .documentType("WORK_AUTHORIZATION").reviewStatus("NOT_APPLICABLE").notApplicable(true).build());
        assertEquals(List.of("WORK_AUTHORIZATION"), service.missingRequired(10L));
    }

    // ── Rejection: reason, email, re-upload, step reopens ─────────

    @Test
    void rejectingNeedsAReasonAndEmailsTheParticipant() {
        ParticipantDocument id = upload("GOVERNMENT_ID");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.review(id.getId(), 1L, "REJECTED", null));
        assertTrue(ex.getMessage().contains("reason"));
        verifyNoInteractions(emails);

        service.review(id.getId(), 1L, "REJECTED", "The photo is blurry");
        assertEquals("REJECTED", id.getReviewStatus());
        assertEquals("The photo is blurry", id.getReviewerNotes());
        verify(emails).sendDocumentResubmitEmail(user, "Government-issued ID", "The photo is blurry", false);
    }

    @Test
    void aRejectedRequiredDocumentReopensTheStepBeforeSigning() {
        uploadAllRequired();
        service.complete(10L);
        user.setProgramSelectionComplete(true);
        user.setCurrentStatus("PROGRAM_SELECTED");

        ParticipantDocument id = rows.stream().filter(d -> d.getDocumentType().equals("GOVERNMENT_ID")).findFirst().get();
        service.review(id.getId(), 1L, "REJECTED", "Expired passport");

        assertEquals(Boolean.FALSE, user.getDocumentsComplete(), "the agreement waits for the new upload");
        assertEquals("ACKNOWLEDGMENT_ACCEPTED", user.getCurrentStatus(), "status back to the real step");
        assertEquals(List.of("GOVERNMENT_ID"), service.missingRequired(10L));

        // The participant uploads a new one and continues: straight back to where they were.
        upload("GOVERNMENT_ID");
        Map<String, Object> done = service.complete(10L);
        assertEquals(true, done.get("success"));
        assertEquals("/agreement", done.get("nextStep"));
        assertEquals("PROGRAM_SELECTED", user.getCurrentStatus());
    }

    @Test
    void aSignedAgreementIsNotUnwoundByARejection() {
        uploadAllRequired();
        service.complete(10L);
        user.setProgramSelectionComplete(true);
        user.setAgreementComplete(true);
        user.setCurrentStatus("AGREEMENT_COMPLETED");

        ParticipantDocument resume = rows.stream().filter(d -> d.getDocumentType().equals("RESUME")).findFirst().get();
        service.review(resume.getId(), 1L, "REJECTED", "Wrong file uploaded");

        assertEquals(Boolean.TRUE, user.getDocumentsComplete());
        assertEquals("AGREEMENT_COMPLETED", user.getCurrentStatus());
        verify(emails).sendDocumentResubmitEmail(user, "Resume / CV", "Wrong file uploaded", false);
        // ...and a required document can be replaced but not simply removed afterwards.
        ParticipantDocument id = rows.stream().filter(d -> d.getDocumentType().equals("GOVERNMENT_ID")).findFirst().get();
        assertThrows(IllegalArgumentException.class, () -> service.delete(id.getId(), 10L));
        assertDoesNotThrow(() -> service.delete(resume.getId(), 10L), "a rejected one can go");
    }

    @Test
    void removingARequiredDocumentBeforeSigningReopensTheStep() {
        uploadAllRequired();
        service.complete(10L);
        ParticipantDocument resume = rows.stream().filter(d -> d.getDocumentType().equals("RESUME")).findFirst().get();
        service.delete(resume.getId(), 10L);
        assertEquals(Boolean.FALSE, user.getDocumentsComplete());
    }

    @Test
    void theSameDecisionTwiceSendsOneEmail() {
        ParticipantDocument id = upload("GOVERNMENT_ID");
        service.review(id.getId(), 1L, "REJECTED", "Blurry");
        service.review(id.getId(), 1L, "REJECTED", "Blurry");
        verify(emails, times(1)).sendDocumentResubmitEmail(any(), any(), any(), anyBoolean());
    }

    // ── The Operations review screen ──────────────────────────────

    @Test
    void theReviewQueueShowsUploadsAndRequestsWithTheParticipant() {
        upload("GOVERNMENT_ID");
        service.markNotApplicable(10L, "WORK_AUTHORIZATION", "Green card holder");
        service.markNotApplicable(10L, "SSN_DOCUMENT", null);   // optional: nothing to decide

        List<DocumentService.ReviewRow> queue = service.reviewQueue(null);
        assertEquals(2, queue.size());
        DocumentService.ReviewRow request = queue.stream()
                .filter(r -> r.documentType().equals("WORK_AUTHORIZATION")).findFirst().get();
        assertEquals("EXCEPTION_REQUESTED", request.reviewStatus());
        assertEquals("Green card holder", request.exceptionReason());
        assertEquals("Pat Doe", request.participantName());
        assertEquals("SIT-2026-00001", request.participantId());
        assertTrue(request.required());
    }
}
