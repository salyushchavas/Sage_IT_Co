package com.spire.backend.controller;

import com.spire.backend.dto.ApiResponse;
import com.spire.backend.service.MasterAgreementService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * The real agreement step for participants: see where it is, say "I'm
 * ready". Website ERMs pick the request up from the ERM dashboard's
 * Agreements tab ({@link WebAgreementStaffController}).
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

    private static Long userId(Authentication auth) {
        return Long.parseLong(auth.getPrincipal().toString());
    }
}
