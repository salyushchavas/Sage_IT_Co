package com.spire.backend.service;

import com.spire.backend.entity.AgreementAcceptance;
import com.spire.backend.entity.ProgramSelection;
import com.spire.backend.entity.Role;
import com.spire.backend.entity.User;
import com.spire.backend.repository.AgreementAcceptanceRepository;
import com.spire.backend.repository.ProgramSelectionRepository;
import com.spire.backend.repository.UserRepository;
import com.spire.backend.repository.WorkflowStateRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Checklist 2.5: the agreement queue shows where each agreement really is; participants can decline. */
class AgreementQueueTest {

    private static User participant(long id) {
        return User.builder().id(id).fullName("P" + id).email("p" + id + "@x.com").participantId("SAGE-2026-0000" + id)
                .role(Role.builder().name("PARTICIPANT").build()).isActive(true)
                .basicInfoComplete(true).acknowledgmentComplete(true).documentsComplete(true)
                .programSelectionComplete(true).build();
    }

    @Test
    void eachAgreementLandsInTheRightStage() {
        User waiting = participant(1), expired = participant(2), declined = participant(3),
                checkStep = participant(4), needsErm = participant(5), withErm = participant(6), done = participant(7),
                staff = User.builder().id(8L).role(Role.builder().name("ERM").build()).participantId(null).isActive(true).build();
        for (User u : List.of(checkStep, needsErm, withErm, done)) u.setAgreementComplete(true);
        for (User u : List.of(needsErm, withErm, done)) u.setCheckUploadComplete(true);

        UserRepository users = mock(UserRepository.class);
        when(users.findAll()).thenReturn(List.of(waiting, expired, declined, checkStep, needsErm, withErm, done, staff));
        when(users.findById(60L)).thenReturn(Optional.of(User.builder().id(60L).fullName("Erin Rao").build()));
        AgreementAcceptanceRepository agreements = mock(AgreementAcceptanceRepository.class);
        LocalDateTime now = LocalDateTime.now();
        when(agreements.findByUserId(anyLong())).thenReturn(Optional.empty());
        when(agreements.findByUserId(3L)).thenReturn(Optional.of(AgreementAcceptance.builder().status("DECLINED")
                .declinedAt(now.minusDays(1)).declineReason("The fee isn't clear").build()));
        for (long id : new long[]{4, 5, 6, 7}) {
            AgreementAcceptance.AgreementAcceptanceBuilder b = AgreementAcceptance.builder().status("VERIFIED").acceptedAt(now.minusDays(2));
            if (id == 6) b.ermRoutedTo(60L).ermRoutedAt(now.minusDays(1));
            if (id == 7) b.ermRoutedTo(60L).ermRoutedAt(now.minusDays(1)).ermReviewedAt(now);
            when(agreements.findByUserId(id)).thenReturn(Optional.of(b.build()));
        }
        ProgramSelectionRepository programs = mock(ProgramSelectionRepository.class);
        when(programs.findFirstByUserIdOrderBySelectionDateDesc(1L)).thenReturn(Optional.of(ProgramSelection.builder().selectionDate(now.minusDays(2)).build()));
        when(programs.findFirstByUserIdOrderBySelectionDateDesc(2L)).thenReturn(Optional.of(ProgramSelection.builder().selectionDate(now.minusDays(20)).build()));
        ErmAssignmentService erms = mock(ErmAssignmentService.class);
        when(erms.getAssignedErm(anyLong())).thenReturn(Optional.empty());

        List<AgreementQueueService.Row> rows = new AgreementQueueService(users, agreements, programs, erms).queue();
        Map<Long, String> stage = new java.util.HashMap<>();
        rows.forEach(r -> stage.put(r.userId(), r.stage()));
        assertEquals(Map.of(1L, "WAITING", 2L, "EXPIRED", 3L, "DECLINED", 4L, "CHECK_STEP", 5L, "NEEDS_ERM", 6L, "ERM_REVIEW"), stage,
                "the reviewed agreement and the staff account are not in the queue");
        assertEquals("DECLINED", rows.get(0).stage(), "the ones needing action come first");
        assertEquals("The fee isn't clear", rows.stream().filter(r -> r.userId() == 3L).findFirst().get().detail());
        assertEquals("With Erin Rao", rows.stream().filter(r -> r.userId() == 6L).findFirst().get().detail());
    }

    @Test
    void aParticipantCanDeclineWithAReasonButNotAfterSigning() {
        UserRepository users = mock(UserRepository.class);
        User pat = participant(10);
        when(users.findById(10L)).thenReturn(Optional.of(pat));
        AgreementAcceptanceRepository agreements = mock(AgreementAcceptanceRepository.class);
        when(agreements.findByUserId(10L)).thenReturn(Optional.empty());
        when(agreements.save(any())).thenAnswer(inv -> inv.getArgument(0));
        WorkflowService workflow = new WorkflowService(users, mock(WorkflowStateRepository.class), mock(RecordService.class));
        ParticipantAgreementService s = new ParticipantAgreementService(mock(AgreementService.class), agreements, users,
                workflow, mock(RecordService.class), mock(ProfileCompletionService.class), new TermsContentService(),
                mock(ProgramSelectionRepository.class));
        assertThrows(IllegalArgumentException.class, () -> s.decline(10L, "no"));
        s.decline(10L, "I need to talk to my family first");
        ArgumentCaptor<AgreementAcceptance> saved = ArgumentCaptor.forClass(AgreementAcceptance.class);
        verify(agreements).save(saved.capture());
        assertEquals("DECLINED", saved.getValue().getStatus());
        assertEquals("I need to talk to my family first", saved.getValue().getDeclineReason());
        assertNotNull(saved.getValue().getDeclinedAt());

        pat.setAgreementComplete(true);
        assertThrows(IllegalStateException.class, () -> s.decline(10L, "Changed my mind"));
    }
}
