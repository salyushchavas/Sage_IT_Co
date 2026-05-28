package com.spire.backend.controller;

import com.spire.backend.dto.ApiResponse;
import com.spire.backend.dto.SalesInquiryDTO;
import com.spire.backend.service.SalesService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sales")
@RequiredArgsConstructor
public class SalesController {

    private final SalesService salesService;

    // ─── Student endpoints ───────────────────────────────────────

    @PostMapping("/inquiries")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<SalesInquiryDTO>> create(
            Authentication authentication,
            @RequestBody Map<String, Object> body) {
        Long userId = userId(authentication);
        Long courseId = body.get("courseId") != null
                ? Long.parseLong(body.get("courseId").toString()) : null;
        if (courseId == null) {
            throw new IllegalArgumentException("courseId is required");
        }
        String subject = body.get("subject") != null ? body.get("subject").toString() : null;
        String budget = body.get("budgetRange") != null ? body.get("budgetRange").toString() : null;
        String message = body.get("message") != null ? body.get("message").toString() : "";
        return ResponseEntity.ok(ApiResponse.success(
                "Inquiry created",
                salesService.createInquiry(userId, courseId, subject, budget, message)));
    }

    @GetMapping("/inquiries/my")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<SalesInquiryDTO>>> myInquiries(Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(
                salesService.getInquiriesForStudent(userId(auth))));
    }

    @GetMapping("/inquiries/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<SalesInquiryDTO>> getOne(
            Authentication auth, @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(
                salesService.getInquiryDetail(id, userId(auth))));
    }

    @PostMapping("/inquiries/{id}/messages")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<SalesInquiryDTO>> postMessage(
            Authentication auth, @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        String message = body.get("message") != null ? body.get("message").toString() : "";
        return ResponseEntity.ok(ApiResponse.success(
                salesService.postMessage(id, userId(auth), message)));
    }

    @PostMapping("/inquiries/{id}/accept-quote")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<SalesInquiryDTO>> acceptQuote(
            Authentication auth, @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        Long messageId = body.get("messageId") != null
                ? Long.parseLong(body.get("messageId").toString()) : null;
        if (messageId == null) {
            throw new IllegalArgumentException("messageId is required");
        }
        return ResponseEntity.ok(ApiResponse.success(
                "Quote accepted", salesService.acceptQuote(id, userId(auth), messageId)));
    }

    @PostMapping("/inquiries/{id}/decline-quote")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<SalesInquiryDTO>> declineQuote(
            Authentication auth, @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        Long messageId = body.get("messageId") != null
                ? Long.parseLong(body.get("messageId").toString()) : null;
        if (messageId == null) {
            throw new IllegalArgumentException("messageId is required");
        }
        return ResponseEntity.ok(ApiResponse.success(
                "Quote declined", salesService.declineQuote(id, userId(auth), messageId)));
    }

    @PostMapping("/inquiries/{id}/close")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<SalesInquiryDTO>> close(
            Authentication auth, @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body) {
        String reason = body != null && body.get("reason") != null
                ? body.get("reason").toString() : null;
        return ResponseEntity.ok(ApiResponse.success(
                "Inquiry closed", salesService.closeInquiry(id, userId(auth), reason)));
    }

    // ─── Instructor endpoints ────────────────────────────────────

    @GetMapping("/inquiries/instructor")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    public ResponseEntity<ApiResponse<List<SalesInquiryDTO>>> instructorInquiries(Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(
                salesService.getInquiriesForInstructor(userId(auth))));
    }

    @PostMapping("/inquiries/{id}/quote")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    public ResponseEntity<ApiResponse<SalesInquiryDTO>> sendQuote(
            Authentication auth, @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        String message = body.get("message") != null ? body.get("message").toString() : null;
        BigDecimal price;
        try {
            price = new BigDecimal(body.get("quotedPrice").toString());
        } catch (Exception e) {
            throw new IllegalArgumentException("quotedPrice is required and must be numeric");
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = body.get("quotedItems") instanceof List
                ? (List<Map<String, Object>>) body.get("quotedItems")
                : List.of();
        return ResponseEntity.ok(ApiResponse.success(
                "Quote sent", salesService.sendQuote(id, userId(auth), message, price, items)));
    }

    private Long userId(Authentication authentication) {
        return Long.parseLong(authentication.getPrincipal().toString());
    }
}
