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
     * Mints a short-lived presigned GET URL for {@code key}. The response is
     * forced to {@code application/pdf} with a {@code Content-Disposition} of
     * {@code inline} or {@code attachment; filename="…"}. A null {@code ttl}
     * uses the default (15 minutes).
     */
    String presignedGetUrl(String key, Duration ttl, String filename, boolean inline);
}
