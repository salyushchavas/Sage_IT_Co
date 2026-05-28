package com.spire.backend.controller;

import com.spire.backend.dto.ApiResponse;
import com.spire.backend.dto.DashboardSummaryDTO;
import com.spire.backend.dto.NextActionDTO;
import com.spire.backend.service.NextActionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class NextActionController {

    private final NextActionService nextActionService;

    @GetMapping("/next-action")
    public ResponseEntity<ApiResponse<NextActionDTO>> getNextAction(Authentication authentication) {
        Long userId = Long.parseLong(authentication.getPrincipal().toString());
        return ResponseEntity.ok(ApiResponse.success(nextActionService.computeNextAction(userId)));
    }

    @GetMapping("/dashboard-summary")
    public ResponseEntity<ApiResponse<DashboardSummaryDTO>> getDashboardSummary(Authentication authentication) {
        Long userId = Long.parseLong(authentication.getPrincipal().toString());
        return ResponseEntity.ok(ApiResponse.success(nextActionService.buildSummary(userId)));
    }
}
