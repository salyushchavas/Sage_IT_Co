package com.spire.backend.service;

import com.spire.backend.entity.CheckDocument;
import com.spire.backend.entity.User;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.exception.UnauthorizedException;
import com.spire.backend.repository.AgreementAcceptanceRepository;
import com.spire.backend.repository.CheckDocumentRepository;
import com.spire.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase 3B — restricted upload path for check soft-copies (PRD §8).
 *
 * Storage delegated to {@link DocumentStorageService} so files land
 * either in Cloudinary (with the restricted folder + signed URL
 * pattern) or on the local filesystem. The {@code check_documents}
 * row holds the metadata Finance reviews — amount, check number,
 * check date, freeform notes. Access to the underlying file is
 * gated by {@link PermissionService#canViewCheckImages} (Finance +
 * System Admin only).
 *
 * The corresponding workflow transition (CHECK_COPY_UPLOADED) is
 * driven here so a participant who uploads a check during the
 * /agreement step automatically advances the workflow without
 * waiting on the send-agreement-email click.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ParticipantCheckService {

    private static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    private static final Set<String> ACCEPTED_CONTENT_TYPES = Set.of(
            "application/pdf", "image/jpeg", "image/jpg", "image/png"
    );
    private static final Set<String> ACCEPTED_EXTENSIONS = Set.of("pdf", "jpg", "jpeg", "png");

    private final CheckDocumentRepository checkDocumentRepository;
    private final AgreementAcceptanceRepository agreementRepository;
    private final UserRepository userRepository;
    private final DocumentStorageService storageService;
    private final WorkflowService workflowService;
    private final RecordService recordService;
    private final EmailTemplateService emailTemplateService;
    private final ProfileCompletionService profileCompletionService;

    // ── Upload ──────────────────────────────────────────────────

    @Transactional
    public CheckDocument upload(
            Long userId,
            MultipartFile file,
            String checkNumber,
            BigDecimal amount,
            LocalDate checkDate,
            String notes
    ) {
        User user = requireGatedUser(userId);
        validateFile(file);

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            throw new IllegalArgumentException("Couldn't read uploaded file: " + e.getMessage());
        }
        String originalName = file.getOriginalFilename() == null
                ? "check" : file.getOriginalFilename();
        // Restricted folder — DocumentStorageService.upload already
        // namespaces by userId; for check images we additionally
        // tag the filename so a stray listing of the user's folder
        // makes the sensitivity obvious.
        DocumentStorageService.StoredFile stored = storageService.upload(
                userId, "check-" + originalName, bytes, file.getContentType());

        CheckDocument row = CheckDocument.builder()
                .userId(userId)
                .fileUrl(stored.url())
                .checkNumber(checkNumber)
                .amount(amount)
                .checkDate(checkDate)
                .notes(notes)
                .reviewStatus("PENDING")
                .maskingStatus("MASKED")
                .build();
        CheckDocument saved = checkDocumentRepository.save(row);

        // Workflow advance — first check upload bumps the user to
        // CHECK_COPY_UPLOADED, then runs the post-agreement chain.
        // Subsequent uploads are idempotent.
        advancePastCheckUpload(user, "check_uploaded");

        // File metadata only on the audit row — actual amount /
        // check number are sensitive and stay in the check_documents
        // table where Finance access controls apply.
        recordService.logAction(userId, RecordService.Category.DOCUMENT,
                "Check soft-copy uploaded",
                "checkDocumentId=" + saved.getId() + " fileName=" + originalName,
                Map.of(
                        "checkDocumentId", saved.getId(),
                        "fileName", originalName,
                        "fileSize", file.getSize()
                ));
        log.info("Check upload user={} id={} size={}", userId, saved.getId(), file.getSize());

        // Email #6 — participant-facing receipt. Best-effort; SMTP
        // outage shouldn't roll back the upload itself.
        try { emailTemplateService.sendCheckUploadConfirmationEmail(user); }
        catch (Exception e) {
            log.warn("Check upload confirmation email failed for user {}: {}",
                    userId, e.getMessage());
        }
        return saved;
    }

    // ── Mark not applicable ─────────────────────────────────────

    @Transactional
    public void markNotApplicable(Long userId) {
        User user = requireGatedUser(userId);
        // "N/A" is just a workflow advance — no row written. The
        // distinction matters for finance reconciliation: a missing
        // row + CHECK_COPY_UPLOADED status means the participant
        // confirmed no check was applicable, vs. truly absent.
        advancePastCheckUpload(user, "check_not_applicable");
        recordService.logAction(userId, RecordService.Category.DOCUMENT,
                "Check upload marked N/A", "user=" + userId, null);
    }

    /**
     * Shared post-check-upload advance. Used by both the upload
     * path and the N/A path. Idempotent — each step checks status
     * first so re-running this is safe.
     *
     *   CHECK_COPY_UPLOADED
     *   → flips agreement_records.erm_notified = true (the agreement
     *     is now "fully done" once finance has the check details)
     *   → SIGNED_AGREEMENT_SENT_TO_ERM
     *   → runs OnboardingService.completeOnboarding chain
     *     (welcome → coordinator intro → ERM → coaches → dashboard)
     */
    private void advancePastCheckUpload(User user, String triggerEvent) {
        Long userId = user.getId();
        if (!workflowService.isStatusAtLeast(user,
                WorkflowService.Status.CHECK_COPY_UPLOADED)) {
            workflowService.transition(user,
                    WorkflowService.Status.CHECK_COPY_UPLOADED,
                    triggerEvent);
        }

        agreementRepository.findByUserId(userId).ifPresent(row -> {
            if (!Boolean.TRUE.equals(row.getErmNotified())) {
                row.setErmNotified(true);
                agreementRepository.save(row);
            }
        });

        if (!workflowService.isStatusAtLeast(user,
                WorkflowService.Status.SIGNED_AGREEMENT_SENT_TO_ERM)) {
            workflowService.transition(user,
                    WorkflowService.Status.SIGNED_AGREEMENT_SENT_TO_ERM,
                    "erm_routed");
        }

        // Phase 1C: the welcome → coordinator → ERM → coaches chain
        // no longer fires from here. It moved to
        // OnboardingService.triggerProfileCompletionFlow, which
        // ProfileCompletionService kicks when the user reaches 100%
        // (all six per-step booleans true). The mark-step call below
        // is the gate — if the user finished every other step first,
        // this flip will trigger the chain; otherwise the chain stays
        // dormant until the remaining boxes are checked.
        User fresh = userRepository.findById(userId).orElse(user);
        profileCompletionService.markStepComplete(fresh, "CHECK_UPLOAD");
    }

    // ── List own checks (no file URL — file fetch is separate) ──

    @Transactional(readOnly = true)
    public List<CheckDocument> listForUser(Long userId) {
        return checkDocumentRepository.findByUserIdOrderByUploadedAtDesc(userId);
    }

    // ── Gate ────────────────────────────────────────────────────

    private User requireGatedUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        if (!workflowService.isStatusAtLeast(user,
                WorkflowService.Status.PROGRAM_SELECTED)) {
            throw new UnauthorizedException(
                    "Complete program selection before uploading check soft-copies.");
        }
        return user;
    }

    private static void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is required");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("File too large (max 10 MB)");
        }
        String ct = file.getContentType();
        if (ct != null && !ACCEPTED_CONTENT_TYPES.contains(ct.toLowerCase())) {
            String name = file.getOriginalFilename();
            String ext = name != null && name.contains(".")
                    ? name.substring(name.lastIndexOf('.') + 1).toLowerCase()
                    : "";
            if (!ACCEPTED_EXTENSIONS.contains(ext)) {
                throw new IllegalArgumentException("Only PDF, JPG, or PNG files are allowed.");
            }
        }
    }
}
