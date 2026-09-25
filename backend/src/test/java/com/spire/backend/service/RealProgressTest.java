package com.spire.backend.service;

import com.spire.backend.dto.AcknowledgmentSubmitRequest;
import com.spire.backend.entity.AgreementAcceptance;
import com.spire.backend.entity.Role;
import com.spire.backend.entity.User;
import com.spire.backend.entity.WorkflowState;
import com.spire.backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Checklist 1.1: a participant moves forward one real step at a time.
 * No jump to step 15 at sign-up, no moving backwards, each step needs the
 * one before it (by its flag, not the status), the onboarding chain only
 * runs once the profile is complete, and the roadmap ticks what happened.
 */
class RealProgressTest {

    /** Nothing after onboarding has happened yet. */
    private static final ParticipantDashboardService.RoadmapFacts NO_FACTS =
            new ParticipantDashboardService.RoadmapFacts(false, false, false, false, 0, false, false, false);

    private UserRepository userRepository;
    private WorkflowStateRepository workflowStateRepository;
    private WorkflowService workflow;
    private User user;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        workflowStateRepository = mock(WorkflowStateRepository.class);
        workflow = new WorkflowService(userRepository, workflowStateRepository, mock(RecordService.class));
        user = User.builder().id(10L).email("p@x.com").fullName("Pat Doe")
                .role(Role.builder().name("PARTICIPANT").build())
                .isActive(true).emailVerified(true).participantId("SIT-2026-00001")
                .currentStatus("ID_EMAIL_SENT").build();
        when(userRepository.findById(10L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ── The status ladder ─────────────────────────────────────────

    @Test
    void statusOnlyMovesForward() {
        workflow.transition(user, WorkflowService.Status.PROGRAM_SELECTED, "t");
        assertEquals("PROGRAM_SELECTED", user.getCurrentStatus());
        workflow.transition(user, WorkflowService.Status.DOCUMENTS_SUBMITTED, "late document step");
        assertEquals("PROGRAM_SELECTED", user.getCurrentStatus(), "never dragged back down");
        verify(workflowStateRepository, times(1)).save(any(WorkflowState.class));
    }

    @Test
    void aDeliberateRepairCanMoveBackAndIsAudited() {
        user.setCurrentStatus("DASHBOARD_ENABLED");
        workflow.repair(user, WorkflowService.Status.ACKNOWLEDGMENT_ACCEPTED, "status_repair_quick_signup", "n");
        assertEquals("ACKNOWLEDGMENT_ACCEPTED", user.getCurrentStatus());
        verify(workflowStateRepository).save(argThat(ws -> "DASHBOARD_ENABLED".equals(ws.getFromStatus())
                && "ACKNOWLEDGMENT_ACCEPTED".equals(ws.getToStatus())));
    }

    @Test
    void theRealStatusIsWorkedOutFromTheProfileSteps() {
        assertEquals(WorkflowService.Status.ID_EMAIL_SENT, WorkflowService.statusFromProfile(user));
        user.setAcknowledgmentComplete(true);
        user.setProgramSelectionComplete(true);   // out of order: doesn't count without documents
        assertEquals(WorkflowService.Status.ACKNOWLEDGMENT_ACCEPTED, WorkflowService.statusFromProfile(user));
        user.setDocumentsComplete(true);
        user.setAgreementComplete(true);
        user.setCheckUploadComplete(true);
        assertEquals(WorkflowService.Status.SIGNED_AGREEMENT_SENT_TO_ERM, WorkflowService.statusFromProfile(user));
    }

    // ── Each step needs the one before it ─────────────────────────

    @Test
    void acknowledgmentNeedsAboutYouAndIsAlwaysValidated() {
        AcknowledgmentService ack = new AcknowledgmentService(mock(AcknowledgmentRepository.class), userRepository,
                workflow, mock(RecordService.class), mock(ProfileCompletionService.class));
        AcknowledgmentSubmitRequest blank = new AcknowledgmentSubmitRequest();

        IllegalStateException first = assertThrows(IllegalStateException.class, () -> ack.submit(10L, blank, null));
        assertTrue(first.getMessage().contains("About You"));

        // Even with a status jumped far ahead, an empty form is refused.
        user.setBasicInfoComplete(true);
        user.setCurrentStatus("DASHBOARD_ENABLED");
        assertThrows(IllegalArgumentException.class, () -> ack.submit(10L, blank, null));
    }

    @Test
    void documentsNeedTheAcknowledgment() {
        user.setCurrentStatus("DASHBOARD_ENABLED");   // the old jump no longer opens anything
        DocumentService docs = new DocumentService(mock(ParticipantDocumentRepository.class), userRepository,
                mock(DocumentStorageService.class), workflow, mock(RecordService.class),
                mock(ProfileCompletionService.class), mock(PermissionService.class), mock(EmailTemplateService.class));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> docs.markNotApplicable(10L, "WORK_AUTHORIZATION", "US citizen"));
        assertTrue(ex.getMessage().contains("acknowledgment"));
    }

    @Test
    void programSelectionNeedsTheDocuments() {
        user.setAcknowledgmentComplete(true);
        ProgramSelectionService programs = new ProgramSelectionService(mock(ProgramSelectionRepository.class),
                userRepository, workflow, mock(RecordService.class), mock(EmailTemplateService.class),
                mock(ProfileCompletionService.class));
        assertThrows(IllegalStateException.class, () -> programs.submit(10L, null));
        assertThrows(IllegalStateException.class, () -> programs.saveDraft(10L, null));
    }

    @Test
    void signingNeedsEveryEarlierStepAndNothingIsMarkedSignedWithoutASignature() {
        AgreementAcceptanceRepository agreements = mock(AgreementAcceptanceRepository.class);
        ProfileCompletionService profile = mock(ProfileCompletionService.class);
        AgreementService agreementService = mock(AgreementService.class);
        TermsContentService terms = new TermsContentService();
        ParticipantAgreementService signing = new ParticipantAgreementService(agreementService, agreements,
                userRepository, workflow, mock(RecordService.class), profile, terms,
                mock(ProgramSelectionRepository.class));
        String v = AgreementService.CURRENT_VERSION;
        String fp = terms.fingerprint(v);
        user.setAcknowledgmentComplete(true);
        user.setDocumentsComplete(true);
        assertThrows(IllegalStateException.class, () -> signing.sign(10L, "Pat Doe", null, null, null, null, v, fp));

        // A status jumped past signing used to return "already signed" and tick the step.
        user.setProgramSelectionComplete(true);
        user.setCurrentStatus("DASHBOARD_ENABLED");
        when(agreements.findByUserId(10L)).thenReturn(Optional.empty());
        signing.sign(10L, "Pat Doe", "data:image/png;base64,AA", "draw", "1.2.3.4", "ua", v, fp);
        verify(agreementService).signImmediate(eq(10L), any(), any(), any(), any(), any(), any());

        // A real stored signature is recognised.
        reset(agreementService);
        when(agreements.findByUserId(10L)).thenReturn(Optional.of(AgreementAcceptance.builder()
                .user(user).status(AgreementService.STATUS_VERIFIED).build()));
        assertEquals(true, signing.sign(10L, "Pat Doe", null, null, null, null, v, fp).get("alreadySigned"));
        verify(agreementService, never()).signImmediate(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void theCheckStepCanNoLongerSkipSigning() {
        user.setAcknowledgmentComplete(true);
        user.setDocumentsComplete(true);
        user.setProgramSelectionComplete(true);
        ParticipantCheckService checks = new ParticipantCheckService(mock(CheckDocumentRepository.class),
                mock(AgreementAcceptanceRepository.class), userRepository, mock(DocumentStorageService.class),
                workflow, mock(RecordService.class), mock(EmailTemplateService.class),
                mock(ProfileCompletionService.class));
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> checks.markNotApplicable(10L));
        assertTrue(ex.getMessage().contains("agreement"));
        assertEquals("ID_EMAIL_SENT", user.getCurrentStatus());
    }

    // ── The onboarding chain ──────────────────────────────────────

    @Test
    void theChainWaitsForACompleteProfile() {
        EmailTemplateService emails = mock(EmailTemplateService.class);
        ErmAssignmentService erms = mock(ErmAssignmentService.class);
        OnboardingService onboarding = new OnboardingService(workflow, emails, erms,
                mock(CoachAssignmentService.class), mock(ProgramSelectionRepository.class), mock(RecordService.class),
                mock(SignedAgreementService.class));

        onboarding.completeOnboarding(user);   // e.g. Operations assigned an ERM mid-onboarding

        verifyNoInteractions(emails, erms);
        assertEquals("ID_EMAIL_SENT", user.getCurrentStatus());
    }

    // ── The Home roadmap ──────────────────────────────────────────

    @Test
    void theRoadmapTicksWhatReallyHappened() {
        List<Boolean> fresh = ParticipantDashboardService.roadmapDone(user, NO_FACTS);
        assertEquals(List.of(false, true, true), fresh.subList(0, 3));
        assertEquals(2, fresh.stream().filter(b -> b).count(), "a new participant has 2 ticks, not 14");
        assertEquals(1, ParticipantDashboardService.currentStep(fresh), "About You comes first");

        user.setBasicInfoComplete(true);
        user.setAcknowledgmentComplete(true);
        user.setDocumentsComplete(true);
        user.setProgramSelectionComplete(true);
        user.setAgreementComplete(true);
        List<Boolean> signed = ParticipantDashboardService.roadmapDone(user, NO_FACTS);
        assertTrue(signed.get(6) && signed.get(8), "agreement sent and complete");
        assertEquals(8, ParticipantDashboardService.currentStep(signed), "then the check step");
    }
}
