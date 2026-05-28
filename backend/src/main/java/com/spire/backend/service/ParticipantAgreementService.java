package com.spire.backend.service;

import com.spire.backend.entity.User;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.exception.UnauthorizedException;
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

    @Transactional
    public Map<String, Object> sign(
            Long userId,
            String legalName,
            String signatureImage,
            String signatureMethod,
            String ipAddress,
            String userAgent
    ) {
        User user = requireGatedUser(userId);

        // Idempotent re-sign — if the user has already moved past
        // AGREEMENT_COMPLETED (e.g. they're on the check-upload step
        // already), route them forward without re-running anything.
        if (workflowService.isStatusAtLeast(user,
                WorkflowService.Status.CHECK_COPY_UPLOADED)) {
            return Map.of(
                    "success", true,
                    "alreadySigned", true,
                    "status", user.getCurrentStatus(),
                    "nextStep", "/welcome"
            );
        }
        if (workflowService.isStatusAtLeast(user,
                WorkflowService.Status.AGREEMENT_COMPLETED)) {
            return Map.of(
                    "success", true,
                    "alreadySigned", true,
                    "status", user.getCurrentStatus(),
                    "nextStep", "/check-upload"
            );
        }

        agreementService.signImmediate(userId, legalName,
                signatureImage, signatureMethod, ipAddress, userAgent);

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

    private User requireGatedUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        if (!workflowService.isStatusAtLeast(user,
                WorkflowService.Status.PROGRAM_SELECTED)) {
            throw new UnauthorizedException(
                    "Complete program selection before reaching the agreement step.");
        }
        return user;
    }
}
