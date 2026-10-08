package com.spire.backend.service;

import com.spire.backend.entity.WebAgreement;
import com.spire.backend.entity.WebAgreementEvent;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.repository.WebAgreementRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The System Admin's agreement tools, the website copy of the console's
 * super-admin maintenance (ConsultantApplicationService's deleteApplication,
 * regenerateCompletedAgreements and revokeErmSignaturesOnCompleted, all
 * untouched): delete (archive) one agreement, re-render every executed
 * agreement from the current template, and revoke every ERM
 * countersignature.
 *
 * System Admin only, checked here by role name (legacy ADMIN also holds
 * ROLE_ADMIN). Every event carries the System Admin's real users.id (the
 * console writes 0). Nothing is ever deleted: an archived agreement keeps
 * its row, and a regenerate stores new files and keeps the old ones. No
 * email anywhere, as in the console. No lock, as the console.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebAgreementMaintenanceService {

    private final WebAgreementRepository agreementRepository;
    private final WebAgreementRenderer renderer;
    private final WebAgreementFileService fileService;
    private final WebAgreementVersionService versionService;
    private final WebAgreementEventService eventService;
    private final WebAgreementAccess access;

    // ── Delete (archive) ─────────────────────────────────────────────

    /**
     * Soft-deletes one agreement at any status (the console's
     * deleteApplication, one transaction): deleted, when and by whom. The row
     * and its files stay; every staff, participant, approver and board query
     * already skips it, so it is "not found" everywhere afterwards. An
     * agreement already deleted is "not found" too. There is no restore.
     */
    @Transactional
    public void archive(String applicationId, Long callerId, HttpServletRequest request) {
        access.requireSystemAdmin(callerId);
        WebAgreement a = agreementRepository.findByApplicationId(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException("Agreement not found."));
        if (Boolean.TRUE.equals(a.getDeleted())) {
            // Already deleted: treat as gone.
            throw new ResourceNotFoundException("Agreement not found.");
        }
        String previousStatus = a.getStatus();
        a.setDeleted(true);
        a.setDeletedAt(LocalDateTime.now());
        a.setDeletedBy(callerId);
        agreementRepository.save(a);

        eventService.append(a.getId(),
                WebAgreementEvent.EventType.APPLICATION_ARCHIVED,
                WebAgreementEvent.ActorType.ERM, callerId,
                Map.of("deletedBy", String.valueOf(callerId),
                        "previousStatus", previousStatus == null ? "" : previousStatus),
                request);
    }

    // ── Regenerate executed agreements ───────────────────────────────

    /**
     * Re-renders every executed (COMPLETED), non-deleted agreement from the
     * current template (the console's regenerateCompletedAgreements). For
     * each: a new final PDF becomes s3Key (and the Phase 1 copy in Phase 1);
     * when the verified version was released, its body and Certificate of
     * Completion are re-rendered too and become consultantPdfS3Key and
     * documentHash, with no new version row. Every file is new; the old ones
     * stay in storage.
     *
     * A dry run only counts. Not one transaction: each agreement is saved on
     * its own, so one failure (a render, storage, or the busy render permit)
     * is counted and the run goes on. Renders run one agreement at a time
     * through the website render permit.
     */
    public Map<String, Object> regenerateCompleted(boolean dryRun, Long callerId, HttpServletRequest request) {
        access.requireSystemAdmin(callerId);
        List<WebAgreement> completed = agreementRepository.findByStatusAndDeletedFalse(
                WebAgreement.Status.COMPLETED.name());
        int processed = 0, regenerated = 0, failed = 0;
        List<String> errors = new ArrayList<>();
        for (WebAgreement a : completed) {
            processed++;
            if (dryRun) continue;
            try {
                regenerateOne(a, callerId, request);
                regenerated++;
            } catch (Exception e) {
                failed++;
                errors.add(a.getApplicationId() + ": " + e.getMessage());
                log.error("Regenerate failed for web agreement {}: {}",
                        a.getApplicationId(), e.getMessage(), e);
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("dryRun", dryRun);
        summary.put("status", WebAgreement.Status.COMPLETED.name());
        summary.put("matched", completed.size());
        summary.put("processed", processed);
        summary.put("regenerated", regenerated);
        summary.put("failed", failed);
        summary.put("errors", errors);
        return summary;
    }

    /**
     * One agreement's re-render. Both files are rendered and stored before
     * the agreement is touched, so a failure leaves it as it was (only an
     * unreferenced new file may be left in storage).
     */
    private void regenerateOne(WebAgreement a, Long callerId, HttpServletRequest request) {
        String oldS3Key = a.getS3Key();
        String oldHash = a.getDocumentHash();
        Integer phase = a.getPhase();

        // 1. The final PDF (every stored signature) from the current template.
        String finalKey = fileService.storePdf(a, "final-p" + (phase == null ? 1 : phase),
                renderer.renderFinalPdf(a));
        // 2. The released verified version: body + certificate, new hash.
        WebAgreementVersionService.Rerender version = Boolean.TRUE.equals(a.getConsultantCopyReleased())
                ? versionService.rerenderWithoutRow(a)
                : null;

        a.setS3Key(finalKey);
        if (phase == null || phase == 1) {
            a.setPhase1FinalPdfS3Key(finalKey);
        }
        if (version != null) {
            a.setConsultantPdfS3Key(version.storedKey());
            a.setDocumentHash(version.sha256());
        }
        agreementRepository.save(a);

        eventService.append(a.getId(),
                WebAgreementEvent.EventType.PDF_GENERATED,
                WebAgreementEvent.ActorType.ERM, callerId,
                Map.of("kind", "regenerated",
                        "reason", "template-correction-build-AA",
                        "oldS3Key", oldS3Key == null ? "" : oldS3Key,
                        "newS3Key", a.getS3Key() == null ? "" : a.getS3Key(),
                        "oldDocumentHash", oldHash == null ? "" : oldHash,
                        "newDocumentHash", a.getDocumentHash() == null ? "" : a.getDocumentHash()),
                request);
    }

    // ── Revoke ERM countersignatures ─────────────────────────────────

    /**
     * Revokes the ERM countersignature on every executed (COMPLETED),
     * non-deleted agreement and sends it back to VERIFIED (the console's
     * revokeErmSignaturesOnCompleted), so the whole chain runs again: the ERM
     * re-sends, the approvers re-approve, the ERM re-signs. Cleared: the ERM
     * signature and its date. Kept: the ERM's name and title (the re-sign
     * prefill), the stored PDFs, the verification and the approval history.
     *
     * A dry run only counts. Not one transaction: each agreement is saved on
     * its own and a failure is counted. No email: participants see their
     * agreement go back from "Executed".
     */
    public Map<String, Object> revokeErmSignatures(boolean dryRun, Long callerId, HttpServletRequest request) {
        access.requireSystemAdmin(callerId);
        List<WebAgreement> completed = agreementRepository.findByStatusAndDeletedFalse(
                WebAgreement.Status.COMPLETED.name());
        int processed = 0, reverted = 0, failed = 0;
        List<String> errors = new ArrayList<>();
        for (WebAgreement a : completed) {
            processed++;
            if (dryRun) continue;
            try {
                revokeOne(a, callerId, request);
                reverted++;
            } catch (Exception e) {
                failed++;
                errors.add(a.getApplicationId() + ": " + e.getMessage());
                log.error("Revoking the ERM signature failed for web agreement {}: {}",
                        a.getApplicationId(), e.getMessage(), e);
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("dryRun", dryRun);
        summary.put("status", WebAgreement.Status.COMPLETED.name());
        summary.put("matched", completed.size());
        summary.put("processed", processed);
        summary.put("reverted", reverted);
        summary.put("failed", failed);
        summary.put("errors", errors);
        return summary;
    }

    /** One agreement's countersignature cleared and the agreement back to VERIFIED. */
    private void revokeOne(WebAgreement a, Long callerId, HttpServletRequest request) {
        String fromStatus = a.getStatus();
        String oldSignatureKey = a.getErmSignatureS3Key();
        // The ERM's name and title stay as the re-sign prefill; the ERM
        // blocks render blank again until the ERM countersigns.
        a.setErmSignatureS3Key(null);
        a.setErmSignatureDate(null);
        a.setStatus(WebAgreement.Status.VERIFIED.name());
        agreementRepository.save(a);

        eventService.append(a.getId(),
                WebAgreementEvent.EventType.ERM_SIGNATURE_REVOKED,
                WebAgreementEvent.ActorType.ERM, callerId,
                Map.of("fromStatus", fromStatus == null ? "" : fromStatus,
                        "toStatus", WebAgreement.Status.VERIFIED.name(),
                        "clearedErmSignatureKey", oldSignatureKey == null ? "" : oldSignatureKey,
                        "reason", "re-sign-after-template-correction"),
                request);
    }
}
