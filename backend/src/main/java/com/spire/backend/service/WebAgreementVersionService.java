package com.spire.backend.service;

import com.spire.backend.entity.WebAgreement;
import com.spire.backend.entity.WebAgreementVersion;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.repository.WebAgreementVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The verified versions of a website agreement (V1, V2, …), the website copy
 * of the console's consultant-version release (ConsultantVersionService plus
 * the version half of ermApproveConsultantVersion and its readers).
 *
 * A version is the ERM preview render (participant signatures, main ERM
 * signature blank, uploads appended) with the Certificate of Completion
 * appended last and the two-pass hash, stored through the website's storage
 * under the participant's folder as
 * {@code web-agreement-consultant-version-p{phase}-…pdf}, never under the
 * console's {@code agreements/} prefix. Every release mints the next number;
 * rows are never updated or deleted, and earlier files stay in storage.
 *
 * No emails, no event: the caller (Verify) records its own event.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebAgreementVersionService {

    private final WebAgreementRenderer renderer;
    private final WebAgreementCertificateService certificateService;
    private final WebAgreementFileService fileService;
    private final WebAgreementVersionRepository versionRepository;

    /** A version just released: its number, the stored value, the stored hash and the PDF size. */
    public record Release(int versionNumber, String storedKey, String sha256, int bytes) {}

    /** A version body re-rendered with no new row (the System Admin's regenerate). */
    public record Rerender(String storedKey, String sha256) {}

    /** The rendered, certified bytes and their stored hash, before storing. */
    private record Certified(byte[] bytes, String sha256) {}

    /**
     * Renders, certifies and stores the next version, then inserts its row
     * ({@code max + 1}, the stored value, the hash, the agreement's phase).
     * Sets {@code consultantPdfS3Key} and {@code documentHash} on {@code a}
     * but does not save it; the caller (one transaction with Verify) does.
     *
     * A render or certificate failure (including a busy render permit) is
     * a 409 "Couldn't render consultant-version PDF: …"; a storage failure
     * a 409 "Couldn't store consultant-version PDF: …". In both cases
     * {@code a} is untouched and no row is written.
     */
    @Transactional
    public Release release(WebAgreement a) {
        Certified c = renderCertified(a);
        String stored;
        try {
            stored = fileService.storePdf(a, kind(a), c.bytes());
        } catch (Exception e) {
            log.error("Storing the consultant-version PDF failed for web agreement {}: {}",
                    a.getApplicationId(), e.getMessage(), e);
            throw new IllegalStateException("Couldn't store consultant-version PDF: " + e.getMessage(), e);
        }

        a.setConsultantPdfS3Key(stored);
        a.setDocumentHash(c.sha256());

        int next = versionRepository.findTopByAgreementIdOrderByVersionNumberDesc(a.getId())
                .map(v -> v.getVersionNumber() + 1)
                .orElse(1);
        versionRepository.save(WebAgreementVersion.builder()
                .agreementId(a.getId())
                .versionNumber(next)
                .s3Key(stored)
                .documentHash(c.sha256())
                .phase(a.getPhase())
                .build());
        return new Release(next, stored, c.sha256(), c.bytes().length);
    }

    /**
     * The regenerate tool's re-render of the version body + certificate: a
     * new stored file and its hash, with no new version row. Changes
     * nothing on {@code a}; the caller repoints {@code consultantPdfS3Key}
     * and {@code documentHash}. Failures are thrown (render: a
     * {@link WebAgreementRenderer.RenderException} carrying the cause's
     * message; busy permit: StorageUnavailableException; storage: as thrown).
     */
    public Rerender rerenderWithoutRow(WebAgreement a) {
        byte[] body = renderer.renderErmPreviewPdf(a);
        byte[] certified;
        try {
            certified = certificateService.appendCertificateAndStamp(body, a);
        } catch (Exception e) {
            throw new WebAgreementRenderer.RenderException(e);
        }
        String stored = fileService.storePdf(a, kind(a), certified);
        return new Rerender(stored, WebAgreementCertificateService.sha256Hex(certified));
    }

    /** Every version of the agreement, oldest first (V1 first). */
    @Transactional(readOnly = true)
    public List<WebAgreementVersion> list(Long agreementId) {
        return versionRepository.findByAgreementIdOrderByVersionNumberAsc(agreementId);
    }

    /**
     * The stored PDF of version {@code n} (no re-render). 404 when the
     * version doesn't exist; null when its file can't be found in storage.
     */
    @Transactional(readOnly = true)
    public byte[] bytes(Long agreementId, int n) {
        WebAgreementVersion v = versionRepository.findByAgreementIdAndVersionNumber(agreementId, n)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "WebAgreementVersion", "versionNumber", String.valueOf(n)));
        return fileService.readBytes(v.getS3Key());
    }

    /**
     * The stored PDF of the version routed for this approval round
     * ({@code approvalVersionNumber}), or null when none was routed or it
     * can't be found, so the caller falls back to a live ERM preview.
     */
    @Transactional(readOnly = true)
    public byte[] routedBytes(WebAgreement a) {
        Integer n = a.getApprovalVersionNumber();
        if (n == null) return null;
        return versionRepository.findByAgreementIdAndVersionNumber(a.getId(), n)
                .map(v -> fileService.readBytes(v.getS3Key()))
                .orElse(null);
    }

    /**
     * The stored PDF of the latest version (highest V), whichever round
     * reviewed it, or null when there is none (the caller falls back to a
     * live ERM preview).
     */
    @Transactional(readOnly = true)
    public byte[] latestBytes(WebAgreement a) {
        return versionRepository.findTopByAgreementIdOrderByVersionNumberDesc(a.getId())
                .map(v -> fileService.readBytes(v.getS3Key()))
                .orElse(null);
    }

    // ── Helpers ──────────────────────────────────────────────────────

    /** Render + certificate + hash, every failure as the console's 409. */
    private Certified renderCertified(WebAgreement a) {
        try {
            byte[] body = renderer.renderErmPreviewPdf(a);
            byte[] certified = certificateService.appendCertificateAndStamp(body, a);
            return new Certified(certified, WebAgreementCertificateService.sha256Hex(certified));
        } catch (Exception e) {
            log.error("Consultant-version render failed for web agreement {}: {}",
                    a.getApplicationId(), e.getMessage(), e);
            throw new IllegalStateException("Couldn't render consultant-version PDF: " + e.getMessage(), e);
        }
    }

    /** The stored file's kind: consultant-version-p{phase} (phase null counts as 1). */
    private static String kind(WebAgreement a) {
        int phase = a.getPhase() == null ? 1 : a.getPhase();
        return "consultant-version-p" + phase;
    }
}
