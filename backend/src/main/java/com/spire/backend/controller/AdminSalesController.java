package com.spire.backend.controller;

import com.spire.backend.dto.ApiResponse;
import com.spire.backend.dto.SalesInquiryDTO;
import com.spire.backend.service.SalesService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Admin-side sales views, mounted under /api/admin/sales/* so the
 * existing /api/admin/** URL guard in SecurityConfig covers them.
 * Keeps the student/instructor SalesController free of a mismatched
 * class-level @RequestMapping.
 */
@RestController
@RequestMapping("/api/admin/sales")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminSalesController {

    private final SalesService salesService;

    @GetMapping("/inquiries")
    public ResponseEntity<ApiResponse<List<SalesInquiryDTO>>> allInquiries() {
        return ResponseEntity.ok(ApiResponse.success(salesService.getAllInquiries()));
    }

    @GetMapping("/inquiries/{id}")
    public ResponseEntity<ApiResponse<SalesInquiryDTO>> getOne(
            Authentication auth, @PathVariable Long id) {
        Long userId = Long.parseLong(auth.getPrincipal().toString());
        return ResponseEntity.ok(ApiResponse.success(
                salesService.getInquiryDetail(id, userId)));
    }

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> stats() {
        return ResponseEntity.ok(ApiResponse.success(salesService.getStats()));
    }
}
