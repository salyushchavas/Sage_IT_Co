package com.spire.backend.controller;

import com.spire.backend.dto.ApiResponse;
import com.spire.backend.dto.CourseDTO;
import com.spire.backend.entity.User;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.repository.UserRepository;
import com.spire.backend.service.CartService;
import com.spire.backend.service.ProfileCompletionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;
    private final ProfileCompletionService profileCompletionService;
    private final UserRepository userRepository;

    @PostMapping("/{courseId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Void>> addToCart(
            Authentication authentication, @PathVariable Long courseId) {
        Long userId = Long.parseLong(authentication.getPrincipal().toString());
        cartService.addToCart(userId, courseId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Added to cart", null));
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<CourseDTO>>> getCart(Authentication authentication) {
        Long userId = Long.parseLong(authentication.getPrincipal().toString());
        return ResponseEntity.ok(ApiResponse.success(cartService.getCart(userId)));
    }

    @DeleteMapping("/{courseId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Void>> removeFromCart(
            Authentication authentication, @PathVariable Long courseId) {
        Long userId = Long.parseLong(authentication.getPrincipal().toString());
        cartService.removeFromCart(userId, courseId);
        return ResponseEntity.ok(ApiResponse.success("Removed from cart", null));
    }

    @DeleteMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Void>> clearCart(Authentication authentication) {
        Long userId = Long.parseLong(authentication.getPrincipal().toString());
        cartService.clearCart(userId);
        return ResponseEntity.ok(ApiResponse.success("Cart cleared", null));
    }

    @PostMapping("/checkout")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Map<String, Object>>> checkout(
            Authentication authentication,
            @RequestBody(required = false) Map<String, Object> body) {
        Long userId = Long.parseLong(authentication.getPrincipal().toString());

        // Phase 1C gate — checkout is profile-complete-only. Cart
        // add / browse stays open so a user can curate while finishing
        // their profile; only the moment of purchase is blocked.
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        if (!profileCompletionService.canEnrollInCourses(user)) {
            Map<String, Object> payload = Map.of(
                    "message", "Complete your profile to check out",
                    "completion", profileCompletionService.getStatus(user)
            );
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    ApiResponse.<Map<String, Object>>error("PROFILE_INCOMPLETE", payload));
        }

        String couponCode = body != null && body.get("couponCode") != null
                ? body.get("couponCode").toString() : null;
        Map<String, Object> result = cartService.checkout(userId, couponCode);
        return ResponseEntity.ok(ApiResponse.success("Checkout successful", result));
    }
}
