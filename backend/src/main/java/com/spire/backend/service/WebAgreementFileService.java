package com.spire.backend.service;

import com.spire.backend.entity.WebAgreement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/**
 * Files of a website agreement: the participant's uploads, the signature
 * images and the PDFs the website makes (verified versions, final PDFs).
 * Everything goes through the website's {@link DocumentStorageService} (S3
 * on live, the local disk on a laptop), under the participant's own folder
 * with a {@code web-agreement-} name, so nothing ever lands under the
 * console's {@code agreements/} prefix or goes through the console's
 * storage. The value it hands back is what the agreement's *S3Key columns
 * hold.
 *
 * Validation is the console's: 1 byte to 10 MB, a real picture
 * (jpeg/png/gif/webp/heic/heif, magic bytes checked) or a real PDF; SVG is
 * refused. Like the console, a replaced file is kept in storage.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebAgreementFileService {

    private static final DateTimeFormatter NAME_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final DocumentStorageService storage;
    /** HEIC → JPEG on the way out, so iPhone photos preview (stored bytes untouched). */
    private final HeicTranscoder heicTranscoder;

    /** A stored file's bytes and content type, for streaming. */
    public record StoredDoc(byte[] bytes, String contentType) {}

    /** A file ready to send to a browser: viewable bytes, their type, and a download name. */
    public record Download(byte[] bytes, String contentType, String filename) {}

    /**
     * The console's upload check; returns the normalised (lower-case) content
     * type. Messages: "{fileLabel} file is empty.", "{fileLabel} file is too
     * large (>10 MB).", "{typeLabel} must be an image (JPG/PNG/HEIC) or PDF."
     */
    public static String validate(byte[] bytes, String contentType, String fileLabel, String typeLabel) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException(fileLabel + " file is empty.");
        }
        if (bytes.length > WebAgreementRules.MAX_UPLOAD_BYTES) {
            throw new IllegalArgumentException(fileLabel + " file is too large (>10 MB).");
        }
        String normalisedType = contentType == null ? "" : contentType.toLowerCase();
        boolean isImage = WebAgreementRules.isSafeImage(normalisedType, bytes);
        boolean isPdf = normalisedType.equals("application/pdf") && WebAgreementRules.hasPdfSignature(bytes);
        if (!isImage && !isPdf) {
            throw new IllegalArgumentException(
                    typeLabel + " must be an image (JPG/PNG/HEIC) or PDF.");
        }
        return normalisedType;
    }

    /**
     * Stores an already-validated upload for the agreement's participant and
     * returns the value to keep on the row. {@code docType} names the file
     * ("workauth", "cheque-0", …).
     */
    public String storeUpload(WebAgreement agreement, String docType, byte[] bytes, String normalisedType) {
        String name = fileName(docType, WebAgreementRules.extFor(normalisedType));
        return storage.upload(agreement.getParticipantUserId(), name, bytes, normalisedType).url();
    }

    /**
     * Decodes a {@code data:image/...;base64,...} signature and stores it as a
     * PNG-named file ({@code role} = "consultant" or "consultant-final").
     * Returns the value to keep on the signature column.
     */
    public String storeSignature(WebAgreement agreement, String dataUrl, String role) throws IOException {
        if (dataUrl == null) {
            throw new IOException("Missing signature data URL.");
        }
        int comma = dataUrl.indexOf(',');
        if (comma < 0) {
            throw new IOException("Malformed data URL (no comma).");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(dataUrl.substring(comma + 1));
        } catch (IllegalArgumentException e) {
            throw new IOException("Malformed data URL (bad base64).", e);
        }
        String mime = "image/png";
        if (dataUrl.startsWith("data:")) {
            int semi = dataUrl.indexOf(';');
            int end = (semi >= 0 && semi < comma) ? semi : comma;
            if (end > 5) mime = dataUrl.substring(5, end);
        }
        return storage.upload(agreement.getParticipantUserId(), fileName(role, "png"), bytes, mime).url();
    }

    /**
     * Stores a PDF the website rendered (a verified version, a final PDF)
     * for the agreement's participant and returns the value to keep on the
     * row. {@code kind} names the file ("consultant-version-p1",
     * "final-p2", …), so it lands as
     * {@code web-agreement-{kind}-{yyyyMMdd-HHmmss}-{rand}.pdf}. Every call
     * is a new file; an earlier one is never overwritten or deleted. Storage
     * failures are thrown to the caller.
     */
    public String storePdf(WebAgreement agreement, String kind, byte[] bytes) {
        return storage.upload(agreement.getParticipantUserId(), fileName(kind, "pdf"), bytes,
                "application/pdf").url();
    }

    /**
     * A stored file's bytes with the type recorded at upload (falling back
     * to what storage sniffs). Null when nothing is stored or the file is
     * gone; storage outages surface as StorageUnavailableException (503).
     */
    public StoredDoc read(String stored, String contentType) {
        if (stored == null || stored.isBlank()) return null;
        DocumentStorageService.Retrieval r = storage.retrieve(stored);
        if (r == null || r.bytes() == null || r.bytes().length == 0) return null;
        String type = contentType == null || contentType.isBlank() ? r.contentType() : contentType;
        return new StoredDoc(r.bytes(), type);
    }

    /** The raw bytes of a stored file, or null (the renderer's attachments). */
    public byte[] readBytes(String stored) {
        if (stored == null || stored.isBlank()) return null;
        return storage.readBytes(stored);
    }

    /**
     * What to send to a browser: HEIC is transcoded to JPEG when a converter
     * is installed; otherwise (or on failure) the original bytes, as the
     * console does.
     */
    public StoredDoc forViewing(StoredDoc doc) {
        if (doc == null) return null;
        if (HeicTranscoder.isHeic(doc.contentType(), doc.bytes())) {
            byte[] jpeg = heicTranscoder.toJpeg(doc.bytes());
            if (jpeg != null) return new StoredDoc(jpeg, "image/jpeg");
        }
        return doc;
    }

    /**
     * A stored upload ready to stream (HEIC transcoded, octet-stream when the
     * type is unknown), named {@code filenameBase} + an extension matching
     * what is served. Null when nothing is stored.
     */
    public Download download(String stored, String contentType, String filenameBase) {
        StoredDoc doc = forViewing(read(stored, contentType));
        if (doc == null) return null;
        String type = doc.contentType() == null || doc.contentType().isBlank()
                ? "application/octet-stream" : doc.contentType();
        return new Download(doc.bytes(), type, filenameBase + extensionForContentType(type));
    }

    /** File extension matching what is actually served, so the Save dialog offers something openable. */
    public static String extensionForContentType(String contentType) {
        String ct = contentType == null
                ? "" : contentType.toLowerCase(java.util.Locale.ROOT).trim();
        if (ct.startsWith("application/pdf")) return ".pdf";
        if (ct.startsWith("image/jpeg") || ct.startsWith("image/jpg")) return ".jpg";
        if (ct.startsWith("image/png")) return ".png";
        if (ct.startsWith("image/gif")) return ".gif";
        if (ct.startsWith("image/webp")) return ".webp";
        if (ct.startsWith("image/tiff")) return ".tiff";
        if (ct.startsWith("image/heic") || ct.startsWith("image/heif")) return ".heic";
        if (ct.startsWith("image/")) return ".img";
        return "";
    }

    /**
     * A stored signature as a {@code data:image/...;base64,} URL for the
     * renderer, or null when there is none or it can't be read (the
     * signature then renders blank, never breaking the render).
     */
    public String signatureDataUrl(String stored) {
        if (stored == null || stored.isBlank()) return null;
        try {
            byte[] bytes = storage.readBytes(stored);
            if (bytes == null || bytes.length == 0) return null;
            String type = DocumentStorageService.sniffContentType(bytes);
            if (type == null || !type.startsWith("image/")) type = "image/png";
            return "data:" + type + ";base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) {
            log.warn("Couldn't read a web agreement signature: {}", e.getMessage());
            return null;
        }
    }

    /** web-agreement-{docType}-{yyyyMMdd-HHmmss}-{rand}.{ext} */
    private static String fileName(String docType, String ext) {
        String rand = Long.toHexString(
                java.util.concurrent.ThreadLocalRandom.current().nextLong() & 0xffffffffL);
        return "web-agreement-" + docType + "-" + LocalDateTime.now().format(NAME_TS) + "-" + rand + "." + ext;
    }
}
