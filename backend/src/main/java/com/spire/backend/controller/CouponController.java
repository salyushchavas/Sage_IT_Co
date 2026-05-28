package com.spire.backend.controller;

import com.spire.backend.dto.ApiResponse;
import com.spire.backend.dto.CouponDTO;
import com.spire.backend.dto.CouponValidationResult;
import com.spire.backend.entity.CartItem;
import com.spire.backend.repository.CartRepository;
import com.spire.backend.service.CouponService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Admin CRUD lives at /api/admin/coupons; the validate endpoint is
 * authenticated-only so any logged-in student can dry-run a code from
 * their cart page before committing to checkout.
 */
@RestController
@RequiredArgsConstructor
public class CouponController {

    private final CouponService couponService;
    private final CartRepository cartRepository;

    // ─── Admin CRUD ──────────────────────────────────────────────

    @GetMapping("/api/admin/coupons")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<CouponDTO>>> getAll() {
        return ResponseEntity.ok(ApiResponse.success(couponService.getAll()));
    }

    @PostMapping("/api/admin/coupons")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<CouponDTO>> create(@RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.success("Coupon created", couponService.create(body)));
    }

    @PutMapping("/api/admin/coupons/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<CouponDTO>> update(
            @PathVariable Long id, @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.success("Coupon updated", couponService.update(id, body)));
    }

    @DeleteMapping("/api/admin/coupons/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<String>> delete(@PathVariable Long id) {
        couponService.delete(id);
        return ResponseEntity.ok(ApiResponse.success("Coupon deleted"));
    }

    // ─── Student-facing validate ─────────────────────────────────

    @PostMapping("/api/coupons/validate")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<CouponValidationResult>> validate(
            Authentication authentication,
            @RequestBody Map<String, Object> body) {
        Long userId = Long.parseLong(authentication.getPrincipal().toString());
        String code = body.get("code") != null ? body.get("code").toString() : "";

        BigDecimal cartTotal = body.get("cartTotal") != null
                ? new BigDecimal(body.get("cartTotal").toString())
                : computeCartTotal(userId);

        return ResponseEntity.ok(ApiResponse.success(
                couponService.validate(code, userId, cartTotal)));
    }

    /**
     * Server-side cart total — trust this, not whatever the client sends.
     * Used as the fallback when the request omits cartTotal.
     */
    private BigDecimal computeCartTotal(Long userId) {
        BigDecimal total = BigDecimal.ZERO;
        for (CartItem item : cartRepository.findByUserId(userId)) {
            if (item.getCourse() != null && item.getCourse().getPrice() != null) {
                total = total.add(item.getCourse().getPrice());
            }
        }
        return total;
    }
}
