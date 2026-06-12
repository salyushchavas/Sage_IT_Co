package com.spire.backend.service;

import com.spire.backend.entity.ConsultantApplication;
import com.spire.backend.repository.ConsultantApplicationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Phase 2 — one-time, idempotent migration of existing Cloudinary-hosted
 * GENERATED documents to S3. In scope (same documents Phase 1 newly writes
 * to S3): the FINAL ERM-signed agreement PDF ({@code final_pdf_public_id}
 * → {@code s3_key}) and the CONSULTANT-version PDF + Certificate of
 * Completion ({@code consultant_pdf_public_id} → {@code consultant_pdf_s3_key}).
 *
 * <p>Nothing on Cloudinary is ever deleted or modified. A record is only
 * pointed at S3 after byte-level verification: an S3 round-trip SHA-256
 * match, plus — for the consultant PDF — a cross-check against the stored
 * {@code document_hash} (which equals the SHA-256 of the exact bytes
 * uploaded to Cloudinary; mismatch ⇒ manual review, never a silent
 * migration). Each record is updated in ITS OWN transaction.
 *
 * <p>Self-contained on purpose (the migration runner + this service, plus
 * two repository finders): it reuses {@link AgreementDocumentService#signedPdfUrl}
 * for the authenticated Cloudinary fetch and {@link ConsultantVersionService#sha256Hex}
 * for hashing (so the audit cross-check uses the identical algorithm), and
 * mirrors the Phase 1 S3 key pattern with the record's ORIGINAL timestamp.
 */
@Service
@Slf4j
public class S3MigrationService {

    public enum Mode {
        OFF, DRY_RUN, EXECUTE;

        public static Mode parse(String raw) {
            if (raw == null) return OFF;
            switch (raw.trim().toLowerCase()) {
                case "dry-run": case "dryrun": case "dry_run": return DRY_RUN;
                case "execute": return EXECUTE;
                default: return OFF;
            }
        }
    }

    private static final String LOG = "[S3-MIGRATION]";
    private static final DateTimeFormatter KEY_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final ConsultantApplicationRepository repo;
    private final AgreementDocumentService agreementDocumentService;
    private final ObjectProvider<S3Client> s3Provider;
    private final TransactionTemplate txTemplate;
    private final String bucket;

    public S3MigrationService(ConsultantApplicationRepository repo,
                              AgreementDocumentService agreementDocumentService,
                              ObjectProvider<S3Client> s3Provider,
                              PlatformTransactionManager txManager,
                              @Value("${aws.s3.bucket:}") String bucket) {
        this.repo = repo;
        this.agreementDocumentService = agreementDocumentService;
        this.s3Provider = s3Provider;
        this.txTemplate = new TransactionTemplate(txManager);
        this.bucket = bucket;
    }

    /** One generated document on a ConsultantApplication that may need migrating. */
    private record Candidate(Long id, String type, String cloudinaryPublicId,
                             String ermId, Integer phase, LocalDateTime createdAt,
                             String auditHash) {}

    public void run(Mode mode) {
        log.info("{} ==== S3 MIGRATION START (mode={}) ====", LOG, mode);

        List<Candidate> candidates = new ArrayList<>();
        for (ConsultantApplication a : repo.findByS3KeyIsNullAndFinalPdfPublicIdIsNotNull()) {
            candidates.add(new Candidate(a.getId(), "final", a.getFinalPdfPublicId(),
                    a.getOwnerErmId(), a.getPhase(), a.getCreatedAt(), null));
        }
        for (ConsultantApplication a : repo.findByConsultantPdfS3KeyIsNullAndConsultantPdfPublicIdIsNotNull()) {
            candidates.add(new Candidate(a.getId(), "consultant", a.getConsultantPdfPublicId(),
                    a.getOwnerErmId(), a.getPhase(), a.getCreatedAt(), a.getDocumentHash()));
        }

        int total = candidates.size();
        int reachable = 0, migrated = 0, brokenSource = 0, verifyFailed = 0,
                auditMismatch = 0, skipped = 0;
        int finalCount = 0, consultantCount = 0;
        List<String> failures = new ArrayList<>();
        List<Long> migratedFinal = new ArrayList<>();
        List<Long> migratedConsultant = new ArrayList<>();

        for (Candidate c : candidates) {
            if ("final".equals(c.type())) finalCount++; else consultantCount++;

            if (mode == Mode.DRY_RUN) {
                boolean ok = reachable(c);
                if (ok) {
                    reachable++;
                    log.info("{} REACHABLE id={} type={} sourceId={}", LOG, c.id(), c.type(), c.cloudinaryPublicId());
                } else {
                    brokenSource++;
                    failures.add("id=" + c.id() + " type=" + c.type() + " reason=BROKEN_SOURCE");
                    log.warn("{} BROKEN_SOURCE id={} type={} sourceId={}", LOG, c.id(), c.type(), c.cloudinaryPublicId());
                }
                continue;
            }

            // EXECUTE — strict order a→f.
            byte[] bytes;
            try {                                                // (a) download from Cloudinary
                bytes = downloadCloudinary(c.cloudinaryPublicId());
            } catch (Exception e) {
                brokenSource++;
                failures.add("id=" + c.id() + " type=" + c.type() + " reason=BROKEN_SOURCE");
                log.warn("{} BROKEN_SOURCE id={} type={} sourceId={} ({})",
                        LOG, c.id(), c.type(), c.cloudinaryPublicId(), e.getClass().getSimpleName());
                continue;
            }
            String srcHash = ConsultantVersionService.sha256Hex(bytes);   // (b) hash source bytes

            String key = buildKey(c);
            try {                                                // (c) upload to S3 with metadata
                putToS3(key, bytes, c.cloudinaryPublicId());
            } catch (Exception e) {
                skipped++;
                failures.add("id=" + c.id() + " type=" + c.type() + " reason=S3_UPLOAD_FAILED");
                log.warn("{} S3_UPLOAD_FAILED id={} type={} ({})",
                        LOG, c.id(), c.type(), e.getClass().getSimpleName());
                continue;
            }

            byte[] back;
            try {                                                // (d) read back from S3
                back = getFromS3(key);
            } catch (Exception e) {
                skipped++;
                failures.add("id=" + c.id() + " type=" + c.type() + " reason=S3_READBACK_FAILED");
                log.warn("{} S3_READBACK_FAILED id={} type={} ({})",
                        LOG, c.id(), c.type(), e.getClass().getSimpleName());
                continue;
            }
            if (!srcHash.equalsIgnoreCase(ConsultantVersionService.sha256Hex(back))) {
                verifyFailed++;
                failures.add("id=" + c.id() + " type=" + c.type() + " reason=VERIFY_FAILED");
                log.warn("{} VERIFY_FAILED id={} type={} (S3 round-trip hash mismatch)", LOG, c.id(), c.type());
                continue;
            }

            // (e) certificate audit-hash cross-check (consultant PDF only).
            if (c.auditHash() != null && !c.auditHash().isBlank()
                    && !c.auditHash().equalsIgnoreCase(srcHash)) {
                auditMismatch++;
                failures.add("id=" + c.id() + " type=" + c.type() + " reason=AUDIT_HASH_MISMATCH");
                log.error("{} AUDIT_HASH_MISMATCH id={} type={} — stored document_hash != Cloudinary bytes; "
                        + "NOT migrated, manual review required", LOG, c.id(), c.type());
                continue;
            }

            try {                                                // (f) point the record at S3 — own tx
                updateS3Key(c, key);
            } catch (Exception e) {
                skipped++;
                failures.add("id=" + c.id() + " type=" + c.type() + " reason=DB_UPDATE_FAILED");
                log.warn("{} DB_UPDATE_FAILED id={} type={} ({})",
                        LOG, c.id(), c.type(), e.getClass().getSimpleName());
                continue;
            }
            migrated++;
            if ("final".equals(c.type())) migratedFinal.add(c.id()); else migratedConsultant.add(c.id());
            log.info("{} MIGRATED id={} ermId={} type={} key={}", LOG, c.id(), c.ermId(), c.type(), key);
        }

        // Final, delimited report for Railway log scanning.
        StringBuilder sb = new StringBuilder();
        sb.append("\n==== S3 MIGRATION REPORT (").append(mode).append(") ====\n");
        sb.append("candidates=").append(total)
          .append(" (final=").append(finalCount).append(", consultant=").append(consultantCount).append(")\n");
        if (mode == Mode.DRY_RUN) {
            sb.append("reachable=").append(reachable).append(" broken_source=").append(brokenSource).append("\n");
        } else {
            sb.append("migrated=").append(migrated)
              .append(" broken_source=").append(brokenSource)
              .append(" verify_failed=").append(verifyFailed)
              .append(" audit_mismatch=").append(auditMismatch)
              .append(" skipped=").append(skipped).append("\n");
        }
        if (failures.isEmpty()) {
            sb.append("failed records: none\n");
        } else {
            sb.append("failed records:\n");
            for (String f : failures) sb.append("  ").append(f).append("\n");
        }
        if (mode == Mode.EXECUTE && (!migratedFinal.isEmpty() || !migratedConsultant.isEmpty())) {
            sb.append("rollback SQL (reverts to Cloudinary reads via the Phase 1 dual-read branch):\n");
            if (!migratedFinal.isEmpty()) {
                sb.append("  UPDATE consultant_applications SET s3_key = NULL WHERE id IN (")
                  .append(join(migratedFinal)).append(");\n");
            }
            if (!migratedConsultant.isEmpty()) {
                sb.append("  UPDATE consultant_applications SET consultant_pdf_s3_key = NULL WHERE id IN (")
                  .append(join(migratedConsultant)).append(");\n");
            }
        }
        sb.append("====================================");
        log.info("{}{}", LOG, sb);
    }

    // ── Helpers ──────────────────────────────────────────────────────

    /**
     * Phase 1 key pattern, mirrored exactly, but stamped with the record's
     * ORIGINAL creation timestamp (deterministic ⇒ a partially-migrated row
     * re-uses the same key on re-run). Source of truth:
     * {@code AgreementDocumentService.buildAgreementPdfKey}.
     */
    private String buildKey(Candidate c) {
        String ermId = (c.ermId() == null || c.ermId().isBlank()) ? "system" : c.ermId();
        int phase = c.phase() == null ? 1 : c.phase();
        LocalDateTime ts = c.createdAt() == null ? LocalDateTime.now() : c.createdAt();
        return "agreements/" + ermId + "/phase-" + phase + "/"
                + c.type() + "-" + ts.format(KEY_TS) + ".pdf";
    }

    /** Authenticated Cloudinary fetch — reuses the app's exact signed-URL mechanism. */
    private byte[] downloadCloudinary(String publicId) throws Exception {
        String url = agreementDocumentService.signedPdfUrl(publicId, Duration.ofMinutes(5));
        if (url == null) throw new java.io.IOException("null signed URL for " + publicId);
        URLConnection conn = new URL(url).openConnection();
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(30_000);
        try (InputStream in = conn.getInputStream()) {
            return in.readAllBytes();
        }
    }

    /** Lightweight reachability probe (ranged GET) for dry-run — never logs the URL. */
    private boolean reachable(Candidate c) {
        try {
            String url = agreementDocumentService.signedPdfUrl(c.cloudinaryPublicId(), Duration.ofMinutes(5));
            if (url == null) return false;
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Range", "bytes=0-0");
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(15_000);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code >= 200 && code < 300;
        } catch (Exception e) {
            return false;
        }
    }

    private void putToS3(String key, byte[] bytes, String sourceId) {
        Map<String, String> metadata = Map.of(
                "migrated-from", "cloudinary",
                "source-id", sourceId,
                "migrated-at", Instant.now().toString());
        s3Provider.getObject().putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType("application/pdf")
                        .metadata(metadata)
                        .build(),
                RequestBody.fromBytes(bytes));
    }

    private byte[] getFromS3(String key) {
        return s3Provider.getObject().getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
    }

    private void updateS3Key(Candidate c, String key) {
        txTemplate.executeWithoutResult(status -> {
            ConsultantApplication fresh = repo.findById(c.id())
                    .orElseThrow(() -> new IllegalStateException("record vanished id=" + c.id()));
            if ("final".equals(c.type())) {
                fresh.setS3Key(key);
            } else {
                fresh.setConsultantPdfS3Key(key);
            }
            repo.save(fresh);
        });
    }

    private static String join(List<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(ids.get(i));
        }
        return sb.toString();
    }
}
