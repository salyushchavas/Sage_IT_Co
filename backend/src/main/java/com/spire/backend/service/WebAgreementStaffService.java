package com.spire.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.spire.backend.entity.User;
import com.spire.backend.entity.WebAgreement;
import com.spire.backend.entity.WebAgreementEvent;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.repository.AgreementRequestRepository;
import com.spire.backend.repository.UserRepository;
import com.spire.backend.repository.WebAgreementRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.spire.backend.service.WebAgreementRules.ChequeEntry;
import static com.spire.backend.service.WebAgreementRules.Doc;
import static com.spire.backend.service.WebAgreementRules.blankToNull;

/**
 * The staff side of the website agreement: the copy of the console's ERM
 * operations ({@link ConsultantApplicationService}, untouched) for website
 * ERMs. Operations and System admins act as the console's super-admin.
 *
 * Who may act: any ERM / admin sees the participants who are ready and can
 * start an agreement; after that the agreement belongs to the ERM who
 * created it (ownerUserId). Another ERM gets "not found", exactly like the
 * console; admins see and act on every agreement. Every event carries the
 * real users.id of whoever acted.
 *
 * The ERM's "Verify" is the console's consultant-version release without
 * the PDF: the status stays VERIFIED and consultantCopyReleased flips. The
 * approvals, countersignature and final PDF come later.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebAgreementStaffService {

    /** Website roles that work on agreements. */
    static final Set<String> STAFF = Set.of("ERM", "OPERATIONS_ADMIN", "SYSTEM_ADMIN");

    /** Website roles that see and act on every agreement (the console's super-admin). */
    static final Set<String> ADMINS = Set.of("OPERATIONS_ADMIN", "SYSTEM_ADMIN");

    private final WebAgreementRepository agreementRepository;
    private final WebAgreementEventService eventService;
    private final WebAgreementFileService fileService;
    private final WebAgreementRenderer renderer;
    private final MasterAgreementService masterAgreementService;
    private final UserRepository userRepository;
    private final AgreementRequestRepository agreementRequestRepository;

    /** Detaches list rows before their PII is removed (open-in-view is on). */
    @PersistenceContext
    private EntityManager entityManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── Who may act ──────────────────────────────────────────────────

    /** The caller, when they are an ERM or an admin; 403 otherwise. */
    User requireStaff(Long callerId) {
        User caller = callerId == null ? null : userRepository.findById(callerId).orElse(null);
        if (caller == null || Boolean.FALSE.equals(caller.getIsActive()) || !STAFF.contains(roleOf(caller))) {
            throw new AccessDeniedException("Only an ERM or an Operations admin can work on agreements.");
        }
        return caller;
    }

    /**
     * The agreement, when the caller may see it: the owner ERM or an admin.
     * Anyone else, and an archived agreement, gets 404 (never 403), so an
     * ERM can't learn that another ERM's agreement exists.
     */
    WebAgreement requireAccess(String applicationId, Long callerId) {
        User caller = requireStaff(callerId);
        WebAgreement a = agreementRepository.findByApplicationId(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException("Agreement not found."));
        if (Boolean.TRUE.equals(a.getDeleted())) {
            throw new ResourceNotFoundException("Agreement not found.");
        }
        if (!isAdmin(caller) && !callerId.equals(a.getOwnerUserId())) {
            throw new ResourceNotFoundException("Agreement not found.");
        }
        return a;
    }

    private static boolean isAdmin(User u) {
        return ADMINS.contains(roleOf(u));
    }

    private static String roleOf(User u) {
        return u == null || u.getRole() == null || u.getRole().getName() == null
                ? "" : u.getRole().getName().toUpperCase();
    }

    // ── Participants ready for their agreement ───────────────────────

    /** Who asked for their agreement and has none open (here or in the console). Every staff caller sees it. */
    public List<MasterAgreementService.ReadyRow> requests(Long callerId) {
        requireStaff(callerId);
        return masterAgreementService.readyForAgreement();
    }

    /** One participant's details, to prefill the create form (only someone on the ready list; 404 otherwise). */
    public MasterAgreementService.ReadyRow request(Long callerId, Long userId) {
        requireStaff(callerId);
        return masterAgreementService.readyRow(userId);
    }

    // ── Create ───────────────────────────────────────────────────────

    /** Body of POST /api/web-agreements: the console's create form plus who it is for. */
    public static class CreateBody {
        /** users.id of the participant. */
        public Long participantUserId;
        public String consultantEmail;
        public String firstName;
        public String middleName;
        public String lastName;
        public String ratePeriod1;
        public String rateAmount1;
        public String ratePeriod2;
        public String rateAmount2;
        public String phase2DeliverablePeriod;
        // Stored as workAuthorizationCategory; visaStatusOther only for "Others".
        public String visaStatus;
        public String visaStatusOther;
        public Boolean requireAppendix1;
        public Boolean requireAppendix2;
        public Boolean requireAppendix3;
        public Boolean requireAppendix4;
        public Boolean requireAppendix5;
        public Boolean requireSsn;
        public String achDebitDates;
        public String achDebitAmounts;
        public String technologyTrack;
        public String customScopeNotes;
        public String portalAuthorizedActions;
        public String portalRevocationContact;
    }

    /**
     * Starts an agreement (the console's createApplication). The participant
     * must exist, be a participant with an active account and have signed
     * the consent; refused (409) when they already have an open website
     * agreement. The caller owns it; status SUBMITTED, effective today. The
     * participant finds it on their dashboard (no email is sent).
     */
    @Transactional
    public WebAgreement create(CreateBody body, Long callerId, HttpServletRequest request) {
        requireStaff(callerId);
        if (body == null || body.participantUserId == null) {
            throw new IllegalArgumentException("participantUserId is required.");
        }
        validateRequired("consultantEmail", body.consultantEmail);
        User participant = userRepository.findById(body.participantUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", body.participantUserId));
        if (participant.getParticipantId() == null || participant.getParticipantId().isBlank()) {
            throw new IllegalArgumentException("That isn't a participant.");
        }
        // Same as the ready list: a deactivated account gets no agreement.
        if (Boolean.FALSE.equals(participant.getIsActive())) {
            throw new IllegalStateException("This participant's account is inactive.");
        }
        if (!Boolean.TRUE.equals(participant.getAgreementComplete())) {
            throw new IllegalStateException("This participant hasn't signed their consent yet.");
        }
        // One start at a time per participant: two staff clicking "Start
        // agreement" together queue on the participant's "I'm ready" row
        // (never on their account), and the check is a locking read, so the
        // second sees the first's agreement (a plain read could miss it under
        // MySQL's repeatable read).
        agreementRequestRepository.findForUpdateByUserId(participant.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "This participant hasn't asked for their agreement yet."));
        boolean open = agreementRepository.findForUpdateByParticipantUserIdAndDeletedFalse(participant.getId())
                .stream().anyMatch(a -> !WebAgreement.Status.CANCELLED.name().equals(a.getStatus()));
        if (open) {
            throw new IllegalStateException("This participant already has an open agreement.");
        }
        String email = body.consultantEmail.trim().toLowerCase();

        // Structured name; consultant_name is First + Middle? + Last.
        String fn = blankToNull(body.firstName);
        String mn = blankToNull(body.middleName);
        String ln = blankToNull(body.lastName);
        // Work-authorization custom value only for "Others".
        String cat = blankToNull(body.visaStatus);
        String catOther = "Others".equalsIgnoreCase(cat == null ? "" : cat)
                ? blankToNull(body.visaStatusOther)
                : null;

        WebAgreement a = WebAgreement.builder()
                .applicationId(UUID.randomUUID().toString())
                .participantUserId(participant.getId())
                .ownerUserId(callerId)
                .consultantEmail(email)
                .consultantName(WebAgreementRules.composeName(fn, mn, ln))
                .firstName(fn)
                .middleName(mn)
                .lastName(ln)
                .ratePeriod1(body.ratePeriod1)
                .rateAmount1(body.rateAmount1)
                .ratePeriod2(body.ratePeriod2)
                .rateAmount2(body.rateAmount2)
                .phase2DeliverablePeriod(blankToNull(body.phase2DeliverablePeriod))
                .workAuthorizationCategory(cat)
                .workAuthorizationOther(catOther)
                .requireAppendix1(Boolean.TRUE.equals(body.requireAppendix1))
                .requireAppendix2(Boolean.TRUE.equals(body.requireAppendix2))
                .requireAppendix3(Boolean.TRUE.equals(body.requireAppendix3))
                .requireAppendix4(Boolean.TRUE.equals(body.requireAppendix4))
                .requireAppendix5(Boolean.TRUE.equals(body.requireAppendix5))
                .requireSsn(Boolean.TRUE.equals(body.requireSsn))
                // ERM-set, read-only to the participant.
                .achDebitDates(blankToNull(body.achDebitDates))
                .achDebitAmounts(blankToNull(body.achDebitAmounts))
                .technologyTrack(blankToNull(body.technologyTrack))
                .customScopeNotes(blankToNull(body.customScopeNotes))
                .portalAuthorizedActions(blankToNull(body.portalAuthorizedActions))
                .portalRevocationContact(blankToNull(body.portalRevocationContact))
                // The effective date is the creation date.
                .effectiveDate(LocalDateTime.now().toLocalDate())
                .status(WebAgreement.Status.SUBMITTED.name())
                .build();
        a = agreementRepository.save(a);

        eventService.append(a.getId(),
                WebAgreementEvent.EventType.CREATED,
                WebAgreementEvent.ActorType.ERM, callerId,
                Map.of("applicationId", a.getApplicationId(),
                        "consultantEmail", a.getConsultantEmail(),
                        "participantUserId", participant.getId()),
                request);

        return a;
    }

    // ── Reads ────────────────────────────────────────────────────────

    /**
     * The agreements list, newest first (the pageable carries the sort). An
     * ERM sees only their own, admins see every one. The most sensitive PII
     * is removed (the detail view still shows it) and the owner's name is
     * filled for the admins' column.
     */
    @Transactional(readOnly = true)
    public Page<WebAgreement> list(String status, Pageable pageable, Long callerId) {
        User caller = requireStaff(callerId);
        boolean all = status == null || status.isBlank() || "ALL".equalsIgnoreCase(status);
        Page<WebAgreement> page;
        if (isAdmin(caller)) {
            page = all
                    ? agreementRepository.findByDeletedFalse(pageable)
                    : agreementRepository.findByStatusAndDeletedFalse(status.trim(), pageable);
        } else {
            page = all
                    ? agreementRepository.findByOwnerUserIdAndDeletedFalse(callerId, pageable)
                    : agreementRepository.findByOwnerUserIdAndStatusAndDeletedFalse(callerId, status.trim(), pageable);
        }
        populateOwnerNames(page.getContent());
        WebAgreementRules.stripSensitivePii(entityManager, page.getContent());
        return page;
    }

    /**
     * One agreement with its timeline: {application, events}. Full detail
     * (PII included) for the owner or an admin, with the take-back state of
     * an open change request resolved.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> detail(String applicationId, Long callerId) {
        WebAgreement a = requireAccess(applicationId, callerId);
        populateOwnerNames(List.of(a));
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("application", decorateRevokeState(a));
        view.put("events", eventService.list(a.getId()));
        return view;
    }

    /** The owning ERM's display name on each row (one query for the page). */
    private void populateOwnerNames(List<WebAgreement> agreements) {
        if (agreements == null || agreements.isEmpty()) return;
        Set<Long> ownerIds = agreements.stream()
                .map(WebAgreement::getOwnerUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ownerIds.isEmpty()) return;
        Map<Long, String> idToName = new HashMap<>();
        for (User u : userRepository.findAllById(ownerIds)) {
            idToName.put(u.getId(), u.getFullName());
        }
        for (WebAgreement a : agreements) {
            if (a.getOwnerUserId() != null) a.setOwnerName(idToName.get(a.getOwnerUserId()));
        }
    }

    // ── Cancel, contact ──────────────────────────────────────────────

    /** Ends the agreement (anything but COMPLETED). No email, like the console. */
    @Transactional
    public WebAgreement cancel(String applicationId, Long callerId, HttpServletRequest request) {
        WebAgreement a = requireAccess(applicationId, callerId);
        String status = a.getStatus();
        if (WebAgreement.Status.COMPLETED.name().equals(status)) {
            throw new IllegalStateException(
                    "Cannot cancel a signed or completed application.");
        }
        a.setStatus(WebAgreement.Status.CANCELLED.name());
        // Cancelling ends any open change request for good.
        WebAgreementRules.clearRevisionUndo(a);
        agreementRepository.save(a);
        eventService.append(a.getId(),
                WebAgreementEvent.EventType.CANCELLED,
                WebAgreementEvent.ActorType.ERM, callerId,
                Map.of("from", status == null ? "" : status),
                request);
        return a;
    }

    /**
     * The owner fixes a wrong participant email / name (the agreement and
     * its renders use it). Allowed in SUBMITTED, VERIFIED, REVISION_REQUESTED
     * and COMPLETED; 409 otherwise. No email is sent.
     */
    @Transactional
    public WebAgreement updateContact(String applicationId, String consultantEmail, String consultantName,
                                      Long callerId, HttpServletRequest request) {
        WebAgreement a = requireAccess(applicationId, callerId);
        String status = a.getStatus();
        boolean editable =
                WebAgreement.Status.SUBMITTED.name().equals(status)
                || WebAgreement.Status.VERIFIED.name().equals(status)
                || WebAgreement.Status.REVISION_REQUESTED.name().equals(status)
                || WebAgreement.Status.COMPLETED.name().equals(status);
        if (!editable) {
            throw new IllegalStateException(
                    "Consultant contact can't be edited in status " + status + ".");
        }
        String newEmail = consultantEmail == null ? "" : consultantEmail.trim();
        if (newEmail.isBlank()
                || !newEmail.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new IllegalArgumentException("A valid consultant email is required.");
        }
        String newName = consultantName == null ? null : consultantName.trim();

        String oldEmail = a.getConsultantEmail();
        String oldName = a.getConsultantName();

        a.setConsultantEmail(newEmail.toLowerCase());
        if (newName != null && !newName.isBlank()) {
            a.setConsultantName(newName);
            WebAgreementRules.syncNameParts(a, newName);
        }
        agreementRepository.save(a);
        eventService.append(a.getId(),
                WebAgreementEvent.EventType.CONSULTANT_CONTACT_UPDATED,
                WebAgreementEvent.ActorType.ERM, callerId,
                Map.of("oldEmail", oldEmail == null ? "" : oldEmail,
                        "newEmail", a.getConsultantEmail(),
                        "oldName", oldName == null ? "" : oldName,
                        "newName", a.getConsultantName() == null ? "" : a.getConsultantName()),
                request);
        return a;
    }

    // ── Change requests ──────────────────────────────────────────────

    /**
     * Section revision (the console's ermRequestRevision). The ERM picks the
     * sections (optional note each; unknown keys are dropped). ERM
     * corrections sent with it are saved only when they change a value, and
     * then open their section too: ACH debit → appendix2, rate card →
     * main-agreement, Phase-2 deliverables period → appendix1. The selected
     * sections' "I understand" ticks are re-armed, the old state is frozen
     * for a take-back; the participant sees the request on their dashboard.
     */
    @Transactional
    public WebAgreement requestRevision(String applicationId, JsonNode sections,
                                        String achDebitDates, String achDebitAmounts,
                                        String ratePeriod1, String rateAmount1,
                                        String ratePeriod2, String rateAmount2,
                                        String phase2DeliverablePeriod,
                                        Long callerId, HttpServletRequest request) {
        WebAgreement a = requireAccess(applicationId, callerId);
        String st = a.getStatus();
        if (!WebAgreementRules.isRevisionRequestable(st)) {
            throw new IllegalStateException(
                    "This application can't be sent back for revision "
                            + "(status=" + st + ").");
        }
        // Freeze the pre-request state BEFORE anything below re-arms an
        // affirmation or overwrites an ERM correction.
        captureRevisionUndo(a, "sections");

        List<String> selectedKeys = new ArrayList<>();
        LinkedHashMap<String, String> notes = new LinkedHashMap<>();
        if (sections != null && sections.isArray()) {
            for (JsonNode row : sections) {
                String key = blankToNull(row.path("key").asText(""));
                if (key == null || !WebAgreementRules.REVISABLE_SECTION_IDS.contains(key)) continue;
                if (!selectedKeys.contains(key)) selectedKeys.add(key);
                String note = blankToNull(row.path("note").asText(""));
                if (note != null) notes.put(key, note);
            }
        }

        // An ACH debit-schedule correction: only a CHANGED value is saved,
        // and it opens Appendix 2 so the participant re-affirms it.
        boolean achChanged = false;
        if (achDebitDates != null && !Objects.equals(
                blankToNull(achDebitDates), blankToNull(a.getAchDebitDates()))) {
            a.setAchDebitDates(blankToNull(achDebitDates));
            achChanged = true;
        }
        if (achDebitAmounts != null && !Objects.equals(
                blankToNull(achDebitAmounts), blankToNull(a.getAchDebitAmounts()))) {
            a.setAchDebitAmounts(blankToNull(achDebitAmounts));
            achChanged = true;
        }
        if (achChanged && !selectedKeys.contains("appendix2")) {
            selectedKeys.add("appendix2");
        }

        // A Phase-2 rate-card correction opens the main agreement (Section 11).
        boolean rateChanged = false;
        if (ratePeriod1 != null && !Objects.equals(
                blankToNull(ratePeriod1), blankToNull(a.getRatePeriod1()))) {
            a.setRatePeriod1(blankToNull(ratePeriod1));
            rateChanged = true;
        }
        if (rateAmount1 != null && !Objects.equals(
                blankToNull(rateAmount1), blankToNull(a.getRateAmount1()))) {
            a.setRateAmount1(blankToNull(rateAmount1));
            rateChanged = true;
        }
        if (ratePeriod2 != null && !Objects.equals(
                blankToNull(ratePeriod2), blankToNull(a.getRatePeriod2()))) {
            a.setRatePeriod2(blankToNull(ratePeriod2));
            rateChanged = true;
        }
        if (rateAmount2 != null && !Objects.equals(
                blankToNull(rateAmount2), blankToNull(a.getRateAmount2()))) {
            a.setRateAmount2(blankToNull(rateAmount2));
            rateChanged = true;
        }
        if (rateChanged && !selectedKeys.contains("main-agreement")) {
            selectedKeys.add("main-agreement");
        }

        // A Phase-2 deliverables-period correction opens Appendix 1 (Schedule 1).
        if (phase2DeliverablePeriod != null && !Objects.equals(
                blankToNull(phase2DeliverablePeriod),
                blankToNull(a.getPhase2DeliverablePeriod()))) {
            a.setPhase2DeliverablePeriod(blankToNull(phase2DeliverablePeriod));
            if (!selectedKeys.contains("appendix1")) {
                selectedKeys.add("appendix1");
            }
        }

        if (selectedKeys.isEmpty()) {
            throw new IllegalArgumentException(
                    "Select at least one section to revise.");
        }

        // The revision scope: [{"key":…,"note":…}, …].
        ArrayNode arr = objectMapper.createArrayNode();
        for (String k : selectedKeys) {
            ObjectNode o = objectMapper.createObjectNode();
            o.put("key", k);
            if (notes.containsKey(k)) o.put("note", notes.get(k));
            arr.add(o);
        }
        a.setRevisionSections(arr.toString());

        // The summary is the participant's wizard banner.
        String summary = WebAgreementRules.buildRevisionSummary(selectedKeys, notes);
        a.setCurrentRevisionRemarks(summary);

        // The participant must re-read and re-affirm each selected section.
        for (String k : selectedKeys) WebAgreementRules.clearAffirmationForSection(a, k);

        Integer prevCount = a.getRevisionCount();
        a.setRevisionCount((prevCount == null ? 0 : prevCount) + 1);
        a.setStatus(WebAgreement.Status.REVISION_REQUESTED.name());
        agreementRepository.save(a);

        eventService.append(a.getId(),
                WebAgreementEvent.EventType.REVISION_REQUESTED,
                WebAgreementEvent.ActorType.ERM, callerId,
                Map.of("selectedSections", selectedKeys,
                        "revisionCount", a.getRevisionCount()),
                request);
        return a;
    }

    /**
     * Signature-only revision: both participant signatures are cleared so a
     * fresh draw is required; content and ticks stay as they are. The
     * wizard is scoped to the signing steps by the "signature" key.
     */
    @Transactional
    public WebAgreement requestSignatureRevision(String applicationId, String note,
                                                 Long callerId, HttpServletRequest request) {
        WebAgreement a = requireAccess(applicationId, callerId);
        String st = a.getStatus();
        if (!WebAgreementRules.isRevisionRequestable(st)) {
            throw new IllegalStateException(
                    "A signature re-sign can't be requested from status " + st + ".");
        }
        // Freeze the signatures before they're wiped below.
        captureRevisionUndo(a, "signature");

        // The primary MUST be cleared (else the participant could reuse it);
        // the closing one too, so the signed record is clearly reset.
        a.setSignatureS3Key(null);
        a.setFinalSignatureS3Key(null);
        a.setSigningAt(null);
        a.setSigningIp(null);
        a.setFinalSignedAt(null);
        a.setFinalSigningIp(null);
        a.setSignatureDate(null);

        ArrayNode arr = objectMapper.createArrayNode();
        ObjectNode o = objectMapper.createObjectNode();
        o.put("key", "signature");
        String trimmedNote = note == null ? null : note.trim();
        if (trimmedNote != null && !trimmedNote.isEmpty()) o.put("note", trimmedNote);
        arr.add(o);
        a.setRevisionSections(arr.toString());

        String summary = "Please re-sign the agreement with a clear, valid signature."
                + (trimmedNote != null && !trimmedNote.isEmpty() ? " " + trimmedNote : "");
        a.setCurrentRevisionRemarks(summary);

        Integer prevCount = a.getRevisionCount();
        a.setRevisionCount((prevCount == null ? 0 : prevCount) + 1);
        a.setStatus(WebAgreement.Status.REVISION_REQUESTED.name());
        agreementRepository.save(a);

        eventService.append(a.getId(),
                WebAgreementEvent.EventType.REVISION_REQUESTED,
                WebAgreementEvent.ActorType.ERM, callerId,
                Map.of("signatureRevision", true,
                        "revisionCount", a.getRevisionCount()),
                request);
        return a;
    }

    /**
     * Document re-upload revision: each requested document's pointer is
     * cleared so a fresh upload is required (the file itself stays in
     * storage, so a take-back can point at it again; cheques keep their
     * number and date). The wizard is scoped by the {@code doc:*} keys.
     */
    @Transactional
    public WebAgreement requestDocumentRevision(String applicationId, List<String> docKeys, String note,
                                                Long callerId, HttpServletRequest request) {
        WebAgreement a = requireAccess(applicationId, callerId);
        String st = a.getStatus();
        if (!WebAgreementRules.isRevisionRequestable(st)) {
            throw new IllegalStateException(
                    "A document re-upload can't be requested from status " + st + ".");
        }

        // Known document keys only, de-duplicated.
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        if (docKeys != null) {
            for (String k : docKeys) {
                if (k != null && WebAgreementRules.DOC_REVISION_SECTION.containsKey(k)) keys.add(k);
            }
        }
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("Select at least one document to re-upload.");
        }
        // Freeze the document pointers before they're cleared.
        captureRevisionUndo(a, "documents");

        for (String k : keys) WebAgreementRules.clearDocument(a, k);

        ArrayNode arr = objectMapper.createArrayNode();
        String trimmedNote = note == null ? null : note.trim();
        boolean hasNote = trimmedNote != null && !trimmedNote.isEmpty();
        for (String k : keys) {
            ObjectNode o = objectMapper.createObjectNode();
            o.put("key", k);
            if (hasNote) o.put("note", trimmedNote);
            arr.add(o);
        }
        a.setRevisionSections(arr.toString());

        StringBuilder sb = new StringBuilder("Please re-upload the following document(s): ");
        int i = 0;
        for (String k : keys) {
            if (i++ > 0) sb.append(", ");
            sb.append(WebAgreementRules.DOC_REVISION_LABELS.getOrDefault(k, k));
        }
        sb.append('.');
        if (hasNote) sb.append(' ').append(trimmedNote);
        String summary = sb.toString();
        a.setCurrentRevisionRemarks(summary);

        Integer prevCount = a.getRevisionCount();
        a.setRevisionCount((prevCount == null ? 0 : prevCount) + 1);
        a.setStatus(WebAgreement.Status.REVISION_REQUESTED.name());
        agreementRepository.save(a);

        eventService.append(a.getId(),
                WebAgreementEvent.EventType.REVISION_REQUESTED,
                WebAgreementEvent.ActorType.ERM, callerId,
                Map.of("documentRevision", true,
                        "documents", String.join(",", keys),
                        "revisionCount", a.getRevisionCount()),
                request);
        return a;
    }

    // ── Take back a change request sent by mistake ───────────────────
    //
    // The three request paths above are destructive: they re-arm ticks,
    // wipe signatures, drop document pointers and can overwrite the ERM's
    // own ACH / rate / deliverable values. captureRevisionUndo freezes those
    // fields (plus the desk the row came off) before the request changes
    // anything; revokeRevision plays them back. The two are a matched pair:
    // a NEW destructive write in a request path needs a line in BOTH.

    /** The one sentence both the guard and the ERM screen use once a round is under way. */
    static final String CONSULTANT_ACTED_REASON =
            "The consultant has already started working on this request, so it "
                    + "can't be taken back — their answers would be left standing "
                    + "against the signature and approvals this would restore. "
                    + "Let them submit, then review what comes back.";

    /**
     * Freeze everything a change request is about to destroy. Call AFTER the
     * status guard and BEFORE the first change. No document is ever deleted
     * from storage here (no Cloudinary), so every pointer can be restored.
     */
    private void captureRevisionUndo(WebAgreement a, String kind) {
        ObjectNode s = objectMapper.createObjectNode();
        s.put("kind", kind);
        s.put("prevStatus", a.getStatus());
        s.put("revisionCount", a.getRevisionCount() == null ? 0 : a.getRevisionCount());
        putUndoText(s, "revisionSections", a.getRevisionSections());
        putUndoText(s, "currentRevisionRemarks", a.getCurrentRevisionRemarks());

        ObjectNode f = s.putObject("fields");

        // ERM corrections a section revision overwrites in place.
        putUndoText(f, "achDebitDates", a.getAchDebitDates());
        putUndoText(f, "achDebitAmounts", a.getAchDebitAmounts());
        putUndoText(f, "ratePeriod1", a.getRatePeriod1());
        putUndoText(f, "rateAmount1", a.getRateAmount1());
        putUndoText(f, "ratePeriod2", a.getRatePeriod2());
        putUndoText(f, "rateAmount2", a.getRateAmount2());
        putUndoText(f, "phase2DeliverablePeriod", a.getPhase2DeliverablePeriod());

        // Ticks a section revision re-arms.
        f.put("affirmedMainAgreement", Boolean.TRUE.equals(a.getAffirmedMainAgreement()));
        f.put("affirmedExhibitA", Boolean.TRUE.equals(a.getAffirmedExhibitA()));
        f.put("affirmedExhibitB", Boolean.TRUE.equals(a.getAffirmedExhibitB()));
        f.put("affirmedAppendix1", Boolean.TRUE.equals(a.getAffirmedAppendix1()));
        f.put("affirmedAppendix2", Boolean.TRUE.equals(a.getAffirmedAppendix2()));
        f.put("affirmedAppendix3", Boolean.TRUE.equals(a.getAffirmedAppendix3()));
        f.put("affirmedAppendix4", Boolean.TRUE.equals(a.getAffirmedAppendix4()));
        f.put("affirmedAppendix5", Boolean.TRUE.equals(a.getAffirmedAppendix5()));

        // Signatures a signature revision wipes.
        putUndoText(f, "signatureS3Key", a.getSignatureS3Key());
        putUndoText(f, "finalSignatureS3Key", a.getFinalSignatureS3Key());
        putUndoText(f, "signingIp", a.getSigningIp());
        putUndoText(f, "finalSigningIp", a.getFinalSigningIp());
        putUndoText(f, "sectionSignatureDates", a.getSectionSignatureDates());
        putUndoTime(f, "signingAt", a.getSigningAt());
        putUndoTime(f, "finalSignedAt", a.getFinalSignedAt());
        putUndoTime(f, "signatureDate", a.getSignatureDate());

        // Document pointers a document revision clears.
        putUndoText(f, "cheques", a.getCheques());
        putUndoText(f, "chequeS3Key", a.getChequeS3Key());
        putUndoText(f, "chequeContentType", a.getChequeContentType());
        putUndoTime(f, "chequeUploadedAt", a.getChequeUploadedAt());
        putUndoText(f, "workAuthDocS3Key", a.getWorkAuthDocS3Key());
        putUndoText(f, "workAuthDocContentType", a.getWorkAuthDocContentType());
        putUndoTime(f, "workAuthDocUploadedAt", a.getWorkAuthDocUploadedAt());
        putUndoText(f, "offerLetterS3Key", a.getOfferLetterS3Key());
        putUndoText(f, "offerLetterContentType", a.getOfferLetterContentType());
        putUndoTime(f, "offerLetterUploadedAt", a.getOfferLetterUploadedAt());
        putUndoText(f, "dlDocS3Key", a.getDlDocS3Key());
        putUndoText(f, "dlDocContentType", a.getDlDocContentType());
        putUndoTime(f, "dlDocUploadedAt", a.getDlDocUploadedAt());
        putUndoText(f, "stateIdDocS3Key", a.getStateIdDocS3Key());
        putUndoText(f, "stateIdDocContentType", a.getStateIdDocContentType());
        putUndoTime(f, "stateIdDocUploadedAt", a.getStateIdDocUploadedAt());
        putUndoText(f, "ssnDocS3Key", a.getSsnDocS3Key());
        putUndoText(f, "ssnDocContentType", a.getSsnDocContentType());
        putUndoTime(f, "ssnDocUploadedAt", a.getSsnDocUploadedAt());

        a.setRevisionPrevStatus(a.getStatus());
        a.setRevisionRequestedAt(LocalDateTime.now());
        a.setRevisionUndoSnapshot(s.toString());
    }

    /** Play {@link #captureRevisionUndo}'s snapshot back onto the row. */
    private void restoreRevisionUndo(WebAgreement a, JsonNode snap) {
        a.setRevisionSections(undoText(snap, "revisionSections"));
        a.setCurrentRevisionRemarks(undoText(snap, "currentRevisionRemarks"));

        JsonNode f = snap.path("fields");
        a.setAchDebitDates(undoText(f, "achDebitDates"));
        a.setAchDebitAmounts(undoText(f, "achDebitAmounts"));
        a.setRatePeriod1(undoText(f, "ratePeriod1"));
        a.setRateAmount1(undoText(f, "rateAmount1"));
        a.setRatePeriod2(undoText(f, "ratePeriod2"));
        a.setRateAmount2(undoText(f, "rateAmount2"));
        a.setPhase2DeliverablePeriod(undoText(f, "phase2DeliverablePeriod"));

        a.setAffirmedMainAgreement(f.path("affirmedMainAgreement").asBoolean(false));
        a.setAffirmedExhibitA(f.path("affirmedExhibitA").asBoolean(false));
        a.setAffirmedExhibitB(f.path("affirmedExhibitB").asBoolean(false));
        a.setAffirmedAppendix1(f.path("affirmedAppendix1").asBoolean(false));
        a.setAffirmedAppendix2(f.path("affirmedAppendix2").asBoolean(false));
        a.setAffirmedAppendix3(f.path("affirmedAppendix3").asBoolean(false));
        a.setAffirmedAppendix4(f.path("affirmedAppendix4").asBoolean(false));
        a.setAffirmedAppendix5(f.path("affirmedAppendix5").asBoolean(false));

        a.setSignatureS3Key(undoText(f, "signatureS3Key"));
        a.setFinalSignatureS3Key(undoText(f, "finalSignatureS3Key"));
        a.setSigningIp(undoText(f, "signingIp"));
        a.setFinalSigningIp(undoText(f, "finalSigningIp"));
        a.setSectionSignatureDates(undoText(f, "sectionSignatureDates"));
        a.setSigningAt(undoTime(f, "signingAt"));
        a.setFinalSignedAt(undoTime(f, "finalSignedAt"));
        a.setSignatureDate(undoTime(f, "signatureDate"));

        a.setCheques(undoText(f, "cheques"));
        a.setChequeS3Key(undoText(f, "chequeS3Key"));
        a.setChequeContentType(undoText(f, "chequeContentType"));
        a.setChequeUploadedAt(undoTime(f, "chequeUploadedAt"));
        a.setWorkAuthDocS3Key(undoText(f, "workAuthDocS3Key"));
        a.setWorkAuthDocContentType(undoText(f, "workAuthDocContentType"));
        a.setWorkAuthDocUploadedAt(undoTime(f, "workAuthDocUploadedAt"));
        a.setOfferLetterS3Key(undoText(f, "offerLetterS3Key"));
        a.setOfferLetterContentType(undoText(f, "offerLetterContentType"));
        a.setOfferLetterUploadedAt(undoTime(f, "offerLetterUploadedAt"));
        a.setDlDocS3Key(undoText(f, "dlDocS3Key"));
        a.setDlDocContentType(undoText(f, "dlDocContentType"));
        a.setDlDocUploadedAt(undoTime(f, "dlDocUploadedAt"));
        a.setStateIdDocS3Key(undoText(f, "stateIdDocS3Key"));
        a.setStateIdDocContentType(undoText(f, "stateIdDocContentType"));
        a.setStateIdDocUploadedAt(undoTime(f, "stateIdDocUploadedAt"));
        a.setSsnDocS3Key(undoText(f, "ssnDocS3Key"));
        a.setSsnDocContentType(undoText(f, "ssnDocContentType"));
        a.setSsnDocUploadedAt(undoTime(f, "ssnDocUploadedAt"));
    }

    /**
     * The snapshot for the round the row is in RIGHT NOW, or null. Every
     * capture is followed by revisionCount++, so the open round's snapshot
     * always has {@code revisionCount == snapshot.revisionCount + 1};
     * anything else is a leftover and must never be replayed.
     */
    private JsonNode readUndoSnapshot(WebAgreement a) {
        String raw = a.getRevisionUndoSnapshot();
        if (raw == null || raw.isBlank()) return null;
        if (a.getRevisionPrevStatus() == null) return null;
        try {
            JsonNode node = objectMapper.readTree(raw);
            if (node == null || !node.isObject()) return null;
            int captured = node.path("revisionCount").asInt(-1);
            int current = a.getRevisionCount() == null ? 0 : a.getRevisionCount();
            if (captured < 0 || current != captured + 1) {
                log.warn("Ignoring stale revision undo snapshot on web agreement {} "
                                + "(captured at round {}, row is at {})",
                        a.getApplicationId(), captured, current);
                return null;
            }
            return node;
        } catch (Exception e) {
            log.warn("Unreadable revision undo snapshot on web agreement {}: {}",
                    a.getApplicationId(), e.getMessage());
            return null;
        }
    }

    /**
     * Resolve "can this open change request still be taken back?" onto the
     * transient fields the ERM detail read carries. No-op (all left null)
     * for a row that isn't in an open change request.
     */
    public WebAgreement decorateRevokeState(WebAgreement a) {
        if (a == null) return null;
        if (!WebAgreement.Status.REVISION_REQUESTED.name().equals(a.getStatus())) {
            return a;
        }
        JsonNode snap = readUndoSnapshot(a);
        if (snap == null) {
            a.setRevisionRevocable(false);
            a.setRevisionRevokeBlockedReason(
                    "There's no record of what this change request altered, so it "
                            + "can't be taken back. (Requests sent before take-back "
                            + "existed, and ones the consultant raised themselves, "
                            + "carry no undo record.)");
            return a;
        }
        // Once the participant has written anything this round, restoring
        // the frozen half over their changed answers would leave content
        // nobody affirmed under the old signature. A hard stop, not a warning.
        if (eventService.participantActedSince(a.getId(), a.getRevisionRequestedAt())) {
            a.setRevisionRevocable(false);
            a.setRevisionConsultantActed(true);
            a.setRevisionRevokeBlockedReason(CONSULTANT_ACTED_REASON);
            return a;
        }
        a.setRevisionRevocable(true);
        a.setRevisionConsultantActed(false);
        a.setRevisionRevokeReverts(ermCorrectionsReverted(a, snap));
        return a;
    }

    /**
     * The ERM's own corrections a take-back would roll back, by display name,
     * so the ERM sees them before committing and the audit event names them.
     * Insertion-ordered: it drives a sentence the ERM reads.
     */
    private static final Map<String, String> ERM_CORRECTION_LABELS;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("achDebitDates", "ACH debit dates");
        m.put("achDebitAmounts", "ACH debit amounts");
        m.put("ratePeriod1", "Rate period 1");
        m.put("rateAmount1", "Rate amount 1");
        m.put("ratePeriod2", "Rate period 2");
        m.put("rateAmount2", "Rate amount 2");
        m.put("phase2DeliverablePeriod", "Phase 2 deliverables period");
        ERM_CORRECTION_LABELS = Collections.unmodifiableMap(m);
    }

    private static List<String> ermCorrectionsReverted(WebAgreement a, JsonNode snap) {
        JsonNode f = snap.path("fields");
        Map<String, String> live = new LinkedHashMap<>();
        live.put("achDebitDates", a.getAchDebitDates());
        live.put("achDebitAmounts", a.getAchDebitAmounts());
        live.put("ratePeriod1", a.getRatePeriod1());
        live.put("rateAmount1", a.getRateAmount1());
        live.put("ratePeriod2", a.getRatePeriod2());
        live.put("rateAmount2", a.getRateAmount2());
        live.put("phase2DeliverablePeriod", a.getPhase2DeliverablePeriod());

        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, String> e : ERM_CORRECTION_LABELS.entrySet()) {
            if (!Objects.equals(
                    blankToNull(undoText(f, e.getKey())), blankToNull(live.get(e.getKey())))) {
                changed.add(e.getValue());
            }
        }
        return changed;
    }

    /**
     * Takes back the open change request: every field the request cleared or
     * overwrote is restored, the row goes back to the status it was revised
     * from and the round counter rolls back (the timeline still records both
     * the request and this take-back). Only while the participant hasn't
     * filled or uploaded anything since the request; there is no override.
     */
    @Transactional
    public WebAgreement revokeRevision(String applicationId, Long callerId, HttpServletRequest request) {
        WebAgreement a = requireAccess(applicationId, callerId);
        String st = a.getStatus();
        if (!WebAgreement.Status.REVISION_REQUESTED.name().equals(st)) {
            throw new IllegalStateException(
                    "There's no open change request to take back (status=" + st + ").");
        }
        JsonNode snap = readUndoSnapshot(a);
        if (snap == null) {
            throw new IllegalStateException(
                    "There's no record of what this change request altered, so it "
                            + "can't be taken back. Let the consultant re-submit "
                            + "instead.");
        }
        String prevStatus = snap.path("prevStatus").asText(null);
        if (prevStatus == null || !WebAgreementRules.isRevisionRequestable(prevStatus)) {
            throw new IllegalStateException(
                    "The state this agreement was revised from can't be restored.");
        }
        if (eventService.participantActedSince(a.getId(), a.getRevisionRequestedAt())) {
            throw new IllegalStateException(CONSULTANT_ACTED_REASON);
        }

        // Read the ERM's own corrections BEFORE the restore wipes them.
        List<String> revertedCorrections = ermCorrectionsReverted(a, snap);

        restoreRevisionUndo(a, snap);
        int round = a.getRevisionCount() == null ? 0 : a.getRevisionCount();
        a.setRevisionCount(Math.max(0, snap.path("revisionCount").asInt(Math.max(0, round - 1))));
        a.setStatus(prevStatus);
        WebAgreementRules.clearRevisionUndo(a);
        agreementRepository.save(a);

        eventService.append(a.getId(),
                WebAgreementEvent.EventType.REVISION_REVOKED,
                WebAgreementEvent.ActorType.ERM, callerId,
                Map.of("kind", snap.path("kind").asText("sections"),
                        "restoredTo", prevStatus,
                        "rolledBackRound", round,
                        "revertedErmCorrections", String.join(", ", revertedCorrections)),
                request);

        return a;
    }

    private static void putUndoText(ObjectNode n, String key, String value) {
        if (value == null) n.putNull(key); else n.put(key, value);
    }

    private static void putUndoTime(ObjectNode n, String key, LocalDateTime value) {
        if (value == null) n.putNull(key); else n.put(key, value.toString());
    }

    private static String undoText(JsonNode node, String key) {
        JsonNode v = node.path(key);
        return v.isNull() || v.isMissingNode() ? null : v.asText();
    }

    private static LocalDateTime undoTime(JsonNode node, String key) {
        String raw = undoText(node, key);
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDateTime.parse(raw);
        } catch (Exception e) {
            return null;
        }
    }

    // ── Verify ───────────────────────────────────────────────────────

    /**
     * The ERM verifies the signed agreement (the console's consultant-version
     * release, without the PDF): only from VERIFIED and not yet verified.
     * The status stays VERIFIED; a change request is still possible
     * afterwards, and a resubmit clears the verification again.
     */
    @Transactional
    public WebAgreement verify(String applicationId, Long callerId, HttpServletRequest request) {
        WebAgreement a = requireAccess(applicationId, callerId);
        if (!WebAgreement.Status.VERIFIED.name().equals(a.getStatus())) {
            throw new IllegalStateException(
                    "Only VERIFIED applications can be verified (status=" + a.getStatus() + ").");
        }
        if (Boolean.TRUE.equals(a.getConsultantCopyReleased())) {
            throw new IllegalStateException("This agreement is already verified.");
        }
        a.setConsultantCopyReleased(true);
        a.setConsultantCopyReleasedAt(LocalDateTime.now());
        a.setConsultantCopyReleasedBy(String.valueOf(callerId));
        agreementRepository.save(a);

        eventService.append(a.getId(),
                WebAgreementEvent.EventType.VERIFIED,
                WebAgreementEvent.ActorType.ERM, callerId,
                Map.of("revisionCount", a.getRevisionCount() == null ? 0 : a.getRevisionCount()),
                request);

        return a;
    }

    // ── Preview and documents ────────────────────────────────────────

    /**
     * The participant-signed agreement as a PDF with the uploaded documents
     * appended (ERM preview: no ERM signature). Rendered on request, never
     * stored. Only once signed (VERIFIED, 409 otherwise); a render failure
     * throws {@link WebAgreementRenderer.RenderException}.
     */
    public WebAgreementFileService.Download previewPdf(String applicationId, Long callerId) {
        WebAgreement a = requireAccess(applicationId, callerId);
        if (!WebAgreement.Status.VERIFIED.name().equals(a.getStatus())) {
            throw new IllegalStateException(
                    "The preview is available once the consultant has signed (status="
                            + a.getStatus() + ").");
        }
        byte[] bytes = renderer.renderPdf(a, true, null);
        String filename = "preview-" + AgreementDocumentService.buildPdfFilename(renderer.toTransient(a));
        return new WebAgreementFileService.Download(bytes, "application/pdf", filename);
    }

    /** One of the five uploaded documents, ready to stream; null when none. */
    @Transactional(readOnly = true)
    public WebAgreementFileService.Download readDoc(String applicationId, Doc doc, Long callerId) {
        WebAgreement a = requireAccess(applicationId, callerId);
        return fileService.download(doc.storedKey(a), doc.contentType(a),
                doc.filenameBase + a.getApplicationId());
    }

    /** Cheque #{@code index}, ready to stream; null when none. */
    @Transactional(readOnly = true)
    public WebAgreementFileService.Download readCheque(String applicationId, int index, Long callerId) {
        WebAgreement a = requireAccess(applicationId, callerId);
        ChequeEntry entry = WebAgreementRules.findEntry(WebAgreementRules.parseCheques(a), index);
        if (entry == null) return null;
        return fileService.download(entry.s3Key(), entry.contentType(),
                "SageITCO-Cheque-" + (index + 1) + "_" + a.getApplicationId());
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private static void validateRequired(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required.");
        }
    }
}
