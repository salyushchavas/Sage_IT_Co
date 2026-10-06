package com.spire.backend.service;

import com.spire.backend.entity.WebAgreement;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The website agreement's copy of the console's rules: which sections the
 * submit gate applies to, what is missing, and what a revision round lets
 * the participant write. The invariant from the console holds here too: an
 * ERM-set read-only field NEVER makes a section touched (that produced two
 * production deadlocks in the console).
 */
class WebAgreementRulesTest {

    private static final byte[] PNG = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R'};
    private static final byte[] SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
            .getBytes(StandardCharsets.UTF_8);

    private static Map<String, Boolean> resolve(WebAgreement a) {
        return WebAgreementRules.resolveEffectiveRequirements(a, Set.of());
    }

    private static WebAgreement blank() {
        return WebAgreement.builder().status(WebAgreement.Status.SUBMITTED.name()).build();
    }

    /** Every CORE field filled, the work-authorization document uploaded, the three ticks set. */
    static WebAgreement complete() {
        return WebAgreement.builder()
                .status(WebAgreement.Status.SUBMITTED.name())
                .applicationId("app-1")
                .firstName("Pat").lastName("Lee")
                .consultantEmail("pat@x.com")
                .primaryPhone("555 201 3344")
                .addressLine1("1 Main St").addressCity("Austin").addressState("TX").addressZip("78701")
                .workAuthDocS3Key("participant-documents/10/web-agreement-workauth.pdf")
                .affirmedMainAgreement(true).affirmedExhibitA(true).affirmedExhibitB(true)
                .build();
    }

    // ── Which sections apply ─────────────────────────────────────────

    @Test
    void nothingSet_noAppendixIsActive() {
        Map<String, Boolean> reqs = resolve(blank());
        for (String k : List.of("appendix1", "appendix2", "appendix3", "appendix4", "appendix5",
                "ssn", "ssnDocRequired")) {
            assertFalse(reqs.get(k), k);
        }
    }

    @Test
    void ermSetFields_neverActivateASection() {
        WebAgreement a = blank();
        a.setAchDebitDates("15th of every month");
        a.setAchDebitAmounts("$416.67");
        a.setPortalAuthorizedActions("View timesheets");
        a.setPortalRevocationContact("ops@sageitco.com");
        a.setPhase2DeliverablePeriod("Monthly");
        Map<String, Boolean> reqs = resolve(a);
        assertFalse(reqs.get("appendix1"));
        assertFalse(reqs.get("appendix2"));
        assertFalse(reqs.get("appendix4"));
    }

    @Test
    void requiredFlagOrParticipantEntry_activatesTheSection() {
        WebAgreement a = blank();
        a.setRequireAppendix1(true);
        a.setAchBankName("Chase");
        a.setPortalEntries("[{\"platform\":\"LinkedIn\",\"username\":\"pat\"}]");
        a.setRequireSsn(true);
        Map<String, Boolean> reqs = resolve(a);
        assertTrue(reqs.get("appendix1"));
        assertTrue(reqs.get("appendix2"));
        assertTrue(reqs.get("appendix4"));
        assertTrue(reqs.get("ssn"));
        assertFalse(reqs.get("appendix3"));
    }

    @Test
    void aRevisionRoundForcesItsSectionsAndTheSsnDocument() {
        WebAgreement a = blank();
        a.setStatus(WebAgreement.Status.REVISION_REQUESTED.name());
        a.setRevisionSections("[{\"key\":\"appendix5\"},{\"key\":\"doc:ssn-doc\"}]");
        Map<String, Boolean> reqs = WebAgreementRules.resolveEffectiveRequirements(a);
        assertTrue(reqs.get("appendix5"));
        assertTrue(reqs.get("ssnDocRequired"));
        // Outside a revision round the same scope forces nothing.
        a.setStatus(WebAgreement.Status.VERIFIED.name());
        assertTrue(WebAgreementRules.revisionForcedSections(a).isEmpty());
        assertFalse(WebAgreementRules.resolveEffectiveRequirements(a).get("appendix5"));
    }

    // ── What is missing ──────────────────────────────────────────────

    @Test
    void blankAgreement_listsTheCoreFieldsAndTheWorkAuthorizationDocument() {
        List<String> missing = WebAgreementRules.collectMissingConsultantFields(blank());
        assertTrue(missing.containsAll(List.of("firstName", "lastName", "consultantEmail", "primaryPhone",
                "addressLine1", "addressCity", "addressState", "addressZip", "workAuthDoc")), missing.toString());
        assertFalse(missing.contains("middleName"));
        assertFalse(missing.contains("addressLine2"));
    }

    @Test
    void completeCore_hasNothingMissing() {
        assertEquals(List.of(), WebAgreementRules.collectMissingConsultantFields(complete()));
        assertEquals(List.of(), WebAgreementRules.collectMissingAffirmations(complete()));
    }

    @Test
    void zipMustBeFiveDigitsOrZipPlusFour() {
        WebAgreement a = complete();
        a.setAddressZip("7870");
        assertTrue(WebAgreementRules.collectMissingConsultantFields(a).contains("addressZip"));
        a.setAddressZip("78701-1234");
        assertFalse(WebAgreementRules.collectMissingConsultantFields(a).contains("addressZip"));
    }

    @Test
    void routingNumberMustBeNineDigits_accountNumberIsFreeForm() {
        WebAgreement a = complete();
        a.setRequireAppendix2(true);
        a.setAchAccountType("Checking");
        a.setAchBankName("Chase");
        a.setAchAccountHolderName("Pat Lee");
        a.setAchAccountNumber("12");
        a.setAchNoticeEmail("pat@x.com");
        a.setAchRoutingNumber("12345678");
        assertEquals(List.of("achRoutingNumber"), WebAgreementRules.collectMissingConsultantFields(a));
        a.setAchRoutingNumber("021-000-021");
        assertEquals(List.of(), WebAgreementRules.collectMissingConsultantFields(a));
    }

    @Test
    void ssnOnlyWhenRequired_andThenAlphanumericOnly() {
        WebAgreement a = appendix3Complete();
        assertEquals(List.of(), WebAgreementRules.collectMissingConsultantFields(a));
        a.setRequireSsn(true);
        assertEquals(List.of("bgFullSsn"), WebAgreementRules.collectMissingConsultantFields(a));
        a.setBgFullSsn("123-45-6789");
        assertEquals(List.of("bgFullSsn"), WebAgreementRules.collectMissingConsultantFields(a));
        a.setBgFullSsn("123456789");
        assertEquals(List.of(), WebAgreementRules.collectMissingConsultantFields(a));
    }

    @Test
    void anIdThatIsStartedMustBeComplete() {
        WebAgreement a = appendix3Complete();
        a.setDlDocS3Key(null);
        a.setBgDriverLicense(null);
        // Nothing provided: at least one ID document.
        assertEquals(List.of("dlDoc"), WebAgreementRules.collectMissingConsultantFields(a));
        // A State-ID document without its number.
        a.setStateIdDocS3Key("participant-documents/10/state.pdf");
        assertEquals(List.of("bgStateId"), WebAgreementRules.collectMissingConsultantFields(a));
        a.setBgStateId("S1234");
        assertEquals(List.of(), WebAgreementRules.collectMissingConsultantFields(a));
    }

    @Test
    void everyDeclaredChequeNeedsANumberAndAFile() {
        WebAgreement a = complete();
        a.setRequireAppendix5(true);
        a.setSecurityCheckCount("2");
        a.setSecurityCheckBank("Chase");
        a.setSecurityCheckHolderName("Pat Lee");
        a.setSecurityCheckAmount("$500");
        a.setCheques("[{\"index\":0,\"number\":\"1001\",\"s3Key\":\"k0\"}]");
        assertEquals(List.of("cheques"), WebAgreementRules.collectMissingConsultantFields(a));
        a.setCheques("[{\"index\":0,\"number\":\"1001\",\"s3Key\":\"k0\"},"
                + "{\"index\":1,\"number\":\"1002\",\"s3Key\":\"k1\"}]");
        assertEquals(List.of(), WebAgreementRules.collectMissingConsultantFields(a));
    }

    @Test
    void appendixTickIsRequiredExactlyWhenTheAppendixIsActive() {
        WebAgreement a = complete();
        assertFalse(WebAgreementRules.collectMissingAffirmations(a).contains("affirmedAppendix1"));
        a.setRequireAppendix1(true);
        assertTrue(WebAgreementRules.collectMissingAffirmations(a).contains("affirmedAppendix1"));
        a.setAffirmedExhibitB(false);
        assertTrue(WebAgreementRules.collectMissingAffirmations(a).contains("affirmedExhibitB"));
    }

    // ── Revision scope ───────────────────────────────────────────────

    @Test
    void writeScope_onlyDuringARevisionRound() {
        WebAgreement a = blank();
        a.setRevisionSections("[{\"key\":\"appendix2\",\"note\":\"bank\"}]");
        assertEquals(Optional.empty(), WebAgreementRules.consultantWriteScope(a));
        a.setStatus(WebAgreement.Status.REVISION_REQUESTED.name());
        assertEquals(Optional.of(Set.of("appendix2")), WebAgreementRules.consultantWriteScope(a));
        assertEquals("appendix2", WebAgreementRules.FIELD_SECTION.get("achBankName"));
        assertEquals("cover", WebAgreementRules.FIELD_SECTION.get("primaryPhone"));
        assertEquals("appendix4", WebAgreementRules.FIELD_SECTION.get("affirmedAppendix4"));
    }

    @Test
    void revisionsCanOnlyBeRequestedFromVerifiedForNow() {
        assertTrue(WebAgreementRules.isRevisionRequestable("VERIFIED"));
        for (String s : List.of("SUBMITTED", "REVISION_REQUESTED", "AWAITING_APPROVALS", "READY_TO_SIGN",
                "COMPLETED", "CANCELLED")) {
            assertFalse(WebAgreementRules.isRevisionRequestable(s), s);
        }
    }

    @Test
    void revisionSummaryReadsLikeTheConsole() {
        assertEquals("Please revise: Exhibit A (wrong track); Appendix 2 — ACH Authorization.",
                WebAgreementRules.buildRevisionSummary(List.of("exhibit-a", "appendix2"),
                        Map.of("exhibit-a", "wrong track")));
    }

    @Test
    void theParticipantPatchCarriesNoErmField() {
        List<String> fields = Arrays.stream(WebAgreementRules.WebAgreementFillPatch.class.getFields())
                .map(java.lang.reflect.Field::getName).toList();
        for (String erm : List.of("achDebitDates", "achDebitAmounts", "portalAuthorizedActions",
                "portalRevocationContact", "workAuthorizationCategory", "technologyTrack",
                "customScopeNotes", "effectiveDate")) {
            assertFalse(fields.contains(erm), erm);
        }
        assertTrue(fields.contains("achBankName"));
    }

    // ── Cheques, dates, uploads ──────────────────────────────────────

    @Test
    void chequesBeyondTheDeclaredCountArePruned() {
        WebAgreement a = blank();
        a.setSecurityCheckCount("1");
        a.setCheques("[{\"index\":0,\"number\":\"1\"},{\"index\":1,\"number\":\"2\"}]");
        WebAgreementRules.pruneChequesToDeclaredCount(a);
        List<WebAgreementRules.ChequeEntry> left = WebAgreementRules.parseCheques(a);
        assertEquals(1, left.size());
        assertEquals("1", left.get(0).number());
    }

    @Test
    void clearingChequeFilesKeepsTheirNumbers() {
        WebAgreement a = blank();
        a.setCheques("[{\"index\":0,\"number\":\"1001\",\"s3Key\":\"k0\",\"contentType\":\"image/png\"}]");
        a.setChequeS3Key("k0");
        WebAgreementRules.clearDocument(a, "doc:cheque");
        WebAgreementRules.ChequeEntry e = WebAgreementRules.parseCheques(a).get(0);
        assertEquals("1001", e.number());
        assertEquals("", e.s3Key());
        assertNull(a.getChequeS3Key());
    }

    @Test
    void onlyTheSectionsSignedThisRoundGetANewDate() {
        WebAgreement a = complete();
        a.setAffirmedAppendix1(true);
        a.setAffirmedAppendix2(true);
        LocalDateTime first = LocalDateTime.of(2026, 1, 5, 10, 0);
        WebAgreementRules.stampSectionSignatureDates(a, first, true);
        a.setSignatureDate(first);
        a.setSignatureS3Key("sig");
        // A revision of Appendix 2 only.
        a.setStatus(WebAgreement.Status.REVISION_REQUESTED.name());
        a.setRevisionSections("[{\"key\":\"appendix2\"}]");
        LocalDateTime second = LocalDateTime.of(2026, 2, 7, 9, 30);
        WebAgreementRules.stampSectionSignatureDates(a, second, false);
        Map<String, String> dates = WebAgreementRules.parseSectionSignatureDates(a.getSectionSignatureDates());
        assertEquals(first.toString(), dates.get("main-agreement"));
        assertEquals(first.toString(), dates.get("appendix1"));
        assertEquals(second.toString(), dates.get("appendix2"));
    }

    @Test
    void picturesAndPdfsPass_svgNeverDoes() {
        assertTrue(WebAgreementRules.isSafeImage("image/png", PNG));
        assertFalse(WebAgreementRules.isSafeImage("image/svg+xml", SVG));
        assertFalse(WebAgreementRules.isSafeImage("image/png", SVG));
        assertTrue(WebAgreementRules.hasPdfSignature("%PDF-1.4".getBytes(StandardCharsets.US_ASCII)));
        assertFalse(WebAgreementRules.hasPdfSignature(SVG));
    }

    @Test
    void namesAreComposedAndSplitLikeTheConsole() {
        assertEquals("Pat Q Lee", WebAgreementRules.composeName("Pat", "Q", "Lee"));
        assertNull(WebAgreementRules.composeName(null, "Q", null));
        WebAgreement a = blank();
        WebAgreementRules.syncNameParts(a, "Ana Maria de Souza");
        assertEquals("Ana", a.getFirstName());
        assertEquals("Maria de", a.getMiddleName());
        assertEquals("Souza", a.getLastName());
    }

    @Test
    void listResponsesLoseTheSensitiveNumbers() {
        WebAgreement a = blank();
        a.setBgFullSsn("123456789");
        a.setAchAccountNumber("99");
        a.setBgDateOfBirth(LocalDate.of(1990, 1, 1));
        List<WebAgreement> list = new ArrayList<>(List.of(a));
        WebAgreementRules.stripSensitivePii(null, list);
        assertNull(a.getBgFullSsn());
        assertNull(a.getAchAccountNumber());
        assertNull(a.getBgDateOfBirth());
    }

    private static WebAgreement appendix3Complete() {
        WebAgreement a = complete();
        a.setRequireAppendix3(true);
        a.setBgFullLegalName("Pat Lee");
        a.setBgOtherNamesUsed("None");
        a.setBgCurrentSameAsResidence(true);
        a.setBgDateOfBirth(LocalDate.of(1990, 1, 1));
        a.setBgDriverLicense("D123");
        a.setDlDocS3Key("participant-documents/10/dl.pdf");
        a.setAffirmedAppendix3(true);
        return a;
    }
}
