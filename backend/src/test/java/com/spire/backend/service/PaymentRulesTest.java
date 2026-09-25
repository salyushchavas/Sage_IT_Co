package com.spire.backend.service;

import com.spire.backend.config.BrandConfig;
import com.spire.backend.entity.Invoice;
import com.spire.backend.entity.PaymentLedger;
import com.spire.backend.entity.PaymentPlan;
import com.spire.backend.entity.Role;
import com.spire.backend.entity.User;
import com.spire.backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Checklists 5.1 and 5.2 (roadmap steps 18–20): the server builds payment
 * schedules that add up to the cent on real calendar months; one open plan
 * per participant; Finance can change a plan until its first invoice (the
 * participant accepts it again); the ledger takes payments, failed
 * payments, waivers and reversals; no overpaying; part-paid invoices go
 * overdue; invoices come as a PDF.
 */
class PaymentRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 25);
    private final List<PaymentPlan> plans = new ArrayList<>();
    private final List<Invoice> invoices = new ArrayList<>();
    private final List<PaymentLedger> ledger = new ArrayList<>();
    private EmailTemplateService emails;
    private PaymentService service;
    private User pat;
    private BusinessClock clock;

    private static BigDecimal usd(String s) { return new BigDecimal(s); }

    /** Money compared by value ($600 = $600.00). */
    private static void assertMoney(String expected, Object actual, String... why) {
        assertEquals(0, usd(expected).compareTo((BigDecimal) actual), (why.length > 0 ? why[0] + ": " : "") + actual);
    }

    @BeforeEach
    void setUp() {
        UserRepository users = mock(UserRepository.class);
        pat = User.builder().id(10L).fullName("Pat Doe").participantId("SAGE-2026-00007").email("pat@x.com")
                .role(Role.builder().name("PARTICIPANT").build()).currentStatus("PHASE_1_COMPLETED").build();
        User coach = User.builder().id(11L).role(Role.builder().name("COACH").build()).currentStatus("PHASE_1_COMPLETED").build();
        when(users.findById(10L)).thenReturn(Optional.of(pat));
        when(users.findById(11L)).thenReturn(Optional.of(coach));
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentPlanRepository planRepo = mock(PaymentPlanRepository.class);
        when(planRepo.save(any())).thenAnswer(inv -> {
            PaymentPlan p = inv.getArgument(0);
            if (p.getId() == null) { p.setId((long) plans.size() + 1); plans.add(p); }
            return p;
        });
        when(planRepo.findById(anyLong())).thenAnswer(inv -> plans.stream().filter(p -> p.getId().equals(inv.getArgument(0))).findFirst());
        when(planRepo.findByUserIdOrderByIdDesc(anyLong())).thenAnswer(inv -> plans.stream()
                .filter(p -> p.getUserId().equals(inv.getArgument(0))).sorted((a, b) -> b.getId().compareTo(a.getId())).toList());
        when(planRepo.findLatestByUserId(anyLong())).thenCallRealMethod();
        when(planRepo.findAll()).thenAnswer(inv -> List.copyOf(plans));
        when(planRepo.count()).thenAnswer(inv -> (long) plans.size());

        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        when(invRepo.save(any())).thenAnswer(inv -> {
            Invoice i = inv.getArgument(0);
            if (i.getId() == null) { i.setId((long) invoices.size() + 1); invoices.add(i); }
            return i;
        });
        when(invRepo.findById(anyLong())).thenAnswer(inv -> invoices.stream().filter(i -> i.getId().equals(inv.getArgument(0))).findFirst());
        when(invRepo.findByPaymentPlanId(anyLong())).thenAnswer(inv -> invoices.stream()
                .filter(i -> inv.getArgument(0).equals(i.getPaymentPlanId())).toList());
        when(invRepo.findByStatusOrderByDueDateAsc(anyString())).thenAnswer(inv -> invoices.stream()
                .filter(i -> inv.getArgument(0).equals(i.getStatus())).toList());
        when(invRepo.findByUserIdOrderByIssueDateDesc(anyLong())).thenAnswer(inv -> List.copyOf(invoices));
        when(invRepo.count()).thenAnswer(inv -> (long) invoices.size());

        PaymentLedgerRepository ledgerRepo = mock(PaymentLedgerRepository.class);
        when(ledgerRepo.save(any())).thenAnswer(inv -> {
            PaymentLedger l = inv.getArgument(0);
            if (l.getId() == null) { l.setId((long) ledger.size() + 1); ledger.add(l); }
            return l;
        });
        when(ledgerRepo.findById(anyLong())).thenAnswer(inv -> ledger.stream().filter(l -> l.getId().equals(inv.getArgument(0))).findFirst());
        when(ledgerRepo.findByInvoiceIdOrderByCreatedAtAsc(anyLong())).thenAnswer(inv -> ledger.stream()
                .filter(l -> inv.getArgument(0).equals(l.getInvoiceId())).toList());
        when(ledgerRepo.findByUserIdOrderByCreatedAtDesc(anyLong())).thenAnswer(inv -> List.copyOf(ledger));

        emails = mock(EmailTemplateService.class);
        clock = new BusinessClock(Clock.fixed(
                ZonedDateTime.of(2026, 9, 25, 10, 0, 0, 0, ZoneId.of("America/Chicago")).toInstant(),
                ZoneId.of("America/Chicago")));
        WorkflowService workflow = new WorkflowService(users, mock(WorkflowStateRepository.class), mock(RecordService.class));
        service = new PaymentService(planRepo, invRepo, ledgerRepo, users, workflow, mock(RecordService.class), emails, clock);
        pdf = new InvoicePdfService(mock(BrandConfig.class, inv -> inv.getMethod().getReturnType() == String.class ? "Sage IT Co" : null),
                users, planRepo, invRepo, ledgerRepo);
    }

    private InvoicePdfService pdf;

    // ── 5.1 schedules ─────────────────────────────────────────────

    @Test
    void theScheduleAddsUpToTheCentOnRealMonths() {
        List<PaymentService.ScheduleItem> s = PaymentService.buildSchedule(usd("1000.00"), 3, LocalDate.of(2027, 1, 31));
        assertEquals(List.of(usd("333.34"), usd("333.33"), usd("333.33")), s.stream().map(PaymentService.ScheduleItem::amount).toList());
        assertEquals(List.of(LocalDate.of(2027, 1, 31), LocalDate.of(2027, 2, 28), LocalDate.of(2027, 3, 31)),
                s.stream().map(PaymentService.ScheduleItem::dueDate).toList(), "no skipping into March");
        assertThrows(IllegalArgumentException.class, () -> PaymentService.validateSchedule(usd("1000.00"), List.of(
                new PaymentService.ScheduleItem(LocalDate.of(2027, 1, 1), usd("333.33"), ""),
                new PaymentService.ScheduleItem(LocalDate.of(2027, 2, 1), usd("333.33"), ""),
                new PaymentService.ScheduleItem(LocalDate.of(2027, 3, 1), usd("333.33"), ""))), "$999.99 isn't $1,000");
        assertThrows(IllegalArgumentException.class, () -> PaymentService.validateSchedule(usd("20.00"), List.of(
                new PaymentService.ScheduleItem(LocalDate.of(2027, 2, 1), usd("10.00"), ""),
                new PaymentService.ScheduleItem(LocalDate.of(2027, 1, 1), usd("10.00"), ""))), "dates out of order");
        assertThrows(IllegalArgumentException.class, () -> PaymentService.buildSchedule(usd("10.005"), 1, TODAY), "fractions of a cent");
    }

    @Test
    void oneOpenPlanPerParticipantAndOnlyForParticipants() {
        service.createPlan(1L, 10L, usd("6000"), 6, TODAY.plusDays(7), null);
        verify(emails).sendPaymentPlanReadyEmail(eq(pat), any(), any(), eq(false));
        assertEquals(usd("6000.00"), plans.get(0).getTotalAmount().setScale(2));
        assertThrows(IllegalStateException.class, () -> service.createPlan(1L, 10L, usd("100"), 1, TODAY.plusDays(7), null));
        assertThrows(IllegalArgumentException.class, () -> service.createPlan(1L, 11L, usd("100"), 1, TODAY.plusDays(7), null),
                "a coach isn't a participant");
        plans.clear();
        assertThrows(IllegalArgumentException.class, () -> service.createPlan(1L, 10L, usd("100"), 1, TODAY.minusDays(1), null),
                "the first due date can't be in the past");
    }

    @Test
    void aChangedPlanIsAcceptedAgainAndCantChangeOnceInvoiced() {
        PaymentPlan plan = service.createPlan(1L, 10L, usd("3000"), 3, TODAY.plusDays(3), null);
        service.acceptPlan(10L, plan.getId(), null, "203.0.113.9");
        assertEquals("PAYMENT_PLAN_ACCEPTED", pat.getCurrentStatus());
        service.updatePlan(1L, plan.getId(), usd("2400"), 4, TODAY.plusDays(3), null);
        assertNull(plan.getAcceptedAt(), "the participant must accept the changed plan");
        assertEquals("PENDING", plan.getStatus());
        verify(emails).sendPaymentPlanReadyEmail(eq(pat), eq(plan), any(), eq(true));
        assertThrows(IllegalStateException.class, () -> service.generateNextInvoice(plan.getId(), TODAY), "no invoice until accepted");
        service.acceptPlan(10L, plan.getId(), null, "203.0.113.9");
        service.generateNextInvoice(plan.getId(), TODAY);
        assertMoney("600", invoices.get(0).getAmount());
        assertThrows(IllegalStateException.class, () -> service.updatePlan(1L, plan.getId(), usd("100"), 1, TODAY.plusDays(3), null));
    }

    // ── 5.2 the ledger ────────────────────────────────────────────

    private Invoice invoiced(String total, int n, LocalDate firstDue) {
        PaymentPlan plan = service.createPlan(1L, 10L, usd(total), n, firstDue, null);
        service.acceptPlan(10L, plan.getId(), null, null);
        return service.generateNextInvoice(plan.getId(), TODAY).orElseThrow();
    }

    @Test
    void noOverpayingAndEveryEntryTypeMovesTheBalanceRight() {
        Invoice inv = invoiced("1000", 1, TODAY.plusDays(5));
        assertThrows(IllegalArgumentException.class, () -> service.recordPayment(1L, inv.getId(), usd("1000.01"), TODAY, "CHEQUE", ""));
        service.recordPayment(1L, inv.getId(), usd("400"), TODAY, "CHEQUE", "check 1001");
        assertEquals("PARTIAL", inv.getStatus());
        assertThrows(IllegalArgumentException.class,
                () -> service.recordEntry(1L, inv.getId(), "FAILED", usd("600"), TODAY, "CARD", "", null), "a reason is needed");
        service.recordEntry(1L, inv.getId(), "FAILED", usd("600"), TODAY, "CARD", "Card declined", null);
        assertMoney("600", inv.getBalance(), "a failed payment changes nothing");
        verify(emails).sendPaymentProblemEmail(eq(pat), eq(inv), argThat(a -> a.compareTo(usd("600")) == 0), eq("Card declined"), eq(false));

        service.recordEntry(1L, inv.getId(), "REVERSAL", null, TODAY, null, "Check 1001 bounced", 1L);
        assertMoney("1000", inv.getBalance(), "the bounced payment is back on the balance");
        assertEquals("UNPAID", inv.getStatus());
        assertThrows(IllegalStateException.class,
                () -> service.recordEntry(1L, inv.getId(), "REVERSAL", null, TODAY, null, "again", 1L), "only once");

        service.recordEntry(1L, inv.getId(), "WAIVER", usd("100"), TODAY, null, "Hardship discount", null);
        service.recordPayment(1L, inv.getId(), usd("900"), TODAY, "BANK_TRANSFER", "");
        assertEquals("PAID", inv.getStatus());
        assertEquals("COMPLETED", plans.get(0).getStatus(), "the only instalment is paid");
        assertThrows(IllegalStateException.class, () -> service.recordPayment(1L, inv.getId(), usd("1"), TODAY, "CASH", ""));

        Map<String, Object> summary = service.participantSummary(10L);
        assertMoney("900", summary.get("totalPaid"), "400 + 900 − 400 reversed");
        assertMoney("100", summary.get("totalWaived"));
        assertEquals(0, ((BigDecimal) summary.get("balance")).signum());
    }

    @Test
    void aPartPaidInvoiceStillGoesOverdue() {
        Invoice inv = invoiced("500", 1, TODAY.plusDays(2));
        service.recordPayment(1L, inv.getId(), usd("200"), TODAY, "CHEQUE", "");
        assertEquals("PARTIAL", inv.getStatus());
        assertEquals(1, service.markOverdueInvoices(TODAY.plusDays(3)));
        assertEquals("OVERDUE", inv.getStatus());
        verify(emails).sendInvoiceOverdueEmail(pat, inv);
        assertEquals(0, service.markOverdueInvoices(TODAY.plusDays(4)), "emailed once");
    }

    @Test
    void theInvoiceComesAsAPdf() {
        Invoice inv = invoiced("750", 3, TODAY.plusDays(5));
        service.recordPayment(1L, inv.getId(), usd("100"), TODAY, "CHEQUE", "");
        byte[] bytes = pdf.render(inv);
        assertTrue(new String(bytes, 0, 5).startsWith("%PDF"));
        assertTrue(bytes.length > 1000);
    }
}
