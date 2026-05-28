package com.spire.backend.service;

import com.spire.backend.dto.CouponDTO;
import com.spire.backend.dto.CouponValidationResult;
import com.spire.backend.entity.Coupon;
import com.spire.backend.entity.CouponRedemption;
import com.spire.backend.entity.User;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.repository.CouponRedemptionRepository;
import com.spire.backend.repository.CouponRepository;
import com.spire.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CouponService {

    private final CouponRepository couponRepository;
    private final CouponRedemptionRepository redemptionRepository;
    private final UserRepository userRepository;

    /**
     * Throws IllegalArgumentException with a student-friendly message
     * if the coupon is invalid, expired, exhausted, already-used by
     * this user, or below min order amount. Returns the persistent
     * Coupon entity so callers (checkout) don't need to re-look it up.
     */
    @Transactional(readOnly = true)
    public Coupon resolveValidCoupon(String rawCode, Long userId, BigDecimal cartTotal) {
        if (rawCode == null || rawCode.isBlank()) {
            throw new IllegalArgumentException("Enter a coupon code.");
        }
        Coupon c = couponRepository.findByCodeIgnoreCase(rawCode.trim())
                .orElseThrow(() -> new IllegalArgumentException("Invalid coupon code."));

        if (!Boolean.TRUE.equals(c.getIsActive())) {
            throw new IllegalArgumentException("This coupon is no longer active.");
        }
        if (c.getExpiresAt() != null && c.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("This coupon has expired.");
        }
        if (c.getMaxUses() != null && c.getUsesCount() != null
                && c.getUsesCount() >= c.getMaxUses()) {
            throw new IllegalArgumentException("This coupon has reached its redemption limit.");
        }
        if (c.getMinOrderAmount() != null
                && cartTotal.compareTo(c.getMinOrderAmount()) < 0) {
            throw new IllegalArgumentException(
                    "This coupon requires a minimum order of ₹" + c.getMinOrderAmount() + ".");
        }
        if (userId != null && redemptionRepository.existsByCouponIdAndUserId(c.getId(), userId)) {
            throw new IllegalArgumentException("You've already used this coupon.");
        }
        return c;
    }

    public BigDecimal calculateDiscount(Coupon c, BigDecimal cartTotal) {
        BigDecimal discount = BigDecimal.ZERO;
        if (c.getDiscountType() == Coupon.DiscountType.PERCENT) {
            discount = cartTotal.multiply(c.getDiscountValue())
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        } else if (c.getDiscountType() == Coupon.DiscountType.FLAT) {
            discount = c.getDiscountValue();
        }
        // Discount can't exceed cart total — clamp it.
        if (discount.compareTo(cartTotal) > 0) discount = cartTotal;
        if (discount.compareTo(BigDecimal.ZERO) < 0) discount = BigDecimal.ZERO;
        return discount.setScale(2, RoundingMode.HALF_UP);
    }

    public CouponValidationResult validate(String rawCode, Long userId, BigDecimal cartTotal) {
        Coupon c = resolveValidCoupon(rawCode, userId, cartTotal);
        BigDecimal discount = calculateDiscount(c, cartTotal);
        return CouponValidationResult.builder()
                .code(c.getCode())
                .discountType(c.getDiscountType().name())
                .discountValue(c.getDiscountValue())
                .cartTotal(cartTotal)
                .discountAmount(discount)
                .finalTotal(cartTotal.subtract(discount).max(BigDecimal.ZERO))
                .build();
    }

    /**
     * Records a redemption and bumps usesCount. Caller is responsible
     * for being inside a transaction (checkout already is).
     */
    @Transactional
    public void redeem(Coupon c, Long userId, BigDecimal discountApplied, BigDecimal orderTotal) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        redemptionRepository.save(CouponRedemption.builder()
                .coupon(c)
                .user(user)
                .discountApplied(discountApplied)
                .orderTotal(orderTotal)
                .build());

        c.setUsesCount((c.getUsesCount() != null ? c.getUsesCount() : 0) + 1);
        couponRepository.save(c);
    }

    // ─── Admin CRUD ──────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<CouponDTO> getAll() {
        return couponRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(CouponDTO::from)
                .toList();
    }

    @Transactional
    public CouponDTO create(Map<String, Object> body) {
        String code = body.get("code") != null ? body.get("code").toString().trim() : "";
        if (code.isBlank()) {
            throw new IllegalArgumentException("Coupon code is required.");
        }
        if (couponRepository.existsByCodeIgnoreCase(code)) {
            throw new IllegalArgumentException("A coupon with that code already exists.");
        }

        Coupon.DiscountType type = Coupon.DiscountType.PERCENT;
        if (body.get("discountType") != null) {
            try {
                type = Coupon.DiscountType.valueOf(body.get("discountType").toString().toUpperCase());
            } catch (IllegalArgumentException ignored) { /* default to PERCENT */ }
        }

        BigDecimal value;
        try {
            value = new BigDecimal(body.get("discountValue").toString());
        } catch (Exception e) {
            throw new IllegalArgumentException("discountValue must be a number.");
        }
        if (value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Discount value must be greater than zero.");
        }
        if (type == Coupon.DiscountType.PERCENT
                && value.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("Percent discount cannot exceed 100.");
        }

        BigDecimal minOrder = parseDecimalOrNull(body.get("minOrderAmount"));
        Integer maxUses = parseIntOrNull(body.get("maxUses"));
        LocalDateTime expiresAt = parseLocalDateTimeOrNull(body.get("expiresAt"));
        boolean active = body.get("isActive") == null
                || Boolean.parseBoolean(body.get("isActive").toString());

        Coupon saved = couponRepository.save(Coupon.builder()
                .code(code.toUpperCase())
                .discountType(type)
                .discountValue(value)
                .minOrderAmount(minOrder)
                .maxUses(maxUses)
                .usesCount(0)
                .expiresAt(expiresAt)
                .isActive(active)
                .build());
        return CouponDTO.from(saved);
    }

    @Transactional
    public CouponDTO update(Long id, Map<String, Object> body) {
        Coupon c = couponRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Coupon", "id", id));

        if (body.get("discountType") != null) {
            try {
                c.setDiscountType(Coupon.DiscountType.valueOf(
                        body.get("discountType").toString().toUpperCase()));
            } catch (IllegalArgumentException ignored) { /* keep */ }
        }
        if (body.get("discountValue") != null) {
            try {
                c.setDiscountValue(new BigDecimal(body.get("discountValue").toString()));
            } catch (Exception ignored) { /* keep */ }
        }
        if (body.containsKey("minOrderAmount")) {
            c.setMinOrderAmount(parseDecimalOrNull(body.get("minOrderAmount")));
        }
        if (body.containsKey("maxUses")) {
            c.setMaxUses(parseIntOrNull(body.get("maxUses")));
        }
        if (body.containsKey("expiresAt")) {
            c.setExpiresAt(parseLocalDateTimeOrNull(body.get("expiresAt")));
        }
        if (body.get("isActive") != null) {
            c.setIsActive(Boolean.parseBoolean(body.get("isActive").toString()));
        }
        // Code is immutable post-creation — students may have it saved.
        return CouponDTO.from(couponRepository.save(c));
    }

    @Transactional
    public void delete(Long id) {
        if (!couponRepository.existsById(id)) {
            throw new ResourceNotFoundException("Coupon", "id", id);
        }
        couponRepository.deleteById(id);
    }

    private BigDecimal parseDecimalOrNull(Object v) {
        if (v == null || v.toString().isBlank()) return null;
        try { return new BigDecimal(v.toString()); } catch (Exception e) { return null; }
    }

    private Integer parseIntOrNull(Object v) {
        if (v == null || v.toString().isBlank()) return null;
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return null; }
    }

    private LocalDateTime parseLocalDateTimeOrNull(Object v) {
        if (v == null || v.toString().isBlank()) return null;
        String raw = v.toString();
        try {
            return LocalDateTime.parse(raw);
        } catch (Exception ignored) {
            try {
                return LocalDateTime.parse(raw + "T23:59:59");
            } catch (Exception ignored2) {
                return null;
            }
        }
    }
}
