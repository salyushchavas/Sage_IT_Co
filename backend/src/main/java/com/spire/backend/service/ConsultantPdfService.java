package com.spire.backend.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.spire.backend.config.BrandConfig;
import com.spire.backend.entity.ConsultantApplication;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.Map;

/**
 * Generates the signed Consultant Agreement PDF and uploads it to
 * Cloudinary. <strong>This is a placeholder template</strong> -- the
 * final layout ships when the legal team provides the master document.
 * The layout below renders enough fields to prove the end-to-end flow
 * (sign -> persist signature -> PDF -> Cloudinary URL -> email).
 *
 * Why OpenPDF instead of e.g. iText 8: Sage already uses OpenPDF for
 * the existing certificate + agreement flows. Same dep, same fonts,
 * same artifact -> consistent at deploy time.
 *
 * Why direct Cloudinary instead of DocumentStorageService.upload:
 * DocumentStorageService keys uploads by user id (no row for a
 * consultant). A bespoke method here keeps the contract clean and
 * lets the consultant-agreements folder live under a dedicated
 * Cloudinary prefix.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConsultantPdfService {

    private static final Color BRAND_FALLBACK = new Color(27, 42, 92);   // #1B2A5C
    private static final Color INK = new Color(31, 41, 55);
    private static final Color MUTED = new Color(107, 114, 128);
    private static final DateTimeFormatter STAMP_FMT =
            DateTimeFormatter.ofPattern("d MMMM yyyy, h:mm a 'IST'");

    private final Cloudinary cloudinary;
    private final BrandConfig brandConfig;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Renders the PDF to bytes and uploads to Cloudinary. Returns the
     * secure_url. Caller persists the URL on the application row.
     */
    public String generateAndUpload(ConsultantApplication application) {
        byte[] pdf = renderToBytes(application);
        return uploadToCloudinary(application.getApplicationId(), pdf);
    }

    // ── PDF rendering ───────────────────────────────────────────────

    private byte[] renderToBytes(ConsultantApplication application) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (Document doc = new Document(PageSize.A4, 48, 48, 56, 56)) {
            PdfWriter.getInstance(doc, baos);
            doc.open();

            Color brand = brandPrimary();

            // Header
            Paragraph header = new Paragraph(
                    brandConfig.getName().toUpperCase(),
                    new Font(Font.HELVETICA, 14, Font.BOLD, brand));
            header.setAlignment(Element.ALIGN_CENTER);
            doc.add(header);

            Paragraph tagline = new Paragraph(
                    brandConfig.getTagline(),
                    new Font(Font.HELVETICA, 9, Font.ITALIC, MUTED));
            tagline.setAlignment(Element.ALIGN_CENTER);
            tagline.setSpacingAfter(18);
            doc.add(tagline);

            Paragraph title = new Paragraph("Consultant Engagement Agreement",
                    new Font(Font.TIMES_ROMAN, 18, Font.BOLD, brand));
            title.setAlignment(Element.ALIGN_CENTER);
            title.setSpacingAfter(14);
            doc.add(title);

            // Application metadata
            doc.add(metaTable(application));

            // Payload table (opaque JSON -> key/value rows)
            doc.add(spacer(10));
            Paragraph fieldsHeader = new Paragraph("Engagement Details",
                    new Font(Font.HELVETICA, 11, Font.BOLD, brand));
            fieldsHeader.setSpacingAfter(6);
            doc.add(fieldsHeader);
            doc.add(payloadTable(application));

            // Acceptance + signature
            doc.add(spacer(14));
            Paragraph accept = new Paragraph("Acceptance",
                    new Font(Font.HELVETICA, 11, Font.BOLD, brand));
            accept.setSpacingAfter(6);
            doc.add(accept);

            String legal = safe(application.getSignedLegalName());
            String signedAt = application.getSignedAt() == null
                    ? "--"
                    : STAMP_FMT.format(application.getSignedAt().atZone(
                            java.time.ZoneId.of("Asia/Kolkata")).toLocalDateTime());
            Paragraph acceptBody = new Paragraph(
                    "I, " + legal + ", confirm that the engagement details above are "
                            + "accurate and that I accept the terms therein. Signed on "
                            + signedAt + ".",
                    new Font(Font.TIMES_ROMAN, 11, Font.NORMAL, INK));
            acceptBody.setSpacingAfter(8);
            doc.add(acceptBody);

            if (application.getSignatureImage() != null) {
                try {
                    com.lowagie.text.Image img = decodeSignatureImage(application.getSignatureImage());
                    if (img != null) {
                        img.scaleToFit(220, 80);
                        img.setSpacingAfter(8);
                        doc.add(img);
                    }
                } catch (Exception e) {
                    log.warn("Couldn't embed consultant signature image: {}", e.getMessage());
                }
            }

            // Audit footer
            String ipBit = application.getSignedIp() == null ? "--" : application.getSignedIp();
            String uaBit = application.getSignedUserAgent() == null
                    ? "--" : application.getSignedUserAgent();
            Paragraph audit = new Paragraph(
                    "Application " + application.getApplicationId()
                            + "  -  IP " + ipBit
                            + "  -  UA " + truncate(uaBit, 80),
                    new Font(Font.HELVETICA, 7, Font.ITALIC, MUTED));
            audit.setAlignment(Element.ALIGN_LEFT);
            audit.setSpacingBefore(18);
            doc.add(audit);

            Paragraph placeholder = new Paragraph(
                    "PLACEHOLDER -- final template pending. Generated on "
                            + STAMP_FMT.format(LocalDateTime.now().atZone(
                                    java.time.ZoneId.of("Asia/Kolkata")).toLocalDateTime()) + ".",
                    new Font(Font.HELVETICA, 7, Font.ITALIC, MUTED));
            placeholder.setAlignment(Element.ALIGN_CENTER);
            doc.add(placeholder);
        } catch (Exception e) {
            throw new RuntimeException("Consultant PDF render failed: " + e.getMessage(), e);
        }
        return baos.toByteArray();
    }

    private PdfPTable metaTable(ConsultantApplication application) {
        PdfPTable table = new PdfPTable(new float[]{1.2f, 3f});
        table.setWidthPercentage(100);
        table.setSpacingBefore(4);
        table.setSpacingAfter(8);
        addRow(table, "Application ID", application.getApplicationId());
        addRow(table, "Consultant", safe(application.getConsultantName()));
        addRow(table, "Email", safe(application.getConsultantEmail()));
        addRow(table, "Phone", safe(application.getConsultantPhone()));
        return table;
    }

    private PdfPTable payloadTable(ConsultantApplication application) {
        PdfPTable table = new PdfPTable(new float[]{1.5f, 3f});
        table.setWidthPercentage(100);

        String payloadJson = application.getPayload();
        if (payloadJson == null || payloadJson.isBlank() || "{}".equals(payloadJson.trim())) {
            PdfPCell cell = new PdfPCell(new Paragraph(
                    "(no additional details captured)",
                    new Font(Font.HELVETICA, 9, Font.ITALIC, MUTED)));
            cell.setColspan(2);
            cell.setBorderColor(new Color(229, 231, 235));
            cell.setPadding(8);
            table.addCell(cell);
            return table;
        }

        try {
            JsonNode payload = objectMapper.readTree(payloadJson);
            if (payload.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> it = payload.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> field = it.next();
                    addRow(table, prettifyKey(field.getKey()), stringifyValue(field.getValue()));
                }
            } else {
                addRow(table, "payload", payload.toString());
            }
        } catch (Exception e) {
            addRow(table, "payload (raw)", payloadJson);
        }
        return table;
    }

    private void addRow(PdfPTable table, String key, String value) {
        Font keyFont = new Font(Font.HELVETICA, 9, Font.BOLD, INK);
        Font valueFont = new Font(Font.HELVETICA, 9, Font.NORMAL, INK);
        PdfPCell k = new PdfPCell(new Paragraph(key, keyFont));
        PdfPCell v = new PdfPCell(new Paragraph(value, valueFont));
        for (PdfPCell cell : new PdfPCell[]{k, v}) {
            cell.setBorderColor(new Color(229, 231, 235));
            cell.setPadding(6);
        }
        table.addCell(k);
        table.addCell(v);
    }

    private static Paragraph spacer(float height) {
        Paragraph p = new Paragraph(" ");
        p.setSpacingAfter(height);
        return p;
    }

    private com.lowagie.text.Image decodeSignatureImage(String dataUrl) throws Exception {
        // Accepts both "data:image/png;base64,XXXX" and raw base64.
        String base64 = dataUrl;
        int comma = dataUrl.indexOf(',');
        if (dataUrl.startsWith("data:") && comma > 0) {
            base64 = dataUrl.substring(comma + 1);
        }
        byte[] bytes = java.util.Base64.getDecoder().decode(base64);
        return com.lowagie.text.Image.getInstance(bytes);
    }

    private Color brandPrimary() {
        String hex = brandConfig.getPrimaryColor();
        if (hex == null || hex.isBlank()) return BRAND_FALLBACK;
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        try {
            int rgb = Integer.parseInt(h, 16);
            return new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
        } catch (NumberFormatException e) {
            return BRAND_FALLBACK;
        }
    }

    private static String safe(String s) {
        return s == null || s.isBlank() ? "--" : s;
    }

    private static String prettifyKey(String key) {
        if (key == null) return "";
        // camelCase / snake_case -> Title Case
        String spaced = key.replaceAll("([a-z])([A-Z])", "$1 $2")
                .replace('_', ' ').replace('-', ' ');
        if (spaced.isEmpty()) return "";
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    private static String stringifyValue(JsonNode node) {
        if (node == null || node.isNull()) return "--";
        if (node.isTextual()) return node.asText();
        if (node.isNumber() || node.isBoolean()) return node.asText();
        return node.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    // ── Cloudinary upload ───────────────────────────────────────────

    private String uploadToCloudinary(String applicationId, byte[] pdf) {
        try {
            String publicId = "consultant-agreements/" + applicationId
                    + "-" + System.currentTimeMillis();
            Map<?, ?> result = cloudinary.uploader().upload(pdf, ObjectUtils.asMap(
                    "public_id", publicId,
                    "resource_type", "raw",
                    "type", "authenticated",
                    "format", "pdf",
                    "overwrite", false
            ));
            Object url = result.get("secure_url");
            if (url == null) {
                throw new RuntimeException("Cloudinary upload returned no secure_url");
            }
            return url.toString();
        } catch (Exception e) {
            throw new RuntimeException("Consultant PDF upload failed: " + e.getMessage(), e);
        }
    }
}
