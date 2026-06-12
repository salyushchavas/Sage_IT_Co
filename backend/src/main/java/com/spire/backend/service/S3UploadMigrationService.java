package com.spire.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Phase 5 — one-time, idempotent migration of existing Cloudinary-hosted
 * agreements CONSULTANT UPLOADS (cheque / multi-cheque / work-auth /
 * offer-letter / DL / SSN) and SIGNATURES (consultant primary + final, ERM)
 * to S3. INDEPENDENT of the Phase-1/2 generated-PDF migration
 * ({@link S3MigrationService}); gated by its own {@code S3_UPLOAD_MIGRATION_MODE}.
 *
 * <p>Nothing on Cloudinary is deleted. A record is only pointed at S3 after a
 * byte-level S3 round-trip SHA-256 verification. Each record (or multi-cheque
 * row) is updated in ITS OWN transaction. Reads stay safe throughout via the
 * Phase-5 dual-read (a row with no {@code *_s3_key} is still served from
 * Cloudinary). Idempotent: a doc/signature drops out of its candidate set once
 * its {@code *_s3_key} is set; a multi-cheque entry once its JSON gains an
 * {@code s3Key}.
 *
 * <p>Uploads consultant docs are fetched via the authenticated re-signed
 * Cloudinary URL (by public_id); signatures via their stored secure_url
 * directly (type=image, public). Signature rows whose stored value is a
 * {@code data:} URL (the legacy simple-submit flow) are skipped — they are not
 * Cloudinary objects.
 */
@Service
@Slf4j
public class S3UploadMigrationService {

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

    private static final String LOG = "[S3-UPLOAD-MIGRATION]";
    private static final DateTimeFormatter KEY_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final ConsultantApplicationRepository repo;
    private final com.cloudinary.Cloudinary cloudinary;
    private final ObjectProvider<S3Client> s3Provider;
    private final TransactionTemplate txTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String bucket;

    public S3UploadMigrationService(ConsultantApplicationRepository repo,
                                    com.cloudinary.Cloudinary cloudinary,
                                    ObjectProvider<S3Client> s3Provider,
                                    PlatformTransactionManager txManager,
                                    @Value("${aws.s3.bucket:}") String bucket) {
        this.repo = repo;
        this.cloudinary = cloudinary;
        this.s3Provider = s3Provider;
        this.txTemplate = new TransactionTemplate(txManager);
        this.bucket = bucket;
    }

    /** Running totals + failure/rollback bookkeeping for one run. */
    private static final class Stats {
        int candidates, reachable, migrated, brokenSource, verifyFailed, skipped;
        final List<String> failures = new ArrayList<>();
        // column → ids migrated this run (for the per-column rollback SQL).
        final Map<String, List<Long>> rollback = new LinkedHashMap<>();
        boolean multiChequeMigrated = false;

        void rollbackAdd(String column, long id) {
            rollback.computeIfAbsent(column, k -> new ArrayList<>()).add(id);
        }
    }

    public void run(Mode mode) {
        log.info("{} ==== S3 UPLOAD MIGRATION START (mode={}) ====", LOG, mode);
        Stats s = new Stats();

        // ── Consultant document uploads (fetched by authenticated public_id) ──
        migrateDocs(mode, s, repo.findByChequeS3KeyIsNullAndChequePublicIdIsNotNull(),
                "cheque", "cheque_s3_key",
                ConsultantApplication::getChequePublicId, ConsultantApplication::getChequeContentType,
                ConsultantApplication::setChequeS3Key);
        migrateDocs(mode, s, repo.findByWorkAuthDocS3KeyIsNullAndWorkAuthDocPublicIdIsNotNull(),
                "workauth", "work_auth_doc_s3_key",
                ConsultantApplication::getWorkAuthDocPublicId, ConsultantApplication::getWorkAuthDocContentType,
                ConsultantApplication::setWorkAuthDocS3Key);
        migrateDocs(mode, s, repo.findByOfferLetterS3KeyIsNullAndOfferLetterPublicIdIsNotNull(),
                "offerletter", "offer_letter_s3_key",
                ConsultantApplication::getOfferLetterPublicId, ConsultantApplication::getOfferLetterContentType,
                ConsultantApplication::setOfferLetterS3Key);
        migrateDocs(mode, s, repo.findByDlDocS3KeyIsNullAndDlDocPublicIdIsNotNull(),
                "dldoc", "dl_doc_s3_key",
                ConsultantApplication::getDlDocPublicId, ConsultantApplication::getDlDocContentType,
                ConsultantApplication::setDlDocS3Key);
        migrateDocs(mode, s, repo.findBySsnDocS3KeyIsNullAndSsnDocPublicIdIsNotNull(),
                "ssndoc", "ssn_doc_s3_key",
                ConsultantApplication::getSsnDocPublicId, ConsultantApplication::getSsnDocContentType,
                ConsultantApplication::setSsnDocS3Key);

        // ── Signatures (fetched by their stored secure_url; skip data: URLs) ──
        migrateSignatures(mode, s, repo.findBySignatureS3KeyIsNullAndSignatureImageIsNotNull(),
                "consultant", "signature_s3_key",
                ConsultantApplication::getSignatureImage, ConsultantApplication::setSignatureS3Key);
        migrateSignatures(mode, s, repo.findByFinalSignatureS3KeyIsNullAndFinalSignatureImageIsNotNull(),
                "consultant-final", "final_signature_s3_key",
                ConsultantApplication::getFinalSignatureImage, ConsultantApplication::setFinalSignatureS3Key);
        migrateSignatures(mode, s, repo.findByErmSignatureS3KeyIsNullAndErmSignatureUrlIsNotNull(),
                "erm", "erm_signature_s3_key",
                ConsultantApplication::getErmSignatureUrl, ConsultantApplication::setErmSignatureS3Key);

        // ── Multi-cheque JSON entries ──
        migrateChequeEntries(mode, s);

        report(mode, s);
    }

    // ── Per-artifact loops ───────────────────────────────────────────

    private void migrateDocs(Mode mode, Stats s, List<ConsultantApplication> rows,
                             String docType, String column,
                             Function<ConsultantApplication, String> getPublicId,
                             Function<ConsultantApplication, String> getContentType,
                             BiConsumer<ConsultantApplication, String> setS3Key) {
        for (ConsultantApplication row : rows) {
            long id = row.getId();
            String publicId = getPublicId.apply(row);
            String contentType = getContentType.apply(row);
            s.candidates++;
            byte[] bytes;
            try {
                bytes = authBytes(publicId, contentType);
            } catch (Exception e) {
                s.brokenSource++;
                s.failures.add(fail(id, docType, "BROKEN_SOURCE"));
                log.warn("{} BROKEN_SOURCE id={} type={} ({})", LOG, id, docType, e.getMessage());
                continue;
            }
            if (mode == Mode.DRY_RUN) {
                s.reachable++;
                log.info("{} REACHABLE id={} type={}", LOG, id, docType);
                continue;
            }
            String key = uploadKey(row, docType, contentType);
            if (!storeVerified(id, docType, key, contentType, bytes, publicId, s)) continue;
            if (!applyInTx(id, fresh -> setS3Key.accept(fresh, key), id2 ->
                    s.failures.add(fail(id2, docType, "DB_UPDATE_FAILED")))) {
                s.skipped++;
                continue;
            }
            s.migrated++;
            s.rollbackAdd(column, id);
            log.info("{} MIGRATED id={} type={} key={}", LOG, id, docType, key);
        }
    }

    private void migrateSignatures(Mode mode, Stats s, List<ConsultantApplication> rows,
                                   String role, String column,
                                   Function<ConsultantApplication, String> getUrl,
                                   BiConsumer<ConsultantApplication, String> setS3Key) {
        for (ConsultantApplication row : rows) {
            long id = row.getId();
            String url = getUrl.apply(row);
            // Legacy simple-submit stores a data: URL in signature_image — not a
            // Cloudinary object. Skip silently (don't count as a candidate).
            if (url == null || !url.startsWith("http")) continue;
            s.candidates++;
            byte[] bytes;
            try {
                bytes = httpBytes(url);
            } catch (Exception e) {
                s.brokenSource++;
                s.failures.add(fail(id, "sig:" + role, "BROKEN_SOURCE"));
                log.warn("{} BROKEN_SOURCE id={} sig={} ({})", LOG, id, role, e.getMessage());
                continue;
            }
            if (mode == Mode.DRY_RUN) {
                s.reachable++;
                log.info("{} REACHABLE id={} sig={}", LOG, id, role);
                continue;
            }
            String key = signatureKey(row, role);
            // Stored bytes keep their image content-type; the PDF render
            // re-normalises to PNG on read, so this is informational only.
            if (!storeVerified(id, "sig:" + role, key, "image/png", bytes, "signature/" + role, s)) continue;
            if (!applyInTx(id, fresh -> setS3Key.accept(fresh, key), id2 ->
                    s.failures.add(fail(id2, "sig:" + role, "DB_UPDATE_FAILED")))) {
                s.skipped++;
                continue;
            }
            s.migrated++;
            s.rollbackAdd(column, id);
            log.info("{} MIGRATED id={} sig={} key={}", LOG, id, role, key);
        }
    }

    private void migrateChequeEntries(Mode mode, Stats s) {
        for (ConsultantApplication row : repo.findByChequesIsNotNull()) {
            long id = row.getId();
            String json = row.getCheques();
            if (json == null || json.isBlank()) continue;
            ArrayNode arr;
            try {
                JsonNode node = objectMapper.readTree(json);
                if (!node.isArray()) continue;
                arr = (ArrayNode) node;
            } catch (Exception e) {
                log.warn("{} cheques JSON parse failed id={} ({})", LOG, id, e.toString());
                continue;
            }
            int storedThisRow = 0;
            boolean changed = false;
            for (JsonNode el : arr) {
                if (!el.isObject()) continue;
                ObjectNode entry = (ObjectNode) el;
                String publicId = entry.path("publicId").asText("");
                String existingS3 = entry.path("s3Key").asText("");
                if (publicId.isBlank() || !existingS3.isBlank()) continue; // already migrated / nothing to do
                int index = entry.path("index").asInt(0);
                String contentType = entry.path("contentType").asText("");
                String label = "cheque-" + index;
                s.candidates++;
                byte[] bytes;
                try {
                    bytes = authBytes(publicId, contentType);
                } catch (Exception e) {
                    s.brokenSource++;
                    s.failures.add(fail(id, label, "BROKEN_SOURCE"));
                    log.warn("{} BROKEN_SOURCE id={} type={} ({})", LOG, id, label, e.getMessage());
                    continue;
                }
                if (mode == Mode.DRY_RUN) {
                    s.reachable++;
                    log.info("{} REACHABLE id={} type={}", LOG, id, label);
                    continue;
                }
                String key = uploadKey(row, label, contentType);
                if (!storeVerified(id, label, key, contentType, bytes, publicId, s)) continue;
                entry.put("s3Key", key);
                changed = true;
                storedThisRow++;
            }
            if (changed && mode == Mode.EXECUTE) {
                final String newJson;
                try {
                    newJson = objectMapper.writeValueAsString(arr);
                } catch (Exception e) {
                    s.skipped += storedThisRow;
                    s.failures.add(fail(id, "cheques", "JSON_WRITE_FAILED"));
                    continue;
                }
                if (applyInTx(id, fresh -> fresh.setCheques(newJson), id2 ->
                        s.failures.add(fail(id2, "cheques", "DB_UPDATE_FAILED")))) {
                    s.migrated += storedThisRow;
                    s.multiChequeMigrated = true;
                    log.info("{} MIGRATED id={} cheques entries={}", LOG, id, storedThisRow);
                } else {
                    s.skipped += storedThisRow;
                }
            }
        }
    }

    // ── S3 + Cloudinary primitives ───────────────────────────────────

    /** Store + S3 round-trip SHA-256 verify. Bumps verifyFailed/skipped + failures on failure. */
    private boolean storeVerified(long id, String label, String key, String contentType,
                                  byte[] bytes, String sourceRef, Stats s) {
        String srcHash = ConsultantVersionService.sha256Hex(bytes);
        try {
            putToS3(key, bytes, contentType, sourceRef);
        } catch (Exception e) {
            s.skipped++;
            s.failures.add(fail(id, label, "S3_UPLOAD_FAILED"));
            log.warn("{} S3_UPLOAD_FAILED id={} type={} ({})", LOG, id, label, e.toString());
            return false;
        }
        byte[] back;
        try {
            back = getFromS3(key);
        } catch (Exception e) {
            s.skipped++;
            s.failures.add(fail(id, label, "S3_READBACK_FAILED"));
            log.warn("{} S3_READBACK_FAILED id={} type={} ({})", LOG, id, label, e.toString());
            return false;
        }
        if (!srcHash.equalsIgnoreCase(ConsultantVersionService.sha256Hex(back))) {
            s.verifyFailed++;
            s.failures.add(fail(id, label, "VERIFY_FAILED"));
            log.warn("{} VERIFY_FAILED id={} type={} (S3 round-trip hash mismatch)", LOG, id, label);
            return false;
        }
        return true;
    }

    private void putToS3(String key, byte[] bytes, String contentType, String sourceRef) {
        Map<String, String> metadata = Map.of(
                "migrated-from", "cloudinary",
                "source-id", sourceRef == null ? "" : sourceRef,
                "migrated-at", Instant.now().toString());
        s3Provider.getObject().putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(contentType == null || contentType.isBlank()
                                ? "application/octet-stream" : contentType)
                        .metadata(metadata)
                        .build(),
                RequestBody.fromBytes(bytes));
    }

    private byte[] getFromS3(String key) {
        return s3Provider.getObject().getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
    }

    /** Authenticated Cloudinary fetch of an upload by public_id (re-signed each call). */
    private byte[] authBytes(String publicId, String contentType) throws Exception {
        boolean isPdf = "application/pdf".equalsIgnoreCase(contentType);
        String url = cloudinary.url()
                .resourceType(isPdf ? "raw" : "image")
                .type("authenticated")
                .signed(true)
                .secure(true)
                .generate(publicId);
        return httpBytes(url);
    }

    private byte[] httpBytes(String url) throws Exception {
        java.net.HttpURLConnection conn =
                (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(30_000);
        try (java.io.InputStream in = conn.getInputStream()) {
            return in.readAllBytes();
        } catch (java.io.IOException e) {
            int code = -1;
            try { code = conn.getResponseCode(); } catch (Exception ignore) { /* no status */ }
            // Sanitized: HTTP status only. The original cause embeds the signed
            // Cloudinary URL, so BROKEN_SOURCE handlers log getMessage(), never
            // the throwable (mirrors S3MigrationService.downloadCloudinary).
            throw new java.io.IOException("Cloudinary GET failed: HTTP " + code, e);
        } finally {
            conn.disconnect();
        }
    }

    /** Run {@code mutator} against a freshly-loaded row in its own transaction. */
    private boolean applyInTx(long id, java.util.function.Consumer<ConsultantApplication> mutator,
                              java.util.function.LongConsumer onFail) {
        try {
            txTemplate.executeWithoutResult(st -> {
                ConsultantApplication fresh = repo.findById(id)
                        .orElseThrow(() -> new IllegalStateException("record vanished id=" + id));
                mutator.accept(fresh);
                repo.save(fresh);
            });
            return true;
        } catch (Exception e) {
            onFail.accept(id);
            log.warn("{} DB_UPDATE_FAILED id={} ({})", LOG, id, e.toString());
            return false;
        }
    }

    // ── Key builders (mirror the live upload/signature key patterns) ──

    private static String ermSegment(ConsultantApplication app) {
        String erm = app.getOwnerErmId();
        return (erm == null || erm.isBlank()) ? "system" : erm;
    }

    /** Per-application key segment — see ConsultantApplicationService.appSegment (collision safety). */
    private static String appSegment(ConsultantApplication app) {
        if (app.getId() != null) return String.valueOf(app.getId());
        String aid = app.getApplicationId();
        return (aid == null || aid.isBlank()) ? "app" : aid;
    }

    private static String rand() {
        return Long.toHexString(
                java.util.concurrent.ThreadLocalRandom.current().nextLong() & 0xffffffffL);
    }

    private String uploadKey(ConsultantApplication app, String docType, String contentType) {
        return "agreements/" + ermSegment(app) + "/uploads/" + appSegment(app) + "-" + docType + "-"
                + LocalDateTime.now().format(KEY_TS) + "-" + rand() + "." + extFor(contentType);
    }

    private String signatureKey(ConsultantApplication app, String role) {
        return "agreements/" + ermSegment(app) + "/signatures/" + appSegment(app) + "-" + role + "-"
                + LocalDateTime.now().format(KEY_TS) + "-" + rand() + ".png";
    }

    private static String extFor(String contentType) {
        if (contentType == null) return "bin";
        String t = contentType.toLowerCase();
        if (t.equals("application/pdf")) return "pdf";
        if (t.equals("image/jpeg") || t.equals("image/jpg")) return "jpg";
        if (t.equals("image/png")) return "png";
        if (t.equals("image/heic") || t.equals("image/heif")) return "heic";
        if (t.startsWith("image/")) {
            String sub = t.substring("image/".length()).replaceAll("[^a-z0-9]", "");
            return sub.isEmpty() ? "img" : sub;
        }
        return "bin";
    }

    private static String fail(long id, String type, String reason) {
        return "id=" + id + " type=" + type + " reason=" + reason;
    }

    // ── Report ───────────────────────────────────────────────────────

    private void report(Mode mode, Stats s) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n==== S3 UPLOAD MIGRATION REPORT (").append(mode).append(") ====\n");
        sb.append("candidates=").append(s.candidates).append("\n");
        if (mode == Mode.DRY_RUN) {
            sb.append("reachable=").append(s.reachable)
              .append(" broken_source=").append(s.brokenSource).append("\n");
        } else {
            sb.append("migrated=").append(s.migrated)
              .append(" broken_source=").append(s.brokenSource)
              .append(" verify_failed=").append(s.verifyFailed)
              .append(" audit_mismatch=0")   // no document_hash on uploads/signatures
              .append(" skipped=").append(s.skipped).append("\n");
        }
        if (s.failures.isEmpty()) {
            sb.append("failed records: none\n");
        } else {
            sb.append("failed records:\n");
            for (String f : s.failures) sb.append("  ").append(f).append("\n");
        }
        if (mode == Mode.EXECUTE && !s.rollback.isEmpty()) {
            sb.append("rollback SQL (reverts to Cloudinary reads via the Phase-5 dual-read):\n");
            for (Map.Entry<String, List<Long>> e : s.rollback.entrySet()) {
                sb.append("  UPDATE consultant_applications SET ").append(e.getKey())
                  .append(" = NULL WHERE id IN (").append(join(e.getValue())).append(");\n");
            }
        }
        if (s.multiChequeMigrated) {
            sb.append("note: multi-cheque entries store their S3 key inside the `cheques` "
                    + "JSON; rollback for those requires restoring the prior JSON (from a "
                    + "backup) — there is no single-column NULL revert.\n");
        }
        sb.append("====================================");
        log.info("{}{}", LOG, sb);
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
