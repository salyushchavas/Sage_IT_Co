package com.spire.backend.service;

import com.spire.backend.entity.EmploymentAcceptance;
import com.spire.backend.entity.ErmAssignment;
import com.spire.backend.entity.PhaseCompletion;
import com.spire.backend.entity.User;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.repository.EmploymentAcceptanceRepository;
import com.spire.backend.repository.ErmAssignmentRepository;
import com.spire.backend.repository.PhaseCompletionRepository;
import com.spire.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Phase 6 — employment acceptance + Phase 1 completion.
 *
 * Two-stage gating:
 *   1. Participant submits employment details (employer, job title,
 *      start date, optional offer doc). Workflow → EMPLOYMENT_ACCEPTED.
 *   2. ERM verifies, or sends the details back for correction with a
 *      reason (checklist 4.5); the participant then submits corrected
 *      details as a new record. {@code ermVerified = true} on the latest
 *      record unlocks the Phase 1 acknowledgment on the participant
 *      dashboard.
 *   3. Participant accepts the PH1-v1.0 acknowledgment. Workflow →
 *      PHASE_1_COMPLETED. Email #13 dispatched to participant + ERM +
 *      finance. Payment system (Phase 7) is gated on this status.
 *   4. ERM approves Phase 1; Phase 2 (post-offer support) begins on the
 *      verified employment start date (checklist 4.5).
 *
 * Gate 6 (Phase 1 prerequisite): the latest employment record must be
 * ERM-verified before {@link #acceptPhase1Completion} succeeds.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmploymentService {

    public static final String PHASE_1 = "PHASE_1";
    public static final String PHASE_2 = "PHASE_2";
    public static final String PHASE_1_ACK_VERSION = "PH1-v1.0";

    private final EmploymentAcceptanceRepository employmentRepository;
    private final PhaseCompletionRepository phaseRepository;
    private final ErmAssignmentRepository ermAssignmentRepository;
    private final UserRepository userRepository;
    private final WorkflowService workflowService;
    private final RecordService recordService;
    private final EmailTemplateService emailTemplateService;
    private final BusinessClock clock;

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
            throw new IllegalStateException(
                    "Employment can be submitted once your team is set up and your dashboard is ready.");
        }

        // Checklist 4.5: one record at a time. A new one is only taken when
        // the ERM sent the last one back for correction.
        Optional<EmploymentAcceptance> previous = latest(userId);
        if (previous.isPresent() && !isReturned(previous.get())) {
            throw new IllegalStateException(Boolean.TRUE.equals(previous.get().getErmVerified())
                    ? "Your employment is already verified."
                    : "Your employment details are with your ERM. If something needs changing, they can send them back to you.");
        }

        if (in.getEmployerClient() == null || in.getEmployerClient().isBlank()
                || in.getJobTitle() == null || in.getJobTitle().isBlank()
                || in.getStartDate() == null) {
            throw new IllegalArgumentException("Employer, job title, and start date are required.");
        }
        LocalDate today = clock.today();
        if (in.getStartDate().isBefore(today.minusYears(1)) || in.getStartDate().isAfter(today.plusYears(1))) {
            throw new IllegalArgumentException("Check the start date: it should be within a year of today.");
        }
        // Only an offer letter this participant uploaded through the portal
        // (the link is shown to the ERM, so it can't be any address).
        String offer = safe(in.getOfferDocumentUrl());
        if (offer != null && !isOfferFileOf(userId, offer)) {
            throw new IllegalArgumentException("Please upload the offer letter again.");
        }

        EmploymentAcceptance row = EmploymentAcceptance.builder()
                .userId(userId)
                .employerClient(in.getEmployerClient().trim())
                .jobTitle(in.getJobTitle().trim())
                .startDate(in.getStartDate())
                .location(safe(in.getLocation()))
                .employmentType(safe(in.getEmploymentType()))
                .offerDocumentUrl(offer)
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

        boolean corrected = previous.isPresent();
        recordService.logAction(userId, RecordService.Category.ACCOUNT,
                corrected ? "Corrected employment details submitted" : "Employment acceptance submitted",
                row.getEmployerClient() + " — " + row.getJobTitle(),
                Map.of(
                        "employmentId", saved.getId(),
                        "employer", saved.getEmployerClient(),
                        "jobTitle", saved.getJobTitle(),
                        "startDate", saved.getStartDate().toString()
                ));

        // Checklist 4.5: the ERM hears about it straight away.
        currentErm(userId).ifPresent(erm -> {
            try {
                emailTemplateService.sendEmploymentToVerifyEmail(erm, user, saved, corrected);
            } catch (Exception e) {
                log.warn("Employment-to-verify email failed for user {}: {}", userId, e.getMessage());
            }
        });
        log.info("Employment accepted by user {} (row {})", userId, saved.getId());
        return saved;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> employmentStatus(Long userId) {
        Optional<EmploymentAcceptance> latest = latest(userId);
        Optional<PhaseCompletion> phase1 = phaseRepository.findByUserIdAndPhase(userId, PHASE_1);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("submitted", latest.isPresent());
        out.put("ermVerified", latest.map(EmploymentAcceptance::getErmVerified).orElse(false));
        out.put("returned", latest.map(EmploymentService::isReturned).orElse(false));
        latest.ifPresent(e -> {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("id", e.getId());
            details.put("employerClient", e.getEmployerClient());
            details.put("jobTitle", e.getJobTitle());
            details.put("startDate", e.getStartDate());
            details.put("location", e.getLocation());
            details.put("employmentType", e.getEmploymentType());
            details.put("offerDocumentUrl", e.getOfferDocumentUrl());
            details.put("hasOffer", e.getOfferDocumentUrl() != null);
            details.put("notes", e.getNotes());
            details.put("acceptanceDate", e.getAcceptanceDate());
            details.put("ermVerifiedDate", e.getErmVerifiedDate());
            details.put("ermNotes", e.getErmNotes());
            details.put("returnedAt", e.getReturnedAt());
            details.put("returnReason", e.getReturnReason());
            out.put("details", details);
        });
        currentErm(userId).ifPresent(erm -> {
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
        phaseRepository.findByUserIdAndPhase(userId, PHASE_2).ifPresent(p -> {
            Map<String, Object> ph = new LinkedHashMap<>();
            ph.put("startDate", p.getStartDate());
            ph.put("started", p.getStartDate() != null && !p.getStartDate().isAfter(clock.today()));
            out.put("phase2", ph);
        });
        return out;
    }

    /** The stored offer letter of the participant's latest employment record, if any. */
    @Transactional(readOnly = true)
    public Optional<String> offerFileOf(Long userId) {
        return latest(userId).map(EmploymentAcceptance::getOfferDocumentUrl).filter(Objects::nonNull);
    }

    // ─── Phase 1 completion (participant) ──────────────────────────

    @Transactional
    public PhaseCompletion acceptPhase1Completion(Long userId, String acknowledgmentVersion) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        // Gate 6 — the latest employment record must be ERM-verified.
        EmploymentAcceptance latest = latest(userId)
                .filter(e -> Boolean.TRUE.equals(e.getErmVerified()))
                .orElseThrow(() -> new IllegalStateException(
                        "Employment must be verified by your ERM before you can complete Phase 1."));

        String version = (acknowledgmentVersion == null || acknowledgmentVersion.isBlank())
                ? PHASE_1_ACK_VERSION : acknowledgmentVersion;

        // Idempotent — re-accepting returns the existing row.
        Optional<PhaseCompletion> existing = phaseRepository.findByUserIdAndPhase(userId, PHASE_1);
        if (existing.isPresent() && existing.get().getAcceptedAt() != null) {
            return existing.get();
        }
        PhaseCompletion row = existing.orElseGet(() -> PhaseCompletion.builder()
                .userId(userId)
                .phase(PHASE_1)
                .ermApproved(false)
                .build());
        row.setAcceptedAt(LocalDateTime.now());
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
            emailTemplateService.sendPhase1CompletionEmails(
                    user, currentErm(userId).orElse(null), latest, saved.getAcceptedAt());
        } catch (Exception e) {
            log.warn("Phase 1 completion emails failed for user {}: {}", userId, e.getMessage());
        }

        log.info("Phase 1 completion accepted by user {} (row {})", userId, saved.getId());
        return saved;
    }

    // ─── ERM verification ──────────────────────────────────────────

    /**
     * The ERM's current participants whose latest employment record isn't
     * verified yet: first the ones waiting for the ERM, then the ones sent
     * back and waiting for the participant's corrected details.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> ermPendingVerifications(Long ermUserId) {
        return ermAssignmentRepository.findByErmUserId(ermUserId).stream()
                .map(ErmAssignment::getUserId)
                .distinct()
                .filter(pid -> isCurrentErm(ermUserId, pid))
                .map(pid -> {
                    User p = userRepository.findById(pid).orElse(null);
                    if (p == null) return null;
                    EmploymentAcceptance latest = latest(pid).orElse(null);
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
                    row.put("hasOffer", latest.getOfferDocumentUrl() != null);
                    row.put("notes", latest.getNotes());
                    row.put("acceptanceDate", latest.getAcceptanceDate());
                    row.put("returned", isReturned(latest));
                    row.put("returnedAt", latest.getReturnedAt());
                    row.put("returnReason", latest.getReturnReason());
                    row.put("resubmitted", employmentRepository.findByUserIdOrderByAcceptanceDateDesc(pid).size() > 1);
                    return row;
                })
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing((Map<String, Object> r) -> (Boolean) r.get("returned"))
                        .thenComparing(r -> (LocalDateTime) r.get("acceptanceDate"),
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    @Transactional
    public EmploymentAcceptance verifyEmployment(Long ermUserId, Long participantId, String notes) {
        requireCurrentErm(ermUserId, participantId);
        EmploymentAcceptance row = latest(participantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "EmploymentAcceptance", "userId", participantId));
        if (Boolean.TRUE.equals(row.getErmVerified())) return row;
        if (isReturned(row)) {
            throw new IllegalStateException(
                    "You sent these details back for correction. Wait for the participant's corrected details.");
        }
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

    /**
     * Checklist 4.5: the ERM sends the participant's employment details back
     * for correction, with a reason the participant is emailed. The record
     * stays for the history; the participant submits corrected details.
     */
    @Transactional
    public EmploymentAcceptance returnForCorrection(Long ermUserId, Long participantId, String reason) {
        requireCurrentErm(ermUserId, participantId);
        String why = reason == null ? "" : reason.trim();
        if (why.length() < 5) {
            throw new IllegalArgumentException("Tell the participant what needs correcting.");
        }
        if (why.length() > 2000) {
            throw new IllegalArgumentException("Please keep the reason under 2,000 characters.");
        }
        EmploymentAcceptance row = latest(participantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "EmploymentAcceptance", "userId", participantId));
        if (Boolean.TRUE.equals(row.getErmVerified())) {
            throw new IllegalStateException("These employment details are already verified.");
        }
        if (isReturned(row)) {
            throw new IllegalStateException("Already sent back. Waiting for the participant's corrected details.");
        }
        row.setReturnedAt(LocalDateTime.now());
        row.setReturnReason(why);
        row.setReturnedBy(ermUserId);
        EmploymentAcceptance saved = employmentRepository.save(row);

        recordService.logAction(participantId, RecordService.Category.ACCOUNT,
                "Employment details sent back for correction",
                why,
                Map.of("employmentId", saved.getId(), "ermUserId", ermUserId));
        userRepository.findById(participantId).ifPresent(p -> {
            try {
                emailTemplateService.sendEmploymentReturnedEmail(p, why);
            } catch (Exception e) {
                log.warn("Employment-returned email failed for user {}: {}", participantId, e.getMessage());
            }
        });
        return saved;
    }

    /** The offer letter the ERM may open: their current participant's latest record only. */
    @Transactional
    public Optional<String> offerFileForErm(Long ermUserId, Long participantId) {
        requireCurrentErm(ermUserId, participantId);
        Optional<String> file = offerFileOf(participantId);
        file.ifPresent(f -> recordService.logAction(participantId, RecordService.Category.DOCUMENT,
                "Offer letter viewed by ERM", null, Map.of("ermUserId", ermUserId)));
        return file;
    }

    /** ERM-side roster of participants who self-accepted Phase 1 but
     *  haven't been approved yet. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> ermPendingPhaseApprovals(Long ermUserId) {
        return ermAssignmentRepository.findByErmUserId(ermUserId).stream()
                .map(ErmAssignment::getUserId)
                .distinct()
                .filter(pid -> isCurrentErm(ermUserId, pid))
                .map(pid -> {
                    User p = userRepository.findById(pid).orElse(null);
                    if (p == null) return null;
                    PhaseCompletion ph = phaseRepository
                            .findByUserIdAndPhase(pid, PHASE_1).orElse(null);
                    if (ph == null || ph.getAcceptedAt() == null) return null;
                    if (Boolean.TRUE.equals(ph.getErmApproved())) return null;
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("userId", p.getId());
                    row.put("participantId", p.getParticipantId());
                    row.put("fullName", p.getFullName());
                    row.put("phaseCompletionId", ph.getId());
                    row.put("acceptedAt", ph.getAcceptedAt());
                    row.put("acknowledgmentVersion", ph.getAcknowledgmentVersion());
                    latestVerifiedEmployment(pid).ifPresent(e -> {
                        row.put("employerClient", e.getEmployerClient());
                        row.put("startDate", e.getStartDate());
                    });
                    return row;
                })
                .filter(Objects::nonNull)
                .toList();
    }

    @Transactional
    public PhaseCompletion approvePhase1(Long ermUserId, Long participantId, String notes) {
        requireCurrentErm(ermUserId, participantId);
        PhaseCompletion ph = phaseRepository.findByUserIdAndPhase(participantId, PHASE_1)
                .filter(p -> p.getAcceptedAt() != null)
                .orElseThrow(() -> new IllegalStateException(
                        "The participant hasn't accepted the Phase 1 completion acknowledgment yet."));
        if (Boolean.TRUE.equals(ph.getErmApproved())) {
            startPhase2(participantId);
            return ph;
        }
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
        PhaseCompletion phase2 = startPhase2(participantId);
        if (phase2 != null) {
            userRepository.findById(participantId).ifPresent(p -> {
                try {
                    emailTemplateService.sendPhase2StartedEmail(p, phase2.getStartDate());
                } catch (Exception e) {
                    log.warn("Phase 2 email failed for user {}: {}", participantId, e.getMessage());
                }
            });
        }
        return saved;
    }

    /**
     * Checklist 4.5: once Phase 1 is approved, Phase 2 (post-offer support)
     * is recorded as beginning on the verified employment start date.
     * Returns the new record, or null when there already was one.
     */
    @Transactional
    public PhaseCompletion startPhase2(Long participantId) {
        if (phaseRepository.findByUserIdAndPhase(participantId, PHASE_2).isPresent()) return null;
        LocalDate start = latestVerifiedEmployment(participantId)
                .map(EmploymentAcceptance::getStartDate)
                .orElse(clock.today());
        PhaseCompletion saved = phaseRepository.save(PhaseCompletion.builder()
                .userId(participantId)
                .phase(PHASE_2)
                .startDate(start)
                .ermApproved(false)
                .build());
        recordService.logAction(participantId, RecordService.Category.ACCOUNT,
                "Phase 2 (post-offer support) starts " + start, null,
                Map.of("phaseCompletionId", saved.getId(), "startDate", start.toString()));
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

    /**
     * Whether a stored file is an offer letter this participant uploaded
     * through {@code /employment/offer-upload}: local files live under
     * participant-documents/{userId}/offer-…, Cloudinary files under
     * spire/documents/{userId}/offer-….
     */
    static boolean isOfferFileOf(Long userId, String url) {
        if (url == null || url.contains("..") || url.contains("\\")) return false;
        if (url.startsWith("participant-documents/" + userId + "/offer-")) return true;
        if (url.startsWith(DocumentStorageService.S3_PREFIX + "participant-documents/" + userId + "/offer-")) return true;
        return url.startsWith("https://res.cloudinary.com/")
                && url.contains("/spire/documents/" + userId + "/offer-");
    }

    private Optional<EmploymentAcceptance> latest(Long userId) {
        return employmentRepository.findByUserIdOrderByAcceptanceDateDesc(userId).stream().findFirst();
    }

    private static boolean isReturned(EmploymentAcceptance e) {
        return e.getReturnedAt() != null;
    }

    private Optional<User> currentErm(Long participantId) {
        return ermAssignmentRepository.findFirstByUserIdOrderByAssignedDateDesc(participantId)
                .map(ErmAssignment::getErmUserId)
                .flatMap(userRepository::findById);
    }

    private boolean isCurrentErm(Long ermUserId, Long participantId) {
        return ermAssignmentRepository.findFirstByUserIdOrderByAssignedDateDesc(participantId)
                .map(a -> ermUserId.equals(a.getErmUserId()))
                .orElse(false);
    }

    /** Only the participant's current ERM (not a previous one) may act on their employment. */
    private void requireCurrentErm(Long ermUserId, Long participantId) {
        if (!isCurrentErm(ermUserId, participantId)) {
            throw new AccessDeniedException("Not the assigned ERM for this participant.");
        }
    }
}
