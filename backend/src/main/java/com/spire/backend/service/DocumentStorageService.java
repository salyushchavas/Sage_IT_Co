package com.spire.backend.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Map;

/**
 * Storage abstraction for participant documents. Tries Cloudinary
 * first (when the {@code cloudinary.cloud-name} env var is set) and
 * falls back to the local filesystem otherwise — same dual-path
 * approach the project uses for certificates / signed agreements,
 * so a dev box without Cloudinary credentials still functions.
 *
 * Documents are uploaded as a "raw" resource_type (Cloudinary's term
 * for non-image files like PDFs) into a per-participant folder. The
 * caller persists the returned {@link StoredFile} URL onto the
 * {@code documents} row; serving back to the participant happens
 * via {@link #signedUrl(String)} which gives a 5-minute time-limited
 * URL for sensitive vault items.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentStorageService {

    /** Cloudinary signed URL TTL — 5 minutes per PRD §13.1. */
    private static final int SIGNED_URL_TTL_SECONDS = 300;
    private static final String LOCAL_DIR = "participant-documents";

    private final Cloudinary cloudinary;

    @Value("${cloudinary.cloud-name:}")
    private String cloudinaryCloudName;

    /**
     * Uploads {@code content} for the given user. Returns a small
     * descriptor the caller stores on the {@code documents} row.
     * Throws on storage failure — caller surfaces a user-visible
     * error and does NOT persist the row.
     */
    public StoredFile upload(Long userId, String filename, byte[] content, String contentType) {
        if (isCloudinaryConfigured()) {
            return uploadToCloudinary(userId, filename, content);
        }
        return uploadToDisk(userId, filename, content);
    }

    /**
     * Returns a viewer-facing URL for a previously-stored URL. For
     * Cloudinary URLs this strips the public-id off and re-signs
     * with a 5-minute expiry. For local-filesystem URLs we hand
     * back the same path; the {@code /api/participants/documents/
     * {id}/view} endpoint streams the file under JWT auth, which
     * gives equivalent gating without a signed URL.
     */
    public String signedUrl(String storedUrl) {
        if (storedUrl == null || storedUrl.isBlank()) return null;
        if (!storedUrl.startsWith("http")) {
            return storedUrl;
        }
        if (!isCloudinaryConfigured()) {
            return storedUrl;
        }
        try {
            // Extract the public id (filename without extension) from
            // the Cloudinary URL pattern .../upload/.../<publicId>.<ext>
            String publicId = extractPublicId(storedUrl);
            if (publicId == null) return storedUrl;
            long expiresAt = (System.currentTimeMillis() / 1000L) + SIGNED_URL_TTL_SECONDS;
            return cloudinary.url()
                    .resourceType("raw")
                    .signed(true)
                    .secure(true)
                    .type("authenticated")
                    .source(publicId)
                    .generate(publicId)
                    + "?_t=" + expiresAt;
        } catch (Exception e) {
            log.warn("Failed to sign Cloudinary URL, returning raw: {}", e.getMessage());
            return storedUrl;
        }
    }

    /**
     * Removes the underlying file when a participant deletes a row.
     * Best-effort — a failure here doesn't block the DB delete.
     */
    public void delete(String storedUrl) {
        if (storedUrl == null || storedUrl.isBlank()) return;
        if (!storedUrl.startsWith("http")) {
            try {
                File f = new File(storedUrl);
                if (f.exists()) {
                    if (!f.delete()) log.debug("delete() returned false for {}", storedUrl);
                }
            } catch (Exception e) {
                log.warn("Local document delete failed: {}", e.getMessage());
            }
            return;
        }
        if (isCloudinaryConfigured()) {
            try {
                String publicId = extractPublicId(storedUrl);
                if (publicId != null) {
                    cloudinary.uploader().destroy(publicId, ObjectUtils.asMap("resource_type", "raw"));
                }
            } catch (Exception e) {
                log.warn("Cloudinary destroy failed for {}: {}", storedUrl, e.getMessage());
            }
        }
    }

    public boolean isCloudinaryConfigured() {
        return cloudinaryCloudName != null && !cloudinaryCloudName.isBlank();
    }

    // ── Internals ─────────────────────────────────────────────────

    private StoredFile uploadToCloudinary(Long userId, String filename, byte[] content) {
        try {
            String publicId = "spire/documents/" + userId + "/" + safeStem(filename)
                    + "-" + System.currentTimeMillis();
            Map<?, ?> result = cloudinary.uploader().upload(content, ObjectUtils.asMap(
                    "public_id", publicId,
                    "resource_type", "raw",
                    "type", "authenticated",
                    "overwrite", false
            ));
            Object url = result.get("secure_url");
            if (url == null) {
                throw new RuntimeException("Cloudinary upload returned no secure_url");
            }
            return new StoredFile(url.toString(), publicId);
        } catch (Exception e) {
            throw new RuntimeException("Cloudinary upload failed: " + e.getMessage(), e);
        }
    }

    private StoredFile uploadToDisk(Long userId, String filename, byte[] content) {
        try {
            String dir = LOCAL_DIR + "/" + userId;
            new File(dir).mkdirs();
            String stored = safeStem(filename) + "-" + System.currentTimeMillis() + extensionOf(filename);
            String path = dir + "/" + stored;
            try (FileOutputStream out = new FileOutputStream(path)) {
                out.write(content);
            }
            return new StoredFile(path, path);
        } catch (Exception e) {
            throw new RuntimeException("Local document upload failed: " + e.getMessage(), e);
        }
    }

    /** Strips a Cloudinary URL down to its public id (no extension). */
    private static String extractPublicId(String url) {
        int upload = url.indexOf("/upload/");
        if (upload < 0) {
            int auth = url.indexOf("/authenticated/");
            if (auth < 0) return null;
            upload = auth + "/authenticated".length() - "/upload".length();
        }
        String tail = url.substring(upload + "/upload/".length());
        // Drop optional version segment (v1234567/).
        int slash = tail.indexOf('/');
        if (slash > 0 && tail.charAt(0) == 'v') {
            tail = tail.substring(slash + 1);
        }
        int dot = tail.lastIndexOf('.');
        return dot > 0 ? tail.substring(0, dot) : tail;
    }

    private static String safeStem(String filename) {
        if (filename == null) return "file";
        String stem = filename.contains(".")
                ? filename.substring(0, filename.lastIndexOf('.'))
                : filename;
        return stem.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String extensionOf(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot) : "";
    }

    /**
     * Tuple returned from {@link #upload}: the public URL or path
     * to persist on the {@code file_url} column, plus the
     * storage-internal handle (Cloudinary public id or disk path)
     * we use to delete the file later.
     */
    public record StoredFile(String url, String storagePath) {}
}
