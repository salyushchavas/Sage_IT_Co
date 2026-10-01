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

/** An ERM checks a participant's documents before the program step (ERM, Operations, System admin). */
@RestController
@RequestMapping("/api/verifications")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ERM','OPERATIONS_ADMIN','SYSTEM_ADMIN')")
public class DocumentVerificationController {

    private final DocumentVerificationService verificationService;

    /** {@code status}: WAITING (default) or VERIFIED. */
    @GetMapping
    public ResponseEntity<ApiResponse<List<DocumentVerificationService.Row>>> queue(
            @RequestParam(value = "status", required = false) String status, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(verificationService.queue(callerId(auth), status)));
    }

    @GetMapping("/{userId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> detail(@PathVariable Long userId, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(verificationService.detail(callerId(auth), userId)));
    }

    /** Confirms the documents; the participant is emailed and can choose their program. */
    @PostMapping("/{userId}/confirm")
    public ResponseEntity<ApiResponse<Map<String, Object>>> confirm(@PathVariable Long userId, Authentication auth) {
        boolean sent = verificationService.confirm(callerId(auth), userId);
        return ResponseEntity.ok(ApiResponse.success(
                sent ? "Confirmed. The participant was emailed and can choose their program."
                        : "Confirmed. The email couldn't be sent, but the program step is open on their dashboard.",
                Map.of("emailSent", sent)));
    }

    /** Sends one document back with a reason; the participant is emailed and sees it on their dashboard. */
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
