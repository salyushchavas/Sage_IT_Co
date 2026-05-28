package com.spire.backend.service;

import com.lowagie.text.*;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfImportedPage;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.PdfStamper;
import com.lowagie.text.pdf.PdfWriter;
import com.spire.backend.entity.AgreementAcceptance;
import com.spire.backend.entity.User;
import com.spire.backend.service.TermsContentService.Section;
import com.spire.backend.service.TermsContentService.TermsDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Generates the personalized signed-agreement PDF emailed to a user
 * after they complete the OTP-verified Terms acceptance flow.
 *
 * Renders directly from {@link TermsContentService}, the same JSON
 * source the website's {@code /api/agreement/terms} endpoint reads,
 * so the PDF wording is always identical to what the user agreed to
 * on screen.
 *
 * Layout strategy:
 *   1. Build the body content as a borderless A4 PDF in memory.
 *   2. If a letterhead template exists at
 *      {@code resources/templates/letterhead.pdf}, overlay each body
 *      page onto a copy of the letterhead (page 1 → first letterhead
 *      page; subsequent body pages → letterhead page 1 reused).
 *   3. If no letterhead template ships, write the body PDF directly
 *      with a native teal header / footer so the document still looks
 *      branded.
 *
 * Files land at {@code signed-agreements/{userId}-{ts}.pdf} relative
 * to the working directory; same convention as the certificates
 * folder. The public download URL is
 * {@code /api/agreement/signed-pdf/{userId}/{fileName}} — gated to
 * the owning user or an admin.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgreementPdfService {

    private static final Color TEAL_PRIMARY = new Color(15, 118, 110);   // #0F766E
    private static final Color TEAL_DARK = new Color(19, 78, 74);        // #134E4A
    private static final Color INK = new Color(31, 41, 55);
    private static final Color MUTED = new Color(107, 114, 128);
    private static final Color LIGHT_BG = new Color(249, 250, 251);

    private static final String LETTERHEAD_PATH = "templates/letterhead.pdf";
    private static final String OUTPUT_DIR = "signed-agreements";

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DATE_TIME_FMT =
            DateTimeFormatter.ofPattern("d MMMM yyyy, h:mm a 'IST'", Locale.ENGLISH);

    private final TermsContentService termsContentService;

    /**
     * Generates the SIGNED PDF for a verified acceptance row, writes
     * it to disk, and returns the on-disk filename. Caller is
     * responsible for persisting the resulting URL on the row.
     *
     * Throws on disk / rendering failure — agreement-acceptance flow
     * wraps the call in try/catch so a PDF outage doesn't roll back
     * the verification itself.
     */
    public String generate(AgreementAcceptance row) {
        new File(OUTPUT_DIR).mkdirs();
        String fileName = row.getUser().getId() + "-" + System.currentTimeMillis() + ".pdf";
        String filePath = OUTPUT_DIR + "/" + fileName;

        byte[] finalPdf = renderPdf(row, true);

        try (var out = new FileOutputStream(filePath)) {
            out.write(finalPdf);
            log.info("Signed agreement PDF written for user {}: {}",
                    row.getUser().getId(), filePath);
            return fileName;
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to write signed agreement PDF: " + e.getMessage(), e);
        }
    }

    /**
     * Generates the BLANK / pending agreement PDF — same letterhead,
     * same terms wording, but the signature page reads "PENDING
     * ACCEPTANCE" with the post-reply / OTP / acceptance-code rows
     * stamped as PENDING placeholders. Returned as bytes only (no
     * disk write) because the pending PDF is consumed by the email
     * relay and then thrown away; the SIGNED copy that lands after
     * OTP verification is the one we persist.
     */
    public byte[] renderPendingBytes(AgreementAcceptance row) {
        return renderPdf(row, false);
    }

    /**
     * Renders the body, then overlays it on the letterhead (if the
     * template ships and the overlay succeeds). Falls back to the
     * letterhead-less body bytes on overlay failure so a corrupted
     * template can never break the flow.
     */
    private byte[] renderPdf(AgreementAcceptance row, boolean signed) {
        TermsDocument doc = termsContentService.getTerms(row.getAgreementVersion());
        byte[] body = renderBody(row, doc, signed);

        ClassPathResource letterhead = new ClassPathResource(LETTERHEAD_PATH);
        if (letterhead.exists()) {
            try (var lhStream = letterhead.getInputStream()) {
                return overlayOnLetterhead(body, lhStream.readAllBytes());
            } catch (Exception e) {
                log.warn("Letterhead overlay failed, falling back to plain body: {}",
                        e.getMessage());
            }
        }
        return body;
    }

    /**
     * Renders the body PDF: terms, acceptance confirmations, and the
     * personalized signature page. Returns the raw PDF bytes so the
     * caller can either ship them unmodified or overlay them onto a
     * letterhead.
     *
     * Margins are sized to fit the Spire letterhead: ~140pt top
     * clears the logo + corner accents; ~100pt bottom clears the
     * address footer; 65pt sides clear the teal corner trim.
     * No header / footer painting happens here — the letterhead
     * provides all branding when overlaid, so a second header bar
     * would just stack on top of the real one.
     */
    private byte[] renderBody(AgreementAcceptance row, TermsDocument doc, boolean signed) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Document document = new Document(PageSize.A4, 65, 65, 140, 100);
            PdfWriter.getInstance(document, baos);
            document.open();

            User user = row.getUser();
            String acceptedAt = signed && row.getAcceptedAt() != null
                    ? row.getAcceptedAt().atZone(IST).format(DATE_TIME_FMT)
                    : "PENDING";

            // ── Page 1+ : Title + acceptance confirmations + full terms
            document.add(centered(signed ? "SIGNED AGREEMENT" : "AGREEMENT FOR REVIEW",
                    new Font(Font.HELVETICA, 11, Font.BOLD, signed ? MUTED : new Color(180, 83, 9)), 16));
            document.add(centered("Terms of Service",
                    new Font(Font.TIMES_ROMAN, 24, Font.BOLD, TEAL_DARK), 8));
            document.add(centered(doc.version()
                            + (doc.effectiveDate() == null || doc.effectiveDate().isBlank()
                                    ? "" : "  •  effective " + doc.effectiveDate()),
                    new Font(Font.HELVETICA, 10, Font.ITALIC, MUTED), 18));

            // ── Acceptance confirmations ────────────────────────────
            document.add(sectionHeader("By accepting, the user confirmed:"));
            for (String c : doc.acceptanceConfirmations()) {
                document.add(bullet(c));
            }
            document.add(spacer(14));

            // ── Full terms ──────────────────────────────────────────
            document.add(sectionHeader("Terms of Service " + doc.version()));
            for (Section s : doc.sections()) {
                Paragraph title = new Paragraph(s.title(),
                        new Font(Font.HELVETICA, 11.5f, Font.BOLD, TEAL_DARK));
                title.setSpacingBefore(8);
                title.setSpacingAfter(2);
                document.add(title);

                Paragraph content = new Paragraph(s.content(),
                        new Font(Font.TIMES_ROMAN, 10.5f, Font.NORMAL, INK));
                content.setAlignment(Element.ALIGN_JUSTIFIED);
                content.setLeading(15);
                document.add(content);
            }

            // ── Final page : Personalized acceptance record ─────────
            // Forced to its own page so the framed signature block
            // always reads as a clean, dedicated artifact regardless
            // of where the terms text happens to break.
            document.newPage();

            document.add(spacer(8));
            document.add(centered("AGREEMENT ACCEPTANCE RECORD",
                    new Font(Font.HELVETICA, 13, Font.BOLD, TEAL_DARK), 4));
            document.add(centered(
                    signed ? "Personalized signature page"
                           : "Personalized signature page  •  PENDING ACCEPTANCE",
                    new Font(Font.HELVETICA, 10, Font.ITALIC,
                            signed ? MUTED : new Color(180, 83, 9)), 18));

            // Embed the user's drawn / uploaded signature in a
            // labeled bordered box at the top of the record. Sits
            // above the data table so the visual signature reads
            // first; the table provides the audit metadata that
            // legally binds it.
            addSignatureBlock(document, row.getSignatureImage());

            // The framed-table layout is rendered via PdfPTable so the
            // borders sit consistently regardless of font metrics.
            document.add(buildAcceptanceRecordTable(row, user, doc, acceptedAt, signed));

            document.add(spacer(20));
            document.add(new Paragraph(
                    signed
                            ? "This document was generated by " + doc.platform() + " and constitutes "
                                    + "a record of the User's acceptance of the Terms of Service. "
                                    + "The acceptance was verified through email reply confirmation "
                                    + "and OTP code verification, and is recorded as binding legal "
                                    + "evidence equivalent to a handwritten signature."
                            : "This document is a copy of the " + doc.platform() + " Terms of Service "
                                    + "presented for review. To accept, reply 'Yes, I agree' to the "
                                    + "email this PDF was attached to. After your reply is received, "
                                    + "you'll receive a one-time verification code; entering it on "
                                    + "the website finalises the acceptance and a fully-signed copy "
                                    + "of this document will be issued to you.",
                    new Font(Font.TIMES_ROMAN, 10.5f, Font.NORMAL, INK)));

            document.add(spacer(20));
            Paragraph signedAs = new Paragraph();
            signedAs.add(new Chunk(signed ? "Signed as:  " : "Will be signed as:  ",
                    new Font(Font.HELVETICA, 11, Font.NORMAL, MUTED)));
            signedAs.add(new Chunk(row.getLegalName(),
                    new Font(Font.HELVETICA, 14, Font.BOLD, INK)));
            document.add(signedAs);
            document.add(new Paragraph(
                    signed ? "Signed on " + acceptedAt
                           : "Acceptance pending — awaiting email reply + verification code",
                    new Font(Font.HELVETICA, 10, Font.ITALIC, MUTED)));

            // ── Contact / jurisdiction footer ───────────────────────
            document.add(spacer(20));
            document.add(new Paragraph(
                    "For verification or questions: " + doc.contactEmail()
                            + "  |  " + doc.supportUrl(),
                    new Font(Font.HELVETICA, 9, Font.NORMAL, MUTED)));
            document.add(new Paragraph(
                    "Jurisdiction: " + doc.jurisdiction(),
                    new Font(Font.HELVETICA, 9, Font.NORMAL, MUTED)));

            document.close();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to render agreement PDF body: " + e.getMessage(), e);
        }
    }

    /**
     * Overlays each body page onto a letterhead template, returning
     * the merged PDF as bytes. The letterhead's first page is reused
     * for every body page — single-page letterheads are the common
     * case, so we don't try to multiplex multi-page letterheads.
     */
    private byte[] overlayOnLetterhead(byte[] bodyBytes, byte[] letterheadBytes)
            throws Exception {
        PdfReader bodyReader = new PdfReader(bodyBytes);
        ByteArrayOutputStream tmp = new ByteArrayOutputStream();
        PdfStamper stamper = new PdfStamper(bodyReader, tmp);

        PdfReader letterheadReader = new PdfReader(letterheadBytes);
        PdfImportedPage letterheadPage = stamper.getImportedPage(letterheadReader, 1);

        int pages = bodyReader.getNumberOfPages();
        for (int i = 1; i <= pages; i++) {
            PdfContentByte underContent = stamper.getUnderContent(i);
            underContent.addTemplate(letterheadPage, 0, 0);
        }
        stamper.close();
        bodyReader.close();
        letterheadReader.close();

        return tmp.toByteArray();
    }

    // ─── Layout helpers ────────────────────────────────────────────

    private static Paragraph spacer(float leading) {
        Paragraph p = new Paragraph(" ");
        p.setLeading(leading);
        return p;
    }

    private static Paragraph centered(String text, Font font, float spacingAfter) {
        Paragraph p = new Paragraph(text, font);
        p.setAlignment(Element.ALIGN_CENTER);
        p.setSpacingAfter(spacingAfter);
        return p;
    }

    private static Paragraph sectionHeader(String text) {
        Paragraph p = new Paragraph(text,
                new Font(Font.HELVETICA, 11, Font.BOLD, TEAL_PRIMARY));
        p.setSpacingBefore(6);
        p.setSpacingAfter(8);
        return p;
    }

    private static Paragraph bullet(String text) {
        Paragraph p = new Paragraph("•  " + text,
                new Font(Font.TIMES_ROMAN, 10.5f, Font.NORMAL, INK));
        p.setIndentationLeft(12);
        p.setLeading(15);
        p.setSpacingAfter(2);
        return p;
    }

    private static String safe(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }

    /**
     * Renders the user's signature image inside a labeled, bordered
     * single-cell {@link PdfPTable}. The data-URL prefix is stripped
     * before base64 decoding; on any decode / embed failure we fall
     * back to a placeholder line so the page still composes.
     */
    private static void addSignatureBlock(Document document, String dataUrl) {
        try {
            Paragraph label = new Paragraph("Digital Signature",
                    new Font(Font.HELVETICA, 10, Font.BOLD, TEAL_PRIMARY));
            label.setSpacingBefore(2);
            label.setSpacingAfter(4);
            document.add(label);
        } catch (Exception ignored) {}

        PdfPTable wrap = new PdfPTable(1);
        wrap.setWidthPercentage(60);
        wrap.setHorizontalAlignment(Element.ALIGN_LEFT);
        wrap.setSpacingBefore(0);
        wrap.setSpacingAfter(14);

        PdfPCell cell = new PdfPCell();
        cell.setBorderColor(MUTED);
        cell.setBorderWidth(0.6f);
        cell.setPadding(10);
        cell.setMinimumHeight(70);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);

        Image sigImage = decodeSignature(dataUrl);
        if (sigImage != null) {
            sigImage.scaleToFit(220, 70);
            sigImage.setAlignment(Image.ALIGN_CENTER);
            cell.addElement(sigImage);
        } else {
            Paragraph placeholder = new Paragraph(
                    "(no signature on file)",
                    new Font(Font.HELVETICA, 9, Font.ITALIC, MUTED));
            placeholder.setAlignment(Element.ALIGN_CENTER);
            cell.addElement(placeholder);
        }
        wrap.addCell(cell);
        try {
            document.add(wrap);
        } catch (Exception ignored) {}
    }

    /**
     * Decodes a {@code data:image/<type>;base64,…} URL into an
     * OpenPDF {@link Image}. Returns null on any failure so callers
     * can render a placeholder instead of crashing the PDF gen.
     */
    private static Image decodeSignature(String dataUrl) {
        if (dataUrl == null || dataUrl.isBlank()) return null;
        try {
            int comma = dataUrl.indexOf(',');
            String b64 = comma >= 0 ? dataUrl.substring(comma + 1) : dataUrl;
            byte[] bytes = java.util.Base64.getDecoder().decode(b64);
            return Image.getInstance(bytes);
        } catch (Exception e) {
            log.warn("Failed to decode signature image for embed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Two-column framed table containing the personalized acceptance
     * record on the final page. Rendered as a {@link PdfPTable} so
     * the borders and column widths stay consistent regardless of
     * label/value font metrics.
     */
    private static PdfPTable buildAcceptanceRecordTable(
            AgreementAcceptance row, User user, TermsDocument doc,
            String acceptedAt, boolean signed
    ) {
        PdfPTable table = new PdfPTable(new float[]{ 1.4f, 3.6f });
        table.setWidthPercentage(100);
        table.setSpacingBefore(8);
        table.setSpacingAfter(8);

        // Status row first so a recipient sees PENDING / ACCEPTED at
        // a glance without scanning the whole table.
        addRecordRow(table, "Status", signed ? "ACCEPTED" : "PENDING ACCEPTANCE");
        addRecordRow(table, "Full legal name",
                safe(row.getLegalName()));
        addRecordRow(table, "Email address",
                safe(user.getEmail()) + (signed ? "  (verified)" : ""));
        addRecordRow(table, "Date of acceptance", acceptedAt);
        addRecordRow(table, "Agreement version", safe(doc.version()));
        addRecordRow(table, "Acceptance code",
                signed
                        ? (row.getAcceptanceCode() == null ? "—" : row.getAcceptanceCode())
                                + "  (verified)"
                        : "PENDING");
        addRecordRow(table, "IP address", safe(row.getIpAddress()));
        addRecordRow(table, "Browser",
                safe(row.getBrowser()) + " on " + safe(row.getOs()));
        addRecordRow(table, "Reply received",
                signed
                        ? (row.getUserReplyReceivedAt() == null
                                ? "—"
                                : row.getUserReplyReceivedAt().atZone(IST).format(DATE_TIME_FMT)
                                        + "   (\"" + safe(row.getUserReplyContent()) + "\")")
                        : "PENDING");

        return table;
    }

    private static void addRecordRow(PdfPTable table, String label, String value) {
        PdfPCell labelCell = new PdfPCell(new Phrase(label,
                new Font(Font.HELVETICA, 10, Font.BOLD, MUTED)));
        labelCell.setBorderColor(LIGHT_BG);
        labelCell.setBorderWidth(0.5f);
        labelCell.setPadding(8);
        labelCell.setBackgroundColor(LIGHT_BG);

        PdfPCell valueCell = new PdfPCell(new Phrase(value,
                new Font(Font.HELVETICA, 10, Font.NORMAL, INK)));
        valueCell.setBorderColor(LIGHT_BG);
        valueCell.setBorderWidth(0.5f);
        valueCell.setPadding(8);

        table.addCell(labelCell);
        table.addCell(valueCell);
    }

}
