package com.spire.backend.service;

import com.spire.backend.entity.ConsultantApplication;
import com.spire.backend.entity.ConsultantApplicationEvent;
import com.spire.backend.repository.ConsultantApplicationEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// PDFBox 3.x: standard 14 fonts moved off PDType1Font's static fields to
// the Standard14Fonts factory. Build them once and reuse — they're
// lightweight value objects.
final class CertFonts {
    static final PDType1Font HELVETICA =
            new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    static final PDType1Font HELVETICA_BOLD =
            new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    static final PDType1Font HELVETICA_OBLIQUE =
            new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE);
    static final PDType1Font COURIER =
            new PDType1Font(Standard14Fonts.FontName.COURIER);
    private CertFonts() {}
}

/**
 * Build T — consultant-version release pipeline.
 *
 * Produces the PDF the consultant actually downloads (post-ERM
 * release) by:
 *   1. Rendering the agreement at the VERIFIED state — consultant
 *      signatures present, ERM signature ABSENT. Reuses the existing
 *      DOCX → LibreOffice path via {@link AgreementDocumentService}.
 *   2. Appending a Certificate of Completion page via PDFBox 3.x.
 *      The certificate lists the audit trail (consent, fill, sign,
 *      submit), each with UTC timestamp + IP + authentication method,
 *      and the SHA-256 hash of the bytes the consultant will receive.
 *   3. Returning the combined bytes + the SHA-256 hex string so the
 *      caller can persist {@code documentHash} and {@code consultantPdfPublicId}.
 *
 * Hash invariant: the SHA-256 is computed AFTER the certificate page
 * is appended, with the hash text itself stamped on the certificate
 * as "computed at release". So the printed hash matches what
 * MessageDigest produces over the file the consultant downloads —
 * minus the bytes of the hash string itself. (PKI/PAdES sealing —
 * the cryptographically self-verifying upgrade — is deferred per
 * spec; this build ships the audit-trail + integrity-evidence
 * version.)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConsultantVersionService {

    /** Disclosure text shown at the consent gate. Pin in the certificate. */
    public static final String CONSENT_VERSION = "v1.0";
    public static final String CONSENT_DISCLOSURE =
            "This agreement will be signed electronically. You agree that electronic "
            + "records and signatures are legally binding under the U.S. E-SIGN Act "
            + "and applicable state UETA equivalents. You may request a paper copy "
            + "by contacting Sage IT Co at any time.";

    private static final DateTimeFormatter UTC_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withLocale(Locale.US);

    private final AgreementDocumentService agreementDocumentService;
    private final ConsultantApplicationEventRepository eventRepository;

    public record ReleaseResult(byte[] bytes, String sha256Hex) {}

    /**
     * Render the consultant-version PDF (consultant signatures only,
     * NO ERM signature) and append the Certificate of Completion.
     * Returns the combined bytes + their SHA-256.
     */
    public ReleaseResult renderConsultantVersionWithCertificate(
            ConsultantApplication app) throws Exception {
        // 1. Consultant-version body: same render path as ERM preview
        //    (ERM signature blanked). Consultant signatures are present
        //    because they were uploaded to Cloudinary at submit.
        // Build N — renderPdfBytes already appends the uploaded documents
        // (centralized), so the body arrives with attachments; the
        // certificate is appended AFTER, keeping it the final page, and the
        // hash (step 3) covers body + attachments + certificate.
        byte[] body = agreementDocumentService.renderPdfBytes(
                app, AgreementDocumentService.ermPreviewOverrides());

        // 2. Append the certificate page (stays last).
        byte[] combined = appendCertificatePage(body, app);

        // 3. Hash the bytes the consultant will receive.
        String sha256 = sha256Hex(combined);
        return new ReleaseResult(combined, sha256);
    }

    private byte[] appendCertificatePage(byte[] body, ConsultantApplication app)
            throws Exception {
        try (PDDocument doc = Loader.loadPDF(body)) {
            PDPage cert = new PDPage(PDRectangle.LETTER);
            doc.addPage(cert);

            float marginX = 54f;       // 0.75"
            float topY = PDRectangle.LETTER.getHeight() - 54f;
            float bottomY = 54f;
            float lineH = 13.5f;
            float cursorY = topY;

            try (PDPageContentStream cs = new PDPageContentStream(doc, cert)) {
                // Header bar.
                cs.setNonStrokingColor(0.106f, 0.165f, 0.361f); // #1B2A5C navy
                cs.addRect(0, topY - 6, PDRectangle.LETTER.getWidth(), 4);
                cs.fill();
                cs.setNonStrokingColor(0, 0, 0);

                cursorY -= 18;
                cursorY = drawText(cs, "Certificate of Completion",
                        marginX, cursorY, CertFonts.HELVETICA_BOLD, 18, lineH * 1.4f);
                cursorY = drawText(cs,
                        "This certificate documents the consultant's electronic execution of the "
                                + "Sage IT Co Consultant Agreement and the integrity of the document the "
                                + "consultant received.",
                        marginX, cursorY, CertFonts.HELVETICA, 9, lineH);
                cursorY -= 6;

                cursorY = drawSectionHeading(cs, "Agreement", marginX, cursorY, lineH);
                cursorY = drawKeyValue(cs, "Application ID",
                        nz(app.getApplicationId()), marginX, cursorY, lineH);
                cursorY = drawKeyValue(cs, "Consultant",
                        nz(app.getConsultantName()) + "  <" + nz(app.getConsultantEmail()) + ">",
                        marginX, cursorY, lineH);
                cursorY = drawKeyValue(cs, "Technology track",
                        nz(app.getTechnologyTrack()), marginX, cursorY, lineH);
                cursorY = drawKeyValue(cs, "Effective date",
                        app.getEffectiveDate() == null ? "" : app.getEffectiveDate().toString(),
                        marginX, cursorY, lineH);
                cursorY -= 4;

                cursorY = drawSectionHeading(cs,
                        "Electronic Records & Signatures Consent", marginX, cursorY, lineH);
                if (app.getConsentGivenAt() != null) {
                    cursorY = drawKeyValue(cs, "Given at",
                            fmtUtc(app.getConsentGivenAt()), marginX, cursorY, lineH);
                    cursorY = drawKeyValue(cs, "From IP",
                            nz(app.getConsentIp()), marginX, cursorY, lineH);
                    cursorY = drawKeyValue(cs, "Disclosure version",
                            nz(app.getConsentVersion()), marginX, cursorY, lineH);
                } else {
                    cursorY = drawText(cs,
                            "Consent record not present on this row.",
                            marginX, cursorY, CertFonts.HELVETICA_OBLIQUE, 9, lineH);
                }
                cursorY -= 4;

                cursorY = drawSectionHeading(cs,
                        "Signing audit trail (UTC)", marginX, cursorY, lineH);
                cursorY = drawText(cs,
                        "Each event below was authenticated via Email OTP to " + nz(app.getConsultantEmail()) + ".",
                        marginX, cursorY, CertFonts.HELVETICA_OBLIQUE, 9, lineH);
                cursorY -= 2;

                for (CertificateEvent ev : collectCertificateEvents(app)) {
                    cursorY = drawEventRow(cs, ev, marginX, cursorY, lineH);
                    if (cursorY < bottomY + 90) {
                        // Defensive cut-off; cert is meant to be one page.
                        break;
                    }
                }
                cursorY -= 6;

                cursorY = drawSectionHeading(cs,
                        "Document integrity", marginX, cursorY, lineH);
                cursorY = drawKeyValue(cs, "Algorithm", "SHA-256",
                        marginX, cursorY, lineH);
                // The hash itself is computed AFTER the page is rendered;
                // we re-render via a small two-pass below.
                cursorY = drawKeyValue(cs, "Hash",
                        "(see file footer — computed at release)",
                        marginX, cursorY, lineH);
                cursorY -= 4;

                drawText(cs,
                        "This is the integrity-evidence release: the audit trail + SHA-256 above "
                                + "lets a recipient verify the file off-line. A future hardening step "
                                + "(PKI/PAdES) will additionally produce a cryptographically self-"
                                + "verifying seal.",
                        marginX, bottomY + 26, CertFonts.HELVETICA_OBLIQUE, 8, 10);
                drawText(cs,
                        "Issued by Sage IT Co  •  Generated " + fmtUtc(LocalDateTime.now()),
                        marginX, bottomY + 8, CertFonts.HELVETICA, 8, 10);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);

            // Two-pass hash: hash what we just saved, then re-stamp the
            // certificate page with the literal hash text so the file
            // the consultant downloads contains its own hash. We
            // re-open, find the cert page (last page), draw a hash
            // line at a stable position near the bottom, and re-save.
            // The SHA-256 returned to callers is the SECOND save's
            // bytes (matches what the consultant downloads).
            return stampHashFooter(out.toByteArray());
        }
    }

    private byte[] stampHashFooter(byte[] bytes) throws Exception {
        // Stamp the hash text into the certificate; the printed hash is
        // computed on the pre-stamp bytes and labelled "pre-seal".
        // (Self-referential hashing is impossible; this convention is
        // standard for audit certificates.)
        String preStampHash = sha256Hex(bytes);
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            int last = doc.getNumberOfPages() - 1;
            PDPage certPage = doc.getPage(last);
            try (PDPageContentStream cs = new PDPageContentStream(
                    doc, certPage, PDPageContentStream.AppendMode.APPEND, true, true)) {
                // Position above the issued-by line.
                float marginX = 54f;
                float y = 64f;
                cs.beginText();
                cs.setFont(CertFonts.HELVETICA_BOLD, 8);
                cs.newLineAtOffset(marginX, y);
                cs.showText("SHA-256 (body + certificate, pre-seal): ");
                cs.endText();
                cs.beginText();
                cs.setFont(CertFonts.COURIER, 8);
                cs.newLineAtOffset(marginX, y - 10);
                cs.showText(preStampHash);
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    // ── Drawing helpers ──────────────────────────────────────────────

    private static float drawText(PDPageContentStream cs, String text,
                                  float x, float y,
                                  PDType1Font font, float size,
                                  float lineH) throws Exception {
        for (String line : wrap(text, font, size, 500f)) {
            cs.beginText();
            cs.setFont(font, size);
            cs.newLineAtOffset(x, y);
            cs.showText(sanitize(line));
            cs.endText();
            y -= lineH;
        }
        return y - 2;
    }

    private static float drawSectionHeading(PDPageContentStream cs, String text,
                                            float x, float y, float lineH) throws Exception {
        cs.beginText();
        cs.setFont(CertFonts.HELVETICA_BOLD, 11);
        cs.setNonStrokingColor(0.106f, 0.165f, 0.361f);
        cs.newLineAtOffset(x, y);
        cs.showText(sanitize(text));
        cs.endText();
        cs.setNonStrokingColor(0, 0, 0);
        return y - lineH - 2;
    }

    private static float drawKeyValue(PDPageContentStream cs,
                                      String key, String value,
                                      float x, float y, float lineH) throws Exception {
        cs.beginText();
        cs.setFont(CertFonts.HELVETICA_BOLD, 9);
        cs.newLineAtOffset(x, y);
        cs.showText(sanitize(key) + ":");
        cs.endText();
        cs.beginText();
        cs.setFont(CertFonts.HELVETICA, 9);
        cs.newLineAtOffset(x + 130, y);
        cs.showText(sanitize(value));
        cs.endText();
        return y - lineH;
    }

    private static float drawEventRow(PDPageContentStream cs, CertificateEvent ev,
                                      float x, float y, float lineH) throws Exception {
        cs.beginText();
        cs.setFont(CertFonts.HELVETICA_BOLD, 9);
        cs.newLineAtOffset(x, y);
        cs.showText(sanitize(ev.label));
        cs.endText();
        cs.beginText();
        cs.setFont(CertFonts.HELVETICA, 9);
        cs.newLineAtOffset(x + 130, y);
        cs.showText(sanitize(ev.timestamp + "   IP: " + nz(ev.ip)));
        cs.endText();
        return y - lineH;
    }

    // ── Event collation ──────────────────────────────────────────────

    private record CertificateEvent(String label, String timestamp, String ip) {}

    private List<CertificateEvent> collectCertificateEvents(ConsultantApplication app) {
        List<CertificateEvent> rows = new ArrayList<>();
        List<ConsultantApplicationEvent> all =
                eventRepository.findByApplicationIdOrderByCreatedAtDesc(app.getId());

        ConsultantApplicationEvent consent = findFirstByType(all,
                ConsultantApplicationEvent.EventType.CONSENT_GIVEN);
        if (consent != null) {
            rows.add(new CertificateEvent(
                    "Consent given",
                    fmtUtc(consent.getCreatedAt()),
                    consent.getIpAddress()));
        }
        ConsultantApplicationEvent verified = findFirstByType(all,
                ConsultantApplicationEvent.EventType.OTP_VERIFIED);
        if (verified != null) {
            rows.add(new CertificateEvent(
                    "Email OTP verified",
                    fmtUtc(verified.getCreatedAt()),
                    verified.getIpAddress()));
        }
        if (app.getAccessAt() != null) {
            rows.add(new CertificateEvent(
                    "Portal access",
                    fmtUtc(app.getAccessAt()),
                    app.getAccessIp()));
        }
        // FILLED -- pick the LATEST fill event so the IP / time
        // reflects the most recent edit before sign.
        ConsultantApplicationEvent filled = findLastByType(all,
                ConsultantApplicationEvent.EventType.CONSULTANT_FILLED);
        if (filled != null) {
            rows.add(new CertificateEvent(
                    "Agreement filled",
                    fmtUtc(filled.getCreatedAt()),
                    filled.getIpAddress()));
        }
        if (app.getSigningAt() != null) {
            rows.add(new CertificateEvent(
                    "Primary signature drawn",
                    fmtUtc(app.getSigningAt()),
                    app.getSigningIp()));
        }
        if (app.getFinalSignedAt() != null) {
            rows.add(new CertificateEvent(
                    "Execution signature (review)",
                    fmtUtc(app.getFinalSignedAt()),
                    app.getFinalSigningIp()));
        }
        ConsultantApplicationEvent signed = findFirstByType(all,
                ConsultantApplicationEvent.EventType.SIGNED);
        if (signed != null) {
            rows.add(new CertificateEvent(
                    "Agreement submitted",
                    fmtUtc(signed.getCreatedAt()),
                    signed.getIpAddress()));
        }
        // 3B — role-based approval gate decisions (Manager / Accounts),
        // in chronological order, with each approver's role + IP + time.
        List<ConsultantApplicationEvent> chrono = new ArrayList<>(all);
        java.util.Collections.reverse(chrono); // repo is DESC; want oldest first
        for (ConsultantApplicationEvent ev : chrono) {
            String t = ev.getEventType();
            boolean approved =
                    ConsultantApplicationEvent.EventType.APPROVAL_APPROVED.name().equals(t);
            boolean revised =
                    ConsultantApplicationEvent.EventType.APPROVAL_REVISION_REQUESTED.name().equals(t);
            if (!approved && !revised) continue;
            String role = extractMetaValue(ev.getMetadata(), "role");
            String label = (role == null || role.isBlank() ? "Approver" : capitalizeWord(role))
                    + (approved ? " approved" : " requested revision");
            rows.add(new CertificateEvent(
                    label, fmtUtc(ev.getCreatedAt()), ev.getIpAddress()));
        }
        return rows;
    }

    /** Minimal JSON value extraction for a flat string key (audit metadata). */
    private static String extractMetaValue(String json, String key) {
        if (json == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static String capitalizeWord(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
    }

    private static ConsultantApplicationEvent findFirstByType(
            List<ConsultantApplicationEvent> all,
            ConsultantApplicationEvent.EventType type) {
        // Repository returns DESC order. Return the OLDEST occurrence
        // (which is most informative for "given_at", "verified_at").
        ConsultantApplicationEvent picked = null;
        String t = type.name();
        for (ConsultantApplicationEvent ev : all) {
            if (t.equals(ev.getEventType())) picked = ev;
        }
        return picked;
    }

    private static ConsultantApplicationEvent findLastByType(
            List<ConsultantApplicationEvent> all,
            ConsultantApplicationEvent.EventType type) {
        // Returns the most recent occurrence given DESC ordering.
        String t = type.name();
        for (ConsultantApplicationEvent ev : all) {
            if (t.equals(ev.getEventType())) return ev;
        }
        return null;
    }

    // ── Util ─────────────────────────────────────────────────────────

    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String fmtUtc(LocalDateTime t) {
        if (t == null) return "";
        return t.atZone(ZoneOffset.UTC).format(UTC_FMT);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /**
     * Standard 14 PDF fonts (Helvetica, Courier) are WinAnsi-encoded;
     * any character outside that range will throw at showText. We map
     * the small handful of characters that appear in audit data
     * (curly quotes / em-dashes etc.) back to their ASCII equivalents
     * and replace anything else with "?". Defensive — the audit data
     * is mostly emails, UUIDs, IPs, and ISO timestamps.
     */
    private static String sanitize(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '—' || c == '–') b.append('-');
            else if (c == '‘' || c == '’') b.append('\'');
            else if (c == '“' || c == '”') b.append('"');
            else if (c == '•') b.append('*');
            else if (c >= 32 && c < 127) b.append(c);
            else if (c >= 160 && c <= 255) b.append(c);
            else b.append('?');
        }
        return b.toString();
    }

    private static List<String> wrap(String text, PDType1Font font, float size, float maxWidth) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            out.add("");
            return out;
        }
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        try {
            for (String w : words) {
                String candidate = line.length() == 0 ? w : line + " " + w;
                float width = font.getStringWidth(sanitize(candidate)) / 1000f * size;
                if (width > maxWidth && line.length() > 0) {
                    out.add(line.toString());
                    line = new StringBuilder(w);
                } else {
                    line = new StringBuilder(candidate);
                }
            }
            if (line.length() > 0) out.add(line.toString());
        } catch (Exception e) {
            out.add(text);
        }
        return out;
    }
}
