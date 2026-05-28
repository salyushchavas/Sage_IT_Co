package com.spire.backend.controller;

import com.spire.backend.dto.ApiResponse;
import com.spire.backend.service.CertificateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Public verification endpoint at the cleaner /api/verify/{number}
 * URL the printed PDFs and share links point to. Mirrors
 * {@code /api/certificates/verify/{id}} so the legacy URL keeps
 * working — both are listed as permitAll in SecurityConfig.
 *
 * Lives in its own controller (rather than as a second mapping on
 * CertificateController) because the absolute path doesn't share the
 * /api/certificates prefix.
 */
@RestController
@RequestMapping("/api/verify")
@RequiredArgsConstructor
public class VerifyController {

    private final CertificateService certificateService;

    @GetMapping("/{certificateNumber}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verify(
            @PathVariable String certificateNumber) {
        return certificateService.findByCertificateId(certificateNumber)
                .map(cert -> {
                    Map<String, Object> data = new HashMap<>();
                    data.put("valid", true);
                    data.put("certificateId", cert.getCertificateId());
                    data.put("studentName", cert.getUser().getFullName());
                    data.put("courseTitle", cert.getCourse().getTitle());
                    data.put("issuedAt", cert.getIssuedAt().toString());
                    data.put("finalScore", cert.getFinalScore());
                    return ResponseEntity.ok(ApiResponse.success(data));
                })
                .orElse(ResponseEntity.ok(ApiResponse.success(Map.of("valid", false))));
    }
}
