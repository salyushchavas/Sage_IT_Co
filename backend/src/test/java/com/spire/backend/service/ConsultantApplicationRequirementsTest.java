package com.spire.backend.service;

import com.spire.backend.entity.ConsultantApplication;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Build AP — pins the rule that decides which sections the submit gate
 * applies to. This is the resolution the wizard is now handed verbatim,
 * so a change here changes what the consultant is shown.
 *
 * It has produced two production deadlocks, both the same shape: a field
 * the ERM sets and the consultant cannot edit was counted as the
 * consultant "engaging with" the section. That made the section active
 * server-side, which required every consultant field in it — while the
 * wizard, seeing require_appendixN unset, hid the section entirely. The
 * consultant was asked for fields that did not exist on their screen and
 * the agreement could never be submitted.
 *
 * The invariant these tests defend: an ERM-set read-only field NEVER
 * makes a section touched.
 */
class ConsultantApplicationRequirementsTest {

    private static Map<String, Boolean> resolve(ConsultantApplication app) {
        return ConsultantApplicationService.resolveEffectiveRequirements(app, Set.of());
    }

    private static ConsultantApplication blankApp() {
        return new ConsultantApplication();
    }

    @Test
    void nothingSet_noAppendixIsActive() {
        Map<String, Boolean> reqs = resolve(blankApp());
        assertFalse(reqs.get("appendix1"));
        assertFalse(reqs.get("appendix2"));
        assertFalse(reqs.get("appendix3"));
        assertFalse(reqs.get("appendix4"));
        assertFalse(reqs.get("appendix5"));
        assertFalse(reqs.get("ssn"));
        assertFalse(reqs.get("ssnDocRequired"));
    }

    /**
     * The harpreetsq@gmail.com deadlock: the ERM filled the debit schedule
     * but left Appendix 2 not-required. Those two fields are ERM-set and
     * read-only to the consultant, so they must not activate the section.
     */
    @Test
    void ermDebitSchedule_doesNotActivateAppendix2() {
        ConsultantApplication app = blankApp();
        app.setAchDebitDates("15th of every month");
        app.setAchDebitAmounts("$416.67");
        assertFalse(resolve(app).get("appendix2"),
                "an ERM-set read-only field must not make Appendix 2 active");
    }

    /** The same guard for Appendix 4 (Build AB-2), incl. its create-time default. */
    @Test
    void ermPortalFields_doNotActivateAppendix4() {
        ConsultantApplication app = blankApp();
        app.setPortalAuthorizedActions("View timesheets, submit invoices");
        app.setPortalRevocationContact("ops@sageitco.com");
        assertFalse(resolve(app).get("appendix4"),
                "an ERM-set read-only field must not make Appendix 4 active");
    }

    @Test
    void consultantEnteredAchField_activatesAppendix2() {
        ConsultantApplication app = blankApp();
        app.setAchBankName("Chase");
        assertTrue(resolve(app).get("appendix2"),
                "a field the consultant actually typed makes the section active");
    }

    @Test
    void affirmationAlone_activatesItsAppendix() {
        ConsultantApplication app = blankApp();
        app.setAffirmedAppendix2(true);
        assertTrue(resolve(app).get("appendix2"));
    }

    @Test
    void ermRequiredFlag_activatesEvenWhenUntouched() {
        ConsultantApplication app = blankApp();
        app.setRequireAppendix2(true);
        assertTrue(resolve(app).get("appendix2"));
    }

    @Test
    void revisionScope_forcesSectionActive() {
        Map<String, Boolean> reqs = ConsultantApplicationService
                .resolveEffectiveRequirements(blankApp(), Set.of("appendix5"));
        assertTrue(reqs.get("appendix5"), "an ERM revision round forces its sections back into scope");
        assertFalse(reqs.get("appendix2"), "sections outside the round stay inactive");
    }

    @Test
    void ssnDocReupload_isRequiredOnlyWhenRequested() {
        assertFalse(resolve(blankApp()).get("ssnDocRequired"));
        Map<String, Boolean> reqs = ConsultantApplicationService
                .resolveEffectiveRequirements(blankApp(), Set.of("doc:ssn-doc"));
        assertTrue(reqs.get("ssnDocRequired"));
    }

    @Test
    void requireSsn_flowsThrough() {
        ConsultantApplication app = blankApp();
        app.setRequireSsn(true);
        assertTrue(resolve(app).get("ssn"));
    }
}
