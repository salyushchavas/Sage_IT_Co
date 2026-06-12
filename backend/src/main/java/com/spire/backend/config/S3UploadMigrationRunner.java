package com.spire.backend.config;

import com.spire.backend.service.S3UploadMigrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Phase 5 — startup entry point for the Cloudinary→S3 migration of agreements
 * CONSULTANT UPLOADS + SIGNATURES. Deliberately SEPARATE from the generated-PDF
 * runner ({@link S3MigrationRunner}) and gated by its own env var
 * {@code S3_UPLOAD_MIGRATION_MODE}.
 *
 * <p>Unlike the (self-healing, default-{@code execute}) PDF runner, this one
 * defaults to {@code off}: the operator opts in explicitly via the runbook
 * ({@code dry-run} → inspect → {@code execute} → {@code off}). Modes:
 * <ul>
 *   <li>{@code off} (default / unset) — skip entirely</li>
 *   <li>{@code dry-run} — resolve + reachability-check each source, classify
 *       REACHABLE / BROKEN_SOURCE; no writes</li>
 *   <li>{@code execute} — migrate idempotently (byte-verified)</li>
 * </ul>
 *
 * <p>Safety mirrors the PDF runner: (1) idempotent — a row/entry drops out once
 * its {@code *_s3_key} (or JSON {@code s3Key}) is set; (2) runs on a background
 * <b>daemon thread</b>, so it never blocks startup/health, and interruption
 * resumes next boot; (3) the Phase-5 dual-read serves un-migrated rows from
 * Cloudinary throughout; (4) it never throws into startup; (5) only runs when
 * {@code aws.s3.bucket} is configured (clean skip on local/dev). This is a
 * concrete {@code @Component} {@link ApplicationRunner} (eagerly registered),
 * so it actually fires — no lazy-init gotcha.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class S3UploadMigrationRunner implements ApplicationRunner {

    private final S3UploadMigrationService migrationService;

    /** Defaults to {@code off} — operator opts in via the runbook. */
    @Value("${S3_UPLOAD_MIGRATION_MODE:off}")
    private String modeRaw;

    /** Only runs when S3 is actually configured (skips local/dev). */
    @Value("${aws.s3.bucket:}")
    private String bucket;

    @Override
    public void run(ApplicationArguments args) {
        S3UploadMigrationService.Mode mode = S3UploadMigrationService.Mode.parse(modeRaw);
        if (mode == S3UploadMigrationService.Mode.OFF) {
            log.info("{} S3_UPLOAD_MIGRATION_MODE=off — upload/signature migration skipped.",
                    "[S3-UPLOAD-MIGRATION]");
            return;
        }
        if (bucket == null || bucket.isBlank()) {
            log.info("[S3-UPLOAD-MIGRATION] aws.s3.bucket not configured — skipping {} migration "
                    + "(no S3 target).", mode);
            return;
        }
        Thread t = new Thread(() -> {
            try {
                migrationService.run(mode);
            } catch (Exception e) {
                log.error("[S3-UPLOAD-MIGRATION] background run failed (app unaffected): {}",
                        e.getMessage(), e);
            }
        }, "s3-upload-migration");
        t.setDaemon(true);
        t.start();
        log.info("[S3-UPLOAD-MIGRATION] started background {} run.", mode);
    }
}
