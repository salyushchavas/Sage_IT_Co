package com.spire.backend.controller;

import com.spire.backend.dto.ApiResponse;
import com.spire.backend.service.DocumentStorageService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/**
 * Hands a stored participant file to a signed-in viewer, whatever storage it
 * is in: the bytes for S3 and the server's disk (sent under the caller's
 * sign-in), or a short-lived signed link for Cloudinary. No public link
 * ever exists. 404 when the file is missing.
 */
final class StoredFileResponse {

    private StoredFileResponse() {}

    /** {@code downloadName} without an extension; the right one is added. */
    static ResponseEntity<?> of(DocumentStorageService storage, String stored, String downloadName) {
        return named(storage, stored, downloadName, true);
    }

    /** {@code fileName} as the participant uploaded it. */
    static ResponseEntity<?> asNamed(DocumentStorageService storage, String stored, String fileName) {
        return named(storage, stored, fileName, false);
    }

    private static ResponseEntity<?> named(DocumentStorageService storage, String stored, String name, boolean addExtension) {
        DocumentStorageService.Retrieval r = storage.retrieve(stored);
        if (r == null) return ResponseEntity.notFound().build();
        if (r.url() != null) {
            return ResponseEntity.ok(ApiResponse.success(Map.of("url", r.url(), "expiresIn", 300)));
        }
        String type = r.contentType() == null ? "application/octet-stream" : r.contentType();
        String ext = switch (type) {
            case "application/pdf" -> ".pdf";
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            default -> "";
        };
        String safe = (name == null || name.isBlank() ? "document" : name).replaceAll("[\\r\\n\"\\\\]", "_");
        if (addExtension && !safe.toLowerCase().endsWith(ext)) safe = safe + ext;
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(type))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + safe + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(r.bytes());
    }
}
