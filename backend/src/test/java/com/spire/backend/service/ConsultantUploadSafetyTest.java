package com.spire.backend.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * QA 2026-09-28: a consultant uploaded an SVG as their work-authorization
 * document; when a console ERM pressed "View document" its script ran on the
 * site's origin and could read the staff member's sign-in tokens. Uploads are
 * now limited to real pictures and PDFs, whatever type the browser claims.
 */
class ConsultantUploadSafetyTest {

    private static final byte[] PNG = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R'};
    private static final byte[] JPEG = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1};
    private static final byte[] HEIC = new byte[]{0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c'};
    private static final byte[] PDF = "%PDF-1.4\n%âãÏÓ\n".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] SVG = ("<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>")
            .getBytes(StandardCharsets.UTF_8);
    private static final byte[] HTML = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);

    @Test
    void realPicturesAreAccepted() {
        assertTrue(ConsultantApplicationService.isSafeImage("image/png", PNG));
        assertTrue(ConsultantApplicationService.isSafeImage("image/jpeg", JPEG));
        assertTrue(ConsultantApplicationService.isSafeImage("image/heic", HEIC));
        // A phone that labels a JPEG as PNG is still a picture.
        assertTrue(ConsultantApplicationService.isSafeImage("image/png", JPEG));
    }

    @Test
    void svgIsRefusedWhateverItsBytes() {
        assertFalse(ConsultantApplicationService.isSafeImage("image/svg+xml", SVG));
        assertFalse(ConsultantApplicationService.isSafeImage("image/svg+xml", PNG));
    }

    @Test
    void activeContentLabelledAsAPictureIsRefused() {
        assertFalse(ConsultantApplicationService.isSafeImage("image/png", HTML));
        assertFalse(ConsultantApplicationService.isSafeImage("image/jpeg", SVG));
        assertFalse(ConsultantApplicationService.isSafeImage("image/png", new byte[0]));
        assertFalse(ConsultantApplicationService.isSafeImage("image/png", null));
    }

    @Test
    void onlyRealPdfsPassAsPdf() {
        assertTrue(ConsultantApplicationService.hasPdfSignature(PDF));
        assertFalse(ConsultantApplicationService.hasPdfSignature(HTML));
        assertFalse(ConsultantApplicationService.hasPdfSignature(null));
    }
}
