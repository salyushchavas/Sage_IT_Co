package com.spire.backend.service;

import com.spire.backend.config.BrandConfig;
import com.spire.backend.entity.*;
import com.spire.backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Checklist 5.4: paid courses need payment. Checkout enrolls free courses,
 * sends paid ones to Stripe Checkout, and enrolls them only when Stripe
 * reports the payment — once, for the right amount. Webhooks must carry a
 * valid, recent Stripe signature.
 */
class CourseCheckoutTest {

    private static final Course FREE = Course.builder().id(1L).title("Intro").price(BigDecimal.ZERO).isFree(true).build();
    private static final Course PAID = Course.builder().id(2L).title("Resume Review").price(new BigDecimal("199.00")).isFree(false).build();

    private final List<CartItem> cart = new ArrayList<>();
    private final Map<String, Payment> payments = new HashMap<>();
    private EnrollmentService enrollments;
    private StripeGateway stripe;
    private PaymentRepository paymentRepo;
    private CouponService coupons;
    private CourseCheckoutService service;
    private User pat;

    @BeforeEach
    void setUp() {
        pat = User.builder().id(10L).email("pat@x.com").fullName("Pat Doe").build();
        UserRepository users = mock(UserRepository.class);
        when(users.findById(10L)).thenReturn(Optional.of(pat));
        CartRepository cartRepo = mock(CartRepository.class);
        when(cartRepo.findByUserId(10L)).thenAnswer(inv -> List.copyOf(cart));
        doAnswer(inv -> { cart.removeIf(i -> i.getCourse().getId().equals(inv.getArgument(1))); return null; })
                .when(cartRepo).deleteByUserIdAndCourseId(anyLong(), anyLong());
        CourseRepository courseRepo = mock(CourseRepository.class);
        when(courseRepo.findById(1L)).thenReturn(Optional.of(FREE));
        when(courseRepo.findById(2L)).thenReturn(Optional.of(PAID));
        enrollments = mock(EnrollmentService.class);
        when(enrollments.enrollAfterPayment(anyLong(), anyLong(), any())).thenReturn(true);
        coupons = mock(CouponService.class);
        paymentRepo = mock(PaymentRepository.class);
        when(paymentRepo.save(any())).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            if (p.getId() == null) p.setId(50L);
            if (p.getStripeSessionId() != null) payments.put(p.getStripeSessionId(), p);
            return p;
        });
        when(paymentRepo.findByStripeSessionId(anyString())).thenAnswer(inv -> Optional.ofNullable(payments.get(inv.getArgument(0))));
        when(paymentRepo.findById(50L)).thenAnswer(inv -> payments.values().stream().findFirst());
        when(paymentRepo.markCompletedIfPending(eq(50L), any(), any())).thenAnswer(inv -> {
            Payment p = payments.values().iterator().next();
            if (p.getStatus() != Payment.Status.PENDING) return 0;
            p.setStatus(Payment.Status.COMPLETED);
            return 1;
        });
        stripe = mock(StripeGateway.class);
        when(stripe.createCheckoutSession(anyLong(), anyString(), anyString(), anyString(), anyMap(), anyString(), anyString()))
                .thenReturn(new StripeGateway.CheckoutSession("cs_test_1", "https://checkout.stripe.com/c/pay/cs_test_1"));
        BrandConfig brand = mock(BrandConfig.class);
        when(brand.getContactEmail()).thenReturn("info@sageitco.com");
        when(brand.getName()).thenReturn("Sage IT Co");
        service = new CourseCheckoutService(cartRepo, courseRepo, users, enrollments, coupons,
                mock(CouponRepository.class), paymentRepo, stripe, mock(RecordService.class),
                mock(EmailTemplateService.class), brand);
        cart.add(CartItem.builder().id(1L).course(FREE).build());
        cart.add(CartItem.builder().id(2L).course(PAID).build());
    }

    @Test
    void aPaidCourseCantBeEnrolledForFree() {
        assertTrue(EnrollmentService.isPaid(PAID));
        assertFalse(EnrollmentService.isPaid(FREE));
        assertFalse(EnrollmentService.isPaid(Course.builder().price(new BigDecimal("50")).isFree(true).build()));
    }

    @Test
    void withoutStripeKeysPaidCoursesStayInTheCart() {
        Map<String, Object> r = service.checkout(10L, null);
        assertEquals(CourseCheckoutService.PAYMENT_UNAVAILABLE, r.get("status"));
        verify(enrollments).enrollAfterPayment(10L, 1L, null);            // the free one
        verify(enrollments, never()).enrollAfterPayment(eq(10L), eq(2L), any());
        assertEquals(List.of(PAID), cart.stream().map(CartItem::getCourse).toList());
    }

    @Test
    void paidCoursesAreEnrolledOnceStripeReportsThePaymentAndOnlyOnce() {
        when(stripe.isConfigured()).thenReturn(true);
        Map<String, Object> r = service.checkout(10L, null);
        assertEquals(CourseCheckoutService.PAYMENT_REQUIRED, r.get("status"));
        assertEquals("https://checkout.stripe.com/c/pay/cs_test_1", r.get("checkoutUrl"));
        verify(stripe).createCheckoutSession(eq(19900L), eq("Resume Review"), eq("pat@x.com"), anyString(), anyMap(), anyString(), anyString());
        verify(enrollments, never()).enrollAfterPayment(eq(10L), eq(2L), any());

        StripeGateway.SessionState wrongAmount = new StripeGateway.SessionState("cs_test_1", "paid", "complete", "pi_1", 100, "usd");
        service.complete(wrongAmount);
        verify(enrollments, never()).enrollAfterPayment(eq(10L), eq(2L), any());

        StripeGateway.SessionState paid = new StripeGateway.SessionState("cs_test_1", "paid", "complete", "pi_1", 19900, "usd");
        service.complete(paid);
        service.complete(paid);                                            // the event again
        verify(enrollments, times(1)).enrollAfterPayment(eq(10L), eq(2L), anyString());
        assertTrue(cart.isEmpty());
    }

    @Test
    void aCouponCoveringEverythingNeedsNoPayment() {
        Coupon full = Coupon.builder().id(3L).code("FREE100").build();
        when(coupons.resolveValidCoupon("FREE100", 10L, new BigDecimal("199.00"))).thenReturn(full);
        when(coupons.calculateDiscount(full, new BigDecimal("199.00"))).thenReturn(new BigDecimal("199.00"));
        Map<String, Object> r = service.checkout(10L, "FREE100");
        assertEquals(CourseCheckoutService.ENROLLED, r.get("status"));
        verify(enrollments).enrollAfterPayment(10L, 2L, "coupon FREE100");
        verify(coupons).redeem(eq(full), eq(10L), any(), any());
        verifyNoInteractions(stripe);
    }

    @Test
    void onlyThePayerCanCheckTheirPayment() {
        when(stripe.isConfigured()).thenReturn(true);
        service.checkout(10L, null);
        assertThrows(AccessDeniedException.class, () -> service.confirm(99L, "cs_test_1"));
    }

    // ── webhook signatures ────────────────────────────────────────

    private static String sign(String payload, String secret, long t) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "t=" + t + ",v1=" + HexFormat.of().formatHex(mac.doFinal((t + "." + payload).getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void onlyValidRecentSignaturesAreAccepted() throws Exception {
        String payload = "{\"type\":\"checkout.session.completed\"}";
        long now = 1_790_000_000L;
        assertTrue(StripeGateway.verifySignature(payload, sign(payload, "whsec_a", now), "whsec_a", now));
        assertFalse(StripeGateway.verifySignature(payload, sign(payload, "whsec_b", now), "whsec_a", now), "wrong secret");
        assertFalse(StripeGateway.verifySignature(payload + " ", sign(payload, "whsec_a", now), "whsec_a", now), "altered event");
        assertFalse(StripeGateway.verifySignature(payload, sign(payload, "whsec_a", now - 600), "whsec_a", now), "replayed later");
        assertFalse(StripeGateway.verifySignature(payload, null, "whsec_a", now));
        assertFalse(StripeGateway.verifySignature(payload, sign(payload, "whsec_a", now), "", now), "no secret, nothing passes");
    }
}
