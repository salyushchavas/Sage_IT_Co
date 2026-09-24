package com.spire.backend.service;

import com.spire.backend.dto.AcknowledgmentSubmitRequest;
import com.spire.backend.entity.Acknowledgment;
import com.spire.backend.entity.Role;
import com.spire.backend.entity.User;
import com.spire.backend.repository.AcknowledgmentRepository;
import com.spire.backend.repository.UserRepository;
import com.spire.backend.repository.WorkflowStateRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Checklist 1.2: the server owns the exact acknowledgment text, accepts only
 * its current version, stores a fingerprint of what was accepted, and records
 * the IP address our hosting proxy saw (not one the browser can set).
 */
class AcknowledgmentRecordTest {

    private static AcknowledgmentSubmitRequest valid(String version) {
        AcknowledgmentSubmitRequest r = new AcknowledgmentSubmitRequest();
        r.setLegalName("Pat Q Doe");
        r.setSignatureImage("data:image/png;base64,AA");
        r.setSignatureMethod("draw");
        r.setInterestAccepted(true);
        r.setDocumentationConsent(true);
        r.setCommunicationConsent(true);
        r.setAcknowledgmentVersion(version);
        return r;
    }

    private record Setup(AcknowledgmentService service, AcknowledgmentRepository repo) {}

    private static Setup setup() {
        UserRepository users = mock(UserRepository.class);
        User user = User.builder().id(10L).email("p@x.com").role(Role.builder().name("PARTICIPANT").build())
                .emailVerified(true).participantId("SIT-2026-00001").basicInfoComplete(true)
                .currentStatus("ID_EMAIL_SENT").build();
        when(users.findById(10L)).thenReturn(Optional.of(user));
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        AcknowledgmentRepository repo = mock(AcknowledgmentRepository.class);
        when(repo.save(any(Acknowledgment.class))).thenAnswer(inv -> {
            Acknowledgment a = inv.getArgument(0);
            a.setId(1L);
            return a;
        });
        WorkflowService workflow = new WorkflowService(users, mock(WorkflowStateRepository.class), mock(RecordService.class));
        return new Setup(new AcknowledgmentService(repo, users, workflow, mock(RecordService.class),
                mock(ProfileCompletionService.class)), repo);
    }

    @Test
    void theTextIsFingerprintedExactly() {
        String canonical = AcknowledgmentText.canonical();
        assertTrue(canonical.startsWith(AcknowledgmentText.VERSION + "\n" + AcknowledgmentText.TITLE));
        AcknowledgmentText.CLAUSES.forEach(c -> assertTrue(canonical.contains(c)));
        assertTrue(canonical.contains(AcknowledgmentText.CONSENT_DOCUMENTATION));
        assertTrue(AcknowledgmentText.fingerprint().matches("[0-9a-f]{64}"));
        assertEquals(6, AcknowledgmentText.CLAUSES.size());
    }

    @Test
    void onlyTheCurrentVersionCanBeAccepted() {
        Setup s = setup();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> s.service().submit(10L, valid("ACK-v0.9"), null));
        assertTrue(ex.getMessage().contains("updated"));
        verify(s.repo(), never()).save(any());
    }

    @Test
    void theRecordStoresTheVersionFingerprintAndTheProxySeenIp() {
        Setup s = setup();
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.addHeader("X-Forwarded-For", "6.6.6.6, 203.0.113.7");   // first hop set by the browser
        http.addHeader("User-Agent", "Mozilla/5.0 (iPhone)");

        s.service().submit(10L, valid(AcknowledgmentText.VERSION), http);

        ArgumentCaptor<Acknowledgment> saved = ArgumentCaptor.forClass(Acknowledgment.class);
        verify(s.repo()).save(saved.capture());
        Acknowledgment row = saved.getValue();
        assertEquals(AcknowledgmentText.VERSION, row.getAcceptedTextVersion());
        assertEquals(AcknowledgmentText.fingerprint(), row.getTextSha256());
        assertEquals("203.0.113.7", row.getIpAddress(), "the hop our proxy appended, not the browser's");
        assertEquals("mobile", row.getDevice());
        assertEquals("Pat Q Doe", row.getLegalName());
    }

    @Test
    void theIpFallsBackToTheSocketAddress() {
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.setRemoteAddr("198.51.100.9");
        assertEquals("198.51.100.9", AcknowledgmentService.clientIp(http));
        http.addHeader("X-Forwarded-For", " , ");
        assertEquals("198.51.100.9", AcknowledgmentService.clientIp(http));
    }
}
