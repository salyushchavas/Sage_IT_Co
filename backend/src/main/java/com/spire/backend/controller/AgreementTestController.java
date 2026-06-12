package com.spire.backend.controller;

import com.spire.backend.entity.ConsultantApplication;
import com.spire.backend.repository.ConsultantApplicationRepository;
import com.spire.backend.service.AgreementDocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * TEMPORARY developer endpoint for verifying the docx-stamper +
 * LibreOffice + Cloudinary pipeline against an existing application.
 *
 * DELETE BEFORE PRODUCTION DEPLOY.
 *
 * Falls under {@code .anyRequest().authenticated()} in SecurityConfig
 * so a regular Sage user JWT works; the agreement-erm console's
 * {@code purpose=agreement_erm} JWT also passes once it's been issued
 * by {@code POST /api/agreement-erm/login} because that token also
 * authenticates as a principal.
 *
 * Usage after Railway redeploy:
 *
 * <pre>
 *   curl -X POST \
 *     -H "Authorization: Bearer &lt;jwt&gt;" \
 *     https://sageitco-production.up.railway.app/api/agreement-test/generate/&lt;applicationId&gt;
 * </pre>
 *
 * Returns a JSON body with the Cloudinary {@code pdfUrl}; open it in
 * a browser and verify (a) no literal {@code ${var}} tokens leaked,
 * (b) cells without data render blank rather than NPE, (c) any
 * present signature images embed inline.
 */
@RestController
@RequestMapping("/api/agreement-test")
@RequiredArgsConstructor
@Slf4j
public class AgreementTestController {

    private final AgreementDocumentService documentService;
    private final ConsultantApplicationRepository applicationRepository;

    @PostMapping("/generate/{appId}")
    public ResponseEntity<Map<String, String>> generate(@PathVariable String appId)
            throws Exception {
        ConsultantApplication app = applicationRepository
                .findByApplicationId(appId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Application not found: " + appId));
        // Phase 1 (S3) — generateAgreementPdf now stores in S3 and returns
        // the S3 key in publicId() (secureUrl() is null). This temp endpoint
        // just echoes the stored key for a quick eyeball check.
        String key = documentService.generateAgreementPdf(app).publicId();
        log.info("Test-generated agreement PDF for {}: s3Key={}", appId, key);
        return ResponseEntity.ok(Map.of(
                "s3Key", key == null ? "" : key, "applicationId", appId));
    }
}
