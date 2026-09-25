package com.spire.backend.service;

import com.spire.backend.entity.*;
import com.spire.backend.repository.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Checklist 6.2 (roadmap §14): the roadmap's exception cases show up for
 * Operations with the participant's ID, and go away once resolved.
 */
class OperationsExceptionsTest {

    private static final LocalDateTime OLD = LocalDateTime.now().minusDays(10);

    private static User participant(long id, String status) {
        return User.builder().id(id).fullName("P" + id).participantId("SAGE-2026-0000" + id).email("p" + id + "@x.com")
                .role(Role.builder().name("PARTICIPANT").build()).isActive(true).emailVerified(true)
                .currentStatus(status).createdAt(OLD).build();
    }

    @Test
    void theRoadmapsExceptionCasesAreListed() {
        User dup1 = participant(1, "DASHBOARD_ENABLED");
        dup1.setPhoneNormalized("+15551234567");
        User dup2 = participant(2, "ID_EMAIL_SENT");
        dup2.setPhoneNormalized("+15551234567");
        User unverified = participant(3, "EMAIL_VERIFICATION_PENDING");
        unverified.setEmailVerified(false);
        User noDocs = participant(4, "ACKNOWLEDGMENT_ACCEPTED");
        noDocs.setAcknowledgmentComplete(true);
        User noCoach = participant(5, "ERM_ASSIGNED");
        User payer = participant(6, "PHASE_1_COMPLETED");
        List<User> everyone = List.of(dup1, dup2, unverified, noDocs, noCoach, payer);

        UserRepository users = mock(UserRepository.class);
        when(users.findAll()).thenReturn(everyone);
        DocumentService documents = mock(DocumentService.class);
        when(documents.missingRequired(4L)).thenReturn(List.of("RESUME"));
        ParticipantDocumentRepository docRepo = mock(ParticipantDocumentRepository.class);
        when(docRepo.findByReviewStatusInOrderByUploadedAtAsc(any())).thenReturn(List.of());
        when(docRepo.findByReviewStatus("REJECTED")).thenReturn(List.of());
        AgreementQueueService queue = mock(AgreementQueueService.class);
        when(queue.queue()).thenReturn(List.of(
                new AgreementQueueService.Row(2L, "SAGE-2026-00002", "P2", "p2@x.com", "DECLINED", OLD, "Too expensive")));
        ErmAssignmentService erms = mock(ErmAssignmentService.class);
        when(erms.getAssignedErm(anyLong())).thenReturn(Optional.empty());
        when(erms.getAssignedErm(5L)).thenReturn(Optional.of(User.builder().id(50L).fullName("Erin").build()));
        CoachAssignmentRepository coaches = mock(CoachAssignmentRepository.class);
        when(coaches.findByUserIdAndStatus(5L, "ACTIVE")).thenReturn(List.of(
                CoachAssignment.builder().userId(5L).coachRole("CAREER_COACH").assignedDate(OLD).build()));
        WeeklyReportRepository weekly = mock(WeeklyReportRepository.class);
        when(weekly.findByStatus("OVERDUE")).thenReturn(List.of(WeeklyReport.builder().userId(1L)
                .weekStart(LocalDate.of(2026, 9, 14)).status("OVERDUE").overdueFlaggedAt(OLD).build()));
        PaymentPlanRepository plans = mock(PaymentPlanRepository.class);
        PaymentPlan plan = PaymentPlan.builder().id(9L).userId(6L).planId("PLAN-2026-00009")
                .totalAmount(new BigDecimal("3000")).status("PENDING").createdAt(OLD).build();
        when(plans.findByStatus("PENDING")).thenReturn(List.of(plan));
        when(plans.findById(9L)).thenReturn(Optional.of(plan));
        CheckTrackingRepository tracking = mock(CheckTrackingRepository.class);
        when(tracking.findAll()).thenReturn(List.of(CheckTracking.builder().id(1L).paymentPlanId(9L).carrier("USPS")
                .physicalTrackingId("9400").mailedDate(LocalDate.now().minusDays(30)).status("IN_TRANSIT").build()));
        EmailLogRepository emails = mock(EmailLogRepository.class);
        when(emails.findTop300ByOrderBySentAtDesc()).thenReturn(List.of(EmailLog.builder().userId(4L).emailType("WELCOME")
                .recipient("p4@x.com").status("FAILED").errorMessage("Mailbox unavailable").sentAt(LocalDateTime.now().minusDays(1)).build()));
        CheckDocumentRepository checks = mock(CheckDocumentRepository.class);
        when(checks.findByReviewStatus("REJECTED")).thenReturn(List.of());
        WorkflowService workflow = new WorkflowService(users, mock(WorkflowStateRepository.class), mock(RecordService.class));

        OperationsExceptionService service = new OperationsExceptionService(users, docRepo, documents, queue, checks,
                erms, coaches, weekly, plans, tracking, emails, workflow, new BusinessClock(Clock.systemUTC()));
        List<OperationsExceptionService.Row> rows = service.all();
        Set<String> types = new HashSet<>();
        rows.forEach(r -> types.add(r.type()));
        assertEquals(Set.of("DUPLICATE_CONTACT", "EMAIL_NOT_VERIFIED", "DOCUMENTS_MISSING", "AGREEMENT_DECLINED",
                "COACH_MISSING", "WEEKLY_OVERDUE", "PLAN_NOT_ACCEPTED", "CHECK_NOT_RECEIVED", "EMAIL_FAILED"), types);
        OperationsExceptionService.Row coach = rows.stream().filter(r -> r.type().equals("COACH_MISSING")).findFirst().orElseThrow();
        assertEquals("SAGE-2026-00005", coach.participantId());
        assertTrue(coach.detail().contains("Resume Specialist") && !coach.detail().contains("Career Coach"), coach.detail());
        assertEquals(2, rows.stream().filter(r -> r.type().equals("DUPLICATE_CONTACT")).count(), "both accounts are flagged");

        // Resolved: the email went out later, the plan was accepted.
        when(emails.findTop300ByOrderBySentAtDesc()).thenReturn(List.of(
                EmailLog.builder().userId(4L).emailType("WELCOME").recipient("p4@x.com").status("SENT").sentAt(LocalDateTime.now()).build(),
                EmailLog.builder().userId(4L).emailType("WELCOME").recipient("p4@x.com").status("FAILED").sentAt(LocalDateTime.now().minusDays(1)).build()));
        when(plans.findByStatus("PENDING")).thenReturn(List.of());
        Set<String> after = new HashSet<>();
        service.all().forEach(r -> after.add(r.type()));
        assertFalse(after.contains("EMAIL_FAILED"));
        assertFalse(after.contains("PLAN_NOT_ACCEPTED"));
    }
}
