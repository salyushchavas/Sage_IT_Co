package com.spire.backend.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Map;

/**
 * Storage for participant files: documents, check copies, offer letters and
 * signed agreements.
 *
 * Where new files go (production-readiness review, 25 Sep):
 * <ol>
 *   <li>Amazon S3, when {@code aws.region} and {@code aws.s3.bucket} are set —
 *       the same private, encrypted bucket the consultant console uses.
 *       Stored as {@code s3:participant-documents/<userId>/<name>}.</li>
 *   <li>Cloudinary, when {@code cloudinary.cloud-name} is set.</li>
 *   <li>The server's own disk otherwise. Fine on a laptop; on Railway the
 *       disk is wiped at every deploy, so a warning is logged and
 *       /api/health shows {@code "storage": "local-disk"}.</li>
 * </ol>
 * Files are read back with {@link #retrieve}, whatever storage they were
 * saved to (older files stay where they are).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentStorageService {

    /** Cloudinary signed URL TTL — 5 minutes per PRD §13.1. */
    private static final int SIGNED_URL_TTL_SECONDS = 300;
    private static final String LOCAL_DIR = "participant-documents";
    public static final String S3_PREFIX = "s3:";

    private final Cloudinary cloudinary;
    private final DocumentStorage s3;

    @Value("${cloudinary.cloud-name:}")
    private String cloudinaryCloudName;

    @Value("${aws.s3.bucket:}")
    private String s3Bucket;

    @Value("${aws.region:}")
    private String s3Region;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /** A file handed back to a viewer: a short-lived link (Cloudinary), or the bytes. */
    public record Retrieval(String url, byte[] bytes, String contentType) {}

    /**
     * Tuple returned from {@link #upload}: the value to persist on the
     * row's file-URL column, plus the storage handle used to delete it later.
     */
    public record StoredFile(String url, String storagePath) {}

    @PostConstruct
    void warnIfFilesWontSurviveADeploy() {
        if ("local-disk".equals(mode()) && System.getenv("RAILWAY_ENVIRONMENT_NAME") != null) {
            log.warn("PARTICIPANT FILES ARE BEING SAVED ON THE SERVER'S DISK, WHICH RAILWAY WIPES AT EVERY DEPLOY. "
                    + "Set AWS_REGION + S3_BUCKET + AWS_ACCESS_KEY_ID + AWS_SECRET_ACCESS_KEY (or Cloudinary) on Railway.");
        }
    }

    /** Where new files go: "s3", "cloudinary" or "local-disk". */
    public String mode() {
        if (isS3Configured()) return "s3";
        if (isCloudinaryConfigured()) return "cloudinary";
        return "local-disk";
    }

    public boolean isS3Configured() {
        return s3Bucket != null && !s3Bucket.isBlank() && s3Region != null && !s3Region.isBlank();
    }

    public boolean isCloudinaryConfigured() {
        return cloudinaryCloudName != null && !cloudinaryCloudName.isBlank();
    }

    /**
     * Saves {@code content} for the given user. Throws on storage failure —
     * the caller shows an error and does NOT save the row.
     */
    public StoredFile upload(Long userId, String filename, byte[] content, String contentType) {
        if (isS3Configured()) return uploadToS3(userId, filename, content, contentType);
        if (isCloudinaryConfigured()) return uploadToCloudinary(userId, filename, content);
        return uploadToDisk(userId, filename, content);
    }

    /**
     * Reads a stored file back for a signed-in viewer: the bytes (S3, disk)
     * or a short-lived link (Cloudinary). Null when the file isn't there.
     */
    public Retrieval retrieve(String stored) {
        if (stored == null || stored.isBlank()) return null;
        if (stored.startsWith(S3_PREFIX)) {
            byte[] bytes = s3.get(stored.substring(S3_PREFIX.length()));
            return bytes == null ? null : new Retrieval(null, bytes, contentTypeOf(bytes, stored));
        }
        if (stored.startsWith("http")) {
            return new Retrieval(signedUrl(stored), null, null);
        }
        File file = localFile(stored);
        if (file == null || !file.isFile()) return null;
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            return new Retrieval(null, bytes, contentTypeOf(bytes, stored));
        } catch (Exception e) {
            log.warn("Couldn't read stored file {}: {}", stored, e.getMessage());
            return null;
        }
    }

    /** The file's bytes, wherever it's stored (e.g. to email or merge it). Null when missing. */
    public byte[] readBytes(String stored) {
        Retrieval r = retrieve(stored);
        if (r == null) return null;
        if (r.bytes() != null) return r.bytes();
        try {
            HttpResponse<byte[]> res = http.send(HttpRequest.newBuilder(URI.create(r.url()))
                    .timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            return res.statusCode() / 100 == 2 ? res.body() : null;
        } catch (Exception e) {
            log.warn("Couldn't download stored file: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Returns a viewer-facing URL for a previously-stored Cloudinary URL,
     * signed. Other storage is read with {@link #retrieve}.
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
     * Removes a stored file. Takes either the stored URL or the storage
     * handle (for Cloudinary that's the public id, which used to be
     * mistaken for a local path, so Cloudinary files were never removed).
     * Best-effort — a failure here doesn't block the caller.
     */
    public void delete(String stored) {
        if (stored == null || stored.isBlank()) return;
        try {
            if (stored.startsWith(S3_PREFIX)) {
                s3.delete(stored.substring(S3_PREFIX.length()));
            } else if (stored.startsWith("http") || stored.startsWith("spire/documents/")) {
                if (!isCloudinaryConfigured()) return;
                String publicId = stored.startsWith("http") ? extractPublicId(stored) : stored;
                if (publicId != null) {
                    cloudinary.uploader().destroy(publicId, ObjectUtils.asMap("resource_type", "raw", "type", "authenticated"));
                }
            } else {
                File f = localFile(stored);
                if (f != null && f.exists() && !f.delete()) log.debug("delete() returned false for {}", stored);
            }
        } catch (Exception e) {
            log.warn("Couldn't delete stored file {}: {}", stored, e.getMessage());
        }
    }

    /**
     * What a file really is, from its first bytes: application/pdf,
     * image/png or image/jpeg; null for anything else. Uploads are checked
     * with this, since the browser's label can be anything.
     */
    public static String sniffContentType(byte[] bytes) {
        if (bytes == null || bytes.length < 4) return null;
        if (bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F') return "application/pdf";
        if ((bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') return "image/png";
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) return "image/jpeg";
        return null;
    }

    // ── Internals ─────────────────────────────────────────────────

    private StoredFile uploadToS3(Long userId, String filename, byte[] content, String contentType) {
        String key = LOCAL_DIR + "/" + userId + "/" + safeStem(filename) + "-" + System.currentTimeMillis()
                + extensionOf(filename);
        String type = sniffContentType(content);
        s3.store(content, key, type != null ? type : (contentType == null ? "application/octet-stream" : contentType));
        return new StoredFile(S3_PREFIX + key, S3_PREFIX + key);
    }

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

    /** A file under the local documents folder (and nowhere else). */
    private static File localFile(String stored) {
        if (stored.contains("..") || stored.contains("\\")) return null;
        if (!stored.startsWith(LOCAL_DIR + "/") && !stored.startsWith("signed-agreements/")) return null;
        return new File(stored);
    }

    private static String contentTypeOf(byte[] bytes, String name) {
        String sniffed = sniffContentType(bytes);
        if (sniffed != null) return sniffed;
        String lower = name.toLowerCase();
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        return "application/octet-stream";
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
        String safe = stem.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.length() > 80 ? safe.substring(0, 80) : safe;
    }

    /** ".pdf" etc.; only letters and digits, so a name can't smuggle a path. */
    private static String extensionOf(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        if (dot < 0) return "";
        String ext = filename.substring(dot + 1).replaceAll("[^A-Za-z0-9]", "");
        return ext.isEmpty() || ext.length() > 10 ? "" : "." + ext.toLowerCase();
    }
}
