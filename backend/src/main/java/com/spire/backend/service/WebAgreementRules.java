package com.spire.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spire.backend.entity.WebAgreement;
import com.spire.backend.entity.WebAgreementEvent;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The website agreement's rules, copied from {@link ConsultantApplicationService}
 * (which stays untouched) and adapted to {@link WebAgreement}: status gates,
 * the revision section picker and write scope, the submit gate (which
 * sections apply, which fields and ticks are missing), cheque bookkeeping,
 * per-section signing dates, upload safety checks and the participant fill
 * patch. Pure static helpers, shared by the participant service and the
 * staff service, so the two sides can never disagree.
 *
 * Differences from the console, all forced by the dropped columns: no
 * Cloudinary ids (a document is present when its *S3Key is set), and no
 * legacy {@code residenceAddress} / {@code portalPlatform} /
 * {@code portalUsername}.
 */
@Slf4j
public final class WebAgreementRules {

    private WebAgreementRules() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Disclosure version pinned at the consent gate (same text as the console's v1.0). */
    public static final String CONSENT_VERSION = "v1.0";

    /** Upload size cap: 1 byte to 10 MB, like the console. */
    public static final long MAX_UPLOAD_BYTES = 10L * 1024L * 1024L;

    /** Highest cheque index the participant may write (0..50). */
    public static final int MAX_CHEQUE_INDEX = 50;

    // ── Status gates ─────────────────────────────────────────────────

    /** The participant may fill, upload and submit only while filling or revising. */
    public static boolean isParticipantWritable(String status) {
        return WebAgreement.Status.SUBMITTED.name().equals(status)
                || WebAgreement.Status.REVISION_REQUESTED.name().equals(status);
    }

    /**
     * The desks an ERM can send a change request from, and the set a revoke
     * can restore a row to: signed, and every approval stage up to the
     * countersign. A request sent during approval leaves the open gates as
     * they are; they drop out of the queues because the status is no longer
     * AWAITING_APPROVALS, and a take-back brings them back.
     */
    public static boolean isRevisionRequestable(String status) {
        return WebAgreement.Status.VERIFIED.name().equals(status)
                || WebAgreement.Status.AWAITING_APPROVALS.name().equals(status)
                || WebAgreement.Status.APPROVAL_REVISION_REQUESTED.name().equals(status)
                || WebAgreement.Status.READY_TO_SIGN.name().equals(status);
    }

    // ── Section-picker revision support ──────────────────────────────

    /** Sections the ERM may select in the revision picker (review/sign excluded). */
    public static final List<String> REVISABLE_SECTION_IDS = List.of(
            "cover", "main-agreement", "exhibit-a", "exhibit-b",
            "appendix1", "appendix2", "appendix3", "appendix4", "appendix5");

    public static final Map<String, String> SECTION_LABELS = Map.ofEntries(
            Map.entry("cover", "Your Information"),
            Map.entry("main-agreement", "The Agreement"),
            Map.entry("exhibit-a", "Exhibit A"),
            Map.entry("exhibit-b", "Exhibit B"),
            Map.entry("appendix1", "Appendix 1 — Employment"),
            Map.entry("appendix2", "Appendix 2 — ACH Authorization"),
            Map.entry("appendix3", "Appendix 3 — Background Check"),
            Map.entry("appendix4", "Appendix 4 — Portal Access"),
            Map.entry("appendix5", "Appendix 5 — Security Cheque"));

    /** field/affirmation key → owning section id (write-scope enforcement). */
    public static final Map<String, String> FIELD_SECTION = buildFieldSectionMap();

    private static Map<String, String> buildFieldSectionMap() {
        Map<String, String> m = new HashMap<>();
        for (String f : new String[]{"firstName", "middleName", "lastName",
                "consultantName", "primaryPhone", "addressLine1", "addressLine2",
                "addressCity", "addressState", "addressZip",
                // ERM-set / read-only cover fields — mapped so a revision of
                // the cover scopes them too.
                "workAuthorizationCategory", "effectiveDate"}) {
            m.put(f, "cover");
        }
        m.put("affirmedMainAgreement", "main-agreement");
        // The Phase-2 rate card (Section 11) lives in the main agreement;
        // ERM-set, mapped so a rate revision scopes the main agreement.
        for (String f : new String[]{"ratePeriod1", "rateAmount1",
                "ratePeriod2", "rateAmount2"}) m.put(f, "main-agreement");
        for (String f : new String[]{"technologyTrack", "customScopeNotes",
                "affirmedExhibitA"}) m.put(f, "exhibit-a");
        m.put("affirmedExhibitB", "exhibit-b");
        for (String f : new String[]{"employerPayrollEntity", "implementationPartner",
                "endClient", "roleTitle", "verifiedStartDate", "payrollCycle",
                "phase2DeliverablePeriod",
                "affirmedAppendix1"}) m.put(f, "appendix1");
        for (String f : new String[]{"achAccountType", "achBankName",
                "achAccountHolderName", "achRoutingNumber", "achAccountNumber",
                "achNoticeEmail", "achDebitDates", "achDebitAmounts",
                "affirmedAppendix2"}) m.put(f, "appendix2");
        for (String f : new String[]{"bgFullLegalName", "bgOtherNamesUsed",
                "bgCurrentAddress", "bgCurrentAddressLine1", "bgCurrentAddressLine2",
                "bgCurrentAddressCity", "bgCurrentAddressState", "bgCurrentAddressZip",
                "bgCurrentSameAsResidence", "bgDateOfBirth", "bgFullSsn", "idType",
                "bgDriverLicense", "bgStateId", "affirmedAppendix3"}) m.put(f, "appendix3");
        // portalAuthorizedActions + portalRevocationContact are ERM-set and
        // not participant-writable, so they are omitted (like ACH debit).
        for (String f : new String[]{"portalEntries", "portalEffectiveDate",
                "affirmedAppendix4"}) m.put(f, "appendix4");
        for (String f : new String[]{"securityCheckCount", "securityCheckBank",
                "securityCheckHolderName", "securityCheckAmount",
                "securityCheckNumbers", "securityCheckDates",
                "affirmedAppendix5"}) m.put(f, "appendix5");
        return Collections.unmodifiableMap(m);
    }

    /**
     * Document re-upload scope keys → the wizard section hosting that upload.
     * Deliberately NOT in {@link #REVISABLE_SECTION_IDS} (like "signature").
     * The SSN document is normally optional, but a re-upload request makes it
     * required again for that round.
     */
    public static final Map<String, String> DOC_REVISION_SECTION = Map.of(
            "doc:workauth", "cover",
            "doc:offer-letter", "appendix1",
            "doc:dl-doc", "appendix3",
            "doc:state-id", "appendix3",
            "doc:ssn-doc", "appendix3",
            "doc:cheque", "appendix5");

    public static final Map<String, String> DOC_REVISION_LABELS = Map.of(
            "doc:workauth", "work-authorization document",
            "doc:offer-letter", "offer letter",
            "doc:dl-doc", "Driver's License document",
            "doc:state-id", "State ID document",
            "doc:ssn-doc", "SSN document",
            "doc:cheque", "security cheque(s)");

    /** "Please revise: Exhibit A (note); Appendix 2 — ACH Authorization." */
    public static String buildRevisionSummary(List<String> keys, Map<String, String> notes) {
        StringBuilder sb = new StringBuilder("Please revise: ");
        for (int i = 0; i < keys.size(); i++) {
            String k = keys.get(i);
            if (i > 0) sb.append("; ");
            sb.append(SECTION_LABELS.getOrDefault(k, k));
            String note = notes.get(k);
            if (note != null && !note.isBlank()) sb.append(" (").append(note).append(")");
        }
        sb.append('.');
        return sb.toString();
    }

    /** Re-arm the "I understand" tick of one section so it must be re-read. */
    public static void clearAffirmationForSection(WebAgreement app, String key) {
        switch (key) {
            case "main-agreement" -> app.setAffirmedMainAgreement(false);
            case "exhibit-a" -> app.setAffirmedExhibitA(false);
            case "exhibit-b" -> app.setAffirmedExhibitB(false);
            case "appendix1" -> app.setAffirmedAppendix1(false);
            case "appendix2" -> app.setAffirmedAppendix2(false);
            case "appendix3" -> app.setAffirmedAppendix3(false);
            case "appendix4" -> app.setAffirmedAppendix4(false);
            case "appendix5" -> app.setAffirmedAppendix5(false);
            default -> { /* cover / review carry no affirmation */ }
        }
    }

    /** Parse a {@code [{"key":"…"}, …]} section-scope JSON into its keys (empty on null/blank/garbage). */
    public static Set<String> parseSectionScopeKeys(String json) {
        Set<String> keys = new LinkedHashSet<>();
        if (json != null && !json.isBlank()) {
            try {
                JsonNode arr = MAPPER.readTree(json);
                if (arr.isArray()) {
                    for (JsonNode n : arr) {
                        String k = n.path("key").asText("");
                        if (!k.isBlank()) keys.add(k);
                    }
                }
            } catch (Exception ignored) { /* treat as unrestricted */ }
        }
        return keys;
    }

    /**
     * The section keys the participant may currently WRITE, or empty when
     * unrestricted. A revision round (REVISION_REQUESTED with a scope) takes
     * precedence; otherwise a Phase-2 fill restricts writes to the reopened
     * sections (later). Present-but-empty means nothing is writable.
     */
    public static Optional<Set<String>> consultantWriteScope(WebAgreement app) {
        if (WebAgreement.Status.REVISION_REQUESTED.name().equals(app.getStatus())
                && app.getRevisionSections() != null && !app.getRevisionSections().isBlank()) {
            return Optional.of(parseSectionScopeKeys(app.getRevisionSections()));
        }
        Integer phase = app.getPhase();
        if (phase != null && phase >= 2
                && WebAgreement.Status.SUBMITTED.name().equals(app.getStatus())
                && app.getPhase2ReopenedSections() != null
                && !app.getPhase2ReopenedSections().isBlank()) {
            return Optional.of(parseSectionScopeKeys(app.getPhase2ReopenedSections()));
        }
        return Optional.empty();
    }

    /**
     * True when the participant can edit the fields of {@code sectionId} in
     * this round: an unrestricted fill, or the section is in the revision or
     * Phase-2 scope. A {@code doc:*} or "signature" key opens no fields.
     */
    public static boolean isSectionWritable(WebAgreement app, String sectionId) {
        return consultantWriteScope(app).map(scope -> scope.contains(sectionId)).orElse(true);
    }

    /**
     * Sections the ERM selected in a restricted revision round. They become
     * REQUIRED for that round even if optional/untouched. Empty otherwise.
     */
    public static Set<String> revisionForcedSections(WebAgreement app) {
        if (WebAgreement.Status.REVISION_REQUESTED.name().equals(app.getStatus())
                && app.getRevisionSections() != null
                && !app.getRevisionSections().isBlank()) {
            return parseSectionScopeKeys(app.getRevisionSections());
        }
        return Collections.emptySet();
    }

    /** Drop the undo record of the open change request (the round is over). */
    public static void clearRevisionUndo(WebAgreement app) {
        app.setRevisionPrevStatus(null);
        app.setRevisionRequestedAt(null);
        app.setRevisionUndoSnapshot(null);
    }

    /**
     * Null the stored pointer(s) for one re-upload key so a fresh upload is
     * required. The stored file itself is kept (the console keeps old S3
     * objects too).
     */
    public static void clearDocument(WebAgreement app, String docKey) {
        switch (docKey) {
            case "doc:workauth" -> {
                app.setWorkAuthDocS3Key(null);
                app.setWorkAuthDocContentType(null);
                app.setWorkAuthDocUploadedAt(null);
            }
            case "doc:offer-letter" -> {
                app.setOfferLetterS3Key(null);
                app.setOfferLetterContentType(null);
                app.setOfferLetterUploadedAt(null);
            }
            case "doc:dl-doc" -> {
                app.setDlDocS3Key(null);
                app.setDlDocContentType(null);
                app.setDlDocUploadedAt(null);
            }
            case "doc:state-id" -> {
                app.setStateIdDocS3Key(null);
                app.setStateIdDocContentType(null);
                app.setStateIdDocUploadedAt(null);
            }
            case "doc:ssn-doc" -> {
                app.setSsnDocS3Key(null);
                app.setSsnDocContentType(null);
                app.setSsnDocUploadedAt(null);
            }
            case "doc:cheque" -> clearChequeFiles(app);
            default -> { /* unknown key — ignore */ }
        }
    }

    /**
     * Every participant WRITE a revision round can accept. Once one of these
     * lands after the request, the round can no longer be taken back. A write
     * that emits no event is invisible to that guard, so every write path
     * must emit one of these.
     */
    public static final Set<String> CONSULTANT_WORK_EVENTS = Set.of(
            WebAgreementEvent.EventType.CONSULTANT_FILLED.name(),
            WebAgreementEvent.EventType.CHEQUE_UPLOADED.name(),
            WebAgreementEvent.EventType.CHEQUE_METADATA_UPDATED.name(),
            WebAgreementEvent.EventType.WORK_AUTH_UPLOADED.name(),
            WebAgreementEvent.EventType.OFFER_LETTER_UPLOADED.name(),
            WebAgreementEvent.EventType.DL_DOC_UPLOADED.name(),
            WebAgreementEvent.EventType.STATE_ID_DOC_UPLOADED.name(),
            WebAgreementEvent.EventType.SSN_DOC_UPLOADED.name());

    // ── Effective-requirements gate ──────────────────────────────────
    //
    // CORE (always required): name, email, phone, billing address, the
    // work-authorization document, and the main agreement + exhibit ticks.
    // APPENDIX 1..5: active when the ERM required it, OR the participant
    // engaged with it (optional-but-touched is all-or-nothing), OR an ERM
    // revision round forced it back in. An ERM-set read-only field NEVER
    // makes a section touched.

    /** True if {@code s} has any non-whitespace character. */
    public static boolean nonBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    /** Fields whose presence makes Appendix 1 "touched" (implementationPartner never counts). */
    static boolean isAppendix1Touched(WebAgreement app) {
        return nonBlank(app.getEmployerPayrollEntity())
                || nonBlank(app.getEndClient())
                || nonBlank(app.getRoleTitle())
                || app.getVerifiedStartDate() != null
                || nonBlank(app.getPayrollCycle())
                || Boolean.TRUE.equals(app.getAffirmedAppendix1());
    }

    /** ACH debit dates/amounts are ERM-set, so they never make Appendix 2 touched. */
    static boolean isAppendix2Touched(WebAgreement app) {
        return nonBlank(app.getAchAccountType())
                || nonBlank(app.getAchBankName())
                || nonBlank(app.getAchAccountHolderName())
                || nonBlank(app.getAchRoutingNumber())
                || nonBlank(app.getAchAccountNumber())
                || nonBlank(app.getAchNoticeEmail())
                || Boolean.TRUE.equals(app.getAffirmedAppendix2());
    }

    /** Document uploads do NOT make Appendix 3 touched; entered data and the tick do. */
    static boolean isAppendix3Touched(WebAgreement app) {
        return nonBlank(app.getBgFullLegalName())
                || nonBlank(app.getBgOtherNamesUsed())
                || nonBlank(app.getBgCurrentAddress())
                || nonBlank(app.getBgCurrentAddressLine1())
                || nonBlank(app.getBgCurrentAddressLine2())
                || nonBlank(app.getBgCurrentAddressCity())
                || nonBlank(app.getBgCurrentAddressState())
                || nonBlank(app.getBgCurrentAddressZip())
                || Boolean.TRUE.equals(app.getBgCurrentSameAsResidence())
                || app.getBgDateOfBirth() != null
                || nonBlank(app.getBgFullSsn())
                || nonBlank(app.getBgDriverLicense())
                || nonBlank(app.getBgStateId())
                || Boolean.TRUE.equals(app.getAffirmedAppendix3());
    }

    /** Authorized actions + revocation contact are ERM-set (one has a default), so they never count. */
    static boolean isAppendix4Touched(WebAgreement app) {
        return nonBlank(app.getPortalEntries())
                || app.getPortalEffectiveDate() != null
                || Boolean.TRUE.equals(app.getAffirmedAppendix4());
    }

    static boolean isAppendix5Touched(WebAgreement app) {
        return nonBlank(app.getSecurityCheckCount())
                || nonBlank(app.getSecurityCheckNumbers())
                || nonBlank(app.getSecurityCheckBank())
                || nonBlank(app.getSecurityCheckHolderName())
                || nonBlank(app.getSecurityCheckAmount())
                || nonBlank(app.getSecurityCheckDates())
                || Boolean.TRUE.equals(app.getAffirmedAppendix5());
    }

    /** At least one portal entry with BOTH a platform and a username. */
    static boolean hasCompletePortalEntry(WebAgreement app) {
        String json = app.getPortalEntries();
        if (json == null || json.isBlank()) return false;
        try {
            JsonNode arr = MAPPER.readTree(json);
            if (arr.isArray()) {
                for (JsonNode e : arr) {
                    if (nonBlank(e.path("platform").asText(""))
                            && nonBlank(e.path("username").asText(""))) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
            // malformed JSON — no complete entry
        }
        return false;
    }

    /**
     * THE resolution of "which sections does the submit gate apply to".
     * The two collectMissing* validators and the participant read (handed to
     * the wizard verbatim) all use it. Keys match the wizard's
     * EffectiveRequirements shape.
     */
    public static Map<String, Boolean> resolveEffectiveRequirements(WebAgreement app) {
        return resolveEffectiveRequirements(app, revisionForcedSections(app));
    }

    /** The pure half, with an explicit {@code forced} scope (tests pin the rule here). */
    public static Map<String, Boolean> resolveEffectiveRequirements(
            WebAgreement app, Set<String> forced) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        out.put("appendix1", Boolean.TRUE.equals(app.getRequireAppendix1())
                || isAppendix1Touched(app) || forced.contains("appendix1"));
        out.put("appendix2", Boolean.TRUE.equals(app.getRequireAppendix2())
                || isAppendix2Touched(app) || forced.contains("appendix2"));
        out.put("appendix3", Boolean.TRUE.equals(app.getRequireAppendix3())
                || isAppendix3Touched(app) || forced.contains("appendix3"));
        out.put("appendix4", Boolean.TRUE.equals(app.getRequireAppendix4())
                || isAppendix4Touched(app) || forced.contains("appendix4"));
        out.put("appendix5", Boolean.TRUE.equals(app.getRequireAppendix5())
                || isAppendix5Touched(app) || forced.contains("appendix5"));
        out.put("ssn", Boolean.TRUE.equals(app.getRequireSsn()));
        // Only an ERM re-upload request re-requires the SSN doc.
        out.put("ssnDocRequired", forced.contains("doc:ssn-doc"));
        return out;
    }

    /**
     * The keys of every effectively-required participant field that's blank
     * or malformed. The console's checks apply in every round. The two
     * website-only rules (phone digits, 18 or older), stricter than the
     * console, apply only where the participant can edit the field this
     * round: a value saved before they existed must never block a submit
     * from a section the participant can't open (Phase 2, change requests).
     */
    public static List<String> collectMissingConsultantFields(WebAgreement app) {
        List<String> missing = new ArrayList<>();
        Set<String> forced = revisionForcedSections(app);
        Map<String, Boolean> active = resolveEffectiveRequirements(app, forced);
        // CORE: structured name (middle optional), email, phone, address (line2 optional).
        addIfBlank(missing, "firstName", app.getFirstName());
        addIfBlank(missing, "lastName", app.getLastName());
        addIfBlank(missing, "consultantEmail", app.getConsultantEmail());
        addIfBlank(missing, "primaryPhone", app.getPrimaryPhone());
        // The wizard's phone rule: 10 to 15 digits, area code included.
        if (nonBlank(app.getPrimaryPhone()) && isSectionWritable(app, "cover")
                && !isValidPhone(app.getPrimaryPhone())) {
            missing.add("primaryPhone");
        }
        addIfBlank(missing, "addressLine1", app.getAddressLine1());
        addIfBlank(missing, "addressCity", app.getAddressCity());
        addIfBlank(missing, "addressState", app.getAddressState());
        addIfBlank(missing, "addressZip", app.getAddressZip());
        if (nonBlank(app.getAddressZip())
                && !app.getAddressZip().trim().matches("\\d{5}(-\\d{4})?")) {
            missing.add("addressZip");
        }
        // The work-authorization document is CORE for every work-auth type.
        if (!nonBlank(app.getWorkAuthDocS3Key())) {
            missing.add("workAuthDoc");
        }

        // Appendix 1 — employment. implementationPartner is never required.
        if (Boolean.TRUE.equals(active.get("appendix1"))) {
            addIfBlank(missing, "employerPayrollEntity", app.getEmployerPayrollEntity());
            addIfBlank(missing, "endClient", app.getEndClient());
            addIfBlank(missing, "roleTitle", app.getRoleTitle());
            if (app.getVerifiedStartDate() == null) missing.add("verifiedStartDate");
            addIfBlank(missing, "payrollCycle", app.getPayrollCycle());
            if (!nonBlank(app.getOfferLetterS3Key())) {
                missing.add("offerLetter");
            }
        }

        // Appendix 2 — ACH (the debit schedule is ERM-set, not part of the gate).
        boolean app2Active = Boolean.TRUE.equals(active.get("appendix2"));
        if (app2Active) {
            addIfBlank(missing, "achAccountType", app.getAchAccountType());
            addIfBlank(missing, "achBankName", app.getAchBankName());
            addIfBlank(missing, "achAccountHolderName", app.getAchAccountHolderName());
            addIfBlank(missing, "achRoutingNumber", app.getAchRoutingNumber());
            addIfBlank(missing, "achAccountNumber", app.getAchAccountNumber());
            addIfBlank(missing, "achNoticeEmail", app.getAchNoticeEmail());
        }

        // Appendix 3 — background check.
        boolean app3Active = Boolean.TRUE.equals(active.get("appendix3"));
        if (app3Active) {
            addIfBlank(missing, "bgFullLegalName", app.getBgFullLegalName());
            addIfBlank(missing, "bgOtherNamesUsed", app.getBgOtherNamesUsed());
            // "Same as residence" makes the residence address authoritative.
            if (!Boolean.TRUE.equals(app.getBgCurrentSameAsResidence())) {
                addIfBlank(missing, "bgCurrentAddressLine1", app.getBgCurrentAddressLine1());
                addIfBlank(missing, "bgCurrentAddressCity", app.getBgCurrentAddressCity());
                addIfBlank(missing, "bgCurrentAddressState", app.getBgCurrentAddressState());
                addIfBlank(missing, "bgCurrentAddressZip", app.getBgCurrentAddressZip());
            }
            if (app.getBgDateOfBirth() == null) {
                missing.add("bgDateOfBirth");
            } else if (isSectionWritable(app, "appendix3")
                    && !isAdultDateOfBirth(app.getBgDateOfBirth())) {
                // A real past date of birth, 18 or older (the wizard's rule).
                missing.add("bgDateOfBirth");
            }
            // A Driver's License AND/OR a State ID: at least one, and any ID
            // started (number OR document) must be complete (number + doc).
            boolean dlNum = nonBlank(app.getBgDriverLicense());
            boolean dlDoc = nonBlank(app.getDlDocS3Key());
            boolean stateNum = nonBlank(app.getBgStateId());
            boolean stateDoc = nonBlank(app.getStateIdDocS3Key());
            boolean dlProvided = dlNum || dlDoc;
            boolean stateProvided = stateNum || stateDoc;
            if (!dlProvided && !stateProvided) {
                missing.add("dlDoc");
            } else {
                if (dlProvided) {
                    if (!dlNum) missing.add("bgDriverLicense");
                    if (!dlDoc) missing.add("dlDoc");
                }
                if (stateProvided) {
                    if (!stateNum) missing.add("bgStateId");
                    if (!stateDoc) missing.add("stateIdDoc");
                }
            }
            if (Boolean.TRUE.equals(app.getRequireSsn())) {
                addIfBlank(missing, "bgFullSsn", app.getBgFullSsn());
            }
        }

        // An ERM "Request re-upload" of the SSN document makes it required
        // until replaced, even in a doc-only round.
        if (forced.contains("doc:ssn-doc") && !nonBlank(app.getSsnDocS3Key())) {
            missing.add("ssnDoc");
        }

        // Appendix 4 — portal access (authorized actions + revocation contact are ERM-set).
        if (Boolean.TRUE.equals(active.get("appendix4"))) {
            if (!hasCompletePortalEntry(app)) {
                missing.add("portalEntries");
            }
            if (app.getPortalEffectiveDate() == null) missing.add("portalEffectiveDate");
        }

        // Appendix 5 — each cheque 0..count-1 needs a number AND an upload.
        if (Boolean.TRUE.equals(active.get("appendix5"))) {
            addIfBlank(missing, "securityCheckCount", app.getSecurityCheckCount());
            addIfBlank(missing, "securityCheckBank", app.getSecurityCheckBank());
            addIfBlank(missing, "securityCheckHolderName", app.getSecurityCheckHolderName());
            addIfBlank(missing, "securityCheckAmount", app.getSecurityCheckAmount());
            int requiredCount = parseChequeCountSafe(app.getSecurityCheckCount());
            if (requiredCount <= 0) {
                missing.add("cheques");
            } else {
                List<ChequeEntry> entries = parseCheques(app);
                for (int i = 0; i < requiredCount; i++) {
                    ChequeEntry e = findEntry(entries, i);
                    if (e == null || e.number() == null || e.number().isBlank()
                            || !nonBlank(e.s3Key())) {
                        missing.add("cheques");
                        break;
                    }
                }
            }
        }

        // Strict format checks, only when the field is effectively required.
        if (app2Active) {
            if (nonBlank(app.getAchRoutingNumber())
                    && !app.getAchRoutingNumber().replaceAll("\\D", "").matches("\\d{9}")) {
                missing.add("achRoutingNumber");
            }
            // The account number is free-form: lengths vary by bank.
        }
        if (app3Active && Boolean.TRUE.equals(app.getRequireSsn())) {
            // SSN is strictly alphanumeric; no hyphens, spaces or symbols.
            if (nonBlank(app.getBgFullSsn())
                    && !app.getBgFullSsn().trim().matches("[A-Za-z0-9]+")) {
                missing.add("bgFullSsn");
            }
        }
        return missing;
    }

    /** The keys of every effectively-required affirmation still false / null. */
    public static List<String> collectMissingAffirmations(WebAgreement app) {
        List<String> missing = new ArrayList<>();
        Map<String, Boolean> active = resolveEffectiveRequirements(app);
        if (!Boolean.TRUE.equals(app.getAffirmedMainAgreement())) missing.add("affirmedMainAgreement");
        if (!Boolean.TRUE.equals(app.getAffirmedExhibitA())) missing.add("affirmedExhibitA");
        if (!Boolean.TRUE.equals(app.getAffirmedExhibitB())) missing.add("affirmedExhibitB");
        if (Boolean.TRUE.equals(active.get("appendix1"))
                && !Boolean.TRUE.equals(app.getAffirmedAppendix1())) {
            missing.add("affirmedAppendix1");
        }
        if (Boolean.TRUE.equals(active.get("appendix2"))
                && !Boolean.TRUE.equals(app.getAffirmedAppendix2())) {
            missing.add("affirmedAppendix2");
        }
        if (Boolean.TRUE.equals(active.get("appendix3"))
                && !Boolean.TRUE.equals(app.getAffirmedAppendix3())) {
            missing.add("affirmedAppendix3");
        }
        if (Boolean.TRUE.equals(active.get("appendix4"))
                && !Boolean.TRUE.equals(app.getAffirmedAppendix4())) {
            missing.add("affirmedAppendix4");
        }
        if (Boolean.TRUE.equals(active.get("appendix5"))
                && !Boolean.TRUE.equals(app.getAffirmedAppendix5())) {
            missing.add("affirmedAppendix5");
        }
        return missing;
    }

    private static void addIfBlank(List<String> out, String key, String value) {
        if (value == null || value.trim().isEmpty()) out.add(key);
    }

    // ── Field formats (the wizard's rules, mirrored) ─────────────────

    /** Digits, spaces and + - . ( ) only, with 10 to 15 digits (area code included). */
    public static boolean isValidPhone(String raw) {
        if (raw == null) return false;
        String t = raw.trim();
        if (!t.matches("[+0-9 ().-]+")) return false;
        int digits = t.replaceAll("\\D", "").length();
        return digits >= 10 && digits <= 15;
    }

    /** A date of birth in the past, at least 18 years ago. */
    public static boolean isAdultDateOfBirth(java.time.LocalDate dob) {
        return dob != null && !dob.plusYears(18).isAfter(java.time.LocalDate.now());
    }

    /**
     * The person-name rule of {@link PersonNames}: letters, marks, digits,
     * spaces and . , ' ’ - only, starting with a letter or digit. Without its
     * two-character minimum, so a middle initial (and a name still being
     * typed) passes.
     */
    private static final java.util.regex.Pattern PERSON_NAME =
            java.util.regex.Pattern.compile("^[\\p{L}\\p{M}\\p{N}][\\p{L}\\p{M}\\p{N} .,'’-]*$");

    /** Most characters of a structured name part (first_name/middle_name/last_name columns). */
    public static final int NAME_PART_MAX = 120;

    /** Most characters of the composed name (the consultant_name column). */
    public static final int COMPOSED_NAME_MAX = 255;

    /** Most characters of one cheque number (the wizard's input stops there too). */
    public static final int CHEQUE_NUMBER_MAX = 64;

    /** Most characters of a portal platform or username. */
    public static final int PORTAL_ENTRY_MAX = 255;

    /**
     * The fill patch's hard limits, checked before anything is saved so one
     * bad value is a 400 naming the field instead of a failed save: each text
     * value fits its column (the wizard stops typing at the same size), the
     * name parts that print as the legal name hold name characters only, and
     * the cheque count is a whole number up to 50. Formats a value only
     * reaches once fully typed (phone, ZIP, date of birth) are left to the
     * submit gate, so the autosave of a half-typed value still works.
     */
    public static void validateFillPatch(WebAgreementFillPatch p) {
        if (p == null) return;
        personNamePart(p.firstName, "First name");
        personNamePart(p.middleName, "Middle name");
        personNamePart(p.lastName, "Last name");
        maxLength(p.primaryPhone, "Primary phone", 32);
        maxLength(p.addressLine1, "Address line 1", 255);
        maxLength(p.addressLine2, "Address line 2", 255);
        maxLength(p.addressCity, "City", 120);
        maxLength(p.addressState, "State", 8);
        maxLength(p.addressZip, "ZIP code", 10);
        maxLength(p.employerPayrollEntity, "Employer (payroll entity)", 255);
        maxLength(p.implementationPartner, "Implementation partner", 255);
        maxLength(p.endClient, "End client", 255);
        maxLength(p.roleTitle, "Role / position", 255);
        maxLength(p.payrollCycle, "Payroll cycle", 255);
        maxLength(p.achAccountType, "Account type", 255);
        maxLength(p.achBankName, "Bank name", 255);
        maxLength(p.achAccountHolderName, "Account holder name", 255);
        maxLength(p.achRoutingNumber, "Routing number", 255);
        maxLength(p.achAccountNumber, "Account number", 255);
        maxLength(p.achNoticeEmail, "Email for advance notice", 255);
        maxLength(p.bgFullLegalName, "Full legal name", 255);
        maxLength(p.bgOtherNamesUsed, "Other names used", 255);
        maxLength(p.bgCurrentAddressLine1, "Current address line 1", 255);
        maxLength(p.bgCurrentAddressLine2, "Current address line 2", 255);
        maxLength(p.bgCurrentAddressCity, "Current address city", 120);
        maxLength(p.bgCurrentAddressState, "Current address state", 8);
        maxLength(p.bgCurrentAddressZip, "Current address ZIP code", 10);
        maxLength(p.bgFullSsn, "Social Security Number", 255);
        maxLength(p.bgDriverLicense, "Driver's License number", 255);
        maxLength(p.bgStateId, "State ID number", 255);
        maxLength(p.securityCheckNumbers, "Cheque numbers", 255);
        maxLength(p.securityCheckBank, "Issuing bank", 255);
        maxLength(p.securityCheckHolderName, "Account holder name", 255);
        maxLength(p.securityCheckAmount, "Amount secured", 255);
        maxLength(p.securityCheckDates, "Cheque dates", 255);
        maxLength(p.idType, "ID type", 16);
        if (nonBlank(p.securityCheckCount)) {
            String count = p.securityCheckCount.trim();
            if (!count.matches("\\d{1,3}") || Integer.parseInt(count) > MAX_CHEQUE_INDEX) {
                throw new IllegalArgumentException(
                        "Number of cheques must be a whole number, up to " + MAX_CHEQUE_INDEX + ".");
            }
        }
    }

    /** 400 naming the field when {@code value} is longer than {@code max} characters. */
    public static void maxLength(String value, String label, int max) {
        if (value != null && value.length() > max) {
            throw new IllegalArgumentException(
                    label + " can be at most " + max + " characters.");
        }
    }

    /** A name part: blank is allowed (middle, or cleared while typing); otherwise name characters only. */
    static void personNamePart(String value, String label) {
        if (value == null) return;
        maxLength(value, label, NAME_PART_MAX);
        String t = value.trim();
        if (!t.isEmpty() && !PERSON_NAME.matcher(t).matches()) {
            throw new IllegalArgumentException(
                    label + " can use letters, spaces, apostrophes, hyphens and periods only.");
        }
    }

    // ── Names and addresses ──────────────────────────────────────────

    /** Trim a string, returning null when null/blank. */
    public static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** "First Middle? Last" from trimmed (or null) parts; null when first and last are both absent. */
    public static String composeName(String first, String middle, String last) {
        if ((first == null || first.isEmpty())
                && (last == null || last.isEmpty())) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (first != null && !first.isEmpty()) sb.append(first);
        if (middle != null && !middle.isEmpty()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(middle);
        }
        if (last != null && !last.isEmpty()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(last);
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? null : out;
    }

    /**
     * Split one full name into First / Middle / Last: one token → first only;
     * two → first + last; three+ → first, middle (the span between), last.
     */
    public static void syncNameParts(WebAgreement app, String fullName) {
        String t = blankToNull(fullName);
        if (t == null) return;
        String[] parts = t.split("\\s+");
        if (parts.length == 1) {
            app.setFirstName(parts[0]);
            app.setMiddleName(null);
            app.setLastName(null);
        } else if (parts.length == 2) {
            app.setFirstName(parts[0]);
            app.setMiddleName(null);
            app.setLastName(parts[1]);
        } else {
            app.setFirstName(parts[0]);
            app.setLastName(parts[parts.length - 1]);
            app.setMiddleName(String.join(" ",
                    java.util.Arrays.copyOfRange(parts, 1, parts.length - 1)));
        }
    }

    /**
     * The Background Check current address, assembled from its structured
     * columns ("Same as residence" makes it the billing address), falling back
     * to the stored single block when no structured part is present.
     */
    public static String assembledCurrentAddress(WebAgreement app) {
        if (Boolean.TRUE.equals(app.getBgCurrentSameAsResidence())) {
            return assembleUsAddress(
                    app.getAddressLine1(), app.getAddressLine2(), app.getAddressCity(),
                    app.getAddressState(), app.getAddressZip(), null);
        }
        return assembleUsAddress(
                app.getBgCurrentAddressLine1(), app.getBgCurrentAddressLine2(),
                app.getBgCurrentAddressCity(), app.getBgCurrentAddressState(),
                app.getBgCurrentAddressZip(), app.getBgCurrentAddress());
    }

    /** "Line1[, Line2], City, ST ZIP", or the legacy single block when no part is set. */
    static String assembleUsAddress(
            String l1, String l2, String c, String st, String z, String legacy) {
        String line1 = trimToEmpty(l1);
        String line2 = trimToEmpty(l2);
        String city = trimToEmpty(c);
        String state = trimToEmpty(st);
        String zip = trimToEmpty(z);
        boolean anyStructured = !(line1.isEmpty() && line2.isEmpty()
                && city.isEmpty() && state.isEmpty() && zip.isEmpty());
        if (!anyStructured) {
            return trimToEmpty(legacy);
        }
        StringBuilder sb = new StringBuilder();
        if (!line1.isEmpty()) sb.append(line1);
        if (!line2.isEmpty()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(line2);
        }
        String cityStateZip = city;
        if (!state.isEmpty()) {
            cityStateZip = cityStateZip.isEmpty() ? state : cityStateZip + ", " + state;
        }
        if (!zip.isEmpty()) {
            cityStateZip = cityStateZip.isEmpty() ? zip : cityStateZip + " " + zip;
        }
        if (!cityStateZip.isEmpty()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(cityStateZip);
        }
        return sb.toString().trim();
    }

    private static String trimToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    // ── Cheques ──────────────────────────────────────────────────────

    /**
     * One entry of the {@code cheques} JSON list. {@code s3Key} holds the
     * DocumentStorageService value of the uploaded file ("" when none yet).
     */
    public record ChequeEntry(
            int index,
            String number,
            String date,
            String s3Key,
            String contentType,
            String uploadedAt) {}

    /**
     * The {@code cheques} JSON as a sorted-by-index list. Falls back to
     * {@code [{index:0, s3Key:<chequeS3Key>}]} when the column is empty but
     * the index-0 mirror is set.
     */
    public static List<ChequeEntry> parseCheques(WebAgreement app) {
        String json = app.getCheques();
        if (json != null && !json.isBlank()) {
            try {
                JsonNode root = MAPPER.readTree(json);
                if (root.isArray()) {
                    List<ChequeEntry> out = new ArrayList<>(root.size());
                    for (JsonNode n : root) {
                        out.add(new ChequeEntry(
                                n.path("index").asInt(0),
                                n.path("number").asText(""),
                                n.path("date").asText(""),
                                n.path("s3Key").asText(""),
                                n.path("contentType").asText(""),
                                n.path("uploadedAt").asText("")));
                    }
                    out.sort(java.util.Comparator.comparingInt(ChequeEntry::index));
                    return out;
                }
            } catch (Exception e) {
                log.warn("Failed to parse cheques JSON for {}: {}",
                        app.getApplicationId(), e.getMessage());
            }
        }
        String legacyS3Key = app.getChequeS3Key();
        if (legacyS3Key != null && !legacyS3Key.isBlank()) {
            return List.of(new ChequeEntry(
                    0, "", "", legacyS3Key,
                    app.getChequeContentType() == null ? "" : app.getChequeContentType(),
                    app.getChequeUploadedAt() == null ? "" : app.getChequeUploadedAt().toString()));
        }
        return List.of();
    }

    public static ChequeEntry findEntry(List<ChequeEntry> entries, int index) {
        for (ChequeEntry e : entries) {
            if (e.index() == index) return e;
        }
        return null;
    }

    /** Replace the entry with the same index, or append it. {@code entries} must be mutable. */
    public static void upsertEntry(List<ChequeEntry> entries, ChequeEntry replacement) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).index() == replacement.index()) {
                entries.set(i, replacement);
                return;
            }
        }
        entries.add(replacement);
    }

    /** Sorts {@code entries} by index (in place) and serialises them. */
    public static String serialiseCheques(List<ChequeEntry> entries) {
        entries.sort(java.util.Comparator.comparingInt(ChequeEntry::index));
        try {
            return MAPPER.writeValueAsString(entries);
        } catch (Exception e) {
            throw new IllegalStateException("Couldn't serialise cheques.", e);
        }
    }

    /** The participant-entered cheque count, capped at 50 (0 when blank or not a number). */
    public static int parseChequeCountSafe(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        try {
            int n = Integer.parseInt(raw.trim());
            if (n < 0) return 0;
            return Math.min(MAX_CHEQUE_INDEX, n);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Drop cheque entries at/beyond the declared count (left over when the
     * participant over-clicked the count, then reduced it). No-op when the
     * count isn't a positive number.
     */
    public static void pruneChequesToDeclaredCount(WebAgreement app) {
        int count = parseChequeCountSafe(app.getSecurityCheckCount());
        if (count <= 0) return;
        String json = app.getCheques();
        if (json == null || json.isBlank()) return;
        List<ChequeEntry> entries = parseCheques(app);
        List<ChequeEntry> kept = new ArrayList<>();
        for (ChequeEntry e : entries) {
            if (e.index() >= 0 && e.index() < count) kept.add(e);
        }
        if (kept.size() != entries.size()) {
            app.setCheques(serialiseCheques(kept));
        }
    }

    /**
     * Clear the uploaded FILE of every cheque but keep its number/date, so a
     * re-upload request only asks for new files.
     */
    public static void clearChequeFiles(WebAgreement app) {
        List<ChequeEntry> entries = parseCheques(app);
        if (!entries.isEmpty()) {
            List<ChequeEntry> cleared = new ArrayList<>(entries.size());
            for (ChequeEntry e : entries) {
                cleared.add(new ChequeEntry(e.index(), e.number(), e.date(), "", "", ""));
            }
            app.setCheques(serialiseCheques(cleared));
        }
        app.setChequeS3Key(null);
        app.setChequeContentType(null);
        app.setChequeUploadedAt(null);
    }

    // ── Per-section signing dates ────────────────────────────────────

    /**
     * Record the per-section signing dates so a targeted revision only
     * re-stamps what was (re)signed this round. The main-agreement date moves
     * only on a fresh primary draw; an appendix date moves only when affirmed
     * AND (unrestricted submit OR in scope). Must run BEFORE
     * {@code setSignatureDate(now)}: it backfills already-signed sections from
     * the prior global date.
     */
    public static void stampSectionSignatureDates(
            WebAgreement app, LocalDateTime now, boolean primaryReSigned) {
        Optional<Set<String>> scopeOpt = consultantWriteScope(app);
        boolean restricted = scopeOpt.isPresent();
        Set<String> scope = scopeOpt.orElseGet(Collections::emptySet);

        Map<String, String> dates = parseSectionSignatureDates(app.getSectionSignatureDates());

        LocalDateTime prevGlobal = app.getSignatureDate();
        if (prevGlobal != null) {
            String prev = prevGlobal.toString();
            boolean hasPrimary = primaryReSigned || nonBlank(app.getSignatureS3Key());
            if (hasPrimary) dates.putIfAbsent("main-agreement", prev);
            for (int n = 1; n <= 5; n++) {
                if (appendixAffirmed(app, n)) dates.putIfAbsent("appendix" + n, prev);
            }
        }

        String nowStr = now.toString();
        if (primaryReSigned) dates.put("main-agreement", nowStr);
        for (int n = 1; n <= 5; n++) {
            if (appendixAffirmed(app, n) && (!restricted || scope.contains("appendix" + n))) {
                dates.put("appendix" + n, nowStr);
            }
        }
        app.setSectionSignatureDates(writeSectionSignatureDates(dates));
    }

    static boolean appendixAffirmed(WebAgreement app, int n) {
        return switch (n) {
            case 1 -> Boolean.TRUE.equals(app.getAffirmedAppendix1());
            case 2 -> Boolean.TRUE.equals(app.getAffirmedAppendix2());
            case 3 -> Boolean.TRUE.equals(app.getAffirmedAppendix3());
            case 4 -> Boolean.TRUE.equals(app.getAffirmedAppendix4());
            case 5 -> Boolean.TRUE.equals(app.getAffirmedAppendix5());
            default -> false;
        };
    }

    static Map<String, String> parseSectionSignatureDates(String json) {
        Map<String, String> out = new LinkedHashMap<>();
        if (json != null && !json.isBlank()) {
            try {
                JsonNode node = MAPPER.readTree(json);
                if (node != null && node.isObject()) {
                    node.fields().forEachRemaining(e -> {
                        if (e.getValue() != null && e.getValue().isTextual()) {
                            out.put(e.getKey(), e.getValue().asText());
                        }
                    });
                }
            } catch (Exception ignored) { /* treat as no per-section dates */ }
        }
        return out;
    }

    static String writeSectionSignatureDates(Map<String, String> dates) {
        try {
            return MAPPER.writeValueAsString(dates);
        } catch (Exception e) {
            log.warn("Failed to serialise section signature dates: {}", e.getMessage());
            return null;
        }
    }

    // ── PII for list responses ───────────────────────────────────────

    /**
     * Remove the most sensitive PII (SSN, driver's licence, State-ID, bank
     * account/routing numbers, date of birth) for list responses. Pass the
     * EntityManager so the row is detached first and the nulls are never
     * flushed back (open-in-view is on); null skips the detach (tests,
     * already-detached copies).
     */
    public static void stripSensitivePii(jakarta.persistence.EntityManager em, WebAgreement app) {
        if (app == null) return;
        if (em != null) em.detach(app);
        app.setBgFullSsn(null);
        app.setBgDriverLicense(null);
        app.setBgStateId(null);
        app.setAchAccountNumber(null);
        app.setAchRoutingNumber(null);
        app.setBgDateOfBirth(null);
    }

    /** {@link #stripSensitivePii} over a list. */
    public static void stripSensitivePii(jakarta.persistence.EntityManager em, List<WebAgreement> apps) {
        if (apps != null) apps.forEach(a -> stripSensitivePii(em, a));
    }

    // ── Upload safety ────────────────────────────────────────────────

    /** Image types a participant may upload; anything else (SVG above all) is refused. */
    public static final Set<String> SAFE_IMAGE_TYPES = Set.of(
            "image/jpeg", "image/jpg", "image/png", "image/gif", "image/webp", "image/heic", "image/heif");

    /**
     * An allowed image type whose bytes really are an image. Staff open these
     * files in the browser, so the declared type alone can't be trusted: an
     * SVG (or HTML labelled image/png) would run script on the site's origin.
     */
    public static boolean isSafeImage(String normalisedType, byte[] bytes) {
        if (!SAFE_IMAGE_TYPES.contains(normalisedType) || bytes == null || bytes.length < 12) return false;
        int b0 = bytes[0] & 0xff, b1 = bytes[1] & 0xff, b2 = bytes[2] & 0xff, b3 = bytes[3] & 0xff;
        boolean jpeg = b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF;
        boolean png = b0 == 0x89 && b1 == 'P' && b2 == 'N' && b3 == 'G';
        boolean gif = b0 == 'G' && b1 == 'I' && b2 == 'F' && b3 == '8';
        boolean webp = b0 == 'R' && b1 == 'I' && b2 == 'F' && b3 == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
        boolean heif = bytes[4] == 'f' && bytes[5] == 't' && bytes[6] == 'y' && bytes[7] == 'p';
        return jpeg || png || gif || webp || heif;
    }

    /** The bytes start with the PDF signature. */
    public static boolean hasPdfSignature(byte[] bytes) {
        return bytes != null && bytes.length >= 5 && bytes[0] == '%' && bytes[1] == 'P'
                && bytes[2] == 'D' && bytes[3] == 'F' && bytes[4] == '-';
    }

    /** File extension for a stored name, from the upload's content type. */
    public static String extFor(String contentType) {
        if (contentType == null) return "bin";
        String t = contentType.toLowerCase();
        if (t.equals("application/pdf")) return "pdf";
        if (t.equals("image/jpeg") || t.equals("image/jpg")) return "jpg";
        if (t.equals("image/png")) return "png";
        if (t.equals("image/heic") || t.equals("image/heif")) return "heic";
        if (t.startsWith("image/")) {
            String sub = t.substring("image/".length()).replaceAll("[^a-z0-9]", "");
            return sub.isEmpty() ? "img" : sub;
        }
        return "bin";
    }

    /**
     * The five single-file uploads: URL path, host section and re-upload key
     * (the write gate), the console's messages, the stored name part, the
     * audit event and the download filename. The cheques have their own
     * per-index endpoints.
     */
    public enum Doc {
        WORKAUTH("workauth", "cover", "doc:workauth",
                "Work-authorization", "Work-authorization document",
                "Work-authorization document uploaded.", "workauth",
                WebAgreementEvent.EventType.WORK_AUTH_UPLOADED, "SageITCO-WorkAuth_"),
        OFFER_LETTER("offer-letter", "appendix1", "doc:offer-letter",
                "Offer letter", "Offer letter",
                "Offer letter uploaded.", "offerletter",
                WebAgreementEvent.EventType.OFFER_LETTER_UPLOADED, "SageITCO-OfferLetter_"),
        DL_DOC("dl-doc", "appendix3", "doc:dl-doc",
                "Driver's License document", "Driver's License document",
                "Driver's License document uploaded.", "dldoc",
                WebAgreementEvent.EventType.DL_DOC_UPLOADED, "SageITCO-DLDoc_"),
        STATE_ID_DOC("state-id-doc", "appendix3", "doc:state-id",
                "State ID document", "State ID document",
                "State ID document uploaded.", "stateiddoc",
                WebAgreementEvent.EventType.STATE_ID_DOC_UPLOADED, "SageITCO-StateIDDoc_"),
        SSN_DOC("ssn-doc", "appendix3", "doc:ssn-doc",
                "SSN document", "SSN document",
                "SSN document uploaded.", "ssndoc",
                WebAgreementEvent.EventType.SSN_DOC_UPLOADED, "SageITCO-SSNDoc_");

        /** URL segment, e.g. {@code /workauth}. */
        public final String path;
        /** Wizard section hosting the upload (a full-section revision unlocks it). */
        public final String sectionId;
        /** Re-upload scope key (a document-only revision unlocks just this one). */
        public final String docKey;
        /** "{fileLabel} file is empty." / "… is too large (>10 MB)." */
        public final String fileLabel;
        /** "{typeLabel} must be an image (JPG/PNG/HEIC) or PDF." */
        public final String typeLabel;
        public final String uploadedMessage;
        /** Part of the stored file name. */
        public final String docType;
        public final WebAgreementEvent.EventType event;
        /** Download filename prefix; the applicationId and extension follow. */
        public final String filenameBase;

        Doc(String path, String sectionId, String docKey, String fileLabel, String typeLabel,
            String uploadedMessage, String docType, WebAgreementEvent.EventType event,
            String filenameBase) {
            this.path = path;
            this.sectionId = sectionId;
            this.docKey = docKey;
            this.fileLabel = fileLabel;
            this.typeLabel = typeLabel;
            this.uploadedMessage = uploadedMessage;
            this.docType = docType;
            this.event = event;
            this.filenameBase = filenameBase;
        }

        /** The document for a URL segment; IllegalArgumentException for anything else. */
        public static Doc fromPath(String path) {
            for (Doc d : values()) {
                if (d.path.equals(path)) return d;
            }
            throw new IllegalArgumentException("Unknown document: " + path);
        }

        public String storedKey(WebAgreement a) {
            return switch (this) {
                case WORKAUTH -> a.getWorkAuthDocS3Key();
                case OFFER_LETTER -> a.getOfferLetterS3Key();
                case DL_DOC -> a.getDlDocS3Key();
                case STATE_ID_DOC -> a.getStateIdDocS3Key();
                case SSN_DOC -> a.getSsnDocS3Key();
            };
        }

        public String contentType(WebAgreement a) {
            return switch (this) {
                case WORKAUTH -> a.getWorkAuthDocContentType();
                case OFFER_LETTER -> a.getOfferLetterContentType();
                case DL_DOC -> a.getDlDocContentType();
                case STATE_ID_DOC -> a.getStateIdDocContentType();
                case SSN_DOC -> a.getSsnDocContentType();
            };
        }

        /** Point the agreement at a newly stored file. */
        public void set(WebAgreement a, String storedKey, String contentType, LocalDateTime at) {
            switch (this) {
                case WORKAUTH -> {
                    a.setWorkAuthDocS3Key(storedKey);
                    a.setWorkAuthDocContentType(contentType);
                    a.setWorkAuthDocUploadedAt(at);
                }
                case OFFER_LETTER -> {
                    a.setOfferLetterS3Key(storedKey);
                    a.setOfferLetterContentType(contentType);
                    a.setOfferLetterUploadedAt(at);
                }
                case DL_DOC -> {
                    a.setDlDocS3Key(storedKey);
                    a.setDlDocContentType(contentType);
                    a.setDlDocUploadedAt(at);
                }
                case STATE_ID_DOC -> {
                    a.setStateIdDocS3Key(storedKey);
                    a.setStateIdDocContentType(contentType);
                    a.setStateIdDocUploadedAt(at);
                }
                case SSN_DOC -> {
                    a.setSsnDocS3Key(storedKey);
                    a.setSsnDocContentType(contentType);
                    a.setSsnDocUploadedAt(at);
                }
            }
        }
    }

    // ── Request bodies ───────────────────────────────────────────────

    /** Body of PUT /cheques/{index}: one cheque's number and date. */
    public static class ChequeMetadataPatch {
        public String number;
        public String date;
    }

    /**
     * Participant partial save (PUT /fill). Same field names as the console's
     * {@code ConsultantFillPatch}, minus what the participant may not change:
     * the ERM-set achDebit*, portalAuthorizedActions, portalRevocationContact,
     * workAuthorizationCategory, technologyTrack, customScopeNotes and
     * effectiveDate (sent anyway, they are ignored as unknown properties),
     * and the dropped legacy columns. Null means "not sent"; only non-null
     * values are written. {@link #validateFillPatch} checks lengths, name
     * characters and the cheque count first; other formats are the submit
     * gate's.
     */
    public static class WebAgreementFillPatch {
        // Structured name (the participant may correct the spelling).
        public String firstName;
        public String middleName;
        public String lastName;
        public String primaryPhone;
        // Structured US billing address.
        public String addressLine1;
        public String addressLine2;
        public String addressCity;
        public String addressState;
        public String addressZip;
        public String employerPayrollEntity;
        public String implementationPartner;
        public String endClient;
        public String roleTitle;
        public java.time.LocalDate verifiedStartDate;
        public String payrollCycle;
        public String achAccountType;
        public String achBankName;
        public String achAccountHolderName;
        public String achRoutingNumber;
        public String achAccountNumber;
        public String achNoticeEmail;
        public String bgFullLegalName;
        public String bgOtherNamesUsed;
        public String bgCurrentAddress;
        public String bgCurrentAddressLine1;
        public String bgCurrentAddressLine2;
        public String bgCurrentAddressCity;
        public String bgCurrentAddressState;
        public String bgCurrentAddressZip;
        public Boolean bgCurrentSameAsResidence;
        public java.time.LocalDate bgDateOfBirth;
        public String bgFullSsn;
        public String bgDriverLicense;
        public String bgStateId;
        // Repeatable platform+username entries (JSON-in-TEXT, at most 10).
        public String portalEntries;
        public java.time.LocalDate portalEffectiveDate;
        public String securityCheckCount;
        public String securityCheckNumbers;
        public String securityCheckBank;
        public String securityCheckHolderName;
        public String securityCheckAmount;
        public String securityCheckDates;
        // "DL" | "STATE_ID".
        public String idType;
        // Per-section "I understand" ticks; false = explicitly unticked.
        public Boolean affirmedMainAgreement;
        public Boolean affirmedExhibitA;
        public Boolean affirmedExhibitB;
        public Boolean affirmedAppendix1;
        public Boolean affirmedAppendix2;
        public Boolean affirmedAppendix3;
        public Boolean affirmedAppendix4;
        public Boolean affirmedAppendix5;

        /** Copies the non-null values; true iff at least one was applied. */
        public boolean applyTo(WebAgreement app) {
            boolean changed = false;
            if (firstName != null)                { app.setFirstName(firstName); changed = true; }
            if (middleName != null)               { app.setMiddleName(middleName); changed = true; }
            if (lastName != null)                 { app.setLastName(lastName); changed = true; }
            if (primaryPhone != null)             { app.setPrimaryPhone(primaryPhone); changed = true; }
            if (addressLine1 != null)             { app.setAddressLine1(addressLine1); changed = true; }
            if (addressLine2 != null)             { app.setAddressLine2(addressLine2); changed = true; }
            if (addressCity != null)              { app.setAddressCity(addressCity); changed = true; }
            if (addressState != null)             { app.setAddressState(addressState); changed = true; }
            if (addressZip != null)               { app.setAddressZip(addressZip); changed = true; }
            if (employerPayrollEntity != null)    { app.setEmployerPayrollEntity(employerPayrollEntity); changed = true; }
            if (implementationPartner != null)    { app.setImplementationPartner(implementationPartner); changed = true; }
            if (endClient != null)                { app.setEndClient(endClient); changed = true; }
            if (roleTitle != null)                { app.setRoleTitle(roleTitle); changed = true; }
            if (verifiedStartDate != null)        { app.setVerifiedStartDate(verifiedStartDate); changed = true; }
            if (payrollCycle != null)             { app.setPayrollCycle(payrollCycle); changed = true; }
            if (achAccountType != null)           { app.setAchAccountType(achAccountType); changed = true; }
            if (achBankName != null)              { app.setAchBankName(achBankName); changed = true; }
            if (achAccountHolderName != null)     { app.setAchAccountHolderName(achAccountHolderName); changed = true; }
            if (achRoutingNumber != null)         { app.setAchRoutingNumber(achRoutingNumber); changed = true; }
            if (achAccountNumber != null)         { app.setAchAccountNumber(achAccountNumber); changed = true; }
            if (achNoticeEmail != null)           { app.setAchNoticeEmail(achNoticeEmail); changed = true; }
            if (bgFullLegalName != null)          { app.setBgFullLegalName(bgFullLegalName); changed = true; }
            if (bgOtherNamesUsed != null)         { app.setBgOtherNamesUsed(bgOtherNamesUsed); changed = true; }
            if (bgCurrentAddress != null)         { app.setBgCurrentAddress(bgCurrentAddress); changed = true; }
            if (bgCurrentAddressLine1 != null)    { app.setBgCurrentAddressLine1(bgCurrentAddressLine1); changed = true; }
            if (bgCurrentAddressLine2 != null)    { app.setBgCurrentAddressLine2(bgCurrentAddressLine2); changed = true; }
            if (bgCurrentAddressCity != null)     { app.setBgCurrentAddressCity(bgCurrentAddressCity); changed = true; }
            if (bgCurrentAddressState != null)    { app.setBgCurrentAddressState(bgCurrentAddressState); changed = true; }
            if (bgCurrentAddressZip != null)      { app.setBgCurrentAddressZip(bgCurrentAddressZip); changed = true; }
            if (bgCurrentSameAsResidence != null) { app.setBgCurrentSameAsResidence(bgCurrentSameAsResidence); changed = true; }
            if (bgDateOfBirth != null)            { app.setBgDateOfBirth(bgDateOfBirth); changed = true; }
            if (bgFullSsn != null)                { app.setBgFullSsn(bgFullSsn); changed = true; }
            if (bgDriverLicense != null)          { app.setBgDriverLicense(bgDriverLicense); changed = true; }
            if (bgStateId != null)                { app.setBgStateId(bgStateId); changed = true; }
            if (portalEntries != null)            { app.setPortalEntries(portalEntries); changed = true; }
            if (portalEffectiveDate != null)      { app.setPortalEffectiveDate(portalEffectiveDate); changed = true; }
            if (securityCheckCount != null)       { app.setSecurityCheckCount(securityCheckCount); changed = true; }
            if (securityCheckNumbers != null)     { app.setSecurityCheckNumbers(securityCheckNumbers); changed = true; }
            if (securityCheckBank != null)        { app.setSecurityCheckBank(securityCheckBank); changed = true; }
            if (securityCheckHolderName != null)  { app.setSecurityCheckHolderName(securityCheckHolderName); changed = true; }
            if (securityCheckAmount != null)      { app.setSecurityCheckAmount(securityCheckAmount); changed = true; }
            if (securityCheckDates != null)       { app.setSecurityCheckDates(securityCheckDates); changed = true; }
            if (idType != null)                   { app.setIdType(idType); changed = true; }
            if (affirmedMainAgreement != null)    { app.setAffirmedMainAgreement(affirmedMainAgreement); changed = true; }
            if (affirmedExhibitA != null)         { app.setAffirmedExhibitA(affirmedExhibitA); changed = true; }
            if (affirmedExhibitB != null)         { app.setAffirmedExhibitB(affirmedExhibitB); changed = true; }
            if (affirmedAppendix1 != null)        { app.setAffirmedAppendix1(affirmedAppendix1); changed = true; }
            if (affirmedAppendix2 != null)        { app.setAffirmedAppendix2(affirmedAppendix2); changed = true; }
            if (affirmedAppendix3 != null)        { app.setAffirmedAppendix3(affirmedAppendix3); changed = true; }
            if (affirmedAppendix4 != null)        { app.setAffirmedAppendix4(affirmedAppendix4); changed = true; }
            if (affirmedAppendix5 != null)        { app.setAffirmedAppendix5(affirmedAppendix5); changed = true; }
            return changed;
        }

        /** Names of every field the caller actually sent (non-null). */
        public List<String> touchedFieldNames() {
            List<String> names = new ArrayList<>();
            if (firstName != null) names.add("firstName");
            if (middleName != null) names.add("middleName");
            if (lastName != null) names.add("lastName");
            if (primaryPhone != null) names.add("primaryPhone");
            if (addressLine1 != null) names.add("addressLine1");
            if (addressLine2 != null) names.add("addressLine2");
            if (addressCity != null) names.add("addressCity");
            if (addressState != null) names.add("addressState");
            if (addressZip != null) names.add("addressZip");
            if (employerPayrollEntity != null) names.add("employerPayrollEntity");
            if (implementationPartner != null) names.add("implementationPartner");
            if (endClient != null) names.add("endClient");
            if (roleTitle != null) names.add("roleTitle");
            if (verifiedStartDate != null) names.add("verifiedStartDate");
            if (payrollCycle != null) names.add("payrollCycle");
            if (achAccountType != null) names.add("achAccountType");
            if (achBankName != null) names.add("achBankName");
            if (achAccountHolderName != null) names.add("achAccountHolderName");
            if (achRoutingNumber != null) names.add("achRoutingNumber");
            if (achAccountNumber != null) names.add("achAccountNumber");
            if (achNoticeEmail != null) names.add("achNoticeEmail");
            if (bgFullLegalName != null) names.add("bgFullLegalName");
            if (bgOtherNamesUsed != null) names.add("bgOtherNamesUsed");
            if (bgCurrentAddress != null) names.add("bgCurrentAddress");
            if (bgCurrentAddressLine1 != null) names.add("bgCurrentAddressLine1");
            if (bgCurrentAddressLine2 != null) names.add("bgCurrentAddressLine2");
            if (bgCurrentAddressCity != null) names.add("bgCurrentAddressCity");
            if (bgCurrentAddressState != null) names.add("bgCurrentAddressState");
            if (bgCurrentAddressZip != null) names.add("bgCurrentAddressZip");
            if (bgCurrentSameAsResidence != null) names.add("bgCurrentSameAsResidence");
            if (bgDateOfBirth != null) names.add("bgDateOfBirth");
            if (bgFullSsn != null) names.add("bgFullSsn");
            if (bgDriverLicense != null) names.add("bgDriverLicense");
            if (bgStateId != null) names.add("bgStateId");
            if (portalEntries != null) names.add("portalEntries");
            if (portalEffectiveDate != null) names.add("portalEffectiveDate");
            if (securityCheckCount != null) names.add("securityCheckCount");
            if (securityCheckNumbers != null) names.add("securityCheckNumbers");
            if (securityCheckBank != null) names.add("securityCheckBank");
            if (securityCheckHolderName != null) names.add("securityCheckHolderName");
            if (securityCheckAmount != null) names.add("securityCheckAmount");
            if (securityCheckDates != null) names.add("securityCheckDates");
            if (idType != null) names.add("idType");
            if (affirmedMainAgreement != null) names.add("affirmedMainAgreement");
            if (affirmedExhibitA != null) names.add("affirmedExhibitA");
            if (affirmedExhibitB != null) names.add("affirmedExhibitB");
            if (affirmedAppendix1 != null) names.add("affirmedAppendix1");
            if (affirmedAppendix2 != null) names.add("affirmedAppendix2");
            if (affirmedAppendix3 != null) names.add("affirmedAppendix3");
            if (affirmedAppendix4 != null) names.add("affirmedAppendix4");
            if (affirmedAppendix5 != null) names.add("affirmedAppendix5");
            return names;
        }
    }
}
