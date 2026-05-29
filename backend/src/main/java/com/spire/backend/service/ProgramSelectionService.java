package com.spire.backend.service;

import com.spire.backend.dto.ProgramSelectionRequest;
import com.spire.backend.entity.ProgramSelection;
import com.spire.backend.entity.User;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.exception.UnauthorizedException;
import com.spire.backend.repository.ProgramSelectionRepository;
import com.spire.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 3A — Step 6 of the participant lifecycle: the participant
 * reviews the versioned service summary and selects their program,
 * phase, skill track, target job title, and availability.
 *
 * Responsibilities:
 *   1. Upsert a {@link ProgramSelection} row from either a draft
 *      save (partial fields, no validation) or a final submit
 *      (full validation + workflow transition + confirmation email).
 *   2. Gate both paths on workflow state — must be at or past
 *      DOCUMENTS_SUBMITTED before any program-selection write is
 *      accepted (PRD §6).
 *   3. Idempotent on final submit — a re-call once the user is
 *      already at PROGRAM_SELECTED or beyond returns the existing
 *      row and skips the transition / email.
 *
 * The selection is a one-row-per-user record. The row is created on
 * first draft save and mutated in place; we never accumulate
 * multiple rows for the same participant.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProgramSelectionService {

    public static final String CURRENT_SUMMARY_VERSION = "SVC-v1.0";

    private final ProgramSelectionRepository programSelectionRepository;
    private final UserRepository userRepository;
    private final WorkflowService workflowService;
    private final RecordService recordService;
    private final EmailTemplateService emailTemplateService;
    private final ProfileCompletionService profileCompletionService;

    // ── Reads ───────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Optional<ProgramSelection> getCurrent(Long userId) {
        return programSelectionRepository.findFirstByUserIdOrderBySelectionDateDesc(userId);
    }

    // ── Draft (partial, no validation, no transition) ────────────

    @Transactional
    public ProgramSelection saveDraft(Long userId, ProgramSelectionRequest req) {
        User user = requireGatedUser(userId);
        // Past-the-finish-line guard: don't let a stray draft call
        // mutate a finalised row once the workflow has moved on.
        if (workflowService.isStatusAtLeast(user, WorkflowService.Status.AGREEMENT_SENT)) {
            throw new UnauthorizedException(
                    "Program selection has already been locked for agreement signing.");
        }
        ProgramSelection row = programSelectionRepository
                .findFirstByUserIdOrderBySelectionDateDesc(userId)
                .orElseGet(() -> ProgramSelection.builder().userId(userId).build());
        copyFieldsIntoRow(row, req);
        ProgramSelection saved = programSelectionRepository.save(row);
        recordService.logAction(userId, RecordService.Category.ACCOUNT,
                "Program selection draft saved",
                "Partial program-selection state saved for user " + userId,
                null);
        return saved;
    }

    // ── Final submit ────────────────────────────────────────────

    @Transactional
    public ProgramSelection submit(Long userId, ProgramSelectionRequest req) {
        User user = requireGatedUser(userId);

        // Idempotent return if already past this step.
        if (workflowService.isStatusAtLeast(user, WorkflowService.Status.PROGRAM_SELECTED)) {
            ProgramSelection existing = programSelectionRepository
                    .findFirstByUserIdOrderBySelectionDateDesc(userId)
                    .orElseGet(() -> programSelectionRepository.save(
                            buildRow(userId, req)));
            // Self-heal the per-step flag if it fell behind status.
            profileCompletionService.markStepComplete(user, "PROGRAM_SELECTION");
            return existing;
        }

        validate(req);

        ProgramSelection row = programSelectionRepository
                .findFirstByUserIdOrderBySelectionDateDesc(userId)
                .orElseGet(() -> ProgramSelection.builder().userId(userId).build());
        copyFieldsIntoRow(row, req);
        // Pin the version we accepted, defaulting to the current
        // server-side constant if the client didn't echo one back.
        if (row.getServiceSummaryVersion() == null || row.getServiceSummaryVersion().isBlank()) {
            row.setServiceSummaryVersion(CURRENT_SUMMARY_VERSION);
        }
        ProgramSelection saved = programSelectionRepository.save(row);

        // Mirror the chosen skillset + availability onto the user
        // record so other onboarding pages (and the welcome email)
        // can read them without re-joining program_selections.
        if (req.getSkillset() != null && !req.getSkillset().isBlank()) {
            user.setSelectedTechnology(req.getSkillset());
        }
        if (req.getAvailability() != null && !req.getAvailability().isBlank()) {
            user.setAvailability(req.getAvailability());
        }
        userRepository.save(user);

        workflowService.transition(user,
                WorkflowService.Status.PROGRAM_SELECTED,
                "program_selected");
        profileCompletionService.markStepComplete(user, "PROGRAM_SELECTION");

        Map<String, Object> details = new HashMap<>();
        details.put("program", saved.getProgram());
        details.put("phase", saved.getPhase());
        details.put("skillset", saved.getSkillset());
        details.put("targetJobTitle", saved.getTargetJobTitle());
        details.put("availability", saved.getAvailability());
        details.put("serviceSummaryVersion", saved.getServiceSummaryVersion());
        recordService.record(userId, "PROGRAM_SELECTED",
                RecordService.Category.ACCOUNT,
                "Program selection submitted",
                "Participant selected " + saved.getProgram(),
                details);

        // Confirmation email — best-effort so a mailer outage
        // doesn't roll back the workflow advance.
        try {
            emailTemplateService.sendProgramSelectionConfirmationEmail(user, saved);
        } catch (Exception e) {
            log.warn("Program selection confirmation email failed for user {}: {}",
                    userId, e.getMessage());
        }

        log.info("Program selection {} submitted by user {} (program={}, phase={})",
                saved.getId(), userId, saved.getProgram(), saved.getPhase());
        return saved;
    }

    // ── Internals ────────────────────────────────────────────────

    private User requireGatedUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        if (!workflowService.isStatusAtLeast(user,
                WorkflowService.Status.DOCUMENTS_SUBMITTED)) {
            throw new UnauthorizedException(
                    "Upload your required documents before selecting a program.");
        }
        return user;
    }

    private static void validate(ProgramSelectionRequest req) {
        if (req == null) throw new IllegalArgumentException("Request body required");
        requireField(req.getProgram(), "Program");
        requireField(req.getPhase(), "Phase");
        requireField(req.getSkillset(), "Technology / skillset");
        requireField(req.getTargetJobTitle(), "Target job title");
        requireField(req.getAvailability(), "Availability");
        if (req.getServiceSummaryVersion() == null || req.getServiceSummaryVersion().isBlank()) {
            throw new IllegalArgumentException(
                    "Service summary review is required (serviceSummaryVersion missing).");
        }
        if (req.getNotes() != null && req.getNotes().length() > 500) {
            throw new IllegalArgumentException("Notes must be 500 characters or fewer.");
        }
    }

    private static void requireField(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
    }

    private static void copyFieldsIntoRow(ProgramSelection row, ProgramSelectionRequest req) {
        if (req == null) return;
        if (req.getProgram() != null) row.setProgram(req.getProgram());
        if (req.getPhase() != null) row.setPhase(req.getPhase());
        if (req.getSkillset() != null) row.setSkillset(req.getSkillset());
        if (req.getTargetJobTitle() != null) row.setTargetJobTitle(req.getTargetJobTitle());
        if (req.getCoachingPreference() != null) row.setCoachingPreference(req.getCoachingPreference());
        if (req.getAvailability() != null) row.setAvailability(req.getAvailability());
        if (req.getServicePackage() != null) row.setServicePackage(req.getServicePackage());
        if (req.getServiceSummaryVersion() != null) row.setServiceSummaryVersion(req.getServiceSummaryVersion());
        if (req.getNotes() != null) row.setNotes(req.getNotes());
    }

    private static ProgramSelection buildRow(Long userId, ProgramSelectionRequest req) {
        ProgramSelection row = ProgramSelection.builder().userId(userId).build();
        copyFieldsIntoRow(row, req);
        if (row.getServiceSummaryVersion() == null) row.setServiceSummaryVersion(CURRENT_SUMMARY_VERSION);
        return row;
    }
}
