package com.spire.backend.service;

import com.spire.backend.entity.AgreementAcceptance;
import com.spire.backend.entity.ErmAssignment;
import com.spire.backend.entity.Role;
import com.spire.backend.entity.User;
import com.spire.backend.repository.AgreementAcceptanceRepository;
import com.spire.backend.repository.ProgramSelectionRepository;
import com.spire.backend.repository.UserRepository;
import com.spire.backend.repository.WorkflowStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Checklists 2.3 and 3.1: after signing, the roadmap's order — the signed
 * agreement reaches an ERM (10), then the welcome (11), the coordinator
 * intro (12), the ERM introduction (13), coaches (14) and the dashboard
 * (15) — each exactly once. No ERM: the chain waits at step 10.
 */
class OnboardingChainTest {

    private UserRepository users;
    private EmailTemplateService emails;
    private ErmAssignmentService erms;
    private CoachAssignmentService coaches;
    private SignedAgreementService agreements;
    private OnboardingService chain;
    private User pat;
    private final User erm = User.builder().id(50L).fullName("Erin Rao").email("erin@x.com")
            .role(Role.builder().name("ERM").build()).isActive(true).build();

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        pat = User.builder().id(10L).email("pat@x.com").fullName("Pat Doe").participantId("SAGE-2026-00007")
                .role(Role.builder().name("PARTICIPANT").build()).isActive(true)
                .basicInfoComplete(true).acknowledgmentComplete(true).documentsComplete(true)
                .programSelectionComplete(true).agreementComplete(true).checkUploadComplete(true)
                .currentStatus("CHECK_COPY_UPLOADED").build();
        emails = mock(EmailTemplateService.class);
        when(emails.sendWelcomeEmail(any())).thenReturn(true);
        erms = mock(ErmAssignmentService.class);
        coaches = mock(CoachAssignmentService.class);
        when(coaches.assignCoaches(any())).thenReturn(Map.of("CAREER_COACH", "Arjun"));
        agreements = mock(SignedAgreementService.class);
        WorkflowService workflow = new WorkflowService(users, mock(WorkflowStateRepository.class), mock(RecordService.class));
        chain = new OnboardingService(workflow, emails, erms, coaches, mock(ProgramSelectionRepository.class),
                mock(RecordService.class), agreements);
    }

    private void ermAvailable() {
        when(erms.getAssignedErm(10L)).thenReturn(Optional.empty()).thenReturn(Optional.of(erm));
        when(erms.assignErm(pat)).thenReturn(Optional.of(ErmAssignment.builder().userId(10L).ermUserId(50L).build()));
    }

    @Test
    void stepsTenToFifteenRunInTheRoadmapsOrder() {
        ermAvailable();
        chain.completeOnboarding(pat);

        InOrder order = inOrder(agreements, emails, coaches);
        order.verify(agreements).routeToErm(pat, erm);                 // 10
        order.verify(emails).sendWelcomeEmail(pat);                     // 11
        order.verify(emails).sendCoordinatorIntroEmail(pat);            // 12
        order.verify(emails).sendErmIntroEmail(pat, erm);               // 13
        order.verify(emails).sendErmAssignmentNotification(eq(erm), eq(pat), any());
        order.verify(coaches).assignCoaches(pat);                       // 14
        order.verify(emails).sendCoachAssignmentEmail(eq(pat), any());
        assertEquals("DASHBOARD_ENABLED", pat.getCurrentStatus());      // 15
    }

    @Test
    void runningTheChainAgainSendsNothingTwice() {
        ermAvailable();
        chain.completeOnboarding(pat);
        when(erms.getAssignedErm(10L)).thenReturn(Optional.of(erm));
        when(coaches.hasAnyCoach(10L)).thenReturn(true);
        chain.completeOnboarding(pat);
        chain.completeOnboarding(pat);
        verify(emails, times(1)).sendWelcomeEmail(pat);
        verify(emails, times(1)).sendCoordinatorIntroEmail(pat);
        verify(emails, times(1)).sendErmIntroEmail(pat, erm);
        verify(agreements, times(1)).routeToErm(pat, erm);
        verify(emails, never()).sendProfileCompleteEmail(any());
    }

    @Test
    void withoutAnErmTheChainWaitsAtStepTen() {
        when(erms.getAssignedErm(10L)).thenReturn(Optional.empty());
        when(erms.assignErm(pat)).thenReturn(Optional.empty());
        chain.completeOnboarding(pat);
        verifyNoInteractions(agreements, coaches);
        verify(emails, never()).sendWelcomeEmail(any());
        assertEquals("CHECK_COPY_UPLOADED", pat.getCurrentStatus(), "no step claimed without an ERM");
    }

    @Test
    void aNewErmFromOperationsGetsTheAgreementAndAnIntroduction() {
        pat.setCurrentStatus("DASHBOARD_ENABLED");
        User newErm = User.builder().id(51L).fullName("Nia Kay").email("nia@x.com")
                .role(Role.builder().name("ERM").build()).build();
        when(erms.getAssignedErm(10L)).thenReturn(Optional.of(newErm));
        when(coaches.hasAnyCoach(10L)).thenReturn(true);
        chain.ermAssignedByOperations(pat);
        verify(agreements).routeToErm(pat, newErm);
        verify(emails).sendErmIntroEmail(pat, newErm);
        verify(emails, never()).sendWelcomeEmail(any());
    }

    // ── SignedAgreementService: routing and the ERM's review ─────

    private record Kit(SignedAgreementService service, EmailTemplateService emails, PermissionService permissions,
                       AgreementAcceptance row) {}

    private Kit kit() {
        AgreementAcceptanceRepository repo = mock(AgreementAcceptanceRepository.class);
        AgreementAcceptance row = AgreementAcceptance.builder().id(1L).user(pat).status(AgreementService.STATUS_VERIFIED)
                .acceptedAt(LocalDateTime.of(2026, 9, 25, 10, 0)).programSnapshot("Full Stack · Java").build();
        when(repo.findByUserId(10L)).thenReturn(Optional.of(row));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        EmailTemplateService mails = mock(EmailTemplateService.class);
        when(mails.sendSignedAgreementToErmEmail(any(), any(), any(), any())).thenReturn(true);
        PermissionService permissions = mock(PermissionService.class);
        return new Kit(new SignedAgreementService(repo, users, permissions, mock(DocumentStorageService.class),
                mock(AgreementPdfService.class), mock(RecordService.class), mails), mails, permissions, row);
    }

    @Test
    void theAgreementGoesToEachErmOnce() {
        Kit k = kit();
        assertTrue(k.service().routeToErm(pat, erm));
        assertTrue(k.service().routeToErm(pat, erm));
        verify(k.emails(), times(1)).sendSignedAgreementToErmEmail(erm, pat, "Full Stack · Java", "25 Sep 2026");
        assertEquals(50L, k.row().getErmRoutedTo());
        assertNotNull(k.row().getErmRoutedAt());
        User other = User.builder().id(51L).fullName("Nia Kay").email("nia@x.com").build();
        k.service().routeToErm(pat, other);
        verify(k.emails()).sendSignedAgreementToErmEmail(eq(other), eq(pat), any(), any());
    }

    @Test
    void onlyTheCurrentErmCanMarkItReviewed() {
        Kit k = kit();
        when(users.findById(50L)).thenReturn(Optional.of(erm));
        when(k.permissions().isAssignedErmFor(erm, 10L)).thenReturn(true);
        k.service().markReviewedByErm(10L, 50L);
        assertEquals(50L, k.row().getErmReviewedBy());
        assertNotNull(k.row().getErmReviewedAt());

        User stranger = User.builder().id(52L).role(Role.builder().name("ERM").build()).build();
        when(users.findById(52L)).thenReturn(Optional.of(stranger));
        assertThrows(AccessDeniedException.class, () -> k.service().markReviewedByErm(10L, 52L));
    }
}
