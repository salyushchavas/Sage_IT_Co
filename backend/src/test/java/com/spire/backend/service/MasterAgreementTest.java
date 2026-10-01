package com.spire.backend.service;

import com.spire.backend.entity.AgreementRequest;
import com.spire.backend.entity.AgreementUser;
import com.spire.backend.entity.AgreementUserRole;
import com.spire.backend.entity.ConsultantApplication;
import com.spire.backend.entity.ProgramSelection;
import com.spire.backend.entity.Role;
import com.spire.backend.entity.User;
import com.spire.backend.repository.AgreementRequestRepository;
import com.spire.backend.repository.AgreementUserRepository;
import com.spire.backend.repository.ConsultantApplicationRepository;
import com.spire.backend.repository.ProgramSelectionRepository;
import com.spire.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The real agreement step: after the consent, "I'm ready to sign the
 * agreement"; a console ERM starts it from the participant's details; the
 * participant's dashboard follows the console's five steps. The console's
 * own data is only read.
 */
class MasterAgreementTest {

    private final List<AgreementRequest> requests = new ArrayList<>();
    private final List<ConsultantApplication> agreements = new ArrayList<>();
    private final List<AgreementUser> consoleUsers = new ArrayList<>();
    private EmailTemplateService emails;
    private MasterAgreementService service;
    private User pat;

    @BeforeEach
    void setUp() {
        pat = User.builder().id(10L).email("Pat.Lee@x.com").fullName("Pat Q Lee").phone("555 201 3344")
                .participantId("SAGE-2026-00010").role(Role.builder().name("PARTICIPANT").build())
                .isActive(true).agreementComplete(true).selectedTechnology("Java Full Stack").build();
        UserRepository users = mock(UserRepository.class);
        when(users.findById(10L)).thenReturn(Optional.of(pat));
        AgreementRequestRepository requestRepo = mock(AgreementRequestRepository.class);
        when(requestRepo.findByUserId(anyLong())).thenAnswer(inv -> requests.stream()
                .filter(r -> r.getUserId().equals(inv.getArgument(0))).findFirst());
        when(requestRepo.findAllByOrderByRequestedAtAsc()).thenAnswer(inv -> List.copyOf(requests));
        when(requestRepo.save(any())).thenAnswer(inv -> {
            AgreementRequest r = inv.getArgument(0);
            r.setRequestedAt(LocalDateTime.now());
            requests.add(r);
            return r;
        });
        ConsultantApplicationRepository appRepo = mock(ConsultantApplicationRepository.class);
        when(appRepo.findByConsultantEmailIgnoreCaseAndDeletedFalseOrderByCreatedAtDesc(anyString()))
                .thenAnswer(inv -> agreements.stream()
                        .filter(a -> a.getConsultantEmail().equalsIgnoreCase(inv.getArgument(0))).toList());
        AgreementUserRepository consoleRepo = mock(AgreementUserRepository.class);
        when(consoleRepo.findAll()).thenAnswer(inv -> List.copyOf(consoleUsers));
        ProgramSelectionRepository programs = mock(ProgramSelectionRepository.class);
        when(programs.findFirstByUserIdOrderBySelectionDateDesc(10L)).thenReturn(Optional.of(ProgramSelection.builder()
                .userId(10L).program("Career Development Program").skillset("Cloud & DevOps")
                .targetJobTitle("DevOps Engineer").build()));
        emails = mock(EmailTemplateService.class);
        service = new MasterAgreementService(requestRepo, appRepo, consoleRepo, users, programs, emails,
                mock(RecordService.class));
        ReflectionTestUtils.setField(service, "appUrl", "https://portal.test");

        consoleUsers.add(console("a", AgreementUserRole.ERM, true));
        consoleUsers.add(console("b", AgreementUserRole.SUPER_ADMIN, true));
        consoleUsers.add(console("c", AgreementUserRole.MANAGER, true));
        consoleUsers.add(console("d", AgreementUserRole.ERM, false));
    }

    private static AgreementUser console(String id, AgreementUserRole role, boolean active) {
        AgreementUser u = new AgreementUser();
        u.setId(id);
        u.setEmail(id + "@console.test");
        u.setFullName("Console " + id);
        u.setRole(role);
        u.setActive(active);
        return u;
    }

    private ConsultantApplication agreement(String status) {
        ConsultantApplication a = ConsultantApplication.builder().applicationId("app-" + agreements.size())
                .consultantEmail("pat.lee@x.com").status(status).ermUserId(0L).build();
        agreements.add(0, a);   // newest first, as the repository returns them
        return a;
    }

    @Test
    void theConsolesFiveStepsReadPlainlyForTheParticipant() {
        assertEquals(1, MasterAgreementService.progressOf("SUBMITTED").step());
        assertTrue(MasterAgreementService.progressOf("SUBMITTED").yourTurn());
        assertTrue(MasterAgreementService.progressOf("REVISION_REQUESTED").yourTurn());
        assertEquals(2, MasterAgreementService.progressOf("VERIFIED").step());
        assertEquals(3, MasterAgreementService.progressOf("AWAITING_APPROVALS").step());
        assertEquals(3, MasterAgreementService.progressOf("APPROVAL_REVISION_REQUESTED").step());
        assertEquals(4, MasterAgreementService.progressOf("READY_TO_SIGN").step());
        assertEquals(5, MasterAgreementService.progressOf("COMPLETED").step());
        assertFalse(MasterAgreementService.progressOf("VERIFIED").yourTurn());
    }

    @Test
    void readyNeedsTheConsentFirst() {
        pat.setAgreementComplete(false);
        assertThrows(IllegalStateException.class, () -> service.request(10L));
        assertTrue(requests.isEmpty());
        verifyNoInteractions(emails);
    }

    @Test
    void readyIsRecordedOnceAndActiveConsoleErmsAreTold() {
        Map<String, Object> s = service.request(10L);
        assertEquals(true, s.get("requested"));
        assertNull(s.get("agreement"));
        verify(emails).sendAgreementRequestedEmail(eq("a@console.test"), eq("Console a"), eq(pat),
                eq("https://portal.test/agreements/new?participant=10"));
        verify(emails).sendAgreementRequestedEmail(eq("b@console.test"), any(), eq(pat), any());
        verify(emails, times(2)).sendAgreementRequestedEmail(any(), any(), any(), any());   // not the manager or the inactive ERM
        service.request(10L);
        assertEquals(1, requests.size(), "once");
        verify(emails, times(2)).sendAgreementRequestedEmail(any(), any(), any(), any());
    }

    @Test
    void theConsoleSeesWhoIsWaitingWithTheirDetailsFilledIn() {
        service.request(10L);
        List<MasterAgreementService.ConsoleRow> waiting = service.waitingForConsole();
        assertEquals(1, waiting.size());
        MasterAgreementService.ConsoleRow row = waiting.get(0);
        assertEquals("Pat", row.firstName());
        assertEquals("Q", row.middleName());
        assertEquals("Lee", row.lastName());
        assertEquals("Pat.Lee@x.com", row.email());
        assertEquals("Cloud & DevOps", row.technology(), "from the program they chose");
        assertEquals("Career Development Program", row.program());
        assertEquals("DevOps Engineer", row.targetJobTitle());

        agreement("SUBMITTED");
        assertTrue(service.waitingForConsole().isEmpty(), "started: no longer waiting");
    }

    @Test
    void theDashboardFollowsTheConsoleAgreement() {
        service.request(10L);
        ConsultantApplication a = agreement("SUBMITTED");
        @SuppressWarnings("unchecked")
        Map<String, Object> ag = (Map<String, Object>) service.status(10L).get("agreement");
        assertEquals(1, ag.get("step"));
        assertEquals(true, ag.get("yourTurn"));
        assertEquals("https://portal.test/consultant/" + a.getApplicationId() + "/login", ag.get("link"));

        a.setStatus("COMPLETED");
        @SuppressWarnings("unchecked")
        Map<String, Object> done = (Map<String, Object>) service.status(10L).get("agreement");
        assertEquals(5, done.get("step"));
        assertEquals(true, done.get("executed"));

        a.setStatus("CANCELLED");
        assertNull(service.status(10L).get("agreement"), "a cancelled one doesn't count");
        assertEquals(1, service.waitingForConsole().size(), "so the console can start a new one");
    }
}
