package com.spire.backend.controller;

import com.spire.backend.dto.ApiResponse;
import com.spire.backend.entity.Certificate;
import com.spire.backend.service.CertificateService;
import com.spire.backend.service.DocumentStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/certificates")
@RequiredArgsConstructor
public class CertificateController {

    private final CertificateService certificateService;
    /** Phase 1 (S3) — used to presign S3-backed certificate downloads. */
    private final DocumentStorage documentStorage;

    // ─── Generate certificate ───────────────────────────────────────

    @PostMapping("/generate/{courseId}")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> generateCertificate(
            @PathVariable Long courseId,
            Authentication auth) {
        Long userId = Long.parseLong(auth.getPrincipal().toString());
        Certificate cert = certificateService.generateCertificate(courseId, userId);

        return ResponseEntity.ok(ApiResponse.success("Certificate generated", toFullDto(cert)));
    }

    // ─── Check if certificate exists ────────────────────────────────

    @GetMapping("/check/{courseId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Map<String, Object>>> checkCertificate(
            @PathVariable Long courseId,
            Authentication auth) {
        Long userId = Long.parseLong(auth.getPrincipal().toString());
        Certificate cert = certificateService.getCertificate(courseId, userId);

        if (cert == null) {
            return ResponseEntity.ok(ApiResponse.success(Map.of("exists", false)));
        }

        Map<String, Object> data = new HashMap<>(toFullDto(cert));
        data.put("exists", true);
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    // ─── Get all user certificates ──────────────────────────────────

    @GetMapping("/my")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getMyCertificates(Authentication auth) {
        Long userId = Long.parseLong(auth.getPrincipal().toString());
        List<Certificate> certs = certificateService.getUserCertificates(userId);

        List<Map<String, Object>> data = certs.stream().map(c -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", c.getId());
            m.put("certificateId", c.getCertificateId());
            m.put("courseId", c.getCourse().getId());
            m.put("courseTitle", c.getCourse().getTitle());
            m.put("certificateUrl", c.getCertificateUrl());
            m.put("issuedAt", c.getIssuedAt().toString());
            m.put("finalScore", c.getFinalScore());
            return m;
        }).toList();

        return ResponseEntity.ok(ApiResponse.success(data));
    }

    // ─── Public verification ───────────────────────────────────────
    // Two paths to the same handler: /api/certificates/verify/{id} (the
    // legacy URL) and /api/verify/{id} (the cleaner public URL the
    // verification page links to). Both stay in security's permitAll
    // list.

    @GetMapping({"/verify/{certificateId}"})
    public ResponseEntity<ApiResponse<Map<String, Object>>> verifyCertificate(
            @PathVariable String certificateId) {
        return certificateService.findByCertificateId(certificateId)
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

    // ─── Download PDF (legacy path-based URL — kept for backward
    //     compat with cert URLs already saved on existing rows) ─────

    @GetMapping("/download/{courseId}/{fileName}")
    public ResponseEntity<Resource> downloadCertificate(
            @PathVariable String courseId, @PathVariable String fileName) {
        // Old certificates: served from the on-disk file (unchanged).
        File file = new File("certificates/" + courseId + "/" + fileName);
        if (file.exists()) {
            Resource resource = new FileSystemResource(file);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                    .body(resource);
        }
        // Phase 1 (S3) — no disk file: resolve the cert from its stored URL
        // and stream from S3 when it has an s3_key. The endpoint stays no-auth
        // and proxies bytes server-side (the presigned URL is never exposed),
        // so the frontend's <a href> link contract is unchanged.
        String certUrl = "/api/certificates/download/" + courseId + "/" + fileName;
        Certificate cert = certificateService.findByCertificateUrl(certUrl).orElse(null);
        if (cert == null || cert.getS3Key() == null || cert.getS3Key().isBlank()) {
            return ResponseEntity.notFound().build();
        }
        return streamFromS3(cert.getS3Key(), fileName);
    }

    // ─── By-cert-number lookups ─────────────────────────────────────
    // These live AFTER the literal-prefix routes (/my, /check, /verify,
    // /generate, /download) — Spring's path-matching prefers literal
    // segments over path variables, so /api/certificates/my still
    // hits getMyCertificates() rather than this catch-all.

    /**
     * Fetch a single certificate by its public number. Owner or admin
     * only — verification by the public uses /api/verify/{number}.
     */
    @GetMapping("/{certificateNumber}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getByNumber(
            @PathVariable String certificateNumber,
            Authentication auth) {
        Long userId = Long.parseLong(auth.getPrincipal().toString());
        return certificateService.findByCertificateId(certificateNumber)
                .filter(c -> c.getUser().getId().equals(userId)
                        || auth.getAuthorities().stream()
                                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())))
                .map(c -> ResponseEntity.ok(ApiResponse.success(toFullDto(c))))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Cleaner download URL keyed on the public cert number rather
     * than the underlying file path. Resolves the cert, then streams
     * the same file the legacy /download/{courseId}/{fileName}
     * endpoint serves.
     */
    @GetMapping("/{certificateNumber}/download")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Resource> downloadByNumber(
            @PathVariable String certificateNumber,
            Authentication auth) {
        Long userId = Long.parseLong(auth.getPrincipal().toString());
        return certificateService.findByCertificateId(certificateNumber)
                .filter(c -> c.getUser().getId().equals(userId)
                        || auth.getAuthorities().stream()
                                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())))
                .map(c -> {
                    // Phase 1 (S3) — new certs stream from S3 (presigned,
                    // proxied server-side). Old certs fall through to disk.
                    if (c.getS3Key() != null && !c.getS3Key().isBlank()) {
                        return streamFromS3(c.getS3Key(), certificateNumber + ".pdf");
                    }
                    String url = c.getCertificateUrl();
                    // certificateUrl is stored as "/api/certificates/download/{courseId}/{fileName}".
                    // Strip the prefix to recover the on-disk file.
                    String prefix = "/api/certificates/download/";
                    if (url == null || !url.startsWith(prefix)) {
                        return ResponseEntity.notFound().<Resource>build();
                    }
                    File file = new File("certificates/" + url.substring(prefix.length()));
                    if (!file.exists()) {
                        return ResponseEntity.notFound().<Resource>build();
                    }
                    Resource resource = new FileSystemResource(file);
                    return ResponseEntity.ok()
                            .contentType(MediaType.APPLICATION_PDF)
                            .header(HttpHeaders.CONTENT_DISPOSITION,
                                    "attachment; filename=\"" + certificateNumber + ".pdf\"")
                            .<Resource>body(resource);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Single shape used by both POST /generate and GET /check so the
     * frontend doesn't have to switch on the source. Includes only
     * fields the owner is allowed to see (no email / user id).
     */
    /**
     * Phase 1 (S3) — presign a short-lived GET URL for {@code s3Key}, fetch
     * the bytes server-side, and return them as a PDF attachment. Keeps both
     * download endpoints' binary response contract identical; the presigned
     * URL is never exposed to the client.
     */
    private ResponseEntity<Resource> streamFromS3(String s3Key, String filename) {
        String url = documentStorage.presignedGetUrl(
                s3Key, java.time.Duration.ofMinutes(15), filename, false);
        byte[] bytes;
        try {
            java.net.URLConnection conn = new java.net.URL(url).openConnection();
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(30_000);
            try (java.io.InputStream in = conn.getInputStream()) {
                bytes = in.readAllBytes();
            }
        } catch (java.io.IOException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body((Resource) new ByteArrayResource(bytes));
    }

    private static Map<String, Object> toFullDto(Certificate cert) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", cert.getId());
        m.put("certificateId", cert.getCertificateId());
        m.put("certificateUrl", cert.getCertificateUrl());
        m.put("issuedAt", cert.getIssuedAt().toString());
        m.put("finalScore", cert.getFinalScore());
        m.put("courseTitle", cert.getCourse().getTitle());
        m.put("verificationUrl", "/verify/" + cert.getCertificateId());
        return m;
    }
}
