package com.spire.backend.service;

import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfImportedPage;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.PdfStamper;
import com.spire.backend.config.BrandConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;

/**
 * The Sage IT Co letterhead under every page of the PDFs the website
 * generates (the signed consent, invoices). The artwork is the same
 * full-page letterhead the agreement template uses; the file comes from
 * {@code brand.letterhead-path} and is stretched to each page's size,
 * as the agreement template does on US Letter.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LetterheadService {

    private final BrandConfig brandConfig;

    private volatile byte[] cached;

    /**
     * The PDF with the letterhead drawn under every page. The body comes back
     * unchanged when there is no letterhead file or the overlay fails, so a
     * missing or broken file never breaks a download.
     */
    public byte[] apply(byte[] body) {
        byte[] letterhead = letterhead();
        if (letterhead == null) return body;
        try {
            return overlay(body, letterhead);
        } catch (Exception e) {
            log.warn("Letterhead overlay failed, using the plain PDF: {}", e.getMessage());
            return body;
        }
    }

    private byte[] letterhead() {
        byte[] bytes = cached;
        if (bytes != null) return bytes;
        String path = brandConfig.getLetterheadPath();
        if (path == null || path.isBlank()) return null;
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) return null;
        try (var in = resource.getInputStream()) {
            bytes = in.readAllBytes();
        } catch (Exception e) {
            log.warn("Couldn't read the letterhead {}: {}", path, e.getMessage());
            return null;
        }
        cached = bytes;
        return bytes;
    }

    /** Draws page 1 of the letterhead under every page of the body, scaled to that page. */
    static byte[] overlay(byte[] bodyBytes, byte[] letterheadBytes) throws Exception {
        PdfReader bodyReader = new PdfReader(bodyBytes);
        PdfReader letterheadReader = new PdfReader(letterheadBytes);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfStamper stamper = new PdfStamper(bodyReader, out);
        PdfImportedPage letterheadPage = stamper.getImportedPage(letterheadReader, 1);
        Rectangle letterheadSize = letterheadReader.getPageSize(1);
        for (int i = 1; i <= bodyReader.getNumberOfPages(); i++) {
            Rectangle size = bodyReader.getPageSize(i);
            stamper.getUnderContent(i).addTemplate(letterheadPage,
                    size.getWidth() / letterheadSize.getWidth(), 0, 0,
                    size.getHeight() / letterheadSize.getHeight(), 0, 0);
        }
        stamper.close();
        bodyReader.close();
        letterheadReader.close();
        return out.toByteArray();
    }
}
