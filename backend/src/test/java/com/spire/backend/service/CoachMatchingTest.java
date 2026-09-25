package com.spire.backend.service;

import com.spire.backend.entity.CoachAssignment;
import com.spire.backend.entity.ProgramSelection;
import com.spire.backend.entity.Role;
import com.spire.backend.entity.User;
import com.spire.backend.repository.CoachAssignmentRepository;
import com.spire.backend.repository.ProgramSelectionRepository;
import com.spire.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Checklist 3.2 (roadmap step 14): each coach slot is filled by that kind
 * of coach, the technical advisor covers the participant's skill, and each
 * coach is emailed about their new participant.
 */
class CoachMatchingTest {

    private final List<CoachAssignment> rows = new ArrayList<>();
    private UserRepository users;
    private ProgramSelectionRepository programs;
    private EmailTemplateService emails;
    private CoachAssignmentService service;
    private User pat;

    private static User coach(long id, String name, String role, String bio) {
        User u = User.builder().id(id).fullName(name).email(name.toLowerCase().replace(' ', '.') + "@x.com")
                .role(Role.builder().name(role).build()).isActive(true).bio(bio).build();
        String[] p = CoachProfiles.fromBio(role, bio);
        u.setCoachTypes(p[0]);
        u.setCoachSkills(p[1]);
        return u;
    }

    // The seeded coaches' real bios.
    private final User arjun = coach(21, "Arjun Menon", "COACH", "Career coach — resume reviews, profile administration, job-market navigation.");
    private final User rahul = coach(22, "Rahul Kapoor", "COACH", "Interview coach — mock interviews, communication training.");
    private final User priya = coach(23, "Priya Sharma", "TECHNICAL_ADVISOR", "Technical advisor — Java Full Stack, Python Full Stack, Cloud & DevOps.");

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        when(users.findAll()).thenReturn(List.of(arjun, rahul, priya));
        for (User c : List.of(arjun, rahul, priya)) when(users.findById(c.getId())).thenReturn(Optional.of(c));
        CoachAssignmentRepository repo = mock(CoachAssignmentRepository.class);
        when(repo.save(any())).thenAnswer(inv -> {
            CoachAssignment a = inv.getArgument(0);
            if (!rows.contains(a)) rows.add(a);
            return a;
        });
        when(repo.findAll()).thenAnswer(inv -> List.copyOf(rows));
        when(repo.findByUserIdAndStatus(anyLong(), eq("ACTIVE"))).thenAnswer(inv -> rows.stream()
                .filter(a -> a.getUserId().equals(inv.getArgument(0)) && "ACTIVE".equals(a.getStatus())).toList());
        programs = mock(ProgramSelectionRepository.class);
        emails = mock(EmailTemplateService.class);
        service = new CoachAssignmentService(repo, users, emails, programs, mock(RecordService.class));
        pat = User.builder().id(10L).fullName("Pat Doe").participantId("SAGE-2026-00007").build();
    }

    private void skill(String s) {
        when(programs.findFirstByUserIdOrderBySelectionDateDesc(10L))
                .thenReturn(Optional.of(ProgramSelection.builder().skillset(s).program("Full Stack").build()));
    }

    @Test
    void theSeededBiosGiveTheRightTypesAndSkills() {
        assertEquals("CAREER_COACH,RESUME_SPECIALIST", arjun.getCoachTypes());
        assertEquals("INTERVIEW_COACH", rahul.getCoachTypes());
        assertEquals("TECHNICAL_ADVISOR", priya.getCoachTypes());
        assertEquals("Java Full Stack,Python Full Stack,Cloud & DevOps", priya.getCoachSkills());
    }

    @Test
    void eachSlotGetsTheRightKindOfCoachAndEachCoachIsEmailed() {
        skill("Java Full Stack");
        Map<String, String> out = service.assignCoaches(pat);
        assertEquals("Arjun Menon", out.get("Career Coach"));
        assertEquals("Arjun Menon", out.get("Resume Specialist"), "only Arjun does resumes (no other candidate)");
        assertEquals("Priya Sharma", out.get("Technical Advisor"));
        assertEquals("Rahul Kapoor", out.get("Interview Coach"), "the interview coach, never a resume slot");
        verify(emails).sendCoachNewParticipantEmail(eq(priya), eq(pat), eq("Technical Advisor"), any());
        verify(emails).sendCoachNewParticipantEmail(eq(rahul), eq(pat), eq("Interview Coach"), any());
        verify(emails, times(4)).sendCoachNewParticipantEmail(any(), eq(pat), any(), any());
        service.assignCoaches(pat);
        verify(emails, times(4)).sendCoachNewParticipantEmail(any(), eq(pat), any(), any());   // no repeats
    }

    @Test
    void noTechnicalAdvisorForTheSkillLeavesThatSlotForOperations() {
        skill(".NET Full Stack");
        Map<String, String> out = service.assignCoaches(pat);
        assertEquals("Awaiting assignment", out.get("Technical Advisor"));
        verify(emails, never()).sendCoachNewParticipantEmail(eq(priya), any(), any(), any());
    }

    @Test
    void operationsCanOnlyPutACoachInARealSlotAndThePreviousOneIsEnded() {
        skill("Java Full Stack");
        service.assignCoaches(pat);
        User stranger = User.builder().id(40L).role(Role.builder().name("FINANCE").build()).isActive(true).build();
        assertThrows(IllegalArgumentException.class, () -> service.assignManually(pat, rahul, "HEAD_COACH", 1L));
        assertThrows(IllegalArgumentException.class, () -> service.assignManually(pat, stranger, "CAREER_COACH", 1L));
        service.assignManually(pat, rahul, "CAREER_COACH", 1L);
        List<CoachAssignment> career = rows.stream().filter(a -> "CAREER_COACH".equals(a.getCoachRole())).toList();
        assertEquals(2, career.size(), "the old row is kept for history");
        assertEquals("ENDED", career.get(0).getStatus());
        assertEquals(22L, career.get(1).getCoachUserId());
        verify(emails).sendCoachNewParticipantEmail(eq(rahul), eq(pat), eq("Career Coach"), any());
    }
}
