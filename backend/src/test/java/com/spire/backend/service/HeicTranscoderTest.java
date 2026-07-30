package com.spire.backend.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Build AQ — detection is the half that runs on every document read,
 * whether or not a converter is installed, so it is the half worth
 * pinning. Misfiring either way is bad: a false negative serves a HEIC
 * the browser cannot display (the original bug), a false positive
 * shells out for a JPEG that never needed converting.
 */
class HeicTranscoderTest {

    /** An ISO-BMFF header: 4 size bytes, "ftyp", then the major brand. */
    private static byte[] isoHeader(String brand) {
        byte[] out = new byte[32];
        out[0] = 0; out[1] = 0; out[2] = 0; out[3] = 0x20;
        System.arraycopy("ftyp".getBytes(StandardCharsets.US_ASCII), 0, out, 4, 4);
        System.arraycopy(brand.getBytes(StandardCharsets.US_ASCII), 0, out, 8, 4);
        return out;
    }

    private static final byte[] JPEG = new byte[]{
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
            0, 0x10, 'J', 'F', 'I', 'F', 0, 1};

    private static final byte[] PNG = new byte[]{
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D};

    @Test
    void detectsByDeclaredContentType() {
        assertTrue(HeicTranscoder.isHeic("image/heic", JPEG));
        assertTrue(HeicTranscoder.isHeic("image/heif", JPEG));
        assertTrue(HeicTranscoder.isHeic("IMAGE/HEIC", JPEG));
        assertTrue(HeicTranscoder.isHeic("image/heic-sequence", JPEG));
    }

    /**
     * The case that actually matters in production: iOS and several
     * Android browsers post a HEIC as octet-stream, or mislabel it
     * image/jpeg. The magic bytes are what catch those.
     */
    @Test
    void detectsByMagicBytesWhenContentTypeLies() {
        assertTrue(HeicTranscoder.isHeic("application/octet-stream", isoHeader("heic")));
        assertTrue(HeicTranscoder.isHeic("image/jpeg", isoHeader("heic")));
        assertTrue(HeicTranscoder.isHeic(null, isoHeader("mif1")));
        assertTrue(HeicTranscoder.isHeic("", isoHeader("hevc")));
    }

    @Test
    void leavesRenderableFormatsAlone() {
        assertFalse(HeicTranscoder.isHeic("image/jpeg", JPEG));
        assertFalse(HeicTranscoder.isHeic("image/png", PNG));
        assertFalse(HeicTranscoder.isHeic("application/pdf",
                "%PDF-1.7".getBytes(StandardCharsets.US_ASCII)));
    }

    /** Browsers decode AVIF natively — converting it would be pure waste. */
    @Test
    void avifIsNotTreatedAsHeic() {
        assertFalse(HeicTranscoder.isHeic("image/avif", isoHeader("avif")));
    }

    @Test
    void toleratesShortAndEmptyInput() {
        assertFalse(HeicTranscoder.isHeic("application/octet-stream", new byte[0]));
        assertFalse(HeicTranscoder.isHeic("application/octet-stream", new byte[]{1, 2, 3}));
        assertFalse(HeicTranscoder.isHeic(null, null));
    }

    /** Nothing to convert must return null, not throw — callers serve the original. */
    @Test
    void emptyInputConvertsToNull() {
        HeicTranscoder transcoder = new HeicTranscoder();
        assertNull(transcoder.toJpeg(null));
        assertNull(transcoder.toJpeg(new byte[0]));
    }

    /**
     * A file that claims to be HEIC but isn't must fail closed. Every
     * converter either rejects it or is absent from this machine; both
     * paths return null so the caller falls back to the stored bytes.
     */
    @Test
    void garbageInputFailsToNullRatherThanThrowing() {
        HeicTranscoder transcoder = new HeicTranscoder();
        assertNull(transcoder.toJpeg("not actually a heic file".getBytes(StandardCharsets.UTF_8)));
    }
}
