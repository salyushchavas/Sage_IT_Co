package com.spire.backend.service;

import com.spire.backend.dto.ProfileCompletionDto;
import com.spire.backend.entity.User;
import com.spire.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Phase 1C — progressive profile completion.
 *
 * After the 2-step signup (enroll + verify) the participant lands
 * on the dashboard immediately. The remaining six lifecycle steps
 * — basic info, acknowledgment, documents, program selection,
 * agreement, check upload — become per-step booleans on the user
 * record. Each existing endpoint calls {@link #markStepComplete}
 * when its work is durable; this service computes the rollup
 * percentage, fires the welcome / ERM / coaches chain at 100%, and
 * is the canonical answer to "can this user enroll in a course?".
 *
 * Browsing is always allowed; only the purchase / enroll path is
 * gated. The frontend reads {@link #canEnrollInCourses} either via
 * the dedicated /completion endpoint or via the 403 response body
 * that gated controllers attach.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProfileCompletionService {

    /** Step keys in display order. The frontend matches on these. */
    public static final List<String> STEPS = Arrays.asList(
            "BASIC_INFO", "ACKNOWLEDGMENT", "DOCUMENTS",
            "PROGRAM_SELECTION", "AGREEMENT", "CHECK_UPLOAD"
    );

    private final UserRepository userRepository;
    private final RecordService recordService;
    // Lazy to avoid a circular-injection chain: OnboardingService →
    // CoachAssignmentService → … may eventually depend on this
    // service. The chain is only invoked on the rare full-profile-
    // completion event, so the late-binding cost is irrelevant.
    private final @Lazy OnboardingService onboardingService;

    @Transactional(readOnly = true)
    public ProfileCompletionDto getStatus(User user) {
        int total = STEPS.size();
        int done = countCompleted(user);
        int pct = total == 0 ? 0 : (done * 100) / total;

        List<ProfileCompletionDto.StepInfo> steps = List.of(
                ProfileCompletionDto.StepInfo.of("BASIC_INFO", "About You",
                        "Location, availability, technology preferences",
                        "1 min", user.getBasicInfoComplete()),
                ProfileCompletionDto.StepInfo.of("ACKNOWLEDGMENT", "Acknowledgment",
                        "Accept program acknowledgment",
                        "3 min", user.getAcknowledgmentComplete()),
                ProfileCompletionDto.StepInfo.of("DOCUMENTS", "Upload Documents",
                        "Government ID, work authorization, resume",
                        "5 min", user.getDocumentsComplete()),
                ProfileCompletionDto.StepInfo.of("PROGRAM_SELECTION", "Choose Programs",
                        "Select one or more programs",
                        "3 min", user.getProgramSelectionComplete()),
                ProfileCompletionDto.StepInfo.of("AGREEMENT", "Sign Agreement",
                        "Review and digitally sign",
                        "4 min", user.getAgreementComplete()),
                ProfileCompletionDto.StepInfo.of("CHECK_UPLOAD", "Check Soft Copies",
                        "Upload checks or mark not applicable",
                        "2 min", user.getCheckUploadComplete())
        );

        return ProfileCompletionDto.builder()
                .completionPercentage(pct)
                .completedSteps(done)
                .totalSteps(total)
                .isComplete(isFullyComplete(user))
                .nextStep(nextStepKey(user))
                .steps(steps)
                .build();
    }

    public int countCompleted(User user) {
        int n = 0;
        if (Boolean.TRUE.equals(user.getBasicInfoComplete())) n++;
        if (Boolean.TRUE.equals(user.getAcknowledgmentComplete())) n++;
        if (Boolean.TRUE.equals(user.getDocumentsComplete())) n++;
        if (Boolean.TRUE.equals(user.getProgramSelectionComplete())) n++;
        if (Boolean.TRUE.equals(user.getAgreementComplete())) n++;
        if (Boolean.TRUE.equals(user.getCheckUploadComplete())) n++;
        return n;
    }

    public boolean isFullyComplete(User user) {
        return Boolean.TRUE.equals(user.getBasicInfoComplete())
                && Boolean.TRUE.equals(user.getAcknowledgmentComplete())
                && Boolean.TRUE.equals(user.getDocumentsComplete())
                && Boolean.TRUE.equals(user.getProgramSelectionComplete())
                && Boolean.TRUE.equals(user.getAgreementComplete())
                && Boolean.TRUE.equals(user.getCheckUploadComplete());
    }

    /** First step still incomplete, or "COMPLETE" when all done. */
    public String nextStepKey(User user) {
        if (!Boolean.TRUE.equals(user.getBasicInfoComplete())) return "BASIC_INFO";
        if (!Boolean.TRUE.equals(user.getAcknowledgmentComplete())) return "ACKNOWLEDGMENT";
        if (!Boolean.TRUE.equals(user.getDocumentsComplete())) return "DOCUMENTS";
        if (!Boolean.TRUE.equals(user.getProgramSelectionComplete())) return "PROGRAM_SELECTION";
        if (!Boolean.TRUE.equals(user.getAgreementComplete())) return "AGREEMENT";
        if (!Boolean.TRUE.equals(user.getCheckUploadComplete())) return "CHECK_UPLOAD";
        return "COMPLETE";
    }

    /**
     * Flips the per-step boolean, recomputes percentage, and fires
     * the welcome chain when the user reaches 100% for the first
     * time. Idempotent — re-running the same step is a no-op.
     */
    @Transactional
    public void markStepComplete(User user, String step) {
        switch (step) {
            case "BASIC_INFO" -> user.setBasicInfoComplete(true);
            case "ACKNOWLEDGMENT" -> user.setAcknowledgmentComplete(true);
            case "DOCUMENTS" -> user.setDocumentsComplete(true);
            case "PROGRAM_SELECTION" -> user.setProgramSelectionComplete(true);
            case "AGREEMENT" -> user.setAgreementComplete(true);
            case "CHECK_UPLOAD" -> user.setCheckUploadComplete(true);
            default -> {
                // Unknown key — log + bail so a typo in a controller
                // doesn't silently no-op forever.
                log.warn("Unknown profile step '{}' for user {}", step, user.getId());
                return;
            }
        }

        int newPct = (countCompleted(user) * 100) / STEPS.size();
        user.setProfileCompletionPct(newPct);

        boolean justCompleted = !Boolean.TRUE.equals(user.getProfileComplete())
                && isFullyComplete(user);
        if (justCompleted) {
            user.setProfileComplete(true);
            user.setProfileCompletedAt(LocalDateTime.now());
        }

        userRepository.save(user);

        try {
            recordService.record(user.getId(), "PROFILE_STEP_COMPLETED",
                    RecordService.Category.ACCOUNT,
                    "Profile step completed",
                    "Step " + step + " marked complete (" + newPct + "% overall)",
                    Map.of("step", step, "percentage", String.valueOf(newPct)));
        } catch (Exception ignored) {
            // Audit failure is best-effort.
        }

        if (justCompleted) {
            log.info("User {} reached 100% profile completion — firing welcome chain",
                    user.getId());
            try {
                onboardingService.triggerProfileCompletionFlow(user);
            } catch (Exception e) {
                log.warn("Profile-completion onboarding chain failed for user {}: {}",
                        user.getId(), e.getMessage());
            }
        }
    }

    /** True when the user has crossed the gate that unlocks purchases. */
    public boolean canEnrollInCourses(User user) {
        return Boolean.TRUE.equals(user.getProfileComplete());
    }
}
