package com.spire.backend.service;

import com.spire.backend.config.BrandConfig;
import com.spire.backend.entity.EmailLog;
import com.spire.backend.repository.EmailLogRepository;
import com.spire.backend.repository.UserRepository;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Checklist 1.4: every email is recorded as SENT, FAILED or SKIPPED, a
 * failed one says so to its caller, and codes never reach the log.
 */
class EmailLogTest {

    private JavaMailSender mailSender;
    private EmailLogRepository logs;
    private EmailService emailService;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage((Session) null));
        logs = mock(EmailLogRepository.class);
        when(logs.save(any(EmailLog.class))).thenAnswer(inv -> inv.getArgument(0));
        UserRepository users = mock(UserRepository.class);
        when(users.findByEmail("pat@x.com")).thenReturn(Optional.of(
                com.spire.backend.entity.User.builder().id(42L).email("pat@x.com").build()));
        emailService = new EmailService(mailSender, mock(BrandConfig.class), new EmailLogService(logs, users));
        ReflectionTestUtils.setField(emailService, "fromEmail", "noreply@sageitco.com");
    }

    /** Stands in for an EmailTemplateService method: its name becomes the email type. */
    private boolean sendDocumentReminderEmail(String to, String subject) {
        return emailService.sendEmail(to, subject, "<p>hi</p>");
    }

    private EmailLog lastLog() {
        ArgumentCaptor<EmailLog> row = ArgumentCaptor.forClass(EmailLog.class);
        verify(logs, atLeastOnce()).save(row.capture());
        return row.getValue();
    }

    @Test
    void aSentEmailIsRecordedWithItsTypeAndUser() {
        assertTrue(sendDocumentReminderEmail("pat@x.com", "Action needed"));
        EmailLog row = lastLog();
        assertEquals("SENT", row.getStatus());
        assertEquals("DOCUMENT_REMINDER", row.getEmailType());
        assertEquals("pat@x.com", row.getRecipient());
        assertEquals(42L, row.getUserId());
        assertNull(row.getErrorMessage());
    }

    @Test
    void aFailedEmailSaysSoAndRecordsTheReason() {
        doThrow(new MailSendException("535 Authentication failed")).when(mailSender).send(any(MimeMessage.class));
        assertFalse(sendDocumentReminderEmail("pat@x.com", "Action needed"), "the caller learns it wasn't sent");
        EmailLog row = lastLog();
        assertEquals("FAILED", row.getStatus());
        assertTrue(row.getErrorMessage().contains("535"));
    }

    @Test
    void withoutEmailSetUpItIsRecordedAsSkipped() {
        ReflectionTestUtils.setField(emailService, "fromEmail", "");
        assertFalse(sendDocumentReminderEmail("pat@x.com", "Action needed"));
        assertEquals("SKIPPED", lastLog().getStatus());
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void codesInSubjectsNeverReachTheLog() {
        sendDocumentReminderEmail("pat@x.com", "Your code is 123456");
        assertEquals("Your code is ******", lastLog().getSubject());
    }

    @Test
    void aBrokenLogNeverBreaksSending() {
        when(logs.save(any(EmailLog.class))).thenThrow(new RuntimeException("db down"));
        assertTrue(sendDocumentReminderEmail("pat@x.com", "Action needed"));
    }

    @Test
    void emailTypesComeFromTheTemplateName() {
        assertEquals("DOCUMENT_REMINDER", EmailService.typeFromMethod("sendDocumentReminderEmail"));
        assertEquals("CONSULTANT_OTP", EmailService.typeFromMethod("sendConsultantOtp"));
        assertEquals("ERM_ASSIGNMENT_NOTIFICATION", EmailService.typeFromMethod("sendErmAssignmentNotification"));
    }
}
