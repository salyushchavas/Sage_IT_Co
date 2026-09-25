package com.spire.backend.service;

import com.spire.backend.entity.EmploymentAcceptance;
import com.spire.backend.entity.ErmAssignment;
import com.spire.backend.entity.PhaseCompletion;
import com.spire.backend.entity.Role;
import com.spire.backend.entity.User;
import com.spire.backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.time.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Checklist 4.5 (roadmap step 17): the ERM can send employment details back
 * for correction; one record is open at a time; only the participant's own
 * uploaded offer letter is accepted; approving Phase 1 starts Phase 2 on the
 * employment start date.
 */
class EmploymentRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 25);
    private final List<EmploymentAcceptance> rows = new ArrayList<>();
    private final List<PhaseCompletion> phases = new ArrayList<>();
    private EmailTemplateService emails;
    private EmploymentService service;
    private User pat;

    @BeforeEach
    void setUp() {
        UserRepository users = mock(UserRepository.class);
        pat = User.builder().id(10L).fullName("Pat Doe").participantId("SAGE-2026-00007").email("pat@x.com")
                .role(Role.builder().name("PARTICIPANT").build()).currentStatus("WEEKLY_REPORTING_ACTIVE").build();
        User erm = User.builder().id(50L).fullName("Erin Rao").email("erin@x.com").build();
        when(users.findById(10L)).thenReturn(Optional.of(pat));
        when(users.findById(50L)).thenReturn(Optional.of(erm));
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        EmploymentAcceptanceRepository repo = mock(EmploymentAcceptanceRepository.class);
        when(repo.save(any())).thenAnswer(inv -> {
            EmploymentAcceptance r = inv.getArgument(0);
            if (r.getId() == null) { r.setId((long) rows.size() + 1); rows.add(r); }
            return r;
        });
        when(repo.findByUserIdOrderByAcceptanceDateDesc(10L)).thenAnswer(inv -> rows.stream()
                .sorted(Comparator.comparing(EmploymentAcceptance::getId).reversed()).toList());
        when(repo.findFirstByUserIdAndErmVerifiedTrueOrderByErmVerifiedDateDesc(10L)).thenAnswer(inv -> rows.stream()
                .filter(r -> Boolean.TRUE.equals(r.getErmVerified())).findFirst());

        PhaseCompletionRepository phaseRepo = mock(PhaseCompletionRepository.class);
        when(phaseRepo.save(any())).thenAnswer(inv -> {
            PhaseCompletion p = inv.getArgument(0);
            if (p.getId() == null) { p.setId((long) phases.size() + 1); phases.add(p); }
            return p;
        });
        when(phaseRepo.findByUserIdAndPhase(anyLong(), anyString())).thenAnswer(inv -> phases.stream()
                .filter(p -> p.getUserId().equals(inv.getArgument(0)) && p.getPhase().equals(inv.getArgument(1))).findFirst());

        ErmAssignmentRepository assignments = mock(ErmAssignmentRepository.class);
        ErmAssignment current = ErmAssignment.builder().userId(10L).ermUserId(50L).build();
        when(assignments.findFirstByUserIdOrderByAssignedDateDesc(10L)).thenReturn(Optional.of(current));
        when(assignments.findByErmUserId(50L)).thenReturn(List.of(current));
        // A former ERM (reassigned away) still has an old row.
        when(assignments.findByErmUserId(51L)).thenReturn(List.of(ErmAssignment.builder().userId(10L).ermUserId(51L).build()));

        emails = mock(EmailTemplateService.class);
        BusinessClock clock = new BusinessClock(Clock.fixed(
                ZonedDateTime.of(2026, 9, 25, 10, 0, 0, 0, ZoneId.of("America/Chicago")).toInstant(),
                ZoneId.of("America/Chicago")));
        WorkflowService workflow = new WorkflowService(users, mock(WorkflowStateRepository.class), mock(RecordService.class));
        service = new EmploymentService(repo, phaseRepo, assignments, users, workflow, mock(RecordService.class), emails, clock);
    }

    private static EmploymentAcceptance offer(String offerUrl) {
        return EmploymentAcceptance.builder().employerClient("Acme").jobTitle("Java Developer")
                .startDate(TODAY.plusDays(10)).offerDocumentUrl(offerUrl).build();
    }

    @Test
    void oneRecordAtATimeUntilTheErmSendsItBack() {
        service.acceptEmployment(10L, offer(null));
        verify(emails).sendEmploymentToVerifyEmail(any(), eq(pat), any(), eq(false));
        assertEquals("EMPLOYMENT_ACCEPTED", pat.getCurrentStatus());
        assertThrows(IllegalStateException.class, () -> service.acceptEmployment(10L, offer(null)),
                "no second record while the first waits for the ERM");

        assertThrows(IllegalArgumentException.class, () -> service.returnForCorrection(50L, 10L, " "), "a reason is required");
        service.returnForCorrection(50L, 10L, "The start date on the offer is 1 Oct");
        verify(emails).sendEmploymentReturnedEmail(pat, "The start date on the offer is 1 Oct");
        assertThrows(IllegalStateException.class, () -> service.verifyEmployment(50L, 10L, ""),
                "a sent-back record can't be verified");
        assertThrows(IllegalStateException.class, () -> service.returnForCorrection(50L, 10L, "again please"));

        service.acceptEmployment(10L, offer(null));                       // the corrected details
        verify(emails).sendEmploymentToVerifyEmail(any(), eq(pat), any(), eq(true));
        assertEquals(2, rows.size(), "the sent-back record is kept for the history");
        service.verifyEmployment(50L, 10L, "Checked the offer letter");
        assertTrue(rows.get(1).getErmVerified());
        assertThrows(IllegalStateException.class, () -> service.returnForCorrection(50L, 10L, "too late now"));
        assertThrows(IllegalStateException.class, () -> service.acceptEmployment(10L, offer(null)));
    }

    @Test
    void onlyTheParticipantsOwnUploadedOfferIsAccepted() {
        assertThrows(IllegalArgumentException.class, () -> service.acceptEmployment(10L, offer("javascript:alert(1)")));
        assertThrows(IllegalArgumentException.class, () -> service.acceptEmployment(10L, offer("https://evil.example/offer.pdf")));
        assertThrows(IllegalArgumentException.class,
                () -> service.acceptEmployment(10L, offer("participant-documents/11/offer-letter-1.pdf")), "another participant's file");
        assertThrows(IllegalArgumentException.class,
                () -> service.acceptEmployment(10L, offer("participant-documents/10/offer-../../11/x.pdf")));
        service.acceptEmployment(10L, offer("participant-documents/10/offer-letter-1727000000.pdf"));
        assertEquals(1, rows.size());
        assertTrue(EmploymentService.isOfferFileOf(10L,
                "https://res.cloudinary.com/demo/raw/authenticated/v1/spire/documents/10/offer-letter-1"));
    }

    @Test
    void theStartDateMustBeRealistic() {
        EmploymentAcceptance typo = offer(null);
        typo.setStartDate(LocalDate.of(2062, 9, 1));
        assertThrows(IllegalArgumentException.class, () -> service.acceptEmployment(10L, typo));
    }

    @Test
    void onlyTheCurrentErmActsOnIt() {
        service.acceptEmployment(10L, offer(null));
        assertThrows(AccessDeniedException.class, () -> service.returnForCorrection(51L, 10L, "not mine any more"));
        assertThrows(AccessDeniedException.class, () -> service.verifyEmployment(51L, 10L, ""));
        assertTrue(service.ermPendingVerifications(51L).isEmpty(), "a former ERM doesn't see it");
        assertEquals(1, service.ermPendingVerifications(50L).size());
    }

    @Test
    void approvingPhase1StartsPhase2OnTheStartDateOnce() {
        service.acceptEmployment(10L, offer(null));
        assertThrows(IllegalStateException.class, () -> service.acceptPhase1Completion(10L, null), "not verified yet");
        assertThrows(IllegalStateException.class, () -> service.approvePhase1(50L, 10L, ""), "nothing to approve yet");
        service.verifyEmployment(50L, 10L, "");
        service.acceptPhase1Completion(10L, null);
        assertEquals("PHASE_1_COMPLETED", pat.getCurrentStatus());

        service.approvePhase1(50L, 10L, "Well done");
        PhaseCompletion phase2 = phases.stream().filter(p -> "PHASE_2".equals(p.getPhase())).findFirst().orElseThrow();
        assertEquals(TODAY.plusDays(10), phase2.getStartDate(), "Phase 2 begins on the employment start date");
        verify(emails).sendPhase2StartedEmail(pat, TODAY.plusDays(10));
        service.approvePhase1(50L, 10L, "again");
        assertEquals(1, phases.stream().filter(p -> "PHASE_2".equals(p.getPhase())).count());
        assertNull(service.startPhase2(10L), "the backfill leaves an existing Phase 2 alone");
        assertEquals(Boolean.FALSE, service.employmentStatus(10L).get("returned"));
    }
}
