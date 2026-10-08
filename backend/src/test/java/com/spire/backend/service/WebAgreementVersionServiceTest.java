package com.spire.backend.service;

import com.spire.backend.entity.WebAgreement;
import com.spire.backend.entity.WebAgreementVersion;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.exception.StorageUnavailableException;
import com.spire.backend.repository.WebAgreementEventRepository;
import com.spire.backend.repository.WebAgreementVersionRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Verify's version: the ERM preview render + the Certificate of Completion,
 * stored through the website's storage under the participant's folder
 * (never the console's {@code agreements/} prefix), numbered max + 1 and
 * tagged with the phase. A render or storage failure is the console's 409
 * and changes nothing. The approvers' readers fall back to null (a live
 * render) when no version is routed or found.
 */
class WebAgreementVersionServiceTest {

    private static final long AGREEMENT = 7L;
    private static final long PARTICIPANT = 10L;

    private WebAgreementRenderer renderer;
    private DocumentStorageService storage;
    private WebAgreementVersionRepository versions;
    private WebAgreementVersionService service;

    private final List<WebAgreementVersion> rows = new ArrayList<>();
    private final Map<String, byte[]> stored = new HashMap<>();
    private int uploads;

    @BeforeEach
    void setUp() throws Exception {
        renderer = mock(WebAgreementRenderer.class);
        when(renderer.renderErmPreviewPdf(any())).thenReturn(pdf(2));

        // The real certificate, over an empty audit log.
        WebAgreementEventRepository events = mock(WebAgreementEventRepository.class);
        when(events.findByAgreementIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
        WebAgreementCertificateService certificate = new WebAgreementCertificateService(events);

        // The real file service over a fake website storage.
        storage = mock(DocumentStorageService.class);
        when(storage.upload(anyLong(), anyString(), any(), anyString())).thenAnswer(inv -> {
            String key = "s3:participant-documents/" + inv.getArgument(0) + "/" + inv.getArgument(1)
                    + "#" + (++uploads);
            stored.put(key, inv.getArgument(2));
            return new DocumentStorageService.StoredFile(key, key);
        });
        when(storage.readBytes(anyString())).thenAnswer(inv -> stored.get(inv.<String>getArgument(0)));
        WebAgreementFileService files = new WebAgreementFileService(storage, mock(HeicTranscoder.class));

        versions = mock(WebAgreementVersionRepository.class);
        when(versions.save(any())).thenAnswer(inv -> {
            WebAgreementVersion v = inv.getArgument(0);
            v.setId((long) rows.size() + 1);
            rows.add(v);
            return v;
        });
        when(versions.findTopByAgreementIdOrderByVersionNumberDesc(anyLong())).thenAnswer(inv ->
                rows.stream().filter(v -> v.getAgreementId().equals(inv.getArgument(0)))
                        .max(Comparator.comparing(WebAgreementVersion::getVersionNumber)));
        when(versions.findByAgreementIdAndVersionNumber(anyLong(), anyInt())).thenAnswer(inv ->
                rows.stream().filter(v -> v.getAgreementId().equals(inv.getArgument(0))
                        && v.getVersionNumber().equals(inv.getArgument(1))).findFirst());
        when(versions.findByAgreementIdOrderByVersionNumberAsc(anyLong())).thenAnswer(inv ->
                rows.stream().filter(v -> v.getAgreementId().equals(inv.getArgument(0)))
                        .sorted(Comparator.comparing(WebAgreementVersion::getVersionNumber)).toList());

        service = new WebAgreementVersionService(renderer, certificate, files, versions);
    }

    private static WebAgreement verified() {
        WebAgreement a = WebAgreementRulesTest.complete();
        a.setId(AGREEMENT);
        a.setApplicationId("0b5e-uuid");
        a.setParticipantUserId(PARTICIPANT);
        a.setOwnerUserId(20L);
        a.setStatus("VERIFIED");
        a.setPhase(1);
        return a;
    }

    // ── Release ──────────────────────────────────────────────────────

    @Test
    void theFirstReleaseIsV1AndEachNextIsMaxPlusOne() {
        WebAgreement a = verified();
        assertEquals(1, service.release(a).versionNumber());
        assertEquals(2, service.release(a).versionNumber());

        // Another agreement's versions never count.
        rows.add(WebAgreementVersion.builder().agreementId(99L).versionNumber(8).s3Key("k").build());
        assertEquals(3, service.release(a).versionNumber());

        assertEquals(List.of(1, 2, 3), service.list(AGREEMENT).stream()
                .map(WebAgreementVersion::getVersionNumber).toList());
    }

    @Test
    void aReleaseStoresTheCertifiedPdfAndPointsTheAgreementAtIt() throws Exception {
        WebAgreement a = verified();
        WebAgreementVersionService.Release r = service.release(a);

        byte[] file = stored.get(r.storedKey());
        assertNotNull(file);
        assertEquals(file.length, r.bytes());
        assertEquals(WebAgreementCertificateService.sha256Hex(file), r.sha256(), "stored hash = SHA-256 of the stored file");
        try (PDDocument doc = Loader.loadPDF(file)) {
            assertEquals(3, doc.getNumberOfPages(), "the ERM preview body + the certificate page");
        }
        verify(renderer).renderErmPreviewPdf(a);
        verify(renderer, never()).renderFinalPdf(any());

        // The agreement points at it but is not saved here (Verify saves it).
        assertEquals(r.storedKey(), a.getConsultantPdfS3Key());
        assertEquals(r.sha256(), a.getDocumentHash());
        assertFalse(a.getConsultantCopyReleased(), "the release flags are Verify's job");

        WebAgreementVersion row = rows.get(0);
        assertEquals(AGREEMENT, row.getAgreementId());
        assertEquals(1, row.getVersionNumber());
        assertEquals(r.storedKey(), row.getS3Key());
        assertEquals(r.sha256(), row.getDocumentHash());
        assertEquals(1, row.getPhase());
    }

    @Test
    void eachVersionIsTaggedWithItsPhase() {
        WebAgreement a = verified();
        service.release(a);
        a.setPhase(2);
        service.release(a);

        assertEquals(1, rows.get(0).getPhase());
        assertEquals(2, rows.get(1).getPhase());
        ArgumentCaptor<String> names = ArgumentCaptor.forClass(String.class);
        verify(storage, times(2)).upload(eq(PARTICIPANT), names.capture(), any(), eq("application/pdf"));
        assertTrue(names.getAllValues().get(0).startsWith("web-agreement-consultant-version-p1-"));
        assertTrue(names.getAllValues().get(1).startsWith("web-agreement-consultant-version-p2-"));
    }

    @Test
    void aRowWithNoPhaseIsStoredAsPhase1LikeTheConsole() {
        WebAgreement a = verified();
        a.setPhase(null);
        service.release(a);
        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        verify(storage).upload(eq(PARTICIPANT), name.capture(), any(), anyString());
        assertTrue(name.getValue().startsWith("web-agreement-consultant-version-p1-"));
        assertNull(rows.get(0).getPhase(), "the row keeps the agreement's phase as it is");
    }

    @Test
    void theStoredFileIsTheParticipantsWebsiteFileNeverTheConsolesAgreementsPrefix() {
        WebAgreement a = verified();
        WebAgreementVersionService.Release r = service.release(a);

        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        verify(storage).upload(eq(PARTICIPANT), name.capture(), any(), eq("application/pdf"));
        assertTrue(name.getValue().matches("web-agreement-consultant-version-p1-\\d{8}-\\d{6}-[0-9a-f]+\\.pdf"),
                name.getValue());
        assertFalse(r.storedKey().startsWith("agreements/"));
        assertFalse(r.storedKey().contains(":agreements/"));
        assertFalse(rows.get(0).getS3Key().startsWith("agreements/"));
    }

    @Test
    void aRenderFailureIsTheConsoles409AndChangesNothing() {
        WebAgreement a = verified();
        a.setConsultantPdfS3Key("s3:old.pdf");
        a.setDocumentHash("oldhash");
        when(renderer.renderErmPreviewPdf(a)).thenThrow(
                new WebAgreementRenderer.RenderException(new IOException("LibreOffice failed (exit 1)")));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.release(a));

        assertEquals("Couldn't render consultant-version PDF: LibreOffice failed (exit 1)", e.getMessage());
        assertUnchanged(a);
    }

    @Test
    void aBusyRenderPermitIsAlsoTheRender409() {
        WebAgreement a = verified();
        a.setConsultantPdfS3Key("s3:old.pdf");
        a.setDocumentHash("oldhash");
        when(renderer.renderErmPreviewPdf(a)).thenThrow(
                new StorageUnavailableException(WebAgreementRenderer.BUSY_MESSAGE, null));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.release(a));

        assertEquals("Couldn't render consultant-version PDF: "
                + "The document service is busy. Please try again in a minute.", e.getMessage());
        assertUnchanged(a);
    }

    @Test
    void aBodyTheCertificateCantReadIsARenderFailure() {
        WebAgreement a = verified();
        a.setConsultantPdfS3Key("s3:old.pdf");
        a.setDocumentHash("oldhash");
        when(renderer.renderErmPreviewPdf(a)).thenReturn("not a pdf".getBytes());

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.release(a));

        assertTrue(e.getMessage().startsWith("Couldn't render consultant-version PDF: "), e.getMessage());
        assertUnchanged(a);
    }

    @Test
    void aStorageFailureIsTheConsoles409AndChangesNothing() {
        WebAgreement a = verified();
        a.setConsultantPdfS3Key("s3:old.pdf");
        a.setDocumentHash("oldhash");
        when(storage.upload(anyLong(), anyString(), any(), anyString()))
                .thenThrow(new RuntimeException("S3 refused the upload"));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.release(a));

        assertEquals("Couldn't store consultant-version PDF: S3 refused the upload", e.getMessage());
        assertEquals("s3:old.pdf", a.getConsultantPdfS3Key());
        assertEquals("oldhash", a.getDocumentHash());
        verify(versions, never()).save(any());
    }

    private void assertUnchanged(WebAgreement a) {
        assertEquals("s3:old.pdf", a.getConsultantPdfS3Key());
        assertEquals("oldhash", a.getDocumentHash());
        verify(storage, never()).upload(anyLong(), anyString(), any(), anyString());
        verify(versions, never()).save(any());
    }

    // ── Regenerate's re-render ───────────────────────────────────────

    @Test
    void aRerenderStoresANewCertifiedFileWithNoRowAndLeavesTheAgreementAlone() {
        WebAgreement a = verified();
        a.setStatus("COMPLETED");
        a.setConsultantPdfS3Key("s3:old.pdf");
        a.setDocumentHash("oldhash");

        WebAgreementVersionService.Rerender r = service.rerenderWithoutRow(a);

        assertEquals(WebAgreementCertificateService.sha256Hex(stored.get(r.storedKey())), r.sha256());
        assertEquals("s3:old.pdf", a.getConsultantPdfS3Key(), "the caller repoints the key");
        assertEquals("oldhash", a.getDocumentHash());
        verify(versions, never()).save(any());
        verify(renderer).renderErmPreviewPdf(a);
    }

    @Test
    void aRerenderFailureCarriesTheUnderlyingMessage() {
        WebAgreement a = verified();
        when(renderer.renderErmPreviewPdf(a)).thenThrow(
                new WebAgreementRenderer.RenderException(new IOException("LibreOffice failed (exit 1)")));
        WebAgreementRenderer.RenderException e = assertThrows(WebAgreementRenderer.RenderException.class,
                () -> service.rerenderWithoutRow(a));
        assertEquals("LibreOffice failed (exit 1)", e.getMessage());
        verify(storage, never()).upload(anyLong(), anyString(), any(), anyString());
    }

    // ── Readers ──────────────────────────────────────────────────────

    @Test
    void aVersionsStoredBytesAreServedAsStoredAndAMissingVersionIs404() {
        WebAgreement a = verified();
        WebAgreementVersionService.Release v1 = service.release(a);
        service.release(a);

        assertArrayEquals(stored.get(v1.storedKey()), service.bytes(AGREEMENT, 1));
        assertEquals(2, rows.size());
        verify(renderer, times(2)).renderErmPreviewPdf(a); // no re-render on read
        assertThrows(ResourceNotFoundException.class, () -> service.bytes(AGREEMENT, 3));
    }

    @Test
    void theRoutedVersionIsTheOneSentForApprovalOrNullForALiveFallback() {
        WebAgreement a = verified();
        WebAgreementVersionService.Release v1 = service.release(a);
        service.release(a);

        a.setApprovalVersionNumber(null);
        assertNull(service.routedBytes(a), "nothing routed (sent before versions existed)");
        a.setApprovalVersionNumber(1);
        assertArrayEquals(stored.get(v1.storedKey()), service.routedBytes(a), "V1 even though V2 exists");
        a.setApprovalVersionNumber(5);
        assertNull(service.routedBytes(a), "a routed version that can't be found");
    }

    @Test
    void theLatestVersionIsTheHighestOrNullWhenThereIsNone() {
        WebAgreement a = verified();
        assertNull(service.latestBytes(a));
        service.release(a);
        WebAgreementVersionService.Release v2 = service.release(a);
        a.setApprovalVersionNumber(1);
        assertArrayEquals(stored.get(v2.storedKey()), service.latestBytes(a));
    }

    @Test
    void aVersionWhoseFileIsGoneReadsAsNull() {
        WebAgreement a = verified();
        WebAgreementVersionService.Release v1 = service.release(a);
        stored.remove(v1.storedKey());
        a.setApprovalVersionNumber(1);
        assertNull(service.bytes(AGREEMENT, 1));
        assertNull(service.routedBytes(a));
        assertNull(service.latestBytes(a));
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private static byte[] pdf(int pages) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) doc.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}
