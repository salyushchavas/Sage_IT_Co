package com.spire.backend.config;

import com.spire.backend.service.S3MigrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Startup entry point for the Cloudinary→S3 document migration.
 *
 * <p><b>Self-healing by default.</b> {@code S3_MIGRATION_MODE} defaults to
 * {@code execute} (was {@code off}): on every deploy where S3 is configured,
 * any agreement PDF still missing an {@code s3_key} is migrated to S3
 * automatically. This removes the manual env-var dance that previously got
 * skipped and left old documents un-migrated. Modes:
 * <ul>
 *   <li>{@code execute} (default) — migrate idempotently</li>
 *   <li>{@code dry-run} — report candidates only, no writes</li>
 *   <li>{@code off} — skip entirely (escape hatch)</li>
 * </ul>
 *
 * <p>Safety: (1) the migration is idempotent — once {@code s3_key} is set a
 * record drops out of the candidate set, so a completed run is a fast no-op;
 * (2) it runs on a background <b>daemon thread</b>, so a large migration never
 * blocks startup/health, and interruption (redeploy) just resumes next boot;
 * (3) reads are protected by the Phase 1 dual-read the whole time — an
 * un-migrated or audit-mismatched record is still served from Cloudinary;
 * (4) it never throws into startup. It only auto-runs when {@code aws.s3.bucket}
 * is configured (so local/dev without S3 is a clean skip).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class S3MigrationRunner implements ApplicationRunner {

    private final S3MigrationService migrationService;

    /** Defaults to {@code execute} so a normal deploy self-heals un-migrated docs. */
    @Value("${S3_MIGRATION_MODE:execute}")
    private String modeRaw;

    /** Auto-migration only runs when S3 is actually configured (skips local/dev). */
    @Value("${aws.s3.bucket:}")
    private String bucket;

    @Override
    public void run(ApplicationArguments args) {
        S3MigrationService.Mode mode = S3MigrationService.Mode.parse(modeRaw);
        if (mode == S3MigrationService.Mode.OFF) {
            log.info("[S3-MIGRATION] S3_MIGRATION_MODE=off — migration skipped.");
            return;
        }
        if (bucket == null || bucket.isBlank()) {
            log.info("[S3-MIGRATION] aws.s3.bucket not configured — skipping {} migration "
                    + "(no S3 target).", mode);
            return;
        }
        // Background daemon thread: a large migration must never block startup
        // or the Railway health check. Reads stay safe via the dual-read while
        // this runs; interruption resumes idempotently on the next boot.
        Thread t = new Thread(() -> {
            try {
                migrationService.run(mode);
            } catch (Exception e) {
                log.error("[S3-MIGRATION] background run failed (app unaffected): {}",
                        e.getMessage(), e);
            }
        }, "s3-migration");
        t.setDaemon(true);
        t.start();
        log.info("[S3-MIGRATION] started background {} run.", mode);
    }
}
