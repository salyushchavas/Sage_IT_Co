package com.spire.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spire.backend.dto.AgreementContent;
import com.spire.backend.entity.User;
import com.spire.backend.entity.WebAgreement;
import com.spire.backend.entity.WebAgreementEvent;
import com.spire.backend.exception.IncompleteSubmissionException;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.repository.UserRepository;
import com.spire.backend.repository.WebAgreementRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.spire.backend.service.WebAgreementRules.ChequeEntry;
import static com.spire.backend.service.WebAgreementRules.Doc;

/**
 * The participant's side of the website agreement: the copy of the
 * console's consultant fill-and-sign flow, with the website sign-in in
 * place of the email code. The caller is always the signed-in user; they
 * can only reach their own agreement (anything else is "not found"), there
 * is no link expiry, and every event carries their users.id.
 *
 * Status rules, revision scope, upload rules and the submit gate are the
 * console's ({@link WebAgreementRules}). On submit the owner ERM is emailed
 * (an active Operations / System admin when the owner can't be).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebAgreementParticipantService {

    /** Thrown inside the email block so a failed email isn't logged as EMAIL_SENT. */
    private static final String EMAIL_NOT_SENT = "the email wasn't sent (see the email log)";

    /** Who gets the "signed" email when the owner ERM can't (first match wins). */
    private static final List<String> FALLBACK_REVIEWER_ROLES = List.of("OPERATIONS_ADMIN", "SYSTEM_ADMIN");

    private final WebAgreementRepository agreementRepository;
    private final WebAgreementEventService eventService;
    private final WebAgreementFileService fileService;
    private final WebAgreementRenderer renderer;
    private final AgreementContentService agreementContentService;
    private final AgreementDocumentService agreementDocumentService;
    private final EmailTemplateService emailTemplateService;
    private final UserRepository userRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── Which agreement ──────────────────────────────────────────────

    /** The caller's newest live, non-cancelled agreement. */
    private Optional<WebAgreement> findMine(Long userId) {
        if (userId == null) return Optional.empty();
        return agreementRepository.findByParticipantUserIdAndDeletedFalseOrderByCreatedAtDesc(userId)
                .stream()
                .filter(a -> !WebAgreement.Status.CANCELLED.name().equals(a.getStatus()))
                .findFirst();
    }

    /** The caller's agreement, or 404. Never another participant's. */
    WebAgreement requireMine(Long userId) {
        WebAgreement a = findMine(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Agreement not found."));
        if (!userId.equals(a.getParticipantUserId())) {
            throw new ResourceNotFoundException("Agreement not found.");
        }
        return a;
    }

    // ── Reads ────────────────────────────────────────────────────────

    /**
     * The wizard's read: the caller's agreement with the submit gate's own
     * requirement answer attached, or null when the ERM hasn't started one.
     * Records ACCESSED, like the console.
     */
    @Transactional
    public WebAgreement getMine(Long userId, HttpServletRequest request) {
        WebAgreement a = findMine(userId).orElse(null);
        if (a == null) return null;
        // Hand the wizard the gate it will actually be judged against.
        a.setEffectiveRequirements(WebAgreementRules.resolveEffectiveRequirements(a));
        eventService.append(a.getId(),
                WebAgreementEvent.EventType.ACCESSED,
                WebAgreementEvent.ActorType.PARTICIPANT, userId,
                Map.of("status", a.getStatus()),
                request);
        return a;
    }

    /** The clauses per wizard section (same docx as the console) + this agreement's fixed values. */
    @Transactional(readOnly = true)
    public AgreementContent content(Long userId) {
        WebAgreement a = requireMine(userId);
        return new AgreementContent(agreementContentService.getSections(), renderer.contentValues(a));
    }

    /**
     * The blank template PDF ("View full agreement"), cached by the console's
     * renderer after the first render. A render failure (no LibreOffice)
     * throws {@link WebAgreementRenderer.RenderException}.
     */
    public byte[] templatePdf(Long userId) {
        requireMine(userId);
        try {
            return agreementDocumentService.getBlankPreviewPdfBytes();
        } catch (Exception e) {
            throw new WebAgreementRenderer.RenderException(e);
        }
    }

    // ── Consent ──────────────────────────────────────────────────────

    /**
     * E-sign consent at the gate before the wizard. Idempotent: once given,
     * the original timestamp and IP are kept and no event is added.
     */
    @Transactional
    public WebAgreement recordConsent(Long userId, HttpServletRequest request) {
        WebAgreement a = requireMine(userId);
        if (a.getConsentGivenAt() != null) {
            return a;
        }
        String ip = AcknowledgmentService.clientIp(request);
        a.setConsentGivenAt(LocalDateTime.now());
        a.setConsentIp(ip);
        a.setConsentVersion(WebAgreementRules.CONSENT_VERSION);
        agreementRepository.save(a);
        eventService.append(a.getId(),
                WebAgreementEvent.EventType.CONSENT_GIVEN,
                WebAgreementEvent.ActorType.PARTICIPANT, userId,
                Map.of("version", WebAgreementRules.CONSENT_VERSION,
                        "ip", ip == null ? "" : ip),
                request);
        return a;
    }

    // ── Fill ─────────────────────────────────────────────────────────

    /**
     * Partial save. Only while filling or revising; during a revision round
     * only the in-scope sections may change. A patch with nothing in it is a
     * no-op with no event.
     */
    @Transactional
    public WebAgreement fill(Long userId, WebAgreementRules.WebAgreementFillPatch patch,
                             HttpServletRequest request) {
        WebAgreement a = requireMine(userId);
        String status = a.getStatus();
        if (!WebAgreementRules.isParticipantWritable(status)) {
            throw new IllegalStateException(
                    "Application is in status " + status
                            + " and cannot be edited by the consultant.");
        }
        if (patch == null) return a;
        Optional<Set<String>> writeScope = WebAgreementRules.consultantWriteScope(a);
        if (writeScope.isPresent()) {
            Set<String> allowed = writeScope.get();
            for (String touched : patch.touchedFieldNames()) {
                String section = WebAgreementRules.FIELD_SECTION.get(touched);
                if (section != null && !allowed.contains(section)) {
                    throw new IllegalArgumentException(
                            "This change is outside the section(s) currently open for "
                                    + "editing. The field '" + touched + "' is locked.");
                }
            }
        }
        // Portal Access soft cap (10), so a direct API call can't store an unbounded list.
        if (patch.portalEntries != null && !patch.portalEntries.isBlank()) {
            try {
                JsonNode arr = objectMapper.readTree(patch.portalEntries);
                if (arr.isArray() && arr.size() > 10) {
                    throw new IllegalArgumentException(
                            "Portal access is limited to 10 entries.");
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
                // malformed JSON — stored as sent; the submit gate finds no complete entry
            }
        }
        boolean changed = patch.applyTo(a);
        if (!changed) {
            return a;
        }
        // Keep the composed name in sync with the structured parts.
        if (patch.firstName != null || patch.middleName != null || patch.lastName != null) {
            String composed = WebAgreementRules.composeName(
                    WebAgreementRules.blankToNull(a.getFirstName()),
                    WebAgreementRules.blankToNull(a.getMiddleName()),
                    WebAgreementRules.blankToNull(a.getLastName()));
            if (composed != null) a.setConsultantName(composed);
        }
        boolean currentAddrTouched = patch.bgCurrentAddressLine1 != null
                || patch.bgCurrentAddressLine2 != null || patch.bgCurrentAddressCity != null
                || patch.bgCurrentAddressState != null || patch.bgCurrentAddressZip != null
                || patch.bgCurrentSameAsResidence != null;
        // With "Same as residence" on, a residence edit also changes the current address.
        boolean residenceTouchedWhileSameAs =
                Boolean.TRUE.equals(a.getBgCurrentSameAsResidence())
                && (patch.addressLine1 != null || patch.addressLine2 != null
                        || patch.addressCity != null || patch.addressState != null
                        || patch.addressZip != null);
        if (currentAddrTouched || residenceTouchedWhileSameAs) {
            a.setBgCurrentAddress(WebAgreementRules.assembledCurrentAddress(a));
        }
        agreementRepository.save(a);
        eventService.append(a.getId(),
                WebAgreementEvent.EventType.CONSULTANT_FILLED,
                WebAgreementEvent.ActorType.PARTICIPANT, userId,
                Map.of("status", status, "fieldsTouched", patch.touchedFieldNames()),
                request);
        return a;
    }

    // ── Submit ───────────────────────────────────────────────────────

    /**
     * Sign and submit (SUBMITTED or REVISION_REQUESTED → VERIFIED). The
     * primary signature may be reused when one is stored (a restricted
     * revision); the closing signature is always drawn again. Missing
     * content throws {@link IncompleteSubmissionException} (400 with the
     * missing keys). The owner ERM is emailed; an email failure never undoes
     * the submit.
     */
    @Transactional
    public WebAgreement submit(Long userId,
                               String signatureBase64,
                               String finalSignatureBase64,
                               String signedLegalName,
                               HttpServletRequest request) {
        WebAgreement a = requireMine(userId);
        String fromStatus = a.getStatus();
        log.info("[web-submit] start appId={} userId={} fromStatus={}",
                a.getApplicationId(), userId, fromStatus);
        if (!WebAgreementRules.isParticipantWritable(fromStatus)) {
            throw new IllegalStateException(
                    "Application is in status " + fromStatus
                            + " and cannot be submitted by the consultant.");
        }
        // A non-blank legal name; a single word (mononym) is fine.
        if (signedLegalName == null || signedLegalName.trim().isEmpty()) {
            throw new IllegalArgumentException("Please enter your full legal name.");
        }

        boolean hasNewPrimary = signatureBase64 != null
                && signatureBase64.startsWith("data:image/");
        boolean hasExistingPrimary = WebAgreementRules.nonBlank(a.getSignatureS3Key());
        boolean missingSig = !hasNewPrimary && !hasExistingPrimary;
        boolean missingFinalSig = finalSignatureBase64 == null || finalSignatureBase64.isBlank()
                || !finalSignatureBase64.startsWith("data:image/");
        List<String> missingFields = WebAgreementRules.collectMissingConsultantFields(a);
        List<String> missingAffs = WebAgreementRules.collectMissingAffirmations(a);
        if (!missingFields.isEmpty() || !missingAffs.isEmpty() || missingSig || missingFinalSig) {
            log.warn("[web-submit] REJECTED appId={} fromStatus={} missingFields={} missingAffirmations={} "
                            + "missingPrimarySig={} missingFinalSig={}",
                    a.getApplicationId(), fromStatus, missingFields, missingAffs,
                    missingSig, missingFinalSig);
            throw new IncompleteSubmissionException(
                    missingFields, missingAffs, missingSig, missingFinalSig);
        }

        try {
            if (hasNewPrimary) {
                a.setSignatureS3Key(fileService.storeSignature(a, signatureBase64, "consultant"));
            }
            a.setFinalSignatureS3Key(
                    fileService.storeSignature(a, finalSignatureBase64, "consultant-final"));
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Couldn't store signature: " + e.getMessage(), e);
        }

        LocalDateTime now = LocalDateTime.now();
        String ip = AcknowledgmentService.clientIp(request);
        a.setSignedLegalName(signedLegalName.trim());
        a.setSignedAt(now);
        a.setSignedIp(ip);
        a.setSignedUserAgent(request == null ? null : request.getHeader("User-Agent"));
        a.setSigningIp(ip);
        a.setSigningAt(now);
        a.setFinalSignedAt(now);
        a.setFinalSigningIp(ip);
        // Per-section dates BEFORE the global date moves (it backfills from it).
        WebAgreementRules.stampSectionSignatureDates(a, now, hasNewPrimary);
        a.setSignatureDate(now);
        // The round is over: no scope, nothing left to take back.
        a.setRevisionSections(null);
        WebAgreementRules.clearRevisionUndo(a);
        // A (re)submission supersedes any earlier verification.
        a.setConsultantCopyReleased(false);
        a.setConsultantCopyReleasedAt(null);
        WebAgreementRules.pruneChequesToDeclaredCount(a);
        a.setStatus(WebAgreement.Status.VERIFIED.name());
        agreementRepository.save(a);
        log.info("[web-submit] SUCCESS appId={} {} -> VERIFIED", a.getApplicationId(), fromStatus);

        eventService.append(a.getId(),
                WebAgreementEvent.EventType.SIGNED,
                WebAgreementEvent.ActorType.PARTICIPANT, userId,
                Map.of("legalName", signedLegalName.trim(),
                        "from", fromStatus,
                        "to", a.getStatus(),
                        "ip", ip == null ? "" : ip),
                request);

        try {
            User reviewer = reviewer(a);
            if (!emailTemplateService.sendWebAgreementSignedEmail(a, reviewer)) {
                throw new IllegalStateException(EMAIL_NOT_SENT);
            }
            eventService.append(a.getId(),
                    WebAgreementEvent.EventType.EMAIL_SENT,
                    WebAgreementEvent.ActorType.SYSTEM, null,
                    Map.of("template", "web_agreement_signed", "to", reviewer.getEmail()),
                    null);
        } catch (Exception e) {
            log.warn("Failed to notify the ERM after web agreement submit for {}: {}",
                    a.getApplicationId(), e.getMessage());
        }
        return a;
    }

    /**
     * Who reviews a signed agreement: the owner ERM while active; else the
     * first active Operations admin, else System admin. Null when nobody.
     */
    private User reviewer(WebAgreement a) {
        if (a.getOwnerUserId() != null) {
            User owner = userRepository.findById(a.getOwnerUserId()).orElse(null);
            if (canReceive(owner)) return owner;
        }
        List<User> everyone = userRepository.findAll();
        for (String role : FALLBACK_REVIEWER_ROLES) {
            for (User u : everyone) {
                if (canReceive(u) && role.equals(roleOf(u))) return u;
            }
        }
        return null;
    }

    private static boolean canReceive(User u) {
        return u != null && !Boolean.FALSE.equals(u.getIsActive())
                && u.getEmail() != null && !u.getEmail().isBlank();
    }

    private static String roleOf(User u) {
        return u.getRole() == null || u.getRole().getName() == null
                ? "" : u.getRole().getName().toUpperCase();
    }

    // ── Preview ──────────────────────────────────────────────────────

    /**
     * The review step's locked-down preview: watermarked page images of the
     * agreement with the draft primary signature, never the PDF itself.
     * Only while filling or revising (409 otherwise). A render failure
     * throws {@link WebAgreementRenderer.RenderException}.
     */
    public Map<String, Object> previewImages(Long userId, String primarySignatureBase64) {
        WebAgreement a = requireMine(userId);
        if (!WebAgreementRules.isParticipantWritable(a.getStatus())) {
            throw new IllegalStateException(
                    "This agreement is no longer open for editing, so there's no preview.");
        }
        String viewerEmail = userRepository.findById(userId)
                .map(User::getEmail)
                .orElse(a.getConsultantEmail());
        List<byte[]> images = renderer.renderPageImages(a, primarySignatureBase64, viewerEmail);
        List<String> pages = new ArrayList<>(images.size());
        for (byte[] png : images) {
            pages.add(Base64.getEncoder().encodeToString(png));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pages", pages);
        payload.put("pageCount", pages.size());
        payload.put("viewerEmail", viewerEmail);
        return payload;
    }

    // ── Uploads ──────────────────────────────────────────────────────

    /**
     * Upload write-gate. Only while filling or revising; during a revision
     * round the whole host section, or exactly the requested {@code doc:*}
     * key, must be in scope (requesting a DL re-upload does not unlock the
     * State-ID or SSN document).
     */
    static void assertUploadWritable(WebAgreement a, String sectionId, String docKey) {
        if (!WebAgreementRules.isParticipantWritable(a.getStatus())) {
            throw new IllegalStateException(
                    "This agreement is no longer open for consultant edits.");
        }
        Optional<Set<String>> scope = WebAgreementRules.consultantWriteScope(a);
        if (scope.isEmpty()) return;
        Set<String> allowed = scope.get();
        if (allowed.contains(sectionId)) return;
        if (docKey != null && allowed.contains(docKey)) return;
        throw new IllegalArgumentException(
                "This change is outside the section(s) currently open for editing.");
    }

    /** One of the five single-file uploads (work authorization, offer letter, DL, State ID, SSN). */
    @Transactional
    public WebAgreement uploadDoc(Long userId, Doc doc, byte[] bytes, String contentType,
                                  HttpServletRequest request) {
        WebAgreement a = requireMine(userId);
        assertUploadWritable(a, doc.sectionId, doc.docKey);
        String type = WebAgreementFileService.validate(bytes, contentType, doc.fileLabel, doc.typeLabel);
        String stored = store(a, doc.docType, bytes, type, doc.typeLabel);
        doc.set(a, stored, type, LocalDateTime.now());
        agreementRepository.save(a);
        eventService.append(a.getId(), doc.event,
                WebAgreementEvent.ActorType.PARTICIPANT, userId,
                Map.of("s3Key", stored, "contentType", type, "bytes", bytes.length),
                request);
        return a;
    }

    /** The caller's own uploaded document, ready to stream; null when none. */
    @Transactional(readOnly = true)
    public WebAgreementFileService.Download readDoc(Long userId, Doc doc) {
        WebAgreement a = requireMine(userId);
        return fileService.download(doc.storedKey(a), doc.contentType(a),
                doc.filenameBase + a.getApplicationId());
    }

    /** Upload the file of cheque #{@code index}; index 0 is mirrored into the single-cheque columns. */
    @Transactional
    public WebAgreement uploadChequeAt(Long userId, int index, byte[] bytes, String contentType,
                                       HttpServletRequest request) {
        WebAgreement a = requireMine(userId);
        assertUploadWritable(a, "appendix5", "doc:cheque");
        if (index < 0 || index > WebAgreementRules.MAX_CHEQUE_INDEX) {
            throw new IllegalArgumentException("Cheque index out of range.");
        }
        String type = WebAgreementFileService.validate(bytes, contentType, "Cheque", "Cheque");
        String stored = store(a, "cheque-" + index, bytes, type, "Cheque");

        List<ChequeEntry> entries = new ArrayList<>(WebAgreementRules.parseCheques(a));
        ChequeEntry existing = WebAgreementRules.findEntry(entries, index);
        LocalDateTime now = LocalDateTime.now();
        WebAgreementRules.upsertEntry(entries, new ChequeEntry(
                index,
                existing == null ? "" : existing.number(),
                existing == null ? "" : existing.date(),
                stored,
                type,
                now.toString()));
        a.setCheques(WebAgreementRules.serialiseCheques(entries));
        // Keep the single-cheque columns current for index 0.
        if (index == 0) {
            a.setChequeS3Key(stored);
            a.setChequeContentType(type);
            a.setChequeUploadedAt(now);
        }
        agreementRepository.save(a);
        eventService.append(a.getId(),
                WebAgreementEvent.EventType.CHEQUE_UPLOADED,
                WebAgreementEvent.ActorType.PARTICIPANT, userId,
                Map.of("s3Key", stored, "index", index, "contentType", type, "bytes", bytes.length),
                request);
        return a;
    }

    /** Set cheque #{@code index}'s number and date; its file (if any) is kept. */
    @Transactional
    public WebAgreement setChequeMetadata(Long userId, int index,
                                          WebAgreementRules.ChequeMetadataPatch patch,
                                          HttpServletRequest request) {
        WebAgreement a = requireMine(userId);
        assertUploadWritable(a, "appendix5", "doc:cheque");
        if (index < 0 || index > WebAgreementRules.MAX_CHEQUE_INDEX) {
            throw new IllegalArgumentException("Cheque index out of range.");
        }
        List<ChequeEntry> entries = new ArrayList<>(WebAgreementRules.parseCheques(a));
        ChequeEntry existing = WebAgreementRules.findEntry(entries, index);
        String number = patch == null || patch.number == null ? "" : patch.number.trim();
        String date = patch == null || patch.date == null ? "" : patch.date.trim();
        WebAgreementRules.upsertEntry(entries, new ChequeEntry(
                index,
                number,
                date,
                existing == null ? "" : existing.s3Key(),
                existing == null ? "" : existing.contentType(),
                existing == null ? "" : existing.uploadedAt()));
        a.setCheques(WebAgreementRules.serialiseCheques(entries));
        agreementRepository.save(a);
        // Every participant write emits an event: the take-back guard reads them.
        eventService.append(a.getId(),
                WebAgreementEvent.EventType.CHEQUE_METADATA_UPDATED,
                WebAgreementEvent.ActorType.PARTICIPANT, userId,
                Map.of("index", index),
                request);
        return a;
    }

    /** The caller's own cheque #{@code index}, ready to stream; null when none. */
    @Transactional(readOnly = true)
    public WebAgreementFileService.Download readCheque(Long userId, int index) {
        WebAgreement a = requireMine(userId);
        ChequeEntry entry = WebAgreementRules.findEntry(WebAgreementRules.parseCheques(a), index);
        if (entry == null) return null;
        return fileService.download(entry.s3Key(), entry.contentType(),
                "SageITCO-Cheque-" + (index + 1) + "_" + a.getApplicationId());
    }

    /** Store an already-validated file; a storage failure reads "Couldn't store …". */
    private String store(WebAgreement a, String docType, byte[] bytes, String type, String label) {
        try {
            return fileService.storeUpload(a, docType, bytes, type);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Couldn't store " + label.toLowerCase() + ": " + e.getMessage(), e);
        }
    }
}
