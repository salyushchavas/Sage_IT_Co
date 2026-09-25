package com.spire.backend.service;

import com.spire.backend.entity.CheckTracking;
import com.spire.backend.entity.PaymentLedger;
import com.spire.backend.entity.PaymentPlan;
import com.spire.backend.repository.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Checklist 5.3: Sage is a US company — amounts are US dollars, times are
 * US Central (converted from the server's UTC clock, not relabelled), the
 * admin overview's revenue is a real number, and mailed-check numbers are
 * masked like check copies.
 */
class UsDollarsAndTimeTest {

    @Test
    void amountsAreDollarsInWholeCents() {
        assertEquals("$1,234.50", Money.usd(new BigDecimal("1234.5")));
        assertEquals("$0.00", Money.usd(BigDecimal.ZERO));
        assertEquals("—", Money.usd(null));
        assertEquals(123456, Money.cents(new BigDecimal("1234.56")));
        assertThrows(IllegalArgumentException.class, () -> Money.cents(new BigDecimal("10.005")));
    }

    @Test
    void storedTimesAreShownInUsCentralTime() {
        TimeZone before = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));   // the server's clock in production
            LocalDateTime utc = LocalDateTime.of(2026, 9, 25, 14, 12);
            assertEquals("September 25, 2026, 9:12 AM CDT", BusinessTime.stamp(utc, null));
            assertEquals("January 15, 2027, 8:00 AM CST", BusinessTime.stamp(LocalDateTime.of(2027, 1, 15, 14, 0), "America/Chicago"));
            assertEquals("CT", BusinessTime.label(""));
        } finally {
            TimeZone.setDefault(before);
        }
        assertEquals("Oct 1, 2026", EmailTemplateService.usDate(LocalDate.of(2026, 10, 1)));
    }

    @Test
    void mailedCheckNumbersAreMasked() {
        CheckTrackingRepository tracking = mock(CheckTrackingRepository.class);
        PaymentPlanRepository plans = mock(PaymentPlanRepository.class);
        PaymentPlan plan = PaymentPlan.builder().id(3L).userId(10L).build();
        when(plans.findByUserIdOrderByIdDesc(10L)).thenReturn(List.of(plan));
        when(plans.findLatestByUserId(10L)).thenCallRealMethod();
        when(plans.findById(3L)).thenReturn(Optional.of(plan));
        CheckTracking row = CheckTracking.builder().id(7L).paymentPlanId(3L).checkNumber("000123456").status("IN_TRANSIT").build();
        when(tracking.findByPaymentPlanId(3L)).thenReturn(List.of(row));
        when(tracking.findAll()).thenReturn(List.of(row));
        when(tracking.findById(7L)).thenReturn(Optional.of(row));
        RecordService records = mock(RecordService.class);
        CheckTrackingService service = new CheckTrackingService(tracking, plans, mock(UserRepository.class),
                mock(WorkflowService.class), records, mock(BusinessClock.class));
        String shown = (String) service.trackingsForUser(10L).get(0).get("checkNumber");
        assertTrue(shown.endsWith("3456") && !shown.contains("000123"), shown);
        assertFalse(service.financeAllTrackings(null).get(0).get("checkNumber").toString().contains("000123"));
        assertEquals("000123456", service.revealCheckNumber(99L, 7L));
        verify(records).record(eq(10L), eq("CHECK_NUMBER_VIEWED"), anyString(), anyString(), anyString(), any(Map.class));
    }

    @Test
    void theAdminOverviewShowsRealRevenue() {
        AdminRevenueService courses = mock(AdminRevenueService.class);
        when(courses.totalLifetimeRevenue()).thenReturn(new BigDecimal("100.00"));
        PaymentLedgerRepository ledger = mock(PaymentLedgerRepository.class);
        when(ledger.findAll()).thenReturn(List.of(
                PaymentLedger.builder().entryType("PAYMENT").amountReceived(new BigDecimal("50")).build(),
                PaymentLedger.builder().entryType("REVERSAL").amountReceived(new BigDecimal("20")).build(),
                PaymentLedger.builder().entryType("FAILED").amountReceived(new BigDecimal("999")).build()));
        AdminService admin = new AdminService(mock(UserRepository.class), mock(RoleRepository.class),
                mock(CourseRepository.class), mock(EnrollmentRepository.class), mock(LessonRepository.class),
                mock(ProgressRepository.class), mock(CertificateRepository.class), mock(SessionRequestRepository.class),
                mock(MentorAssignmentRepository.class), mock(RecordService.class), courses, ledger);
        assertEquals(0, new BigDecimal("130.00").compareTo((BigDecimal) admin.getAnalytics().get("totalRevenue")),
                "course payments + program payments − reversals; failed attempts aren't money");
    }
}
