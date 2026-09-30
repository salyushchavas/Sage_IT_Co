package com.spire.backend.controller;

import com.spire.backend.dto.ApiResponse;
import com.spire.backend.service.DocumentVerificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 30 Sep: an ERM verifies a participant's documents and program before
 * the agreement is sent. ERM, Operations admin and System admin.
 */
@RestController
@RequestMapping("/api/verifications")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ERM','OPERATIONS_ADMIN','SYSTEM_ADMIN')")
public class DocumentVerificationController {

    private final DocumentVerificationService verificationService;

    /** {@code status}: WAITING (default) or SENT (verified; agreement not signed yet). */
    @GetMapping
    public ResponseEntity<ApiResponse<List<DocumentVerificationService.Row>>> queue(
            @RequestParam(value = "status", required = false) String status, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(verificationService.queue(callerId(auth), status)));
    }

    @GetMapping("/{userId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> detail(@PathVariable Long userId, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(verificationService.detail(callerId(auth), userId)));
    }

    /** Verifies the documents and emails the participant their agreement. */
    @PostMapping("/{userId}/verify")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verify(@PathVariable Long userId, Authentication auth) {
        boolean sent = verificationService.verify(callerId(auth), userId);
        return ResponseEntity.ok(ApiResponse.success(
                sent ? "Verified. The agreement was emailed to the participant."
                        : "Verified, but the email couldn't be sent. The participant can still open the agreement from their dashboard.",
                Map.of("emailSent", sent)));
    }

    /** Sends one document back with a reason; the participant is emailed and uploads it again. */
    @PostMapping("/{userId}/documents/{documentId}/send-back")
    public ResponseEntity<ApiResponse<Map<String, Object>>> sendBack(
            @PathVariable Long userId, @PathVariable Long documentId,
            @RequestBody Map<String, String> body, Authentication auth) {
        verificationService.sendBack(callerId(auth), userId, documentId, body.get("reason"));
        return ResponseEntity.ok(ApiResponse.success("Sent back to the participant", Map.of("documentId", documentId)));
    }

    private static Long callerId(Authentication auth) {
        return Long.parseLong(auth.getPrincipal().toString());
    }
}
