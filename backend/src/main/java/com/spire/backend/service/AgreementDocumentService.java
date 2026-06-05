package com.spire.backend.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.spire.backend.entity.ConsultantApplication;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import pro.verron.officestamper.api.OfficeStamperConfiguration;
import pro.verron.officestamper.api.StreamStamper;
import pro.verron.officestamper.preset.Image;
import pro.verron.officestamper.preset.OfficeStamperConfigurations;
import pro.verron.officestamper.preset.OfficeStampers;
import pro.verron.officestamper.preset.Resolvers;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Two-stage agreement PDF generator.
 *
 * Pipeline: docx-stamper fills the prepared Word template with the
 * application's data (null-safe -- missing fields render as blank,
 * never as literal ${name} text), LibreOffice converts the filled
 * .docx to PDF in a headless subprocess, and Cloudinary stores the
 * artifact under a stable {@code agreements/{appId}} public_id so
 * later regenerations overwrite cleanly.
 *
 * The method is callable at any state of the workflow: when the
 * consultant has signed but the ERM hasn't, {@code ermSignatureUrl}
 * is null and that placeholder renders empty (no broken image). When
 * both have signed, both signature images are embedded inline.
 *
 * Phase 3 will pick when to call this and which URL field on the
 * entity to persist into ({@code signedPdfUrl} for the intermediate
 * consultant-only artifact, {@code finalPdfUrl} for the final
 * countersigned version). This service is intentionally stateless --
 * it neither mutates the entity nor writes to the DB.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgreementDocumentService {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("MMMM d, yyyy");

    /** LibreOffice conversion timeout. 60s is comfortably above the
     *  observed ~3-8s cold-start range on Railway's containers. */
    private static final long LIBREOFFICE_TIMEOUT_SECONDS = 60L;

    private final Cloudinary cloudinary;

    @Value("classpath:templates/SageITCO_Master_Agreement_TEMPLATE.docx")
    private Resource templateResource;

    /** Single hardcoded operator. Same property as the
     *  agreement-erm login. */
    @Value("${agreement-erm.email}")
    private String agreementErmEmail;

    public String generateAgreementPdf(ConsultantApplication app) throws Exception {
        Map<String, Object> ctx = buildContext(app);
        Path filledDocx = null;
        Path pdf = null;
        try {
            filledDocx = fillTemplate(ctx);
            pdf = convertToPdf(filledDocx);
            return uploadToCloudinary(pdf, app.getApplicationId());
        } finally {
            safeDelete(filledDocx);
            safeDelete(pdf);
        }
    }

    // ── Context build ───────────────────────────────────────────────

    private Map<String, Object> buildContext(ConsultantApplication app) {
        Map<String, Object> c = new HashMap<>();
        Function<String, String> nz = s -> s == null ? "" : s;
        Function<LocalDate, String> fd = d -> d == null ? "" : d.format(DATE_FMT);
        Function<LocalDateTime, String> fdt =
                d -> d == null ? "" : d.toLocalDate().format(DATE_FMT);

        // Header. The template's ${participantFullLegalName} and
        // ${primaryEmail} placeholders don't have 1:1 entity getters
        // -- we map them to the closest existing fields. signedLegalName
        // is set by the consultant at sign time; before that, fall back
        // to the consultantName the ERM seeded the row with.
        String participantName = app.getSignedLegalName() != null
                && !app.getSignedLegalName().isBlank()
                ? app.getSignedLegalName()
                : app.getConsultantName();
        c.put("effectiveDate", fd.apply(app.getEffectiveDate()));
        c.put("participantFullLegalName", nz.apply(participantName));
        c.put("primaryEmail", nz.apply(app.getConsultantEmail()));
        c.put("primaryPhone", nz.apply(app.getPrimaryPhone()));
        c.put("workAuthorizationCategory", nz.apply(app.getWorkAuthorizationCategory()));
        c.put("residenceAddress", nz.apply(app.getResidenceAddress()));

        // Rate card (Section 11 + Appendix 1).
        c.put("ratePeriod1", nz.apply(app.getRatePeriod1()));
        c.put("rateAmount1", nz.apply(app.getRateAmount1()));
        c.put("ratePeriod2", nz.apply(app.getRatePeriod2()));
        c.put("rateAmount2", nz.apply(app.getRateAmount2()));

        // Exhibit A.
        c.put("technologyTrack", nz.apply(app.getTechnologyTrack()));
        c.put("customScopeNotes", nz.apply(app.getCustomScopeNotes()));

        // Appendix 1 -- employment.
        c.put("employerPayrollEntity", nz.apply(app.getEmployerPayrollEntity()));
        c.put("implementationPartner", nz.apply(app.getImplementationPartner()));
        c.put("endClient", nz.apply(app.getEndClient()));
        c.put("roleTitle", nz.apply(app.getRoleTitle()));
        c.put("verifiedStartDate", fd.apply(app.getVerifiedStartDate()));
        c.put("payrollCycle", nz.apply(app.getPayrollCycle()));

        // Appendix 2 -- ACH (optional).
        c.put("achAccountType", nz.apply(app.getAchAccountType()));
        c.put("achBankName", nz.apply(app.getAchBankName()));
        c.put("achAccountHolderName", nz.apply(app.getAchAccountHolderName()));
        c.put("achRoutingNumber", nz.apply(app.getAchRoutingNumber()));
        c.put("achAccountNumber", nz.apply(app.getAchAccountNumber()));
        c.put("achNoticeEmail", nz.apply(app.getAchNoticeEmail()));
        c.put("achDebitDates", nz.apply(app.getAchDebitDates()));
        c.put("achDebitAmounts", nz.apply(app.getAchDebitAmounts()));

        // Appendix 3 -- background check (sensitive PII).
        c.put("bgFullLegalName", nz.apply(app.getBgFullLegalName()));
        c.put("bgOtherNamesUsed", nz.apply(app.getBgOtherNamesUsed()));
        c.put("bgCurrentAddress", nz.apply(app.getBgCurrentAddress()));
        c.put("bgDateOfBirth", fd.apply(app.getBgDateOfBirth()));
        c.put("bgFullSsn", nz.apply(app.getBgFullSsn()));
        c.put("bgDriverLicense", nz.apply(app.getBgDriverLicense()));

        // Appendix 4 -- portal access (optional).
        c.put("portalPlatform", nz.apply(app.getPortalPlatform()));
        c.put("portalUsername", nz.apply(app.getPortalUsername()));
        c.put("portalAuthorizedActions", nz.apply(app.getPortalAuthorizedActions()));
        c.put("portalEffectiveDate", fd.apply(app.getPortalEffectiveDate()));
        c.put("portalRevocationContact", nz.apply(app.getPortalRevocationContact()));

        // Appendix 5 -- security check (optional).
        c.put("securityCheckCount", nz.apply(app.getSecurityCheckCount()));
        c.put("securityCheckNumbers", nz.apply(app.getSecurityCheckNumbers()));
        c.put("securityCheckBank", nz.apply(app.getSecurityCheckBank()));
        c.put("securityCheckHolderName", nz.apply(app.getSecurityCheckHolderName()));
        c.put("securityCheckAmount", nz.apply(app.getSecurityCheckAmount()));
        c.put("securityCheckDates", nz.apply(app.getSecurityCheckDates()));

        // ERM signature block.
        c.put("ermName", nz.apply(app.getErmName()));
        c.put("ermTitle", nz.apply(app.getErmTitle()));
        c.put("ermEmail", agreementErmEmail);
        c.put("signatureDate", fdt.apply(app.getSignatureDate()));

        // Image placeholders -- separate entity fields. Each renders as
        // blank when the corresponding signature hasn't been captured.
        c.put("signatureImage", buildImage(app.getSignatureImage()));
        c.put("ermSignatureImage", buildImage(app.getErmSignatureUrl()));

        return c;
    }

    /**
     * Returns an {@link Image} object for docx-stamper to embed, or an
     * empty string when the URL is missing / unreachable so the
     * placeholder renders blank instead of crashing the run.
     */
    private Object buildImage(String url) {
        if (url == null || url.isBlank()) return "";
        try {
            byte[] bytes;
            try (InputStream in = new URL(url).openStream()) {
                bytes = in.readAllBytes();
            }
            return new Image(bytes);
        } catch (Exception e) {
            log.warn("Failed loading signature image from {}: {}",
                    url, e.getMessage());
            return "";
        }
    }

    // ── Template fill ───────────────────────────────────────────────

    private Path fillTemplate(Map<String, Object> ctx) throws IOException {
        Path out = Files.createTempFile("agreement-", ".docx");
        // standard() -- includes the default preprocessors + comment
        // processors so the template's table-repeat / display-if
        // comments behave as expected.
        // nullToEmpty()      -- any null context value renders as "".
        // image()            -- map preset.Image values into the doc
        //                       at the placeholder position.
        OfficeStamperConfiguration cfg = OfficeStamperConfigurations.standard()
                .addResolver(Resolvers.image())
                .addResolver(Resolvers.nullToEmpty())
                .replaceUnresolvedExpressions(true)
                .unresolvedExpressionsDefaultValue("")
                .setFailOnUnresolvedExpression(false);
        StreamStamper<?> stamper = OfficeStampers.docxStamper(cfg);
        try (InputStream in = templateResource.getInputStream();
             OutputStream os = Files.newOutputStream(out)) {
            stamper.stamp(in, ctx, os);
        }
        return out;
    }

    // ── LibreOffice headless conversion ─────────────────────────────

    private Path convertToPdf(Path docx) throws IOException, InterruptedException {
        Path outDir = docx.getParent();
        Process p = new ProcessBuilder(
                "libreoffice", "--headless",
                "--convert-to", "pdf",
                "--outdir", outDir.toString(),
                docx.toString())
                .redirectErrorStream(true)
                .start();
        if (!p.waitFor(LIBREOFFICE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException(
                    "LibreOffice conversion timed out after "
                            + LIBREOFFICE_TIMEOUT_SECONDS + "s");
        }
        if (p.exitValue() != 0) {
            String err = new String(p.getInputStream().readAllBytes());
            throw new IOException(
                    "LibreOffice failed (exit " + p.exitValue() + "): " + err);
        }
        String pdfName = docx.getFileName().toString()
                .replaceFirst("\\.docx$", ".pdf");
        Path pdf = outDir.resolve(pdfName);
        if (!Files.exists(pdf)) {
            throw new IOException("Expected PDF output missing: " + pdf);
        }
        return pdf;
    }

    // ── Cloudinary upload ───────────────────────────────────────────

    private String uploadToCloudinary(Path pdf, String appId) throws IOException {
        Map<?, ?> result = cloudinary.uploader().upload(pdf.toFile(),
                ObjectUtils.asMap(
                        "public_id", "agreements/" + appId,
                        "resource_type", "raw",
                        "overwrite", true));
        Object url = result.get("secure_url");
        if (url == null) {
            throw new IOException("Cloudinary upload returned no secure_url");
        }
        return url.toString();
    }

    private static void safeDelete(Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (Exception ignore) {
            /* best-effort cleanup */
        }
    }
}
