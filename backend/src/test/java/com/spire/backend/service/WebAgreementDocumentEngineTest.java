package com.spire.backend.service;

import com.spire.backend.entity.WebAgreement;
import com.spire.backend.exception.StorageUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The website's own PDF engine and its LibreOffice slot: its own profile
 * folder (never the console's), one conversion at a time, a wait bounded
 * well under the database pool's 30 seconds, an immediate busy answer when
 * three requests already wait, and the slot always coming back, even when
 * the cleanup after a failed conversion throws (the console's slot-loss
 * bug). A render is admitted before any work: past five in progress it is
 * busy at once, without building, reading or filling anything. LibreOffice
 * itself is stood in for here.
 */
class WebAgreementDocumentEngineTest {

    @TempDir
    Path tmp;

    private final AtomicInteger conversions = new AtomicInteger();
    private final AtomicInteger inside = new AtomicInteger();
    private final AtomicInteger mostAtOnce = new AtomicInteger();
    private CountDownLatch firstInside;
    private CountDownLatch letFirstFinish;
    /** What the stand-in LibreOffice does on each call (by call number, from 1). */
    private volatile Conversion behaviour;
    private final List<Path> recycled = new ArrayList<>();
    private volatile RuntimeException recycleFailure;
    private TestEngine engine;

    private interface Conversion {
        Path run(int call, Path docx, Path outDir) throws IOException, InterruptedException;
    }

    /** The engine with LibreOffice and (optionally) the cleanup stood in for. */
    private class TestEngine extends WebAgreementDocumentEngine {
        @Override
        Path runLibreOffice(Path profile, Path docx, Path outDir) throws IOException, InterruptedException {
            int now = inside.incrementAndGet();
            mostAtOnce.accumulateAndGet(now, Math::max);
            try {
                return behaviour.run(conversions.incrementAndGet(), docx, outDir);
            } finally {
                inside.decrementAndGet();
            }
        }

        @Override
        void recycleProfileSlot(Path profile) {
            synchronized (recycled) {
                recycled.add(profile);
            }
            if (recycleFailure != null) throw recycleFailure;
            super.recycleProfileSlot(profile);
        }
    }

    @BeforeEach
    void setUp() {
        firstInside = new CountDownLatch(1);
        letFirstFinish = new CountDownLatch(1);
        behaviour = (call, docx, outDir) -> pdfFor(docx, outDir);
        engine = new TestEngine();
        engine.profileDir = tmp.resolve("profiles").resolve("slot-0");
    }

    // ── Its own slot ─────────────────────────────────────────────────

    @Test
    void theSlotIsTheWebsitesOwnAndTheWaitIsWellUnderTheDatabaseTimeout() {
        WebAgreementDocumentEngine real = new WebAgreementDocumentEngine();
        assertEquals(Path.of(System.getProperty("java.io.tmpdir"), "sage-web-lo-profiles", "slot-0"), real.profileDir);
        assertFalse(real.profileDir.toString().contains("/sage-lo-profiles"), "never the console's profiles");
        assertEquals(Duration.ofSeconds(15), real.slotWait);
        assertTrue(real.slotWait.compareTo(Duration.ofSeconds(30)) < 0, "under Hikari's 30s connection timeout");
        assertEquals(3, WebAgreementDocumentEngine.MAX_WAITING);
        assertEquals(1, real.slot.availablePermits(), "one slot");
        assertEquals(5, WebAgreementDocumentEngine.MAX_IN_PROGRESS, "one converting, three in line, one preparing");
        assertEquals(5, real.admission.availablePermits());
    }

    @Test
    void aSecondConversionWaitsForTheFirst() throws Exception {
        behaviour = blockFirstCall();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Path> first = pool.submit(() -> engine.convertToPdf(docx("a")));
            assertTrue(firstInside.await(10, TimeUnit.SECONDS));
            Future<Path> second = pool.submit(() -> engine.convertToPdf(docx("b")));

            Thread.sleep(300);
            assertFalse(second.isDone(), "parked on the slot");
            assertEquals(1, conversions.get());

            letFirstFinish.countDown();
            assertTrue(Files.exists(first.get(10, TimeUnit.SECONDS)));
            assertTrue(Files.exists(second.get(10, TimeUnit.SECONDS)));
        } finally {
            letFirstFinish.countDown();
            pool.shutdownNow();
        }
        assertEquals(2, conversions.get());
        assertEquals(1, mostAtOnce.get(), "never two conversions at once");
        assertEquals(1, engine.slot.availablePermits());
        assertTrue(Files.isDirectory(engine.profileDir), "the profile is kept after a good conversion");
    }

    @Test
    void aWaitPastTheLimitIsBusyAndNeverReachesLibreOffice() throws Exception {
        engine.slotWait = Duration.ofMillis(100);
        behaviour = blockFirstCall();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Path> first = pool.submit(() -> engine.convertToPdf(docx("a")));
            assertTrue(firstInside.await(10, TimeUnit.SECONDS));

            StorageUnavailableException e = assertThrows(StorageUnavailableException.class,
                    () -> engine.convertToPdf(docx("b")));
            assertEquals("The document service is busy. Please try again in a minute.", e.getMessage());
            assertEquals(WebAgreementRenderer.BUSY_MESSAGE, e.getMessage());

            letFirstFinish.countDown();
            first.get(10, TimeUnit.SECONDS);
        } finally {
            letFirstFinish.countDown();
            pool.shutdownNow();
        }
        assertEquals(1, conversions.get());
        assertEquals(0, engine.waiting.get());
        assertEquals(1, engine.slot.availablePermits());
    }

    @Test
    void withThreeAlreadyWaitingTheNextIsBusyAtOnce() throws Exception {
        engine.slotWait = Duration.ofSeconds(20);
        behaviour = blockFirstCall();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<Path>> waiters = new ArrayList<>();
        try {
            Future<Path> first = pool.submit(() -> engine.convertToPdf(docx("a")));
            assertTrue(firstInside.await(10, TimeUnit.SECONDS));
            for (int i = 0; i < 3; i++) {
                String name = "w" + i;
                waiters.add(pool.submit(() -> engine.convertToPdf(docx(name))));
            }
            long deadline = System.currentTimeMillis() + 10_000;
            while (engine.waiting.get() < 3 && System.currentTimeMillis() < deadline) Thread.sleep(10);
            assertEquals(3, engine.waiting.get(), "three requests wait");

            long started = System.nanoTime();
            StorageUnavailableException e = assertThrows(StorageUnavailableException.class,
                    () -> engine.convertToPdf(docx("refused")));
            long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertEquals(WebAgreementDocumentEngine.BUSY_MESSAGE, e.getMessage());
            assertTrue(tookMs < 1_000, "answered at once, not after the wait (" + tookMs + " ms)");
            assertEquals(3, engine.waiting.get(), "the refused one never counted as waiting");

            letFirstFinish.countDown();
            first.get(10, TimeUnit.SECONDS);
            for (Future<Path> w : waiters) assertTrue(Files.exists(w.get(10, TimeUnit.SECONDS)));
        } finally {
            letFirstFinish.countDown();
            pool.shutdownNow();
        }
        assertEquals(4, conversions.get(), "the refused request never reached LibreOffice");
        assertEquals(1, mostAtOnce.get());
        assertEquals(0, engine.waiting.get());
        assertEquals(1, engine.slot.availablePermits());
    }

    // ── Admission, before any work ───────────────────────────────────

    @Test
    void pastFiveRendersInProgressTheNextIsBusyAtOnceAndBuildsNothing() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch allIn = new CountDownLatch(WebAgreementDocumentEngine.MAX_IN_PROGRESS);
        ExecutorService pool = Executors.newFixedThreadPool(WebAgreementDocumentEngine.MAX_IN_PROGRESS);
        List<Future<Object>> inProgress = new ArrayList<>();
        AtomicBoolean overridesRan = new AtomicBoolean();
        try {
            for (int i = 0; i < WebAgreementDocumentEngine.MAX_IN_PROGRESS; i++) {
                inProgress.add(pool.submit(() -> engine.admitted(() -> {
                    allIn.countDown();
                    assertTrue(release.await(20, TimeUnit.SECONDS));
                    return null;
                })));
            }
            assertTrue(allIn.await(10, TimeUnit.SECONDS));
            assertEquals(0, engine.admission.availablePermits());

            long started = System.nanoTime();
            StorageUnavailableException e = assertThrows(StorageUnavailableException.class,
                    () -> engine.renderPdfBytes(agreement(), (ctx, svc) -> overridesRan.set(true)));
            long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertEquals(WebAgreementDocumentEngine.BUSY_MESSAGE, e.getMessage());
            assertTrue(tookMs < 500, "answered at once (" + tookMs + " ms)");
            assertFalse(overridesRan.get(), "the overrides (where the renderer reads the signatures) never ran");
            assertThrows(StorageUnavailableException.class, () -> engine.getBlankPreviewPdfBytes(),
                    "the first blank render is admitted like any other");
            assertEquals(0, conversions.get(), "nothing reached LibreOffice");
            assertEquals(0, engine.waiting.get(), "nothing waited for the slot");

            release.countDown();
            for (Future<Object> f : inProgress) f.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertEquals(WebAgreementDocumentEngine.MAX_IN_PROGRESS, engine.admission.availablePermits());

        byte[] pdf = engine.renderPdfBytes(agreement(), (ctx, svc) -> overridesRan.set(true));
        assertEquals("%PDF-1.7", new String(pdf), "admitted again once one finished");
        assertTrue(overridesRan.get());
        assertEquals(1, conversions.get());
    }

    @Test
    void withTheLineFullTheOnePreparingIsAdmittedAndTheSlotDecides() throws Exception {
        engine.slotWait = Duration.ofSeconds(20);
        behaviour = blockFirstCall();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<byte[]>> rendering = new ArrayList<>();
        try {
            rendering.add(pool.submit(() -> engine.renderPdfBytes(agreement(), null)));
            assertTrue(firstInside.await(10, TimeUnit.SECONDS));
            for (int i = 0; i < 3; i++) rendering.add(pool.submit(() -> engine.renderPdfBytes(agreement(), null)));
            long deadline = System.currentTimeMillis() + 10_000;
            while (engine.waiting.get() < 3 && System.currentTimeMillis() < deadline) Thread.sleep(10);
            assertEquals(3, engine.waiting.get(), "one converting, three in line");
            assertEquals(1, engine.admission.availablePermits(), "one place left, for a request preparing");

            AtomicBoolean prepared = new AtomicBoolean();
            StorageUnavailableException e = assertThrows(StorageUnavailableException.class,
                    () -> engine.renderPdfBytes(agreement(), (ctx, svc) -> prepared.set(true)));
            assertEquals(WebAgreementDocumentEngine.BUSY_MESSAGE, e.getMessage());
            assertTrue(prepared.get(), "admitted and prepared; the full line refused it at the slot");
            assertEquals(1, engine.admission.availablePermits(), "its place came back");

            letFirstFinish.countDown();
            for (Future<byte[]> f : rendering) assertEquals("%PDF-1.7", new String(f.get(10, TimeUnit.SECONDS)));
        } finally {
            letFirstFinish.countDown();
            pool.shutdownNow();
        }
        assertEquals(4, conversions.get());
        assertEquals(1, mostAtOnce.get());
        assertEquals(WebAgreementDocumentEngine.MAX_IN_PROGRESS, engine.admission.availablePermits());
        assertEquals(1, engine.slot.availablePermits());
    }

    @Test
    void aFailedRenderGivesTheAdmissionAndTheSlotBack() throws Exception {
        behaviour = (call, docx, outDir) -> {
            throw new IOException("LibreOffice failed (exit 1)");
        };
        for (int i = 0; i < WebAgreementDocumentEngine.MAX_IN_PROGRESS + 2; i++) {
            assertThrows(IOException.class, () -> engine.renderPdfBytes(agreement(), null));
        }
        assertThrows(IllegalStateException.class, () -> engine.renderPdfBytes(agreement(), (ctx, svc) -> {
            throw new IllegalStateException("an override failed");
        }));
        assertEquals(WebAgreementDocumentEngine.MAX_IN_PROGRESS, engine.admission.availablePermits());
        assertEquals(1, engine.slot.availablePermits());
    }

    @Test
    void aCachedBlankTemplateNeedsNoAdmission() throws Exception {
        byte[] first = engine.getBlankPreviewPdfBytes();
        assertEquals(1, conversions.get());
        engine.admission.drainPermits();
        try {
            assertSame(first, engine.getBlankPreviewPdfBytes(), "served from the cache while every render is busy");
        } finally {
            engine.admission.release(WebAgreementDocumentEngine.MAX_IN_PROGRESS);
        }
        assertEquals(1, conversions.get());
    }

    // ── Failures always give the slot back ───────────────────────────

    @Test
    void aFailedConversionWipesTheProfileAndGivesTheSlotBack() throws Exception {
        Files.createDirectories(engine.profileDir.resolve("user"));
        Files.writeString(engine.profileDir.resolve("user").resolve(".lock"), "stale");
        behaviour = (call, docx, outDir) -> {
            if (call == 1) throw new IOException("LibreOffice failed (exit 1)");
            return pdfFor(docx, outDir);
        };

        IOException e = assertThrows(IOException.class, () -> engine.convertToPdf(docx("a")));
        assertEquals("LibreOffice failed (exit 1)", e.getMessage());
        assertEquals(List.of(engine.profileDir), recycled);
        assertFalse(Files.exists(engine.profileDir.resolve("user")), "the stale profile is wiped");
        assertTrue(Files.isDirectory(engine.profileDir), "and made again");
        assertEquals(1, engine.slot.availablePermits());

        engine.slotWait = Duration.ofMillis(100);
        assertTrue(Files.exists(engine.convertToPdf(docx("b"))), "the next conversion gets the slot");
    }

    @Test
    void aCleanupThatThrowsStillGivesTheSlotBack() throws Exception {
        // The console's convertToPdf loses its slot here: the unchecked
        // exception skips its PROFILE_SLOTS.put.
        recycleFailure = new UncheckedIOException(new NoSuchFileException("slot-0/user/gallery"));
        behaviour = (call, docx, outDir) -> {
            if (call <= 2) throw new IOException("LibreOffice failed (exit 81)");
            return pdfFor(docx, outDir);
        };

        assertThrows(UncheckedIOException.class, () -> engine.convertToPdf(docx("a")));
        assertThrows(UncheckedIOException.class, () -> engine.convertToPdf(docx("b")));
        assertEquals(1, engine.slot.availablePermits(), "two failed cleanups, the slot is still there");

        recycleFailure = null;
        engine.slotWait = Duration.ofMillis(100);
        assertTrue(Files.exists(engine.convertToPdf(docx("c"))));
        assertEquals(1, engine.slot.availablePermits());
    }

    @Test
    void theProfileWipeNeverThrowsOnAWalkThatFailsHalfway() throws Exception {
        Path profile = tmp.resolve("walk");
        Path locked = profile.resolve("user").resolve("gallery");
        Files.createDirectories(locked);
        Files.writeString(locked.resolve("x.dat"), "x");
        Files.writeString(profile.resolve("registrymodifications.xcu"), "x");
        // An unreadable folder makes Files.walk fail midway with an unchecked exception.
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        try {
            assertThrows(UncheckedIOException.class, () -> {
                try (var walk = Files.walk(profile)) {
                    walk.count();
                }
            }, "the walk does fail with an unchecked exception");
            assertDoesNotThrow(() -> WebAgreementDocumentEngine.safeDeleteRecursively(profile));
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }
        assertDoesNotThrow(() -> WebAgreementDocumentEngine.safeDeleteRecursively(profile));
        assertFalse(Files.exists(profile));
        assertDoesNotThrow(() -> WebAgreementDocumentEngine.safeDeleteRecursively(tmp.resolve("missing")));
    }

    // ── The copied helpers ───────────────────────────────────────────

    @Test
    void filenamesFollowTheConsolesRule() {
        assertEquals("SageITCO-Agreement_Abhi-G_React.pdf", WebAgreementDocumentEngine.buildPdfFilename(app("Abhi G", "React")));
        assertEquals("SageITCO-Agreement_Maria-OBrien_Java-Full-Stack.pdf",
                WebAgreementDocumentEngine.buildPdfFilename(app("Maria O'Brien", "Java Full Stack")));
        assertEquals("SageITCO-Agreement_Abhi-G.pdf", WebAgreementDocumentEngine.buildPdfFilename(app("Abhi G", null)));
        WebAgreement nameless = app(null, null);
        assertEquals("SageITCO-Agreement_web-0b5e.pdf", WebAgreementDocumentEngine.buildPdfFilename(nameless),
                "the website's files have always carried web-<applicationId>");
        nameless.setConsultantName("Pat Lee");
        assertEquals("SageITCO-Agreement_Pat-Lee.pdf", WebAgreementDocumentEngine.buildPdfFilename(nameless));
        nameless.setConsultantName("!!!");
        assertEquals("SageITCO-Agreement_web-0b5e.pdf", WebAgreementDocumentEngine.buildPdfFilename(nameless));
    }

    @Test
    void theClauseValuesUseTheConsolesFormatsAndNeverTheConsolesErmAddress() {
        WebAgreement t = new WebAgreement();
        t.setEffectiveDate(LocalDate.of(2026, 10, 6));
        t.setWorkAuthorizationCategory("Others");
        t.setWorkAuthorizationOther("H-1B transfer");
        Map<String, String> v = engine.nonEditableDisplayValues(t);
        assertEquals("10-06-2026", v.get("effectiveDate"));
        assertEquals("H-1B transfer", v.get("workAuthorizationCategory"));
        assertEquals("", v.get("ermEmail"), "blank; the renderer puts the owner's website email");
        assertFalse(v.containsValue("ermuser@sageitco.com"), "never the console's agreement-erm.email");
        assertEquals("", v.get("ermSignatureDate"));
        assertEquals("(pending)", v.get("finalSigningIp"));
    }

    // ── Helpers ──────────────────────────────────────────────────────

    /** The first call blocks until {@link #letFirstFinish}; the rest convert at once. */
    private Conversion blockFirstCall() {
        return (call, docx, outDir) -> {
            if (call == 1) {
                firstInside.countDown();
                assertTrue(letFirstFinish.await(20, TimeUnit.SECONDS));
            }
            return pdfFor(docx, outDir);
        };
    }

    private Path docx(String name) throws IOException {
        return Files.writeString(tmp.resolve(name + ".docx"), "docx");
    }

    private static Path pdfFor(Path docx, Path outDir) throws IOException {
        String name = docx.getFileName().toString().replaceFirst("\\.docx$", ".pdf");
        return Files.writeString(outDir.resolve(name), "%PDF-1.7");
    }

    private static WebAgreement app(String name, String track) {
        WebAgreement a = new WebAgreement();
        a.setSignedLegalName(name);
        a.setTechnologyTrack(track);
        a.setApplicationId("0b5e");
        return a;
    }

    /** A small agreement the real template is filled with. */
    private static WebAgreement agreement() {
        WebAgreement a = app("Pat Lee", "Java");
        a.setConsultantEmail("pat@x.com");
        return a;
    }
}
