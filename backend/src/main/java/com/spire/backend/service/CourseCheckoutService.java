package com.spire.backend.service;

import com.spire.backend.config.BrandConfig;
import com.spire.backend.entity.CartItem;
import com.spire.backend.entity.Coupon;
import com.spire.backend.entity.Course;
import com.spire.backend.entity.Payment;
import com.spire.backend.entity.User;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.repository.CartRepository;
import com.spire.backend.repository.CouponRepository;
import com.spire.backend.repository.CourseRepository;
import com.spire.backend.repository.PaymentRepository;
import com.spire.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Checklist 5.4: buying courses. Free courses in the cart are enrolled
 * straight away. Paid ones go through Stripe Checkout, and the participant
 * is enrolled only once Stripe reports the payment (by webhook, or when they
 * come back from the payment page) — before, checkout enrolled everyone for
 * free. A coupon that covers the whole amount needs no payment. Without
 * Stripe keys, paid courses stay in the cart with a clear message.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CourseCheckoutService {

    public static final String ENROLLED = "ENROLLED";
    public static final String PAYMENT_REQUIRED = "PAYMENT_REQUIRED";
    public static final String PAYMENT_UNAVAILABLE = "PAYMENT_UNAVAILABLE";

    private final CartRepository cartRepository;
    private final CourseRepository courseRepository;
    private final UserRepository userRepository;
    private final EnrollmentService enrollmentService;
    private final CouponService couponService;
    private final CouponRepository couponRepository;
    private final PaymentRepository paymentRepository;
    private final StripeGateway stripe;
    private final RecordService recordService;
    private final EmailTemplateService emailTemplateService;
    private final BrandConfig brandConfig;

    @Value("${app.url:https://sageitco.com}")
    private String appUrl;

    @Transactional
    public Map<String, Object> checkout(Long userId, String couponCode) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        List<CartItem> items = cartRepository.findByUserId(userId);
        if (items.isEmpty()) {
            throw new IllegalArgumentException("Cart is empty");
        }
        List<Course> free = new ArrayList<>();
        List<Course> paid = new ArrayList<>();
        for (CartItem item : items) (EnrollmentService.isPaid(item.getCourse()) ? paid : free).add(item.getCourse());

        BigDecimal subtotal = paid.stream().map(Course::getPrice).reduce(BigDecimal.ZERO, BigDecimal::add);
        Coupon coupon = null;
        BigDecimal discount = BigDecimal.ZERO;
        if (couponCode != null && !couponCode.isBlank()) {
            // Re-validated here: the cart may have changed since "Apply".
            coupon = couponService.resolveValidCoupon(couponCode, userId, subtotal);
            discount = couponService.calculateDiscount(coupon, subtotal);
        }
        BigDecimal total = subtotal.subtract(discount).max(BigDecimal.ZERO);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("subtotal", subtotal);
        result.put("discount", discount);
        result.put("total", total);
        result.put("couponCode", coupon != null ? coupon.getCode() : null);

        List<String> enrolled = new ArrayList<>();
        for (Course c : free) {
            if (enrollmentService.enrollAfterPayment(userId, c.getId(), null)) enrolled.add(c.getTitle());
            cartRepository.deleteByUserIdAndCourseId(userId, c.getId());
        }
        result.put("enrolled", enrolled);

        if (paid.isEmpty()) {
            result.put("status", ENROLLED);
            return result;
        }
        if (total.signum() == 0) {
            // The coupon covers everything: nothing to pay.
            for (Course c : paid) {
                if (enrollmentService.enrollAfterPayment(userId, c.getId(), "coupon " + coupon.getCode())) {
                    enrolled.add(c.getTitle());
                }
                cartRepository.deleteByUserIdAndCourseId(userId, c.getId());
            }
            redeem(coupon, userId, discount, total, subtotal);
            result.put("status", ENROLLED);
            return result;
        }
        if (!stripe.isConfigured()) {
            result.put("status", PAYMENT_UNAVAILABLE);
            result.put("message", "Online payment isn't available yet, so paid courses can't be bought on the site. "
                    + "Please contact us at " + brandConfig.getContactEmail() + " to enroll.");
            return result;
        }

        Payment payment = paymentRepository.save(Payment.builder()
                .user(user)
                .amount(total)
                .status(Payment.Status.PENDING)
                .provider("STRIPE")
                .currency("USD")
                .courseIds(String.join(",", paid.stream().map(c -> c.getId().toString()).toList()))
                .couponCode(coupon == null ? null : coupon.getCode())
                .discountAmount(discount)
                .build());
        String description = paid.size() == 1 ? paid.get(0).getTitle()
                : paid.size() + " courses — " + brandConfig.getName();
        StripeGateway.CheckoutSession session = stripe.createCheckoutSession(Money.cents(total), description,
                user.getEmail(), "payment-" + payment.getId(),
                Map.of("paymentId", payment.getId().toString(), "userId", userId.toString()),
                appUrl + "/cart?checkout=success&session_id={CHECKOUT_SESSION_ID}",
                appUrl + "/cart?checkout=cancelled");
        payment.setStripeSessionId(session.id());
        paymentRepository.save(payment);
        recordService.logAction(userId, RecordService.Category.PAYMENT, "Online checkout started",
                description + " — " + Money.usd(total),
                Map.of("paymentId", payment.getId(), "amount", total));
        result.put("status", PAYMENT_REQUIRED);
        result.put("checkoutUrl", session.url());
        result.put("paymentId", payment.getId());
        return result;
    }

    /**
     * Stripe says the session is paid: enroll the participant in what they
     * bought. Idempotent — the webhook and the return page may both call it,
     * and Stripe may send an event twice. The amount must match what we
     * asked for.
     */
    /** What {@link #complete} did. */
    public enum Outcome { COMPLETED, ALREADY_COMPLETED, NOT_PAID, AMOUNT_MISMATCH }

    @Transactional
    public Outcome complete(StripeGateway.SessionState state) {
        Payment payment = paymentRepository.findByStripeSessionId(state.id())
                .orElseThrow(() -> new ResourceNotFoundException("Payment", "stripeSessionId", state.id()));
        if (!state.paid()) return Outcome.NOT_PAID;
        Long userId = payment.getUser().getId();
        if (state.amountTotal() != Money.cents(payment.getAmount()) || !"usd".equalsIgnoreCase(state.currency())) {
            log.error("Stripe session {} paid {} {} but payment {} expected {}", state.id(), state.amountTotal(),
                    state.currency(), payment.getId(), payment.getAmount());
            recordService.logAction(userId, RecordService.Category.PAYMENT, "Online payment amount didn't match",
                    "Session " + state.id(), Map.of("paymentId", payment.getId()));
            return Outcome.AMOUNT_MISMATCH;
        }
        if (paymentRepository.markCompletedIfPending(payment.getId(), state.paymentIntent(), LocalDateTime.now()) == 0) {
            return Outcome.ALREADY_COMPLETED;   // the webhook or the return page got there first
        }
        List<Course> courses = new ArrayList<>();
        for (Long courseId : courseIdsOf(payment)) {
            enrollmentService.enrollAfterPayment(userId, courseId, "payment #" + payment.getId());
            cartRepository.deleteByUserIdAndCourseId(userId, courseId);
            courseRepository.findById(courseId).ifPresent(courses::add);
        }
        if (payment.getCouponCode() != null) {
            couponRepository.findByCodeIgnoreCase(payment.getCouponCode()).ifPresent(c -> redeem(c, userId,
                    payment.getDiscountAmount() == null ? BigDecimal.ZERO : payment.getDiscountAmount(),
                    payment.getAmount(), payment.getAmount().add(payment.getDiscountAmount() == null
                            ? BigDecimal.ZERO : payment.getDiscountAmount())));
        }
        recordService.logAction(userId, RecordService.Category.PAYMENT, "Online payment received",
                Money.usd(payment.getAmount()) + " — " + courses.size() + " course(s)",
                Map.of("paymentId", payment.getId(), "stripeSession", state.id()));
        // The status update above cleared the session, so the buyer is read afresh.
        userRepository.findById(userId).ifPresent(buyer -> {
            try {
                emailTemplateService.sendCoursePurchaseEmail(buyer, courses, payment.getAmount(),
                        "SAGE-PAY-" + payment.getId());
            } catch (Exception e) {
                log.warn("Purchase email failed for user {}: {}", userId, e.getMessage());
            }
        });
        return Outcome.COMPLETED;
    }

    /** The session expired or its payment failed: nothing is enrolled; the cart is untouched. */
    @Transactional
    public void fail(StripeGateway.SessionState state) {
        paymentRepository.findByStripeSessionId(state.id()).ifPresent(p -> {
            Long userId = p.getUser().getId();
            if (paymentRepository.markFailedIfPending(p.getId()) == 1) {
                recordService.logAction(userId, RecordService.Category.PAYMENT,
                        "Online payment not completed", "Session " + state.id(), Map.of("paymentId", p.getId()));
            }
        });
    }

    /**
     * The participant is back from the payment page: check the session with
     * Stripe (in case the webhook hasn't arrived yet) and report where
     * things stand. Only their own payment.
     */
    @Transactional
    public Map<String, Object> confirm(Long userId, String sessionId) {
        Payment payment = paymentRepository.findByStripeSessionId(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", "stripeSessionId", sessionId));
        if (!userId.equals(payment.getUser().getId())) throw new AccessDeniedException("Not your payment.");
        String status = payment.getStatus().name();
        if (payment.getStatus() == Payment.Status.PENDING) {
            // Ask Stripe; whichever of this and the webhook gets there first enrolls them.
            Outcome outcome = stripe.retrieveSession(sessionId).map(this::complete).orElse(Outcome.NOT_PAID);
            if (outcome == Outcome.COMPLETED || outcome == Outcome.ALREADY_COMPLETED) status = Payment.Status.COMPLETED.name();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", status);
        out.put("amount", payment.getAmount());
        out.put("courses", courseIdsOf(payment).stream()
                .map(id -> courseRepository.findById(id).map(Course::getTitle).orElse("Course #" + id)).toList());
        return out;
    }

    private void redeem(Coupon coupon, Long userId, BigDecimal discount, BigDecimal total, BigDecimal subtotal) {
        if (coupon == null) return;
        couponService.redeem(coupon, userId, discount, total);
        recordService.record(userId, "COUPON_APPLIED", RecordService.Category.PAYMENT,
                "Coupon applied: " + coupon.getCode(),
                "Applied coupon " + coupon.getCode() + " — saved " + Money.usd(discount)
                        + " on a total of " + Money.usd(subtotal),
                Map.of("couponCode", coupon.getCode(), "discountAmount", discount, "finalTotal", total));
    }

    private static List<Long> courseIdsOf(Payment payment) {
        if (payment.getCourseIds() == null || payment.getCourseIds().isBlank()) return List.of();
        return Arrays.stream(payment.getCourseIds().split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(Long::valueOf).toList();
    }
}
