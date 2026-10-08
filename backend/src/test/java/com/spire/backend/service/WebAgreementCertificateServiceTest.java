package com.spire.backend.service;

import com.spire.backend.entity.WebAgreement;
import com.spire.backend.entity.WebAgreementEvent;
import com.spire.backend.repository.WebAgreementEventRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * The Certificate of Completion is the console's page, built from the
 * website's own audit log: rows in the console's order, no Email OTP
 * sentence or row, "Portal access" from the first ACCESSED event, approver
 * decisions labelled by role. Two-pass hash: the page prints the hash of the
 * pre-stamp bytes, while the stored hash is of the final bytes, so the two
 * never match (copied as is).
 */
class WebAgreementCertificateServiceTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 1, 9, 0, 0);

    private WebAgreementEventRepository events;
    private WebAgreementCertificateService service;
    private final List<WebAgreementEvent> log = new ArrayList<>();

    @BeforeEach
    void setUp() {
        events = mock(WebAgreementEventRepository.class);
        // The repository answers newest first, like the real one.
        when(events.findByAgreementIdOrderByCreatedAtDesc(7L)).thenAnswer(inv -> {
            List<WebAgreementEvent> desc = new ArrayList<>(log);
            desc.sort((x, y) -> y.getCreatedAt().compareTo(x.getCreatedAt()));
            return desc;
        });
        service = new WebAgreementCertificateService(events);
    }

    private static WebAgreement agreement() {
        WebAgreement a = WebAgreementRulesTest.complete();
        a.setId(7L);
        a.setApplicationId("0b5e-uuid");
        a.setConsultantName("Pat Lee");
        a.setTechnologyTrack("Java Full Stack");
        a.setEffectiveDate(LocalDate.of(2026, 10, 1));
        a.setConsentGivenAt(T0);
        a.setConsentIp("10.0.0.10");
        a.setConsentVersion("v1.0");
        a.setSigningAt(T0.plusHours(5));
        a.setSigningIp("10.0.0.50");
        a.setFinalSignedAt(T0.plusHours(5).plusMinutes(2));
        a.setFinalSigningIp("10.0.0.52");
        return a;
    }

    private void event(WebAgreementEvent.EventType type, LocalDateTime at, String ip, String metadata) {
        log.add(WebAgreementEvent.builder()
                .agreementId(7L).eventType(type.name()).actorType("PARTICIPANT")
                .createdAt(at).ipAddress(ip).metadata(metadata).build());
    }

    /** A full history: two submits, two fills, two accesses, two consents, then decisions. */
    private void fullHistory() {
        event(WebAgreementEvent.EventType.CONSENT_GIVEN, T0, "10.0.0.10", "{}");
        event(WebAgreementEvent.EventType.ACCESSED, T0.plusMinutes(1), "10.0.0.1", "{}");
        event(WebAgreementEvent.EventType.CONSENT_GIVEN, T0.plusMinutes(2), "10.0.0.11", "{}");
        event(WebAgreementEvent.EventType.CONSULTANT_FILLED, T0.plusHours(1), "10.0.0.2", "{}");
        event(WebAgreementEvent.EventType.SIGNED, T0.plusHours(2), "10.0.0.3", "{}");
        event(WebAgreementEvent.EventType.ACCESSED, T0.plusHours(3), "10.0.0.4", "{}");
        event(WebAgreementEvent.EventType.CONSULTANT_FILLED, T0.plusHours(4), "10.0.0.5", "{}");
        event(WebAgreementEvent.EventType.SIGNED, T0.plusHours(6), "10.0.0.6", "{}");
        event(WebAgreementEvent.EventType.VERIFIED, T0.plusHours(7), "10.0.0.7", "{\"version\":\"1\"}");
        event(WebAgreementEvent.EventType.SENT_FOR_APPROVAL, T0.plusHours(8), "10.0.0.7", "{}");
        event(WebAgreementEvent.EventType.APPROVAL_REVISION_REQUESTED, T0.plusHours(9), "10.0.0.8",
                "{\"role\":\"MANAGER\",\"round\":1,\"approver\":\"Mia Manager\",\"note\":\"Fix\"}");
        event(WebAgreementEvent.EventType.APPROVAL_APPROVED, T0.plusHours(10), "10.0.0.9",
                "{\"role\":\"ACCOUNTS\",\"round\":2,\"approver\":\"Al Accounts\",\"ip\":\"10.0.0.9\"}");
        event(WebAgreementEvent.EventType.APPROVAL_APPROVED, T0.plusHours(11), "10.0.0.12",
                "{\"round\":2}");
    }

    @Test
    void rowsComeFromOurEventsInTheConsolesOrderWithNoOtpRow() {
        fullHistory();
        List<WebAgreementCertificateService.CertificateEvent> rows =
                service.collectCertificateEvents(agreement());

        assertEquals(List.of(
                "Consent given",
                "Portal access",
                "Agreement filled",
                "Primary signature drawn",
                "Execution signature (review)",
                "Agreement submitted",
                "Manager requested revision",
                "Accounts approved",
                "Approver approved"), rows.stream().map(WebAgreementCertificateService.CertificateEvent::label).toList());
        // Consent and submitted use the OLDEST event; filled uses the LATEST.
        assertEquals(new WebAgreementCertificateService.CertificateEvent(
                "Consent given", "2026-10-01 09:00:00 UTC", "10.0.0.10"), rows.get(0));
        assertEquals("10.0.0.5", rows.get(2).ip());
        assertEquals("2026-10-01 13:00:00 UTC", rows.get(2).timestamp());
        assertEquals("10.0.0.50", rows.get(3).ip());
        assertEquals("2026-10-01 14:00:00 UTC", rows.get(3).timestamp());
        assertEquals("10.0.0.52", rows.get(4).ip());
        assertEquals("10.0.0.3", rows.get(5).ip());
        assertEquals("2026-10-01 11:00:00 UTC", rows.get(5).timestamp());
        verify(events).findByAgreementIdOrderByCreatedAtDesc(7L);
    }

    @Test
    void theFirstAccessedEventIsThePortalAccessRow() {
        fullHistory();
        WebAgreementCertificateService.CertificateEvent access = service.collectCertificateEvents(agreement())
                .stream().filter(r -> r.label().equals("Portal access")).findFirst().orElseThrow();
        assertEquals("2026-10-01 09:01:00 UTC", access.timestamp());
        assertEquals("10.0.0.1", access.ip());
    }

    @Test
    void approverDecisionsAreLabelledByTheirRoleOldestFirst() {
        event(WebAgreementEvent.EventType.APPROVAL_APPROVED, T0.plusHours(3), "10.0.0.3",
                "{\"role\":\"MANAGER\",\"round\":3}");
        event(WebAgreementEvent.EventType.APPROVAL_REVISION_REQUESTED, T0.plusHours(1), "10.0.0.1",
                "{\"role\":\"ACCOUNTS\",\"round\":2,\"note\":\"Bank name\"}");
        event(WebAgreementEvent.EventType.APPROVAL_APPROVED, T0.plusHours(2), "10.0.0.2",
                "{\"role\":\"MANAGER\",\"round\":2}");
        WebAgreement a = agreement();
        a.setSigningAt(null);
        a.setFinalSignedAt(null);

        List<WebAgreementCertificateService.CertificateEvent> rows = service.collectCertificateEvents(a);

        assertEquals(List.of("Accounts requested revision", "Manager approved", "Manager approved"),
                rows.stream().map(WebAgreementCertificateService.CertificateEvent::label).toList());
        assertEquals(List.of("10.0.0.1", "10.0.0.2", "10.0.0.3"),
                rows.stream().map(WebAgreementCertificateService.CertificateEvent::ip).toList());
    }

    @Test
    void thePageIsTheConsolesCertificateWithoutTheOtpSentence() throws Exception {
        fullHistory();
        byte[] out = service.appendCertificateAndStamp(twoPagePdf(), agreement());

        try (PDDocument doc = Loader.loadPDF(out)) {
            assertEquals(3, doc.getNumberOfPages(), "one certificate page, added last");
        }
        String text = lastPageText(out);
        assertTrue(text.contains("Certificate of Completion"), text);
        assertTrue(text.contains("This certificate documents the consultant's electronic execution"), text);
        assertTrue(text.contains("Application ID:"), text);
        assertTrue(text.contains("0b5e-uuid"), text);
        assertTrue(text.contains("Consultant:"), text);
        assertTrue(text.contains("Pat Lee") && text.contains("<pat@x.com>"), text);
        assertTrue(text.contains("Java Full Stack"), text);
        assertTrue(text.contains("2026-10-01"), text);
        assertTrue(text.contains("Electronic Records & Signatures Consent"), text);
        assertTrue(text.contains("Disclosure version:"), text);
        assertTrue(text.contains("v1.0"), text);
        assertTrue(text.contains("Signing audit trail (UTC)"), text);
        assertTrue(text.contains("Portal access"), text);
        assertTrue(text.contains("Manager requested revision"), text);
        assertTrue(text.contains("Algorithm:"), text);
        assertTrue(text.contains("SHA-256"), text);
        assertTrue(text.contains("(see file footer - computed at release)"), text);
        assertTrue(text.contains("Issued by Sage IT Co  *  Generated "), text);
        assertFalse(text.contains("OTP"), "no Email OTP sentence and no OTP row");
    }

    @Test
    void noConsentRecordPrintsTheConsolesLine() throws Exception {
        WebAgreement a = agreement();
        a.setConsentGivenAt(null);
        String text = lastPageText(service.appendCertificateAndStamp(twoPagePdf(), a));
        assertTrue(text.contains("Consent record not present on this row."), text);
        assertFalse(text.contains("Given at:"), text);
    }

    @Test
    void theStampedPagePrintsThePreStampHash() throws Exception {
        fullHistory();
        byte[] preStamp = service.appendCertificatePage(twoPagePdf(), agreement());
        byte[] stamped = service.stampHashFooter(preStamp);

        String text = lastPageText(stamped);
        assertTrue(text.contains("SHA-256 (body + certificate, pre-seal):"), text);
        assertTrue(text.contains(WebAgreementCertificateService.sha256Hex(preStamp)), text);
        assertFalse(lastPageText(preStamp).contains("pre-seal"), "the hash is stamped in the second pass");
    }

    @Test
    void theStoredHashIsOfTheFinalBytesAndNeverThePrintedOne() throws Exception {
        fullHistory();
        byte[] out = service.appendCertificateAndStamp(twoPagePdf(), agreement());

        Matcher m = Pattern.compile("[0-9a-f]{64}").matcher(lastPageText(out));
        assertTrue(m.find(), "a printed hash");
        String printed = m.group();
        String stored = WebAgreementCertificateService.sha256Hex(out);
        assertNotEquals(printed, stored, "the printed (pre-seal) hash never equals the stored one");
        assertFalse(lastPageText(out).contains(stored), "the stored hash is printed nowhere");
    }

    @Test
    void sha256HexIsLowerCaseHex() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                WebAgreementCertificateService.sha256Hex("abc".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void charactersTheFontCantDrawNeverBreakTheCertificate() throws Exception {
        WebAgreement a = agreement();
        a.setConsultantName("Zoë O’Brien — 李");
        a.setTechnologyTrack("Data • AI");
        String text = lastPageText(service.appendCertificateAndStamp(twoPagePdf(), a));
        assertTrue(text.contains("Zoë O'Brien - ?"), text);
        assertTrue(text.contains("Data * AI"), text);
    }

    @Test
    void rowsStopNearTheBottomMarginWithNoContinuedPage() throws Exception {
        for (int i = 0; i < 80; i++) {
            event(WebAgreementEvent.EventType.APPROVAL_APPROVED, T0.plusMinutes(i), "10.0.1." + i,
                    "{\"role\":\"MANAGER\"}");
        }
        byte[] out = service.appendCertificateAndStamp(twoPagePdf(), agreement());
        try (PDDocument doc = Loader.loadPDF(out)) {
            assertEquals(3, doc.getNumberOfPages(), "still one certificate page");
        }
        String text = lastPageText(out);
        int shown = text.split("Manager approved", -1).length - 1;
        assertTrue(shown > 0 && shown < 80, "rows cut off near the bottom: " + shown);
        assertFalse(text.toLowerCase().contains("continued"));
        assertTrue(text.contains("Document integrity"), "the integrity block still prints");
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private static byte[] twoPagePdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static String lastPageText(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(doc.getNumberOfPages());
            stripper.setEndPage(doc.getNumberOfPages());
            return stripper.getText(doc);
        }
    }
}
