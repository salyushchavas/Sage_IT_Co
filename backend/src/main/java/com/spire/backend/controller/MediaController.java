package com.spire.backend.controller;

import com.cloudinary.Cloudinary;
import com.spire.backend.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Generates a short-lived Cloudinary signature for client-side
 * direct video uploads. Used by the bulk-upload flow on the
 * instructor content manager — the browser POSTs each file
 * straight to Cloudinary instead of relaying through Spring
 * Boot, which would otherwise have to buffer GBs of video for
 * a single batch.
 *
 * Returning a signature instead of an unsigned upload preset
 * keeps the upload constrained to our folder + resource_type
 * and is harder to abuse: the secret never leaves the server,
 * the timestamp expires after Cloudinary's default 1-hour window,
 * and the signature is bound to the exact params we sign.
 */
@RestController
@RequestMapping("/api/instructor")
@RequiredArgsConstructor
public class MediaController {

    private final Cloudinary cloudinary;

    /** Cloud name is harmless to expose — it's already part of every video URL. */
    @Value("${cloudinary.cloud-name}")
    private String cloudName;

    @Value("${cloudinary.api-key}")
    private String apiKey;

    @Value("${cloudinary.api-secret}")
    private String apiSecret;

    @GetMapping("/cloudinary-signature")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getSignature() {
        long timestamp = System.currentTimeMillis() / 1000L;
        String folder = "spire/courses/bulk";

        // Sign exactly the params we want the client to send. If the
        // client appends extras (or omits these), Cloudinary rejects
        // the upload. Cloudinary's apiSignRequest filters out the
        // file/api_key/signature fields and signs the rest sorted by
        // key — we mirror that here.
        Map<String, Object> paramsToSign = new LinkedHashMap<>();
        paramsToSign.put("timestamp", timestamp);
        paramsToSign.put("folder", folder);

        String signature = cloudinary.apiSignRequest(paramsToSign, apiSecret);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cloudName", cloudName);
        body.put("apiKey", apiKey);
        body.put("timestamp", timestamp);
        body.put("folder", folder);
        body.put("signature", signature);
        return ResponseEntity.ok(ApiResponse.success(body));
    }

}
