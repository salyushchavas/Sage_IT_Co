package com.spire.backend.service;

import com.spire.backend.controller.FinanceController;
import com.spire.backend.dto.CheckDocumentDTO;
import com.spire.backend.entity.CheckDocument;
import com.spire.backend.entity.Role;
import com.spire.backend.entity.User;
import com.spire.backend.repository.*;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Checklist 2.4 (roadmap step 8): check numbers are masked; Finance must
 * give a reason to reject, which is emailed; the participant replaces a
 * rejected copy; the receipt email comes before the welcome chain.
 */
class CheckCopiesTest {

    @Test
    void checkNumbersAreMasked() {
        assertEquals("••••5678", CheckDocumentDTO.maskCheckNumber("12345678"));
        assertEquals("••••5678", CheckDocumentDTO.maskCheckNumber("12-345-678"));
        assertEquals("••34", CheckDocumentDTO.maskCheckNumber("1234"));
        assertNull(CheckDocumentDTO.maskCheckNumber(null));
        assertEquals("••••5678", CheckDocumentDTO.from(CheckDocument.builder().checkNumber("12345678").build()).getCheckNumber());
    }

    private static final Authentication FINANCE = new UsernamePasswordAuthenticationToken("7", null, List.of());

    private record Kit(FinanceController controller, CheckDocument check, EmailTemplateService emails) {}

    private Kit finance() {
        CheckDocumentRepository checks = mock(CheckDocumentRepository.class);
        CheckDocument check = CheckDocument.builder().id(3L).userId(10L).checkNumber("12345678")
                .reviewStatus("PENDING").fileUrl("participant-documents/10/check-a.png").build();
        when(checks.findById(3L)).thenReturn(Optional.of(check));
        when(checks.save(any())).thenAnswer(inv -> inv.getArgument(0));
        UserRepository users = mock(UserRepository.class);
        when(users.findById(10L)).thenReturn(Optional.of(User.builder().id(10L).email("p@x.com")
                .role(Role.builder().name("PARTICIPANT").build()).build()));
        EmailTemplateService emails = mock(EmailTemplateService.class);
        FinanceController c = new FinanceController(checks, users, mock(RecordService.class), mock(PaymentService.class),
                mock(CheckTrackingService.class), mock(PaymentPlanRepository.class), mock(InvoiceRepository.class),
                mock(PaymentLedgerRepository.class), emails, mock(DocumentStorageService.class),
                mock(WorkflowService.class), mock(EmploymentService.class), mock(InvoicePdfService.class), mock(BusinessClock.class));
        return new Kit(c, check, emails);
    }

    @Test
    void rejectingNeedsAReasonAndEmailsItOnce() {
        Kit k = finance();
        assertThrows(IllegalArgumentException.class,
                () -> k.controller().reviewCheck(3L, Map.of("status", "REJECTED"), FINANCE));
        verifyNoInteractions(k.emails());
        k.controller().reviewCheck(3L, Map.of("status", "REJECTED", "notes", "The image is blurry"), FINANCE);
        k.controller().reviewCheck(3L, Map.of("status", "REJECTED", "notes", "The image is blurry"), FINANCE);
        assertEquals("REJECTED", k.check().getReviewStatus());
        assertEquals("The image is blurry", k.check().getReviewNotes());
        assertEquals(7L, k.check().getReviewedBy());
        verify(k.emails(), times(1)).sendCheckRejectedEmail(any(), eq("••••5678"), eq("The image is blurry"), eq(3L));
    }

    @Test
    void theFinanceListNeverCarriesTheFullNumberOrTheImageLink() {
        Kit k = finance();
        CheckDocumentRepository repo = mock(CheckDocumentRepository.class);
        when(repo.findAll()).thenReturn(List.of(k.check()));
        FinanceController c = new FinanceController(repo, mock(UserRepository.class), mock(RecordService.class),
                mock(PaymentService.class), mock(CheckTrackingService.class), mock(PaymentPlanRepository.class),
                mock(InvoiceRepository.class), mock(PaymentLedgerRepository.class), mock(EmailTemplateService.class),
                mock(DocumentStorageService.class), mock(WorkflowService.class), mock(EmploymentService.class),
                mock(InvoicePdfService.class), mock(BusinessClock.class));
        Map<String, Object> row = c.listChecks(null).getBody().getData().get(0);
        assertEquals("••••5678", row.get("checkNumber"));
        assertFalse(row.containsKey("fileUrl"));
        assertEquals(true, row.get("hasFile"));
    }

    private ParticipantCheckService participantSide(CheckDocumentRepository checks, EmailTemplateService emails,
                                                    ProfileCompletionService profile) {
        UserRepository users = mock(UserRepository.class);
        User pat = User.builder().id(10L).email("p@x.com").participantId("SAGE-2026-00007")
                .role(Role.builder().name("PARTICIPANT").build()).agreementComplete(true)
                .currentStatus("AGREEMENT_COMPLETED").build();
        when(users.findById(10L)).thenReturn(Optional.of(pat));
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(checks.save(any())).thenAnswer(inv -> {
            CheckDocument d = inv.getArgument(0);
            if (d.getId() == null) d.setId(99L);
            return d;
        });
        DocumentStorageService storage = mock(DocumentStorageService.class);
        when(storage.upload(anyLong(), anyString(), any(), any()))
                .thenReturn(new DocumentStorageService.StoredFile("participant-documents/10/c.png", "participant-documents/10/c.png"));
        WorkflowService workflow = new WorkflowService(users, mock(WorkflowStateRepository.class), mock(RecordService.class));
        return new ParticipantCheckService(checks, users, storage, workflow,
                mock(RecordService.class), emails, profile);
    }

    private static MockMultipartFile image() {
        return new MockMultipartFile("file", "check.png", "image/png", new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3});
    }

    @Test
    void onlyAnOwnRejectedCopyCanBeReplaced() {
        CheckDocumentRepository checks = mock(CheckDocumentRepository.class);
        when(checks.findById(5L)).thenReturn(Optional.of(CheckDocument.builder().id(5L).userId(10L).reviewStatus("PENDING").build()));
        when(checks.findById(6L)).thenReturn(Optional.of(CheckDocument.builder().id(6L).userId(11L).reviewStatus("REJECTED").build()));
        when(checks.findById(7L)).thenReturn(Optional.of(CheckDocument.builder().id(7L).userId(10L).reviewStatus("REJECTED").build()));
        ParticipantCheckService s = participantSide(checks, mock(EmailTemplateService.class), mock(ProfileCompletionService.class));
        assertThrows(IllegalArgumentException.class, () -> s.upload(10L, image(), "12345678", null, null, null, 5L));
        assertThrows(IllegalArgumentException.class, () -> s.upload(10L, image(), "12345678", null, null, null, 6L));
        CheckDocument saved = s.upload(10L, image(), "12345678", null, null, null, 7L);
        assertEquals(7L, saved.getReplacesCheckId());
        assertEquals("PENDING", saved.getReviewStatus());
    }

    @Test
    void theReceiptArrivesBeforeTheWelcomeChainStarts() {
        EmailTemplateService emails = mock(EmailTemplateService.class);
        ProfileCompletionService profile = mock(ProfileCompletionService.class);
        participantSide(mock(CheckDocumentRepository.class), emails, profile)
                .upload(10L, image(), "12345678", null, null, null, null);
        InOrder order = inOrder(emails, profile);
        order.verify(emails).sendCheckUploadConfirmationEmail(any());
        order.verify(profile).markStepComplete(any(), eq("CHECK_UPLOAD"));
    }
}
