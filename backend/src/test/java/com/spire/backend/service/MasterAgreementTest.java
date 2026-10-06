package com.spire.backend.service;

import com.spire.backend.entity.AgreementRequest;
import com.spire.backend.entity.ConsultantApplication;
import com.spire.backend.entity.ProgramSelection;
import com.spire.backend.entity.Role;
import com.spire.backend.entity.User;
import com.spire.backend.entity.WebAgreement;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.repository.AgreementRequestRepository;
import com.spire.backend.repository.ConsultantApplicationRepository;
import com.spire.backend.repository.ProgramSelectionRepository;
import com.spire.backend.repository.UserRepository;
import com.spire.backend.repository.WebAgreementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The real agreement step: after the consent, "I'm ready to sign the
 * agreement"; the website ERMs are told and start it from the participant's
 * details. The participant's dashboard follows an agreement already open in
 * the console (read-only, by email) exactly as before, otherwise the
 * website agreement.
 */
class MasterAgreementTest {

    private final List<AgreementRequest> requests = new ArrayList<>();
    private final List<ConsultantApplication> agreements = new ArrayList<>();
    private final List<WebAgreement> webAgreements = new ArrayList<>();
    private final List<User> users = new ArrayList<>();
    private EmailTemplateService emails;
    private MasterAgreementService service;
    private User pat;

    @BeforeEach
    void setUp() {
        pat = User.builder().id(10L).email("Pat.Lee@x.com").fullName("Pat Q Lee").phone("555 201 3344")
                .participantId("SAGE-2026-00010").role(Role.builder().name("PARTICIPANT").build())
                .isActive(true).agreementComplete(true).selectedTechnology("Java Full Stack").build();
        users.add(pat);
        users.add(staff(20L, "ERM", true));
        users.add(staff(21L, "ERM", true));
        users.add(staff(22L, "ERM", false));
        users.add(staff(30L, "OPERATIONS_ADMIN", true));
        users.add(staff(31L, "SYSTEM_ADMIN", true));
        UserRepository userRepo = mock(UserRepository.class);
        when(userRepo.findById(anyLong())).thenAnswer(inv -> users.stream()
                .filter(u -> u.getId().equals(inv.getArgument(0))).findFirst());
        when(userRepo.findAll()).thenAnswer(inv -> List.copyOf(users));
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
        WebAgreementRepository webRepo = mock(WebAgreementRepository.class);
        when(webRepo.findByParticipantUserIdAndDeletedFalseOrderByCreatedAtDesc(anyLong()))
                .thenAnswer(inv -> webAgreements.stream()
                        .filter(a -> a.getParticipantUserId().equals(inv.getArgument(0))).toList());
        when(webRepo.findByParticipantUserIdInAndDeletedFalse(any())).thenAnswer(inv -> {
            Collection<?> ids = inv.getArgument(0);
            return webAgreements.stream().filter(a -> ids.contains(a.getParticipantUserId())).toList();
        });
        ProgramSelectionRepository programs = mock(ProgramSelectionRepository.class);
        when(programs.findFirstByUserIdOrderBySelectionDateDesc(10L)).thenReturn(Optional.of(ProgramSelection.builder()
                .userId(10L).program("Career Development Program").skillset("Cloud & DevOps")
                .targetJobTitle("DevOps Engineer").build()));
        emails = mock(EmailTemplateService.class);
        service = new MasterAgreementService(requestRepo, appRepo, webRepo, userRepo, programs, emails,
                mock(RecordService.class));
        ReflectionTestUtils.setField(service, "appUrl", "https://portal.test");
    }

    private static User staff(long id, String role, boolean active) {
        return User.builder().id(id).email("staff" + id + "@sage.test").fullName("Staff " + id)
                .role(Role.builder().name(role).build()).isActive(active).build();
    }

    private ConsultantApplication agreement(String status) {
        ConsultantApplication a = ConsultantApplication.builder().applicationId("app-" + agreements.size())
                .consultantEmail("pat.lee@x.com").status(status).ermUserId(0L).build();
        agreements.add(0, a);   // newest first, as the repository returns them
        return a;
    }

    private WebAgreement webAgreement(String status) {
        WebAgreement a = WebAgreement.builder().id((long) webAgreements.size() + 1)
                .applicationId("web-" + webAgreements.size()).participantUserId(10L).ownerUserId(20L)
                .consultantEmail("pat.lee@x.com").status(status).build();
        webAgreements.add(0, a);   // newest first
        return a;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> shown() {
        return (Map<String, Object>) service.status(10L).get("agreement");
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
    void readyIsRecordedOnceAndActiveWebsiteErmsAreTold() {
        Map<String, Object> s = service.request(10L);
        assertEquals(true, s.get("requested"));
        assertNull(s.get("agreement"));
        verify(emails).sendWebAgreementRequestedEmail(argThat(u -> u.getId() == 20L), eq(pat));
        verify(emails).sendWebAgreementRequestedEmail(argThat(u -> u.getId() == 21L), eq(pat));
        // Not the inactive ERM, the admins or the participant; never the console's users.
        verify(emails, times(2)).sendWebAgreementRequestedEmail(any(), any());
        verify(emails, never()).sendAgreementRequestedEmail(any(), any(), any(), any());
        service.request(10L);
        assertEquals(1, requests.size(), "once");
        verify(emails, times(2)).sendWebAgreementRequestedEmail(any(), any());
    }

    @Test
    void oneErmsEmailFailingDoesNotStopTheOthers() {
        when(emails.sendWebAgreementRequestedEmail(argThat(u -> u != null && u.getId() == 20L), any()))
                .thenThrow(new RuntimeException("smtp down"));
        service.request(10L);
        verify(emails).sendWebAgreementRequestedEmail(argThat(u -> u.getId() == 21L), eq(pat));
        assertEquals(1, requests.size());
    }

    @Test
    void staffSeeWhoIsWaitingWithTheirDetailsFilledIn() {
        service.request(10L);
        List<MasterAgreementService.ReadyRow> waiting = service.readyForAgreement();
        assertEquals(1, waiting.size());
        MasterAgreementService.ReadyRow row = waiting.get(0);
        assertEquals(10L, row.userId());
        assertEquals("Pat", row.firstName());
        assertEquals("Q", row.middleName());
        assertEquals("Lee", row.lastName());
        assertEquals("Pat.Lee@x.com", row.email());
        assertEquals("Cloud & DevOps", row.technology(), "from the program they chose");
        assertEquals("Career Development Program", row.program());
        assertEquals("DevOps Engineer", row.targetJobTitle());
        assertNotNull(row.requestedAt());
        assertEquals("Pat", service.readyRow(10L).firstName());
        assertThrows(ResourceNotFoundException.class, () -> service.readyRow(20L), "a staff account isn't on the list");
        assertThrows(ResourceNotFoundException.class, () -> service.readyRow(999L));
    }

    @Test
    void anOpenAgreementHereOrInTheConsoleTakesThemOffTheList() {
        service.request(10L);
        WebAgreement web = webAgreement("SUBMITTED");
        assertTrue(service.readyForAgreement().isEmpty(), "started on the website");
        assertThrows(ResourceNotFoundException.class, () -> service.readyRow(10L), "nothing to prefill");
        web.setStatus("CANCELLED");
        assertEquals(1, service.readyForAgreement().size(), "a cancelled one doesn't count");

        ConsultantApplication console = agreement("VERIFIED");
        assertTrue(service.readyForAgreement().isEmpty(), "open in the console");
        console.setStatus("CANCELLED");
        assertEquals(1, service.readyForAgreement().size());

        pat.setIsActive(false);
        assertTrue(service.readyForAgreement().isEmpty(), "inactive accounts aren't listed");
    }

    @Test
    void theDashboardFollowsTheConsoleAgreement() {
        service.request(10L);
        ConsultantApplication a = agreement("SUBMITTED");
        Map<String, Object> ag = shown();
        assertEquals(1, ag.get("step"));
        assertEquals(true, ag.get("yourTurn"));
        assertEquals("CONSOLE", ag.get("source"));
        assertEquals("https://portal.test/consultant/" + a.getApplicationId() + "/login", ag.get("link"));

        a.setStatus("COMPLETED");
        Map<String, Object> done = shown();
        assertEquals(5, done.get("step"));
        assertEquals(true, done.get("executed"));

        a.setStatus("CANCELLED");
        assertNull(service.status(10L).get("agreement"), "a cancelled one doesn't count");
    }

    @Test
    void theDashboardFollowsTheWebsiteAgreementThroughItsSteps() {
        service.request(10L);
        WebAgreement a = webAgreement("SUBMITTED");
        Map<String, Object> ag = shown();
        assertEquals("WEBSITE", ag.get("source"));
        assertEquals("/dashboard/agreement", ag.get("link"));
        assertEquals(5, ag.get("totalSteps"));
        assertEquals(1, ag.get("step"));
        assertEquals("Ready for you to fill and sign", ag.get("stage"));
        assertEquals(true, ag.get("yourTurn"));

        a.setStatus("REVISION_REQUESTED");
        assertEquals(1, shown().get("step"));
        assertEquals("Your ERM asked for changes", shown().get("stage"));
        assertEquals(true, shown().get("yourTurn"));

        a.setStatus("VERIFIED");
        a.setConsultantCopyReleased(false);
        assertEquals(2, shown().get("step"));
        assertEquals("Signed by you; your ERM is checking it", shown().get("stage"));
        assertEquals(false, shown().get("yourTurn"));

        a.setConsultantCopyReleased(true);
        assertEquals(3, shown().get("step"));
        assertEquals("Verified by your ERM. Internal approval comes next", shown().get("stage"));
        assertEquals(false, shown().get("executed"));

        a.setStatus("CANCELLED");
        assertNull(service.status(10L).get("agreement"), "a cancelled one is no agreement");
    }

    @Test
    void anOpenConsoleAgreementWinsOverAWebsiteOne() {
        webAgreement("SUBMITTED");
        agreement("VERIFIED");
        assertEquals("CONSOLE", shown().get("source"));
        assertEquals(2, shown().get("step"));
    }
}
