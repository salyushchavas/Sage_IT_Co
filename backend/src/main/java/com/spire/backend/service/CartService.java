package com.spire.backend.service;

import com.spire.backend.dto.CourseDTO;
import com.spire.backend.entity.CartItem;
import com.spire.backend.entity.Coupon;
import com.spire.backend.entity.Course;
import com.spire.backend.entity.User;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.repository.CartRepository;
import com.spire.backend.repository.CourseRepository;
import com.spire.backend.repository.EnrollmentRepository;
import com.spire.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CartService {

    private final CartRepository cartRepository;
    private final UserRepository userRepository;
    private final CourseRepository courseRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final EnrollmentService enrollmentService;
    private final CouponService couponService;
    private final RecordService recordService;

    @Transactional
    public void addToCart(Long userId, Long courseId) {
        if (cartRepository.existsByUserIdAndCourseId(userId, courseId)) {
            throw new IllegalArgumentException("Course is already in your cart");
        }

        if (enrollmentRepository.existsByUserIdAndCourseId(userId, courseId)) {
            throw new IllegalArgumentException("You are already enrolled in this course");
        }

        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new ResourceNotFoundException("Course", "id", courseId));

        if (Boolean.TRUE.equals(course.getIsFree())) {
            throw new IllegalArgumentException("Free courses can be enrolled directly");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        CartItem item = CartItem.builder()
                .user(user)
                .course(course)
                .build();

        cartRepository.save(item);
    }

    public List<CourseDTO> getCart(Long userId) {
        return cartRepository.findByUserId(userId).stream()
                .map(item -> CourseDTO.from(item.getCourse()))
                .collect(Collectors.toList());
    }

    @Transactional
    public void removeFromCart(Long userId, Long courseId) {
        cartRepository.deleteByUserIdAndCourseId(userId, courseId);
    }

    @Transactional
    public void clearCart(Long userId) {
        cartRepository.deleteByUserId(userId);
    }

    @Transactional
    public Map<String, Object> checkout(Long userId, String couponCode) {
        List<CartItem> items = cartRepository.findByUserId(userId);
        if (items.isEmpty()) {
            throw new IllegalArgumentException("Cart is empty");
        }

        BigDecimal subtotal = BigDecimal.ZERO;
        for (CartItem item : items) {
            Course course = item.getCourse();
            subtotal = subtotal.add(course.getPrice() != null ? course.getPrice() : BigDecimal.ZERO);
        }

        BigDecimal discount = BigDecimal.ZERO;
        Coupon coupon = null;
        if (couponCode != null && !couponCode.isBlank()) {
            // Re-validate at checkout — the cart may have changed since
            // the student clicked Apply, and we want to fail fast if the
            // coupon was deactivated or used up in the meantime.
            coupon = couponService.resolveValidCoupon(couponCode, userId, subtotal);
            discount = couponService.calculateDiscount(coupon, subtotal);
        }
        BigDecimal total = subtotal.subtract(discount).max(BigDecimal.ZERO);

        for (CartItem item : items) {
            enrollmentService.enrollUser(userId, item.getCourse().getId());
        }

        if (coupon != null) {
            couponService.redeem(coupon, userId, discount, total);

            Map<String, Object> couponDetails = new java.util.HashMap<>();
            couponDetails.put("couponCode", coupon.getCode());
            couponDetails.put("discountType", coupon.getDiscountType().name());
            couponDetails.put("discountValue", coupon.getDiscountValue());
            couponDetails.put("subtotal", subtotal);
            couponDetails.put("discountAmount", discount);
            couponDetails.put("finalTotal", total);
            recordService.record(userId, "COUPON_APPLIED", RecordService.Category.PAYMENT,
                    "Coupon applied: " + coupon.getCode(),
                    "Applied coupon " + coupon.getCode() + " — saved ₹" + discount + " on cart total of ₹" + subtotal,
                    couponDetails);
        }

        cartRepository.deleteByUserId(userId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("subtotal", subtotal);
        result.put("discount", discount);
        result.put("total", total);
        result.put("couponCode", coupon != null ? coupon.getCode() : null);
        return result;
    }
}
