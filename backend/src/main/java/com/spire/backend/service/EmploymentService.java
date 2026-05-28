package com.spire.backend.service;

import com.spire.backend.entity.EmploymentAcceptance;
import com.spire.backend.entity.ErmAssignment;
import com.spire.backend.entity.PhaseCompletion;
import com.spire.backend.entity.User;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.exception.UnauthorizedException;
import com.spire.backend.repository.EmploymentAcceptanceRepository;
import com.spire.backend.repository.ErmAssignmentRepository;
import com.spire.backend.repository.PhaseCompletionRepository;
import com.spire.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 6 — employment acceptance + Phase 1 completion.
 *
 * Two-stage gating:
 *   1. Participant submits employment details (employer, job title,
 *      start date, optional offer doc). Workflow → EMPLOYMENT_ACCEPTED.
 *   2. ERM verifies. {@code ermVerified = true} unlocks the Phase 1
 *      acknowledgment on the participant dashboard.
 *   3. Participant accepts the PH1-v1.0 acknowledgment. Workflow →
 *      PHASE_1_COMPLETED. Email #13 dispatched to participant + ERM +
 *      finance. Payment system (Phase 7) is gated on this status.
 *
 * Gate 6 (Phase 1 prerequisite): {@code employment.ermVerified must
 * be true} before {@link #acceptPhase1Completion} succeeds.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmploymentService {

    public static final String PHASE_1 = "PHASE_1";
    public static final String PHASE_1_ACK_VERSION = "PH1-v1.0";

    private final EmploymentAcceptanceRepository employmentRepository;
    private final PhaseCompletionRepository phaseRepository;
    private final ErmAssignmentRepository ermAssignmentRepository;
    private final UserRepository userRepository;
    private final WorkflowService workflowService;
    private final RecordService recordService;
    private final EmailTemplateService emailTemplateService;

    // ─── Employment acceptance (participant) ────────────────────────

    @Transactional
    public EmploymentAcceptance acceptEmployment(Long userId, EmploymentAcceptance in) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        // Gate: must be at or past DASHBOARD_ENABLED. Below that the
        // participant is mid-onboarding and hasn't earned the right
        // to log employment yet.
        if (!workflowService.isStatusAtLeast(user,
                WorkflowService.Status.DASHBOARD_ENABLED)) {
            throw new UnauthorizedException(
                    "Employment can be submitted once your dashboard is active.");
        }

        if (in.getEmployerClient() == null || in.getEmployerClient().isBlank()
                || in.getJobTitle() == null || in.getJobTitle().isBlank()
                || in.getStartDate() == null) {
            throw new IllegalArgumentException("Employer, job title, and start date are required.");
        }

        EmploymentAcceptance row = EmploymentAcceptance.builder()
                .userId(userId)
                .employerClient(in.getEmployerClient().trim())
                .jobTitle(in.getJobTitle().trim())
                .startDate(in.getStartDate())
                .location(safe(in.getLocation()))
                .employmentType(safe(in.getEmploymentType()))
                .offerDocumentUrl(safe(in.getOfferDocumentUrl()))
                .notes(safe(in.getNotes()))
                .acceptanceDate(LocalDateTime.now())
                .ermVerified(false)
                .build();
        EmploymentAcceptance saved = employmentRepository.save(row);

        if (!workflowService.isStatusAtLeast(user,
                WorkflowService.Status.EMPLOYMENT_ACCEPTED)) {
            workflowService.transition(user,
                    WorkflowService.Status.EMPLOYMENT_ACCEPTED,
                    "employment_submitted");
        }

        recordService.logAction(userId, RecordService.Category.ACCOUNT,
                "Employment acceptance submitted",
                row.getEmployerClient() + " — " + row.getJobTitle(),
                Map.of(
                        "employmentId", saved.getId(),
                        "employer", saved.getEmployerClient(),
                        "jobTitle", saved.getJobTitle(),
                        "startDate", saved.getStartDate().toString()
                ));
        log.info("Employment accepted by user {} (row {})", userId, saved.getId());
        return saved;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> employmentStatus(Long userId) {
        Optional<EmploymentAcceptance> latest = employmentRepository
                .findByUserIdOrderByAcceptanceDateDesc(userId).stream().findFirst();
        Optional<PhaseCompletion> phase1 = phaseRepository.findByUserIdAndPhase(userId, PHASE_1);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("submitted", latest.isPresent());
        out.put("ermVerified", latest.map(EmploymentAcceptance::getErmVerified).orElse(false));
        latest.ifPresent(e -> {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("id", e.getId());
            details.put("employerClient", e.getEmployerClient());
            details.put("jobTitle", e.getJobTitle());
            details.put("startDate", e.getStartDate());
            details.put("location", e.getLocation());
            details.put("employmentType", e.getEmploymentType());
            details.put("offerDocumentUrl", e.getOfferDocumentUrl());
            details.put("notes", e.getNotes());
            details.put("acceptanceDate", e.getAcceptanceDate());
            details.put("ermVerifiedDate", e.getErmVerifiedDate());
            details.put("ermNotes", e.getErmNotes());
            out.put("details", details);
        });
        ermAssignmentRepository.findFirstByUserIdOrderByAssignedDateDesc(userId)
                .map(ErmAssignment::getErmUserId)
                .flatMap(userRepository::findById)
                .ifPresent(erm -> {
                    out.put("ermName", erm.getFullName());
                    out.put("ermEmail", erm.getEmail());
                });
        phase1.ifPresent(p -> {
            Map<String, Object> ph = new LinkedHashMap<>();
            ph.put("acceptedAt", p.getAcceptedAt());
            ph.put("acknowledgmentVersion", p.getAcknowledgmentVersion());
            ph.put("ermApproved", p.getErmApproved());
            ph.put("ermApprovedDate", p.getErmApprovedDate());
            out.put("phase1", ph);
        });
        return out;
    }

    // ─── Phase 1 completion (participant) ──────────────────────────

    @Transactional
    public PhaseCompletion acceptPhase1Completion(Long userId, String acknowledgmentVersion) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        // Gate 6 — must have an ERM-verified employment row.
        EmploymentAcceptance latest = employmentRepository
                .findFirstByUserIdAndErmVerifiedTrueOrderByErmVerifiedDateDesc(userId)
                .orElseThrow(() -> new UnauthorizedException(
                        "Employment must be verified by your ERM before you can complete Phase 1."));

        String version = (acknowledgmentVersion == null || acknowledgmentVersion.isBlank())
                ? PHASE_1_ACK_VERSION : acknowledgmentVersion;

        // Idempotent — re-accepting returns the existing row.
        Optional<PhaseCompletion> existing = phaseRepository.findByUserIdAndPhase(userId, PHASE_1);
        PhaseCompletion row = existing.orElseGet(() -> PhaseCompletion.builder()
                .userId(userId)
                .phase(PHASE_1)
                .ermApproved(false)
                .build());
        if (row.getAcceptedAt() == null) {
            row.setAcceptedAt(LocalDateTime.now());
        }
        row.setAcknowledgmentVersion(version);
        PhaseCompletion saved = phaseRepository.save(row);

        if (!workflowService.isStatusAtLeast(user,
                WorkflowService.Status.PHASE_1_COMPLETED)) {
            workflowService.transition(user,
                    WorkflowService.Status.PHASE_1_COMPLETED,
                    "phase_1_acknowledgment_accepted");
        }

        recordService.logAction(userId, RecordService.Category.ACCOUNT,
                "Phase 1 completion acknowledged",
                "Version " + version,
                Map.of(
                        "phaseCompletionId", saved.getId(),
                        "acknowledgmentVersion", version,
                        "employmentId", latest.getId()
                ));

        // Email #13 — best-effort.
        try {
            User erm = ermAssignmentRepository.findFirstByUserIdOrderByAssignedDateDesc(userId)
                    .map(ErmAssignment::getErmUserId)
                    .flatMap(userRepository::findById)
                    .orElse(null);
            emailTemplateService.sendPhase1CompletionEmails(
                    user, erm, latest, saved.getAcceptedAt());
        } catch (Exception e) {
            log.warn("Phase 1 completion emails failed for user {}: {}", userId, e.getMessage());
        }

        log.info("Phase 1 completion accepted by user {} (row {})", userId, saved.getId());
        return saved;
    }

    // ─── ERM verification ──────────────────────────────────────────

    /** Returns the participants assigned to this ERM whose latest
     *  employment row is awaiting verification. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> ermPendingVerifications(Long ermUserId) {
        return ermAssignmentRepository.findByErmUserId(ermUserId).stream()
                .map(a -> {
                    User p = userRepository.findById(a.getUserId()).orElse(null);
                    if (p == null) return null;
                    EmploymentAcceptance latest = employmentRepository
                            .findByUserIdOrderByAcceptanceDateDesc(a.getUserId()).stream()
                            .findFirst().orElse(null);
                    if (latest == null) return null;
                    if (Boolean.TRUE.equals(latest.getErmVerified())) return null;
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("userId", p.getId());
                    row.put("participantId", p.getParticipantId());
                    row.put("fullName", p.getFullName());
                    row.put("employmentId", latest.getId());
                    row.put("employerClient", latest.getEmployerClient());
                    row.put("jobTitle", latest.getJobTitle());
                    row.put("startDate", latest.getStartDate());
                    row.put("location", latest.getLocation());
                    row.put("employmentType", latest.getEmploymentType());
                    row.put("offerDocumentUrl", latest.getOfferDocumentUrl());
                    row.put("notes", latest.getNotes());
                    row.put("acceptanceDate", latest.getAcceptanceDate());
                    return row;
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    @Transactional
    public EmploymentAcceptance verifyEmployment(Long ermUserId, Long participantId, String notes) {
        // ERM must actually be assigned to this participant.
        boolean assigned = ermAssignmentRepository.findByErmUserId(ermUserId).stream()
                .anyMatch(a -> participantId.equals(a.getUserId()));
        if (!assigned) {
            throw new UnauthorizedException("Not the assigned ERM for this participant.");
        }

        EmploymentAcceptance row = employmentRepository
                .findByUserIdOrderByAcceptanceDateDesc(participantId).stream()
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(
                        "EmploymentAcceptance", "userId", participantId));
        row.setErmVerified(true);
        row.setErmVerifiedDate(LocalDateTime.now());
        if (notes != null && !notes.isBlank()) {
            row.setErmNotes(notes.trim());
        }
        EmploymentAcceptance saved = employmentRepository.save(row);

        recordService.logAction(participantId, RecordService.Category.ACCOUNT,
                "Employment verified by ERM",
                notes,
                Map.of("employmentId", saved.getId(), "ermUserId", ermUserId));
        return saved;
    }

    /** ERM-side roster of participants who self-accepted Phase 1 but
     *  haven't been approved yet. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> ermPendingPhaseApprovals(Long ermUserId) {
        return ermAssignmentRepository.findByErmUserId(ermUserId).stream()
                .map(a -> {
                    User p = userRepository.findById(a.getUserId()).orElse(null);
                    if (p == null) return null;
                    PhaseCompletion ph = phaseRepository
                            .findByUserIdAndPhase(a.getUserId(), PHASE_1).orElse(null);
                    if (ph == null) return null;
                    if (Boolean.TRUE.equals(ph.getErmApproved())) return null;
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("userId", p.getId());
                    row.put("participantId", p.getParticipantId());
                    row.put("fullName", p.getFullName());
                    row.put("phaseCompletionId", ph.getId());
                    row.put("acceptedAt", ph.getAcceptedAt());
                    row.put("acknowledgmentVersion", ph.getAcknowledgmentVersion());
                    return row;
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    @Transactional
    public PhaseCompletion approvePhase1(Long ermUserId, Long participantId, String notes) {
        boolean assigned = ermAssignmentRepository.findByErmUserId(ermUserId).stream()
                .anyMatch(a -> participantId.equals(a.getUserId()));
        if (!assigned) {
            throw new UnauthorizedException("Not the assigned ERM for this participant.");
        }

        PhaseCompletion ph = phaseRepository.findByUserIdAndPhase(participantId, PHASE_1)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "PhaseCompletion", "userId", participantId));
        ph.setErmApproved(true);
        ph.setErmApprovedDate(LocalDateTime.now());
        if (notes != null && !notes.isBlank()) {
            ph.setErmNotes(notes.trim());
        }
        PhaseCompletion saved = phaseRepository.save(ph);
        recordService.logAction(participantId, RecordService.Category.ACCOUNT,
                "Phase 1 completion approved by ERM",
                notes,
                Map.of("phaseCompletionId", saved.getId(), "ermUserId", ermUserId));
        return saved;
    }

    private static String safe(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** Helper for the participant dashboard's roadmap step lookup —
     *  exposes the latest verified employment row for the deeplink. */
    @Transactional(readOnly = true)
    public Optional<EmploymentAcceptance> latestVerifiedEmployment(Long userId) {
        return employmentRepository
                .findFirstByUserIdAndErmVerifiedTrueOrderByErmVerifiedDateDesc(userId);
    }

    /** Public helper for downstream Phase 7 wiring (payments etc.). */
    public static boolean isPhase1Complete(LocalDate today, PhaseCompletion phase1) {
        if (phase1 == null || phase1.getAcceptedAt() == null) return false;
        return !phase1.getAcceptedAt().toLocalDate().isAfter(today);
    }
}
