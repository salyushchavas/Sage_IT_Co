package com.spire.backend.service;

import com.spire.backend.entity.Role;
import com.spire.backend.entity.User;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checklists 3.4 and 3.5: the Home roadmap card ticks what really happened
 * (not what the status suggests), and the one next action never points at
 * a tab that is still locked.
 */
class RoadmapCardTest {

    private static User finished(String status) {
        return User.builder().id(10L).participantId("SAGE-2026-00007").role(Role.builder().name("PARTICIPANT").build())
                .basicInfoComplete(true).emailVerified(true).acknowledgmentComplete(true).documentsComplete(true)
                .programSelectionComplete(true).agreementComplete(true).checkUploadComplete(true)
                .currentStatus(status).build();
    }

    private static ParticipantDashboardService.RoadmapFacts facts(boolean welcome, boolean coord, boolean erm, boolean coach,
                                                                 long reports, boolean invoice, boolean plan, boolean ready) {
        return new ParticipantDashboardService.RoadmapFacts(welcome, coord, erm, coach, reports, invoice, plan, ready);
    }

    @Test
    void laterStepsAreTickedFromWhatReallyHappened() {
        // Status says weekly reporting is active, but no report was ever submitted.
        List<Boolean> done = ParticipantDashboardService.roadmapDone(finished("WEEKLY_REPORTING_ACTIVE"),
                facts(true, true, true, true, 0, false, false, true));
        assertTrue(done.get(14), "15 dashboard");
        assertFalse(done.get(15), "16 weekly reporting needs a submitted report");
        assertEquals(16, ParticipantDashboardService.currentStep(done));

        // The welcome email failed: step 11 is not ticked even though the status moved on.
        done = ParticipantDashboardService.roadmapDone(finished("DASHBOARD_ENABLED"),
                facts(false, true, true, true, 0, false, false, true));
        assertFalse(done.get(10), "11 welcome not sent");
        assertTrue(done.get(11));

        // An ERM status without an ERM (the old order bug) doesn't tick step 13.
        done = ParticipantDashboardService.roadmapDone(finished("COACHES_ASSIGNED"),
                facts(true, true, false, true, 0, false, false, false));
        assertFalse(done.get(12), "13 needs a real ERM");
    }

    @Test
    void theLabelsFollowTheRoadmap() {
        assertEquals(20, ParticipantDashboardService.ROADMAP_STEPS.size());
        assertEquals(List.of("Weekly reporting", "Employment & Phase 1", "Payment plan & checks", "Invoices", "Payments tracked"),
                ParticipantDashboardService.ROADMAP_STEPS.subList(15, 20));
    }

    @Test
    void theNextActionNeverPointsAtALockedTab() {
        // Finished onboarding, team not ready yet: Weekly is locked, so point to the welcome page.
        Map<String, String> a = ParticipantDashboardService.nextActionFor(finished("WELCOME_SENT"),
                facts(true, false, false, false, 0, false, false, false));
        assertEquals("/welcome", a.get("href"));

        a = ParticipantDashboardService.nextActionFor(finished("DASHBOARD_ENABLED"),
                facts(true, true, true, true, 0, false, false, true));
        assertEquals("#weekly", a.get("href"));
        assertTrue(a.get("label").contains("first weekly report"));

        // Phase 1 done but Finance hasn't made a plan: nothing to accept yet.
        a = ParticipantDashboardService.nextActionFor(finished("PHASE_1_COMPLETED"),
                facts(true, true, true, true, 5, false, false, true));
        assertEquals("#home", a.get("href"));
        a = ParticipantDashboardService.nextActionFor(finished("PHASE_1_COMPLETED"),
                facts(true, true, true, true, 5, false, true, true));
        assertEquals("#payments", a.get("href"));
    }
}
