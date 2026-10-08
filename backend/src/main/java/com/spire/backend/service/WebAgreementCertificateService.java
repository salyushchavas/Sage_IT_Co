package com.spire.backend.service;

import com.spire.backend.entity.WebAgreement;
import com.spire.backend.entity.WebAgreementEvent;
import com.spire.backend.repository.WebAgreementEventRepository;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Certificate of Completion on a verified version: our own copy of the
 * console's {@code ConsultantVersionService.appendCertificatePage} and
 * {@code stampHashFooter}, reading the website's audit log
 * ({@code web_agreement_events}) instead of the console's. Same page,
 * layout, fonts, sizes, wording and sanitising, with two things left out
 * because the website has no email code: the "authenticated via Email OTP"
 * sentence and the "Email OTP verified" row. "Portal access" is the first
 * ACCESSED event (the console's first-access time).
 *
 * Two-pass hash, as the console: the document with the certificate is
 * saved, the SHA-256 of those bytes is stamped on the certificate as
 * "pre-seal", and the file is saved again. The caller stores
 * {@link #sha256Hex} of the returned bytes, so the printed hash never equals
 * the stored one or a hash of the file (copied as is).
 *
 * One Letter page, added last, with no letterhead (the console has none
 * there). Times are the server's local time labelled "UTC", exactly as the
 * console's fmtUtc. Nothing here is stored; the caller stores the bytes.
 */
@Service
@RequiredArgsConstructor
public class WebAgreementCertificateService {

    // PDFBox 3 standard fonts: our own copy of the console's four CertFonts.
    private static final PDType1Font HELVETICA = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDType1Font HELVETICA_BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private static final PDType1Font HELVETICA_OBLIQUE = new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE);
    private static final PDType1Font COURIER = new PDType1Font(Standard14Fonts.FontName.COURIER);

    private static final DateTimeFormatter UTC_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withLocale(Locale.US);

    private final WebAgreementEventRepository eventRepository;

    /** One audit-trail row: label, formatted time, IP. */
    record CertificateEvent(String label, String timestamp, String ip) {}

    /**
     * {@code body} with the certificate page appended last and the
     * pre-seal hash stamped on it. Throws on a PDF it can't read or write.
     */
    public byte[] appendCertificateAndStamp(byte[] body, WebAgreement a) throws IOException {
        return stampHashFooter(appendCertificatePage(body, a));
    }

    /** SHA-256 of {@code bytes} as lower-case hex (the stored documentHash). */
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

    // ── The page (the console's appendCertificatePage) ───────────────

    /** Pass one: the certificate page, saved, before the hash is stamped. */
    byte[] appendCertificatePage(byte[] body, WebAgreement a) throws IOException {
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
                        marginX, cursorY, HELVETICA_BOLD, 18, lineH * 1.4f);
                cursorY = drawText(cs,
                        "This certificate documents the consultant's electronic execution of the "
                                + "Sage IT Co Consultant Agreement and the integrity of the document the "
                                + "consultant received.",
                        marginX, cursorY, HELVETICA, 9, lineH);
                cursorY -= 6;

                cursorY = drawSectionHeading(cs, "Agreement", marginX, cursorY, lineH);
                cursorY = drawKeyValue(cs, "Application ID",
                        nz(a.getApplicationId()), marginX, cursorY, lineH);
                // "Consultant" is the console's label, kept verbatim.
                cursorY = drawKeyValue(cs, "Consultant",
                        nz(a.getConsultantName()) + "  <" + nz(a.getConsultantEmail()) + ">",
                        marginX, cursorY, lineH);
                cursorY = drawKeyValue(cs, "Technology track",
                        nz(a.getTechnologyTrack()), marginX, cursorY, lineH);
                cursorY = drawKeyValue(cs, "Effective date",
                        a.getEffectiveDate() == null ? "" : a.getEffectiveDate().toString(),
                        marginX, cursorY, lineH);
                cursorY -= 4;

                cursorY = drawSectionHeading(cs,
                        "Electronic Records & Signatures Consent", marginX, cursorY, lineH);
                if (a.getConsentGivenAt() != null) {
                    cursorY = drawKeyValue(cs, "Given at",
                            fmtUtc(a.getConsentGivenAt()), marginX, cursorY, lineH);
                    cursorY = drawKeyValue(cs, "From IP",
                            nz(a.getConsentIp()), marginX, cursorY, lineH);
                    cursorY = drawKeyValue(cs, "Disclosure version",
                            nz(a.getConsentVersion()), marginX, cursorY, lineH);
                } else {
                    cursorY = drawText(cs,
                            "Consent record not present on this row.",
                            marginX, cursorY, HELVETICA_OBLIQUE, 9, lineH);
                }
                cursorY -= 4;

                cursorY = drawSectionHeading(cs,
                        "Signing audit trail (UTC)", marginX, cursorY, lineH);
                // The console's "Each event below was authenticated via Email
                // OTP to …" sentence is left out: the website has no email code.
                cursorY -= 2;

                for (CertificateEvent ev : collectCertificateEvents(a)) {
                    cursorY = drawEventRow(cs, ev, marginX, cursorY, lineH);
                    if (cursorY < bottomY + 90) {
                        // Defensive cut-off; the certificate is meant to be one page.
                        break;
                    }
                }
                cursorY -= 6;

                cursorY = drawSectionHeading(cs,
                        "Document integrity", marginX, cursorY, lineH);
                cursorY = drawKeyValue(cs, "Algorithm", "SHA-256",
                        marginX, cursorY, lineH);
                // The hash itself is computed after the page is saved and
                // stamped in the second pass (stampHashFooter).
                drawKeyValue(cs, "Hash",
                        "(see file footer — computed at release)",
                        marginX, cursorY, lineH);

                drawText(cs,
                        "This is the integrity-evidence release: the audit trail + SHA-256 above "
                                + "lets a recipient verify the file off-line. A future hardening step "
                                + "(PKI/PAdES) will additionally produce a cryptographically self-"
                                + "verifying seal.",
                        marginX, bottomY + 26, HELVETICA_OBLIQUE, 8, 10);
                drawText(cs,
                        "Issued by Sage IT Co  •  Generated " + fmtUtc(LocalDateTime.now()),
                        marginX, bottomY + 8, HELVETICA, 8, 10);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /**
     * Pass two: stamps "SHA-256 (body + certificate, pre-seal):" and the hash
     * of {@code bytes} at y=64 on the last page, then saves again.
     */
    byte[] stampHashFooter(byte[] bytes) throws IOException {
        String preStampHash = sha256Hex(bytes);
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            int last = doc.getNumberOfPages() - 1;
            PDPage certPage = doc.getPage(last);
            try (PDPageContentStream cs = new PDPageContentStream(
                    doc, certPage, PDPageContentStream.AppendMode.APPEND, true, true)) {
                // Above the issued-by line.
                float marginX = 54f;
                float y = 64f;
                cs.beginText();
                cs.setFont(HELVETICA_BOLD, 8);
                cs.newLineAtOffset(marginX, y);
                cs.showText("SHA-256 (body + certificate, pre-seal): ");
                cs.endText();
                cs.beginText();
                cs.setFont(COURIER, 8);
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
                                  float lineH) throws IOException {
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
                                            float x, float y, float lineH) throws IOException {
        cs.beginText();
        cs.setFont(HELVETICA_BOLD, 11);
        cs.setNonStrokingColor(0.106f, 0.165f, 0.361f);
        cs.newLineAtOffset(x, y);
        cs.showText(sanitize(text));
        cs.endText();
        cs.setNonStrokingColor(0, 0, 0);
        return y - lineH - 2;
    }

    private static float drawKeyValue(PDPageContentStream cs,
                                      String key, String value,
                                      float x, float y, float lineH) throws IOException {
        cs.beginText();
        cs.setFont(HELVETICA_BOLD, 9);
        cs.newLineAtOffset(x, y);
        cs.showText(sanitize(key) + ":");
        cs.endText();
        cs.beginText();
        cs.setFont(HELVETICA, 9);
        cs.newLineAtOffset(x + 130, y);
        cs.showText(sanitize(value));
        cs.endText();
        return y - lineH;
    }

    private static float drawEventRow(PDPageContentStream cs, CertificateEvent ev,
                                      float x, float y, float lineH) throws IOException {
        cs.beginText();
        cs.setFont(HELVETICA_BOLD, 9);
        cs.newLineAtOffset(x, y);
        cs.showText(sanitize(ev.label()));
        cs.endText();
        cs.beginText();
        cs.setFont(HELVETICA, 9);
        cs.newLineAtOffset(x + 130, y);
        cs.showText(sanitize(ev.timestamp() + "   IP: " + nz(ev.ip())));
        cs.endText();
        return y - lineH;
    }

    // ── The audit trail (the console's collectCertificateEvents) ─────

    /**
     * The rows in the console's order: consent (oldest), portal access
     * (oldest ACCESSED), filled (latest), primary signature, execution
     * signature, submitted (oldest SIGNED), then every approver decision
     * oldest first. No OTP row.
     */
    List<CertificateEvent> collectCertificateEvents(WebAgreement a) {
        List<CertificateEvent> rows = new ArrayList<>();
        List<WebAgreementEvent> all = eventRepository.findByAgreementIdOrderByCreatedAtDesc(a.getId());

        WebAgreementEvent consent = findFirstByType(all, WebAgreementEvent.EventType.CONSENT_GIVEN);
        if (consent != null) {
            rows.add(new CertificateEvent(
                    "Consent given",
                    fmtUtc(consent.getCreatedAt()),
                    consent.getIpAddress()));
        }
        // The console's "Email OTP verified" row is left out (no email code).
        WebAgreementEvent access = findFirstByType(all, WebAgreementEvent.EventType.ACCESSED);
        if (access != null) {
            rows.add(new CertificateEvent(
                    "Portal access",
                    fmtUtc(access.getCreatedAt()),
                    access.getIpAddress()));
        }
        // The latest fill, so the time / IP reflect the last edit before signing.
        WebAgreementEvent filled = findLastByType(all, WebAgreementEvent.EventType.CONSULTANT_FILLED);
        if (filled != null) {
            rows.add(new CertificateEvent(
                    "Agreement filled",
                    fmtUtc(filled.getCreatedAt()),
                    filled.getIpAddress()));
        }
        if (a.getSigningAt() != null) {
            rows.add(new CertificateEvent(
                    "Primary signature drawn",
                    fmtUtc(a.getSigningAt()),
                    a.getSigningIp()));
        }
        if (a.getFinalSignedAt() != null) {
            rows.add(new CertificateEvent(
                    "Execution signature (review)",
                    fmtUtc(a.getFinalSignedAt()),
                    a.getFinalSigningIp()));
        }
        WebAgreementEvent signed = findFirstByType(all, WebAgreementEvent.EventType.SIGNED);
        if (signed != null) {
            rows.add(new CertificateEvent(
                    "Agreement submitted",
                    fmtUtc(signed.getCreatedAt()),
                    signed.getIpAddress()));
        }
        // Approver decisions (Manager / Accounts), oldest first.
        List<WebAgreementEvent> chrono = new ArrayList<>(all);
        Collections.reverse(chrono); // the repository is newest first
        for (WebAgreementEvent ev : chrono) {
            String t = ev.getEventType();
            boolean approved = WebAgreementEvent.EventType.APPROVAL_APPROVED.name().equals(t);
            boolean revised = WebAgreementEvent.EventType.APPROVAL_REVISION_REQUESTED.name().equals(t);
            if (!approved && !revised) continue;
            String role = extractMetaValue(ev.getMetadata(), "role");
            String label = (role == null || role.isBlank() ? "Approver" : capitalizeWord(role))
                    + (approved ? " approved" : " requested revision");
            rows.add(new CertificateEvent(label, fmtUtc(ev.getCreatedAt()), ev.getIpAddress()));
        }
        return rows;
    }

    /** Minimal JSON value extraction for a flat string key (audit metadata). */
    private static String extractMetaValue(String json, String key) {
        if (json == null) return null;
        Matcher m = Pattern
                .compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static String capitalizeWord(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
    }

    /** The OLDEST event of a type, from a newest-first list. */
    private static WebAgreementEvent findFirstByType(List<WebAgreementEvent> all,
                                                     WebAgreementEvent.EventType type) {
        WebAgreementEvent picked = null;
        String t = type.name();
        for (WebAgreementEvent ev : all) {
            if (t.equals(ev.getEventType())) picked = ev;
        }
        return picked;
    }

    /** The NEWEST event of a type, from a newest-first list. */
    private static WebAgreementEvent findLastByType(List<WebAgreementEvent> all,
                                                    WebAgreementEvent.EventType type) {
        String t = type.name();
        for (WebAgreementEvent ev : all) {
            if (t.equals(ev.getEventType())) return ev;
        }
        return null;
    }

    // ── Util ─────────────────────────────────────────────────────────

    /** Server-local time labelled UTC, exactly as the console's fmtUtc. */
    private static String fmtUtc(LocalDateTime t) {
        if (t == null) return "";
        return t.atZone(ZoneOffset.UTC).format(UTC_FMT);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /**
     * The standard 14 fonts are WinAnsi-encoded and throw on anything
     * outside it: curly quotes, dashes and bullets map to ASCII, anything
     * else unknown becomes "?" (the console's sanitize).
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
