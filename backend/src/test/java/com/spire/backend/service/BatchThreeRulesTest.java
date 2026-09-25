package com.spire.backend.service;

import com.spire.backend.entity.Coupon;
import com.spire.backend.entity.ErmAssignment;
import com.spire.backend.entity.Role;
import com.spire.backend.entity.User;
import com.spire.backend.entity.UserRecord;
import com.spire.backend.repository.CouponRedemptionRepository;
import com.spire.backend.repository.CouponRepository;
import com.spire.backend.repository.ErmAssignmentRepository;
import com.spire.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Rules from the production-readiness review (batch 3). */
class BatchThreeRulesTest {

    private static User user(long id, String role, boolean active) {
        return User.builder().id(id).fullName("U" + id).role(Role.builder().name(role).build()).isActive(active).build();
    }

    @Test
    void staffSideEventsStayOffTheParticipantsRecentActivity() {
        assertFalse(ParticipantDashboardService.shownToParticipant(
                UserRecord.builder().recordType("DOCUMENT_VIEWED").title("Document viewed by staff").build()));
        assertFalse(ParticipantDashboardService.shownToParticipant(
                UserRecord.builder().recordType("WORKFLOW").title("Status changed: A → B").build()));
        assertFalse(ParticipantDashboardService.shownToParticipant(
                UserRecord.builder().recordType("ACCOUNT").title("Offer letter viewed by ERM").build()));
        assertTrue(ParticipantDashboardService.shownToParticipant(
                UserRecord.builder().recordType("ACCOUNT").title("Weekly report submitted").build()));
    }

    @Test
    void onlyAnActiveErmCanBeAssignedAndHistoryIsKept() {
        ErmAssignmentRepository repo = mock(ErmAssignmentRepository.class);
        UserRepository users = mock(UserRepository.class);
        ErmAssignmentService service = new ErmAssignmentService(repo, users);
        User participant = user(10, "PARTICIPANT", true);
        when(repo.findFirstByUserIdOrderByAssignedDateDesc(10L))
                .thenReturn(Optional.of(ErmAssignment.builder().id(1L).userId(10L).ermUserId(2L).build()));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThrows(IllegalArgumentException.class, () -> service.assignManually(participant, user(3, "COACH", true), 9L));
        assertThrows(IllegalArgumentException.class, () -> service.assignManually(participant, user(4, "ERM", false), 9L));
        assertThrows(IllegalArgumentException.class, () -> service.assignManually(user(11, "FINANCE", true), user(5, "ERM", true), 9L));

        ErmAssignment row = service.assignManually(participant, user(5, "ERM", true), 9L);
        assertNull(row.getId(), "a new row: the earlier assignment stays as history");
        assertEquals(5L, row.getErmUserId());
    }

    @Test
    void aSecondPaidCheckoutWithTheSameCouponStillCompletes() {
        CouponRedemptionRepository redemptions = mock(CouponRedemptionRepository.class);
        CouponRepository coupons = mock(CouponRepository.class);
        UserRepository users = mock(UserRepository.class);
        CouponService service = new CouponService(coupons, redemptions, users);
        Coupon c = Coupon.builder().id(3L).code("SAVE10").build();
        when(redemptions.existsByCouponIdAndUserId(3L, 10L)).thenReturn(true);
        assertDoesNotThrow(() -> service.redeem(c, 10L, BigDecimal.TEN, BigDecimal.valueOf(90)));
        verify(redemptions, never()).save(any());
        verify(coupons, never()).addUse(any());
    }
}
