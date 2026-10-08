package com.spire.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.spire.backend.entity.User;
import com.spire.backend.entity.WebAgreement;
import com.spire.backend.entity.WebAgreementEvent;
import com.spire.backend.repository.WebAgreementRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The end of the website agreement's chain, the website copy of the
 * console's (ConsultantApplicationService's ermApproveAndSign and
 * advanceToPhase2, the ERM download-pdf route and the /me prefill; all
 * untouched): the signer's name and title for the countersign form, the
 * countersign with its final PDF, the executed PDF for the ERM, and the
 * advance of an executed Phase 1 agreement to Phase 2.
 *
 * Who: the owning ERM, or an Operations or System admin acting on any
 * agreement; anyone else gets "not found". Every event carries the real
 * users.id of whoever acted. No email anywhere: the console mails the
 * executed PDF to its inbox and notifies the consultant; on the website the
 * screens show it. No lock, as the console.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebAgreementExecutionService {

    private final WebAgreementRepository agreementRepository;
    private final WebAgreementFileService fileService;
    private final WebAgreementRenderer renderer;
    private final WebAgreementEventService eventService;
    private final WebAgreementAccess access;
    private final WebAgreementStaffTitleService titleService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Body of the advance: which sections Phase 2 makes required (the
     * console's Phase2Promotion, same six fields). true promotes the
     * section; false or null leaves it alone. When every field is null (or
     * there is no body) every appendix not yet required is promoted and the
     * SSN is left alone.
     */
    public static class Phase2Promotion {
        public Boolean appendix1;
        public Boolean appendix2;
        public Boolean appendix3;
        public Boolean appendix4;
        public Boolean appendix5;
        public Boolean ssn;
    }

    /**
     * The stored executed PDF couldn't be read (gone from storage, or the
     * store didn't answer). The console's download answers that with 502.
     */
    public static class FinalPdfUnreadable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public FinalPdfUnreadable(Throwable cause) {
            super("The stored final PDF couldn't be read.", cause);
        }
    }

    // ── Signer profile ───────────────────────────────────────────────

    /**
     * The countersign form's prefill (the console's /me): the CALLER's
     * {fullName, title}, the title as printed on agreements ("" when none
     * was set). ERMs and admins only.
     */
    @Transactional(readOnly = true)
    public Map<String, String> signerProfile(Long callerId) {
        User caller = access.requireStaff(callerId);
        Map<String, String> out = new LinkedHashMap<>();
        out.put("fullName", caller.getFullName() == null ? "" : caller.getFullName());
        out.put("title", titleService.titleOf(caller.getId()).orElse(""));
        return out;
    }

    // ── Countersign ──────────────────────────────────────────────────

    /**
     * "Approve & sign" (the console's ermApproveAndSign, one transaction):
     * only once every required approver has approved (READY_TO_SIGN). Name
     * and title are taken as typed; only blank is refused, and nothing ties
     * them to the signer. The signature is stored under the participant's
     * folder, the countersign date is now, an empty effective date becomes
     * today, and the agreement is COMPLETED.
     *
     * Then, best effort: the final PDF (every signature, the uploads, no
     * certificate) is rendered and stored as a new file; it becomes s3Key,
     * and in Phase 1 also the Phase 1 copy, which Phase 2 never overwrites.
     * If that fails (the render permit included) the agreement stays
     * COMPLETED with no PDF and only a log line, as in the console. No email.
     */
    @Transactional
    public WebAgreement approveAndSign(String applicationId, String ermName, String ermTitle,
                                       String ermSignatureBase64, Long callerId, HttpServletRequest request) {
        WebAgreement a = access.requireAccess(applicationId, callerId);
        if (!WebAgreement.Status.READY_TO_SIGN.name().equals(a.getStatus())) {
            throw new IllegalStateException(
                    "Only READY_TO_SIGN applications can be countersigned "
                            + "(status=" + a.getStatus() + "). All required "
                            + "approvals must be in first.");
        }
        if (ermName == null || ermName.isBlank()
                || ermTitle == null || ermTitle.isBlank()) {
            throw new IllegalArgumentException(
                    "ERM name and title are required to countersign.");
        }
        if (ermSignatureBase64 == null || ermSignatureBase64.isBlank()
                || !ermSignatureBase64.startsWith("data:image/")) {
            throw new IllegalArgumentException(
                    "An ERM signature image is required (data:image/...).");
        }

        String ermSignatureKey;
        try {
            ermSignatureKey = fileService.storeSignature(a, ermSignatureBase64, "erm");
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Couldn't store ERM signature: " + e.getMessage(), e);
        }

        LocalDateTime now = LocalDateTime.now();
        // Set at create; an older row without one gets today so the
        // closing block isn't blank.
        if (a.getEffectiveDate() == null) {
            a.setEffectiveDate(now.toLocalDate());
        }
        a.setErmName(ermName);
        a.setErmTitle(ermTitle);
        a.setErmSignatureS3Key(ermSignatureKey);
        // The ERM's own date line; the participant's signatureDate is untouched.
        a.setErmSignatureDate(now);
        a.setStatus(WebAgreement.Status.COMPLETED.name());
        agreementRepository.save(a);

        eventService.append(a.getId(),
                WebAgreementEvent.EventType.APPROVED_AND_SIGNED,
                WebAgreementEvent.ActorType.ERM, callerId,
                Map.of("ermName", ermName, "ermTitle", ermTitle),
                request);

        try {
            byte[] pdf = renderer.renderFinalPdf(a);
            Integer signPhase = a.getPhase();
            String key = fileService.storePdf(a, "final-p" + (signPhase == null ? 1 : signPhase), pdf);
            a.setS3Key(key);
            // The Phase 1 copy: Phase 2's countersign rewrites only s3Key and
            // the advance clears neither, so a Manager can still see it.
            if (signPhase == null || signPhase == 1) {
                a.setPhase1FinalPdfS3Key(key);
            }
            agreementRepository.save(a);
            eventService.append(a.getId(),
                    WebAgreementEvent.EventType.PDF_GENERATED,
                    WebAgreementEvent.ActorType.SYSTEM, null,
                    Map.of("s3Key", key == null ? "" : key, "kind", "final"),
                    null);
        } catch (Exception e) {
            log.error("Final PDF generation failed for web agreement {}: {}",
                    applicationId, e.getMessage());
        }

        return a;
    }

    // ── The executed PDF ─────────────────────────────────────────────

    /**
     * The stored executed PDF for the ERM (the console's download-pdf), named
     * like the console's ({@code SageITCO-Agreement_{name}[_{track}].pdf}).
     * Null when none is stored (404). There is no status check, as in the
     * console, so after a Phase 2 advance it still serves the last one.
     * Throws {@link FinalPdfUnreadable} when the stored file can't be read.
     */
    @Transactional(readOnly = true)
    public WebAgreementFileService.Download downloadFinal(String applicationId, Long callerId) {
        WebAgreement a = access.requireAccess(applicationId, callerId);
        String key = a.getS3Key();
        if (!WebAgreementRules.nonBlank(key)) return null;
        byte[] bytes;
        try {
            bytes = fileService.readBytes(key);
        } catch (RuntimeException e) {
            log.warn("Final PDF unreadable for web agreement {}: {}", applicationId, e.getMessage());
            throw new FinalPdfUnreadable(e);
        }
        if (bytes == null || bytes.length == 0) {
            log.warn("Final PDF missing from storage for web agreement {}", applicationId);
            throw new FinalPdfUnreadable(null);
        }
        return new WebAgreementFileService.Download(bytes, "application/pdf",
                WebAgreementDocumentEngine.buildPdfFilename(a));
    }

    // ── Phase 2 ──────────────────────────────────────────────────────

    /**
     * Reopens an executed Phase 1 agreement on the same document (the
     * console's advanceToPhase2, one transaction). The promoted sections
     * become required (see {@link Phase2Promotion}); every filled field is
     * kept. Cleared: the closing signature with its time and IP, the
     * affirmations of the promoted appendices only, the ERM's name, title
     * and signature, and the participant's signing date. Kept: the primary
     * signature and legal name, every other affirmation, the stored PDFs,
     * the ERM's countersign date and the verification.
     *
     * The participant may then write only the promoted appendices (the
     * "ssn" key is never in that scope). One difference from the console,
     * the owner's decision of 8 Oct 2026 (spec L-H1): when the submit gate
     * would ask for the SSN and Appendix 3 is not promoted, Appendix 3 is
     * reopened too, with every earlier answer kept, so the participant can
     * enter it; the SSN stays required. The console leaves it locked and the
     * agreement can only be cancelled. The agreement goes back to SUBMITTED
     * in Phase 2; the event also records the two signature files the advance
     * let go of and whether Appendix 3 was reopened for the SSN. No email:
     * the participant's dashboard turns to their step.
     */
    @Transactional
    public WebAgreement advanceToPhase2(String applicationId, Phase2Promotion promotion,
                                        Long callerId, HttpServletRequest request) {
        WebAgreement a = access.requireAccess(applicationId, callerId);
        if (!WebAgreement.Status.COMPLETED.name().equals(a.getStatus())) {
            throw new IllegalStateException(
                    "Only a COMPLETED agreement can be advanced to Phase 2 "
                            + "(status=" + a.getStatus() + ").");
        }
        Integer currentPhase = a.getPhase();
        if (currentPhase != null && currentPhase >= 2) {
            throw new IllegalStateException(
                    "This agreement is already at Phase " + currentPhase + ".");
        }

        // Any field sent is an explicit choice (true promotes, false or
        // missing leaves it alone); none sent promotes every appendix not yet
        // required and leaves the SSN alone.
        boolean p1 = promotion != null && Boolean.TRUE.equals(promotion.appendix1);
        boolean p2 = promotion != null && Boolean.TRUE.equals(promotion.appendix2);
        boolean p3 = promotion != null && Boolean.TRUE.equals(promotion.appendix3);
        boolean p4 = promotion != null && Boolean.TRUE.equals(promotion.appendix4);
        boolean p5 = promotion != null && Boolean.TRUE.equals(promotion.appendix5);
        boolean pSsn = promotion != null && Boolean.TRUE.equals(promotion.ssn);
        boolean explicitSelection = promotion != null && (
                promotion.appendix1 != null || promotion.appendix2 != null
                || promotion.appendix3 != null || promotion.appendix4 != null
                || promotion.appendix5 != null || promotion.ssn != null);
        if (!explicitSelection) {
            p1 = !Boolean.TRUE.equals(a.getRequireAppendix1());
            p2 = !Boolean.TRUE.equals(a.getRequireAppendix2());
            p3 = !Boolean.TRUE.equals(a.getRequireAppendix3());
            p4 = !Boolean.TRUE.equals(a.getRequireAppendix4());
            p5 = !Boolean.TRUE.equals(a.getRequireAppendix5());
        }

        List<String> promoted = new ArrayList<>();
        if (p1) { a.setRequireAppendix1(true); promoted.add("appendix1"); }
        if (p2) { a.setRequireAppendix2(true); promoted.add("appendix2"); }
        if (p3) { a.setRequireAppendix3(true); promoted.add("appendix3"); }
        if (p4) { a.setRequireAppendix4(true); promoted.add("appendix4"); }
        if (p5) { a.setRequireAppendix5(true); promoted.add("appendix5"); }
        if (pSsn) { a.setRequireSsn(true); promoted.add("ssn"); }

        // Website only (owner, 8 Oct 2026): a required SSN the participant
        // hasn't given (blank, or not letters and digits) is never out of
        // reach. Appendix 3, where it is entered, reopens like a promoted
        // appendix; its requirement flag is left as it is.
        boolean appendix3ReopenedForSsn = !p3
                && WebAgreementRules.collectMissingConsultantFields(a).contains("bgFullSsn");

        // Phase 1 stays as signed: the primary signature, the legal name and
        // every affirmation of a section not reopened are kept. The
        // participant re-signs the closing block and re-affirms only the
        // reopened appendices.
        String clearedFinalSignatureKey = a.getFinalSignatureS3Key();
        a.setFinalSignatureS3Key(null);
        a.setFinalSignedAt(null);
        a.setFinalSigningIp(null);
        if (p1) a.setAffirmedAppendix1(false);
        if (p2) a.setAffirmedAppendix2(false);
        if (p3 || appendix3ReopenedForSsn) a.setAffirmedAppendix3(false);
        if (p4) a.setAffirmedAppendix4(false);
        if (p5) a.setAffirmedAppendix5(false);

        // What the participant may write in Phase 2 (appendix keys only).
        List<String> reopened = new ArrayList<>(promoted);
        if (appendix3ReopenedForSsn) {
            reopened.add("appendix3");
            Collections.sort(reopened);
        }
        a.setPhase2ReopenedSections(sectionScopeJson(reopened));

        // The Phase 1 countersignature goes; the Phase 2 countersign makes a
        // new one. The ERM's date and the stored PDFs stay.
        String clearedErmSignatureKey = a.getErmSignatureS3Key();
        a.setErmName(null);
        a.setErmTitle(null);
        a.setErmSignatureS3Key(null);
        a.setSignatureDate(null);

        a.setPhase(2);
        a.setStatus(WebAgreement.Status.SUBMITTED.name());
        agreementRepository.save(a);

        eventService.append(a.getId(),
                WebAgreementEvent.EventType.ADVANCED_TO_PHASE_2,
                WebAgreementEvent.ActorType.ERM, callerId,
                Map.of("ermUserId", callerId,
                        "promoted", promoted,
                        "appendix3ReopenedForSsn", appendix3ReopenedForSsn,
                        "clearedErmSignatureKey", clearedErmSignatureKey == null ? "" : clearedErmSignatureKey,
                        "clearedFinalSignatureKey", clearedFinalSignatureKey == null ? "" : clearedFinalSignatureKey),
                request);

        return a;
    }

    /** {@code [{"key":"appendix3"}, …]} of the appendix keys only ("ssn" is dropped); "[]" when none. */
    String sectionScopeJson(List<String> keys) {
        ArrayNode arr = objectMapper.createArrayNode();
        if (keys != null) {
            for (String k : keys) {
                if (k != null && k.startsWith("appendix")) {
                    arr.add(objectMapper.createObjectNode().put("key", k));
                }
            }
        }
        return arr.toString();
    }
}
