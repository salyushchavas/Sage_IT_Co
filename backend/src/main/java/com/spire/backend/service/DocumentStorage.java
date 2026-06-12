package com.spire.backend.service;

import java.time.Duration;

/**
 * Phase 1 — abstraction over generated-document (agreement PDF /
 * certificate) storage. The S3-backed implementation
 * ({@link S3StorageService}) is the storage backend for all NEWLY
 * generated documents; existing Cloudinary- and disk-backed records keep
 * being served by their untouched original paths (dual-read).
 *
 * <p>NB: intentionally named {@code DocumentStorage} (not
 * {@code DocumentStorageService}) because that class name is already taken
 * by the unrelated participant-document vault and must not be disturbed.
 */
public interface DocumentStorage {

    /**
     * Stores {@code bytes} under {@code key} with the given content type and
     * returns the key (the canonical identifier persisted on the record).
     */
    String store(byte[] bytes, String key, String contentType);

    /**
     * Reads the full object stored under {@code key} back into memory. Used by
     * the agreements consultant-upload + signature paths, which stream bytes
     * to the browser (or merge them into the rendered PDF) rather than
     * redirecting to a presigned URL — so the caller sets the real
     * {@code Content-Type} from the persisted metadata. Avoids the
     * {@link #presignedGetUrl} application/pdf constraint for non-PDF artifacts.
     */
    byte[] get(String key);

    /**
     * Mints a short-lived presigned GET URL for {@code key}. The response is
     * forced to {@code application/pdf} with a {@code Content-Disposition} of
     * {@code inline} or {@code attachment; filename="…"}. A null {@code ttl}
     * uses the default (15 minutes).
     */
    String presignedGetUrl(String key, Duration ttl, String filename, boolean inline);
}
