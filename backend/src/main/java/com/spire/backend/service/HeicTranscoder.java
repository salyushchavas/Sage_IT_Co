package com.spire.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Build AQ — transcodes HEIC/HEIF to JPEG so uploaded documents can be
 * PREVIEWED in a browser.
 *
 * iPhones shoot HEIC by default, so a consultant photographing a cheque
 * uploads {@code image/heic}. No mainstream browser can decode it: the
 * ERM clicks "view" and instead of the cheque they get a Save-As dialog
 * for a file Windows has no codec for either. The document is stored
 * fine and is fine in the record — it just cannot be looked at, which is
 * the entire point of the preview.
 *
 * Conversion happens on READ rather than on upload, deliberately: it
 * fixes every HEIC already sitting in S3, not just future ones. The
 * stored bytes stay untouched — the original upload remains the record.
 *
 * No pure-Java HEIC decoder exists on this stack (ImageIO has no HEIF
 * plugin and none of the usual add-ons cover it), so this shells out,
 * the same way the PDF pipeline shells out to LibreOffice. Everything
 * here degrades to "serve the original bytes" — a browser that cannot
 * render them is exactly where we started, so a conversion failure must
 * never turn a working download into an error.
 *
 * Process handling follows the LibreOffice lesson: bounded wait, output
 * fully drained, destroyForcibly + a second wait on timeout. An orphan
 * here would reparent to PID 1 and (under tini) be reaped, but leaving
 * one is still a leak of a PID slot.
 */
@Slf4j
@Service
public class HeicTranscoder {

    /** Generous for a phone photo; a converter still running past this is wedged. */
    private static final long CONVERT_TIMEOUT_SECONDS = 25;

    /** Visually lossless for a document photo, roughly a third of the bytes. */
    private static final String JPEG_QUALITY = "82";

    /**
     * Candidate command lines, most preferred first. {@code {in}} / {@code {out}}
     * are substituted. heif-convert (libheif-examples) is purpose-built and the
     * one the deploy installs; the ImageMagick spellings are fallbacks so this
     * still works on an image that happens to carry them instead.
     */
    private static final List<String[]> CONVERTERS = List.of(
            new String[]{"heif-convert", "-q", JPEG_QUALITY, "{in}", "{out}"},
            new String[]{"magick", "{in}", "-quality", JPEG_QUALITY, "{out}"},
            new String[]{"convert", "{in}", "-quality", JPEG_QUALITY, "{out}"});

    /** Logged once, not per request — a missing converter is a deploy fact, not an event. */
    private final AtomicBoolean warnedUnavailable = new AtomicBoolean(false);

    /**
     * True when these bytes are HEIC/HEIF. Trusts the magic bytes over the
     * declared content type: iOS Safari and several Android browsers post a
     * HEIC as {@code application/octet-stream} or even {@code image/jpeg},
     * so sniffing is what actually catches them.
     */
    public static boolean isHeic(String contentType, byte[] bytes) {
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (ct.startsWith("image/heic") || ct.startsWith("image/heif")) return true;
        return hasHeicBrand(bytes);
    }

    /**
     * ISO base-media "ftyp" box at offset 4, major brand at offset 8.
     * Deliberately excludes {@code avif} — browsers render AVIF natively,
     * so converting it would be pure waste.
     */
    private static boolean hasHeicBrand(byte[] bytes) {
        if (bytes == null || bytes.length < 12) return false;
        if (bytes[4] != 'f' || bytes[5] != 't' || bytes[6] != 'y' || bytes[7] != 'p') {
            return false;
        }
        String brand = new String(bytes, 8, 4, java.nio.charset.StandardCharsets.US_ASCII)
                .toLowerCase(Locale.ROOT);
        return switch (brand) {
            case "heic", "heix", "heim", "heis",
                 "hevc", "hevx", "hevm", "hevs",
                 "mif1", "msf1" -> true;
            default -> false;
        };
    }

    /**
     * Converts HEIC bytes to JPEG, or returns {@code null} when it cannot —
     * no converter on the image, a corrupt file, a timeout. Callers serve the
     * original bytes on null.
     */
    public byte[] toJpeg(byte[] heicBytes) {
        if (heicBytes == null || heicBytes.length == 0) return null;
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("heic-preview-");
            Path in = workDir.resolve("in.heic");
            Path out = workDir.resolve("out.jpg");
            Files.write(in, heicBytes);

            for (String[] template : CONVERTERS) {
                byte[] converted = runConverter(template, in, out, workDir);
                if (converted != null) return converted;
            }
            if (warnedUnavailable.compareAndSet(false, true)) {
                log.warn("No HEIC converter available on this image "
                        + "(tried heif-convert/magick/convert). HEIC uploads will "
                        + "download instead of previewing. Install libheif-examples.");
            }
            return null;
        } catch (Exception e) {
            log.warn("HEIC transcode failed: {}", e.getMessage());
            return null;
        } finally {
            deleteQuietly(workDir);
        }
    }

    /**
     * Runs one candidate. Returns the JPEG bytes, or null when the binary is
     * absent (so the caller tries the next spelling) or the run failed.
     */
    private byte[] runConverter(String[] template, Path in, Path out, Path workDir) {
        String[] cmd = new String[template.length];
        for (int i = 0; i < template.length; i++) {
            cmd[i] = template[i]
                    .replace("{in}", in.toAbsolutePath().toString())
                    .replace("{out}", out.toAbsolutePath().toString());
        }
        Process process = null;
        try {
            process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        } catch (IOException notInstalled) {
            // Binary isn't on this image — try the next spelling.
            return null;
        }
        try {
            // Drain before waiting: a converter that fills the pipe buffer while
            // nobody reads it blocks forever and we'd hit the timeout instead.
            String output;
            try (InputStream stdout = process.getInputStream()) {
                output = new String(stdout.readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
            }
            if (!process.waitFor(CONVERT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("HEIC converter '{}' timed out after {}s; killing it",
                        cmd[0], CONVERT_TIMEOUT_SECONDS);
                return null;
            }
            if (process.exitValue() != 0) {
                log.warn("HEIC converter '{}' exited {}: {}",
                        cmd[0], process.exitValue(), output.strip());
                return null;
            }
            Path produced = resolveOutput(out, workDir);
            if (produced == null) {
                log.warn("HEIC converter '{}' reported success but wrote no output", cmd[0]);
                return null;
            }
            byte[] jpeg = Files.readAllBytes(produced);
            return jpeg.length == 0 ? null : jpeg;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("HEIC converter '{}' failed: {}", cmd[0], e.getMessage());
            return null;
        } finally {
            // Never leave the child behind, on any path out of here.
            if (process.isAlive()) {
                process.destroyForcibly();
                try {
                    process.waitFor(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * heif-convert renames its output to {@code out-1.jpg}, {@code out-2.jpg}…
     * when the container holds more than one image (a Live Photo, a burst),
     * so the exact path we asked for may not exist. Take the first frame.
     */
    private static Path resolveOutput(Path expected, Path workDir) throws IOException {
        if (Files.exists(expected) && Files.size(expected) > 0) return expected;
        try (var stream = Files.list(workDir)) {
            return stream
                    .filter(p -> p.getFileName().toString().startsWith("out"))
                    .filter(p -> {
                        try {
                            return Files.size(p) > 0;
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .min(Comparator.comparing(p -> p.getFileName().toString()))
                    .orElse(null);
        }
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Temp dir; the OS reclaims it.
                }
            });
        } catch (IOException ignored) {
            // Nothing actionable — the file is in the OS temp dir.
        }
    }
}
