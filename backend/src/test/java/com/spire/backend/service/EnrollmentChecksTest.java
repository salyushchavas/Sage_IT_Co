package com.spire.backend.service;

import com.spire.backend.entity.ParticipantDocument;
import com.spire.backend.entity.Role;
import com.spire.backend.entity.User;
import com.spire.backend.repository.EmailLogRepository;
import com.spire.backend.repository.ParticipantDocumentRepository;
import com.spire.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Checklist 1.5 (roadmap steps 1–2): duplicate phone numbers are caught
 * whatever their format, and the documents reminder goes to participants
 * only, naming what's missing.
 */
class EnrollmentChecksTest {

    // ── Phone numbers ─────────────────────────────────────────────

    @Test
    void theSameNumberInAnyFormatIsTheSameNumber() {
        String expected = "+15551234567";
        for (String raw : List.of("(555) 123-4567", "555-123-4567", "555.123.4567", "+1 555 123 4567",
                "1 (555) 123 4567", "+1-555-123-4567", "001 555 123 4567")) {
            assertEquals(expected, PhoneNumbers.normalize(raw), raw);
        }
        assertEquals("+919876543210", PhoneNumbers.normalize("+91 98765 43210"));
        assertNull(PhoneNumbers.normalize("  "));
    }

    @Test
    void somethingThatIsntAPhoneNumberIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> PhoneNumbers.normalize("12345"));
        assertThrows(IllegalArgumentException.class, () -> PhoneNumbers.normalize("call me"));
        assertNull(PhoneNumbers.normalizeOrNull("x"));
    }

    @Test
    void aNumberAnotherActiveAccountHoldsIsRefused() {
        UserRepository users = mock(UserRepository.class);
        User holder = User.builder().id(7L).isActive(true).build();
        when(users.findByPhoneNormalized("+15551234567")).thenReturn(List.of(holder));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> PhoneNumbers.requireAvailable(users, "+1 (555) 123-4567", null));
        assertTrue(ex.getMessage().contains("already used"));
        // ...but the holder keeping their own number is fine,
        assertEquals("+15551234567", PhoneNumbers.requireAvailable(users, "555-123-4567", 7L));
        // ...and a deactivated account's number can be reused.
        holder.setIsActive(false);
        assertEquals("+15551234567", PhoneNumbers.requireAvailable(users, "555-123-4567", null));
    }

    // ── The documents reminder ────────────────────────────────────

    private static User participant(long id, String role) {
        return User.builder().id(id).email("p" + id + "@x.com").fullName("Pat Doe").participantId("SAGE-2026-0000" + id)
                .role(Role.builder().name(role).build()).isActive(true).emailVerified(true)
                .basicInfoComplete(true).acknowledgmentComplete(true)
                .createdAt(LocalDateTime.now().minusDays(3)).build();
    }

    private record Job(DocumentReminderJob job, EmailTemplateService emails, List<ParticipantDocument> docs) {}

    private static Job job(List<User> everyone, long remindersAlreadySent) {
        UserRepository users = mock(UserRepository.class);
        when(users.findAll()).thenReturn(everyone);
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        List<ParticipantDocument> docs = new ArrayList<>();
        ParticipantDocumentRepository repo = mock(ParticipantDocumentRepository.class);
        when(repo.findByUserIdOrderByUploadedAtDesc(anyLong())).thenAnswer(inv -> docs.stream()
                .filter(d -> d.getUserId().equals(inv.getArgument(0))).toList());
        DocumentService documentService = new DocumentService(repo, users, mock(DocumentStorageService.class),
                mock(WorkflowService.class), mock(RecordService.class), mock(ProfileCompletionService.class),
                mock(PermissionService.class), mock(EmailTemplateService.class));
        EmailLogRepository logs = mock(EmailLogRepository.class);
        when(logs.countByEmailTypeAndUserIdAndStatus(eq("DOCUMENT_REMINDER"), anyLong(), eq("SENT")))
                .thenReturn(remindersAlreadySent);
        EmailTemplateService emails = mock(EmailTemplateService.class);
        when(emails.sendDocumentReminderEmail(any(), anyBoolean(), any())).thenReturn(true);
        ProfileCompletionService profile = new ProfileCompletionService(users, mock(RecordService.class),
                mock(OnboardingService.class));
        return new Job(new DocumentReminderJob(users, repo, documentService, profile, logs, emails,
                mock(EmailService.class)), emails, docs);
    }

    @Test
    void theReminderNamesTheMissingDocumentsAndSkipsStaff() {
        User pat = participant(1, "PARTICIPANT");
        User erm = participant(2, "ERM");   // staff with the same state never gets it
        Job j = job(List.of(pat, erm), 0);
        j.docs().add(ParticipantDocument.builder().id(10L).userId(1L).documentType("GOVERNMENT_ID")
                .reviewStatus("PENDING").notApplicable(false).build());

        assertEquals(1, j.job().sendReminders());
        verify(j.emails()).sendDocumentReminderEmail(eq(pat), eq(false),
                eq(List.of("Work Authorization / Visa", "Resume / CV")));
        verify(j.emails(), never()).sendDocumentReminderEmail(eq(erm), anyBoolean(), any());
        assertNotNull(pat.getLastNudgeSentAt());
    }

    @Test
    void aSentBackDocumentIsIncludedAndAPendingRequestIsNot() {
        User pat = participant(1, "PARTICIPANT");
        Job j = job(List.of(pat), 0);
        j.docs().add(ParticipantDocument.builder().id(10L).userId(1L).documentType("GOVERNMENT_ID")
                .reviewStatus("REJECTED").notApplicable(false).build());
        j.docs().add(ParticipantDocument.builder().id(11L).userId(1L).documentType("WORK_AUTHORIZATION")
                .reviewStatus("EXCEPTION_REQUESTED").notApplicable(true).build());
        j.docs().add(ParticipantDocument.builder().id(12L).userId(1L).documentType("RESUME")
                .reviewStatus("PENDING").notApplicable(false).build());

        j.job().sendReminders();
        verify(j.emails()).sendDocumentReminderEmail(eq(pat), eq(false), eq(List.of("Government-issued ID")));
    }

    @Test
    void theAcknowledgmentStepGetsItsOwnWording() {
        User pat = participant(1, "PARTICIPANT");
        pat.setAcknowledgmentComplete(false);
        Job j = job(List.of(pat), 0);
        j.job().sendReminders();
        verify(j.emails()).sendDocumentReminderEmail(eq(pat), eq(true), eq(List.of()));
    }

    @Test
    void nobodyIsRemindedTooOftenOrForever() {
        User recent = participant(1, "PARTICIPANT");
        recent.setLastNudgeSentAt(LocalDateTime.now().minusDays(1));
        User newcomer = participant(2, "PARTICIPANT");
        newcomer.setCreatedAt(LocalDateTime.now().minusHours(2));
        User unverified = participant(3, "PARTICIPANT");
        unverified.setEmailVerified(false);
        User done = participant(4, "PARTICIPANT");
        done.setDocumentsComplete(true);   // next step is the program: the profile reminder's job
        assertEquals(0, job(List.of(recent, newcomer, unverified, done), 0).job().sendReminders());
        assertEquals(0, job(List.of(participant(5, "PARTICIPANT")), DocumentReminderJob.MAX_REMINDERS)
                .job().sendReminders(), "at most " + DocumentReminderJob.MAX_REMINDERS + " reminders");
    }

    @Test
    void aFailedReminderIsNotStampedSoItIsTriedAgain() {
        User pat = participant(1, "PARTICIPANT");
        Job j = job(List.of(pat), 0);
        when(j.emails().sendDocumentReminderEmail(any(), anyBoolean(), any())).thenReturn(false);
        assertEquals(0, j.job().sendReminders());
        assertNull(pat.getLastNudgeSentAt());
    }
}
