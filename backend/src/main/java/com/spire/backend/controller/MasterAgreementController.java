package com.spire.backend.controller;

import com.spire.backend.dto.ApiResponse;
import com.spire.backend.service.MasterAgreementService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * The real agreement step (the /agreements console) for participants.
 * Participant side: see where it is, say "I'm ready". Console side
 * (console ERM token): who is waiting, and their details for the create form.
 */
@RestController
@RequiredArgsConstructor
public class MasterAgreementController {

    private final MasterAgreementService masterAgreementService;

    @GetMapping("/api/participants/agreement-request")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Map<String, Object>>> status(Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(masterAgreementService.status(userId(auth))));
    }

    /** "I'm ready to sign the agreement". */
    @PostMapping("/api/participants/agreement-request")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Map<String, Object>>> request(Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success("Request sent", masterAgreementService.request(userId(auth))));
    }

    @GetMapping("/api/agreement-erm/participant-requests")
    @PreAuthorize("hasRole('AGREEMENT_ERM')")
    public ResponseEntity<ApiResponse<List<MasterAgreementService.ConsoleRow>>> waiting() {
        return ResponseEntity.ok(ApiResponse.success(masterAgreementService.waitingForConsole()));
    }

    @GetMapping("/api/agreement-erm/participant-requests/{userId}")
    @PreAuthorize("hasRole('AGREEMENT_ERM')")
    public ResponseEntity<ApiResponse<MasterAgreementService.ConsoleRow>> one(@PathVariable Long userId) {
        return ResponseEntity.ok(ApiResponse.success(masterAgreementService.consoleRow(userId)));
    }

    private static Long userId(Authentication auth) {
        return Long.parseLong(auth.getPrincipal().toString());
    }
}
