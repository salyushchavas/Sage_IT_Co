package com.spire.backend.service;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.spire.backend.entity.AgreementAcceptance;
import com.spire.backend.entity.ProgramSelection;
import com.spire.backend.entity.Role;
import com.spire.backend.entity.User;
import com.spire.backend.repository.AgreementAcceptanceRepository;
import com.spire.backend.repository.ProgramSelectionRepository;
import com.spire.backend.repository.UserRepository;
import com.spire.backend.repository.WorkflowStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Checklist 2.2: only the current agreement text can be signed; the
 * record and the PDF carry the Participant ID, the program and the text's
 * fingerprint; the PDF is kept in document storage and can be downloaded
 * by the right people only.
 */
class SignedAgreementTest {

    private final TermsContentService terms = new TermsContentService();
    private UserRepository users;
    private User pat;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        pat = User.builder().id(10L).email("pat@x.com").fullName("Pat Q Doe").participantId("SAGE-2026-00007")
                .role(Role.builder().name("PARTICIPANT").build()).isActive(true)
                .acknowledgmentComplete(true).documentsComplete(true).programSelectionComplete(true)
                .currentStatus("PROGRAM_SELECTED").build();
        when(users.findById(10L)).thenReturn(Optional.of(pat));
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private ParticipantAgreementService signing(AgreementService agreementService) {
        AgreementAcceptanceRepository agreements = mock(AgreementAcceptanceRepository.class);
        when(agreements.findByUserId(10L)).thenReturn(Optional.empty());
        ProgramSelectionRepository programs = mock(ProgramSelectionRepository.class);
        when(programs.findFirstByUserIdOrderBySelectionDateDesc(10L)).thenReturn(Optional.of(ProgramSelection.builder()
                .program("Full Stack").phase("Phase 1").skillset("Java").targetJobTitle("Developer").build()));
        WorkflowService workflow = new WorkflowService(users, mock(WorkflowStateRepository.class), mock(RecordService.class));
        return new ParticipantAgreementService(agreementService, agreements, users, workflow,
                mock(RecordService.class), mock(ProfileCompletionService.class), terms, programs);
    }

    @Test
    void anOutdatedOrMissingTextVersionCantBeSigned() {
        AgreementService agreementService = mock(AgreementService.class);
        ParticipantAgreementService s = signing(agreementService);
        String v = AgreementService.CURRENT_VERSION;
        for (String[] shown : new String[][]{{null, null}, {"v1.0", terms.fingerprint(v)}, {v, "0".repeat(64)}}) {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> s.sign(10L, "Pat Q Doe", "data:image/png;base64,AA", "draw", "1.1.1.1", "ua", shown[0], shown[1]));
            assertTrue(ex.getMessage().contains("reload"));
        }
        verify(agreementService, never()).signImmediate(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void theSignatureRecordsTheParticipantIdProgramAndFingerprint() {
        AgreementService agreementService = mock(AgreementService.class);
        String v = AgreementService.CURRENT_VERSION;
        signing(agreementService).sign(10L, "Pat Q Doe", "data:image/png;base64,AA", "draw", "1.1.1.1", "ua",
                v, terms.fingerprint(v));
        ArgumentCaptor<AgreementService.SigningDetails> details = ArgumentCaptor.forClass(AgreementService.SigningDetails.class);
        verify(agreementService).signImmediate(eq(10L), eq("Pat Q Doe"), any(), any(), any(), any(), details.capture());
        assertEquals("SAGE-2026-00007", details.getValue().participantId());
        assertEquals("Full Stack · Phase 1 · Java · Developer", details.getValue().programSummary());
        assertEquals(terms.fingerprint(v), details.getValue().textSha256());
        assertTrue(details.getValue().textSha256().matches("[0-9a-f]{64}"));
    }

    private AgreementPdfService pdfService() {
        com.spire.backend.config.BrandConfig brand = mock(com.spire.backend.config.BrandConfig.class);
        when(brand.getName()).thenReturn("Sage IT Co");
        return new AgreementPdfService(brand, terms);
    }

    private AgreementAcceptance signedRow() {
        return AgreementAcceptance.builder().id(5L).user(pat).legalName("Pat Q Doe")
                .agreementVersion(AgreementService.CURRENT_VERSION).status(AgreementService.STATUS_VERIFIED)
                .acceptedAt(LocalDateTime.now()).ipAddress("203.0.113.7").browser("Chrome").os("macOS")
                .signatureMethod("draw").textSha256(terms.fingerprint(AgreementService.CURRENT_VERSION))
                .participantIdSnapshot("SAGE-2026-00007").programSnapshot("Full Stack · Phase 1 · Java · Developer")
                .build();
    }

    @Test
    void thePdfNamesTheParticipantProgramAndHowItWasSigned() throws Exception {
        byte[] pdf = pdfService().renderSignedBytes(signedRow());
        PdfReader reader = new PdfReader(pdf);
        StringBuilder text = new StringBuilder();
        PdfTextExtractor extractor = new PdfTextExtractor(reader);
        for (int i = 1; i <= reader.getNumberOfPages(); i++) text.append(extractor.getTextFromPage(i)).append('\n');
        String t = text.toString().replaceAll("\\s+", " ");
        assertTrue(t.contains("SAGE-2026-00007"), "Participant ID on the PDF");
        assertTrue(t.contains("Full Stack"), "program on the PDF");
        assertTrue(t.contains("signed on the website") || t.contains("on the website"), "on-site wording");
        assertFalse(t.contains("email reply confirmation"), "no claim of an email reply that never happened");
    }

    private record Kit(SignedAgreementService service, DocumentStorageService storage, RecordService records,
                       AgreementAcceptanceRepository agreements) {}

    private Kit kit(User viewer, boolean allowed) {
        AgreementAcceptanceRepository agreements = mock(AgreementAcceptanceRepository.class);
        AgreementAcceptance row = signedRow();
        when(agreements.findByUserId(10L)).thenReturn(Optional.of(row));
        when(agreements.save(any())).thenAnswer(inv -> inv.getArgument(0));
        if (viewer != null) when(users.findById(viewer.getId())).thenReturn(Optional.of(viewer));
        PermissionService permissions = mock(PermissionService.class);
        when(permissions.canViewDocumentsOf(any(), eq(10L))).thenReturn(allowed);
        when(permissions.roleOf(any())).thenReturn(viewer == null ? "" : viewer.getRole().getName());
        DocumentStorageService storage = mock(DocumentStorageService.class);
        when(storage.upload(anyLong(), anyString(), any(), anyString()))
                .thenReturn(new DocumentStorageService.StoredFile("/tmp/does-not-exist.pdf", "/tmp/does-not-exist.pdf"));
        RecordService records = mock(RecordService.class);
        return new Kit(new SignedAgreementService(agreements, users, permissions, storage,
                pdfService(), records, mock(EmailTemplateService.class)), storage, records, agreements);
    }

    @Test
    void theParticipantCanDownloadTheirsAndALostCopyIsRecreated() {
        Kit k = kit(pat, true);
        SignedAgreementService.SignedPdf pdf = k.service().forDownload(10L, 10L);
        assertNotNull(pdf.bytes());
        assertTrue(new String(pdf.bytes(), 0, 5).startsWith("%PDF"));
        assertEquals("Sage-IT-Co-Agreement-SAGE-2026-00007.pdf", pdf.fileName());
        verify(k.storage()).upload(eq(10L), eq("signed-agreement.pdf"), any(), eq("application/pdf"));
        verify(k.records(), never()).record(eq(10L), eq("AGREEMENT_VIEWED"), any(), any(), any(), any());
    }

    @Test
    void staffDownloadsAreRecordedAndOthersAreRefused() {
        User ops = User.builder().id(2L).role(Role.builder().name("OPERATIONS_ADMIN").build()).build();
        Kit k = kit(ops, true);
        k.service().forDownload(10L, 2L);
        verify(k.records()).record(eq(10L), eq("AGREEMENT_VIEWED"), any(), any(), any(), any());

        User other = User.builder().id(3L).role(Role.builder().name("PARTICIPANT").build()).build();
        Kit refused = kit(other, false);
        assertThrows(AccessDeniedException.class, () -> refused.service().forDownload(10L, 3L));
    }

    @Test
    void aCloudCopyIsSentAsBytesNeverAsALink() {
        Kit k = kit(pat, true);
        AgreementAcceptance row = k.agreements().findByUserId(10L).orElseThrow();
        row.setSignedAgreementPdfUrl("https://res.cloudinary.com/x/raw/upload/v1/agreements/10/a.pdf");
        byte[] pdf = "%PDF-1.4 kept".getBytes();
        when(k.storage().retrieve(anyString())).thenReturn(
                new DocumentStorageService.Retrieval(null, pdf, "application/pdf"));
        SignedAgreementService.SignedPdf out = k.service().forDownload(10L, 10L);
        assertNull(out.url(), "no link that could be passed on");
        assertArrayEquals(pdf, out.bytes());
    }

    @Test
    void aStoreThatDoesntAnswerIsAnErrorNotAReason() {
        Kit k = kit(pat, true);
        AgreementAcceptance row = k.agreements().findByUserId(10L).orElseThrow();
        row.setSignedAgreementPdfUrl("s3:participant-documents/10/signed-agreement.pdf");
        row.setPdfSha256("original-hash");
        when(k.storage().retrieve(anyString())).thenThrow(new com.spire.backend.exception.StorageUnavailableException("down", null));
        assertThrows(com.spire.backend.exception.StorageUnavailableException.class, () -> k.service().forDownload(10L, 10L));
        assertEquals("s3:participant-documents/10/signed-agreement.pdf", row.getSignedAgreementPdfUrl(), "not re-created");
        assertEquals("original-hash", row.getPdfSha256(), "the as-signed fingerprint stays");
    }
}
