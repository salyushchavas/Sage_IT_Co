package com.spire.backend.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class HealthController {

    /**
     * Short git commit of the running build, from the variable Railway sets
     * on GitHub deploys; "unknown" elsewhere. Lets anyone confirm which
     * commit is live without dashboard access.
     */
    private static final String COMMIT = shortCommit(System.getenv("RAILWAY_GIT_COMMIT_SHA"));

    static String shortCommit(String sha) {
        if (sha == null || sha.isBlank()) return "unknown";
        String s = sha.trim();
        return s.length() > 7 ? s.substring(0, 7) : s;
    }

    /** Where new participant files go ("s3", "cloudinary" or "local-disk"), checkable without signing in. */
    private final com.spire.backend.service.DocumentStorageService storage;

    public HealthController(com.spire.backend.service.DocumentStorageService storage) {
        this.storage = storage;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "commit", COMMIT,
                "storage", storage.mode(),
                "timestamp", Instant.now().toString()
        ));
    }
}
