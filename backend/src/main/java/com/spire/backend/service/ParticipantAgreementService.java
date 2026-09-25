package com.spire.backend.service;

import com.spire.backend.entity.AgreementAcceptance;
import com.spire.backend.entity.User;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.repository.AgreementAcceptanceRepository;
import com.spire.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * One-click on-site agreement signing for the participant lifecycle.
 *
 *   sign() → AGREEMENT_SENT → AGREEMENT_COMPLETED
 *
 * Stops at AGREEMENT_COMPLETED — the participant then proceeds to
 * /check-upload to either upload check soft-copies or mark them
 * not applicable. {@link ParticipantCheckService} owns the next
 * leg: CHECK_COPY_UPLOADED → SIGNED_AGREEMENT_SENT_TO_ERM →
 * OnboardingService.completeOnboarding chain → /welcome.
 *
 * Wraps {@link AgreementService#signImmediate} so the legacy
 * /agreement-legacy email-reply path can keep using AgreementService
 * directly without touching the participant workflow.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ParticipantAgreementService {

    private final AgreementService agreementService;
    private final AgreementAcceptanceRepository agreementRepository;
    private final UserRepository userRepository;
    private final WorkflowService workflowService;
    private final RecordService recordService;
    private final ProfileCompletionService profileCompletionService;
    private final TermsContentService termsContentService;
    private final com.spire.backend.repository.ProgramSelectionRepository programSelectionRepository;

    @Transactional
    public Map<String, Object> sign(
            Long userId,
            String legalName,
            String signatureImage,
            String signatureMethod,
            String ipAddress,
            String userAgent,
            String agreementVersion,
            String textFingerprint
    ) {
        User user = requireGatedUser(userId);

        // Idempotent re-sign — only when the agreement really was signed:
        // the step flag, or (for an older row whose flag fell behind) a
        // stored VERIFIED signature. The status alone used to be jumped
        // ahead at sign-up, which marked AGREEMENT done with nothing signed.
        boolean signedRecord = agreementRepository.findByUserId(userId)
                .map(row -> AgreementService.STATUS_VERIFIED.equals(row.getStatus()))
                .orElse(false);
        if (Boolean.TRUE.equals(user.getAgreementComplete()) || signedRecord) {
            if (!Boolean.TRUE.equals(user.getAgreementComplete())) {
                profileCompletionService.markStepComplete(user, "AGREEMENT");
            }
            return Map.of(
                    "success", true,
                    "alreadySigned", true,
                    "status", user.getCurrentStatus(),
                    "nextStep", Boolean.TRUE.equals(user.getCheckUploadComplete())
                            ? "/dashboard?tab=complete-profile" : "/check-upload"
            );
        }

        // Only the current text can be signed: the version and fingerprint
        // the page showed must be the ones the server holds (checklist 2.2,
        // as for the acknowledgment in 1.2).
        String current = AgreementService.CURRENT_VERSION;
        String currentFingerprint = termsContentService.fingerprint(current);
        if (!current.equals(agreementVersion) || !currentFingerprint.equals(textFingerprint)) {
            throw new IllegalStateException(
                    "The agreement text has been updated. Please reload the page and review the current version.");
        }
        agreementService.signImmediate(userId, legalName,
                signatureImage, signatureMethod, ipAddress, userAgent,
                new AgreementService.SigningDetails(currentFingerprint, user.getParticipantId(),
                        programSummary(userId)));

        user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        if (!workflowService.isStatusAtLeast(user,
                WorkflowService.Status.AGREEMENT_SENT)) {
            workflowService.transition(user,
                    WorkflowService.Status.AGREEMENT_SENT,
                    "agreement_sent");
        }
        if (!workflowService.isStatusAtLeast(user,
                WorkflowService.Status.AGREEMENT_COMPLETED)) {
            workflowService.transition(user,
                    WorkflowService.Status.AGREEMENT_COMPLETED,
                    "agreement_completed");
        }
        profileCompletionService.markStepComplete(user, "AGREEMENT");

        // The agreement row records on-site signing; the
        // ermNotified=true flag flips after check upload completes
        // (or is marked N/A), not here.
        agreementRepository.findByUserId(userId).ifPresent(row -> {
            agreementRepository.save(row);
        });

        recordService.logAction(userId, RecordService.Category.ACCOUNT,
                "Agreement signed on-site",
                "Next step: check soft-copy upload",
                Map.of("workflowStatus", user.getCurrentStatus()));

        log.info("Agreement signed on-site for user {} → currentStatus={}",
                userId, user.getCurrentStatus());
        return Map.of(
                "success", true,
                "status", user.getCurrentStatus(),
                "nextStep", "/check-upload"
        );
    }

    /**
     * Checklist 2.5 (roadmap: Operations reviews "declined" agreements):
     * the participant declines to sign, with a reason. Operations sees it
     * in the agreement queue; the participant can still sign later.
     */
    @Transactional
    public AgreementAcceptance decline(Long userId, String reason) {
        User user = requireGatedUser(userId);
        String why = reason == null ? "" : reason.trim();
        if (why.length() < 5) {
            throw new IllegalArgumentException("Please tell us why you're declining, so Operations can help.");
        }
        if (why.length() > 1000) {
            throw new IllegalArgumentException("Please keep the reason under 1,000 characters.");
        }
        AgreementAcceptance row = agreementRepository.findByUserId(userId).orElseGet(() -> AgreementAcceptance.builder()
                .user(user)
                .legalName(user.getFullName() == null ? "—" : user.getFullName())
                .agreementVersion(AgreementService.CURRENT_VERSION)
                .codeVerified(false)
                .build());
        if (Boolean.TRUE.equals(user.getAgreementComplete())
                || AgreementService.STATUS_VERIFIED.equals(row.getStatus())) {
            throw new IllegalStateException("The agreement is already signed.");
        }
        row.setStatus(AgreementService.STATUS_DECLINED);
        row.setDeclinedAt(java.time.LocalDateTime.now());
        row.setDeclineReason(why);
        AgreementAcceptance saved = agreementRepository.save(row);
        recordService.record(userId, "AGREEMENT_DECLINED", RecordService.Category.ACCOUNT,
                "Agreement declined", "The participant declined to sign: " + why,
                Map.of("reason", why));
        return saved;
    }

    /** "Program · Phase · Skillset · Target role" as chosen, for the record and the PDF. */
    String programSummary(Long userId) {
        return programSelectionRepository.findFirstByUserIdOrderBySelectionDateDesc(userId)
                .map(p -> java.util.stream.Stream.of(p.getProgram(), p.getPhase(), p.getSkillset(), p.getTargetJobTitle())
                        .filter(v -> v != null && !v.isBlank())
                        .collect(java.util.stream.Collectors.joining(" · ")))
                .filter(s -> !s.isBlank())
                .orElse(null);
    }

    private User requireGatedUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        // Every step before signing must really be done (roadmap §4.1: no
        // signing until acknowledgment, required documents and program
        // selection are complete).
        if (!Boolean.TRUE.equals(user.getAcknowledgmentComplete())
                || !Boolean.TRUE.equals(user.getDocumentsComplete())
                || !Boolean.TRUE.equals(user.getProgramSelectionComplete())) {
            throw new IllegalStateException(
                    "Complete the acknowledgment, documents and program selection before signing.");
        }
        return user;
    }
}
