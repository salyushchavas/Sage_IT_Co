package com.spire.backend.config;

import com.spire.backend.service.S3MigrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Phase 2 — startup entry point for the one-time Cloudinary→S3 migration.
 *
 * <p>Controlled by the {@code S3_MIGRATION_MODE} env var
 * ({@code off | dry-run | execute}; unset = off). The bean is an eagerly
 * registered {@link ApplicationRunner} (same mechanism as {@code DataSeeder},
 * which is a {@code CommandLineRunner} — both fire at startup; there is no
 * {@code spring.main.lazy-initialization} in this app that could suppress
 * them). It runs after the context is ready (so the {@code s3_key} columns
 * exist) and NEVER throws — any failure is logged and the app continues.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class S3MigrationRunner implements ApplicationRunner {

    private final S3MigrationService migrationService;

    /** Reads the env var directly via Spring's environment (no properties file change). */
    @Value("${S3_MIGRATION_MODE:off}")
    private String modeRaw;

    @Override
    public void run(ApplicationArguments args) {
        S3MigrationService.Mode mode = S3MigrationService.Mode.parse(modeRaw);
        if (mode == S3MigrationService.Mode.OFF) {
            log.info("[S3-MIGRATION] S3_MIGRATION_MODE=off — migration skipped.");
            return;
        }
        try {
            migrationService.run(mode);
        } catch (Exception e) {
            // Must never crash app startup — a migration problem is operator-
            // recoverable; the running app (Phase 1 dual-read) is unaffected.
            log.error("[S3-MIGRATION] runner failed (app continues normally): {}",
                    e.getMessage(), e);
        }
    }
}
