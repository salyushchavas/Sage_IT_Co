package com.spire.backend.service;

import com.spire.backend.entity.AgreementAcceptance;
import com.spire.backend.entity.ErmAssignment;
import com.spire.backend.entity.ParticipantDocument;
import com.spire.backend.entity.ProgramSelection;
import com.spire.backend.entity.User;
import com.spire.backend.entity.UserRecord;
import com.spire.backend.entity.WeeklyReport;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.repository.AgreementAcceptanceRepository;
import com.spire.backend.repository.CoachAssignmentRepository;
import com.spire.backend.repository.ErmAssignmentRepository;
import com.spire.backend.repository.ParticipantDocumentRepository;
import com.spire.backend.repository.ProgramSelectionRepository;
import com.spire.backend.repository.UserRecordRepository;
import com.spire.backend.repository.UserRepository;
import com.spire.backend.repository.WeeklyReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Phase 5B — ERM dashboard data access.
 *
 * Caller-scope rule: every read returns only data for participants
 * whose erm_assignments.erm_user_id points to the calling ERM.
 * Cross-ERM data leakage is prevented in {@link #requireAssignment}.
 *
 * Field-scope rule (PRD §13): ERMs do not see SSN values or check
 * images. This service surfaces only the metadata fields the ERM
 * needs; document IDs surface but the resolved file URL stays at
 * the participant-document endpoint, which the ERM-side UI does
 * not currently call.
 */
@Service
@RequiredArgsConstructor
public class ErmService {

    private final ErmAssignmentRepository ermAssignmentRepository;
    private final CoachAssignmentRepository coachAssignmentRepository;
    private final UserRepository userRepository;
    private final ProgramSelectionRepository programSelectionRepository;
    private final ParticipantDocumentRepository participantDocumentRepository;
    private final AgreementAcceptanceRepository agreementRepository;
    private final WeeklyReportRepository weeklyReportRepository;
    private final UserRecordRepository userRecordRepository;
    private final RecordService recordService;

    /**
     * Roster — participants whose CURRENT ERM is this ERM (a participant
     * reassigned to someone else no longer shows here).
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> roster(Long ermUserId) {
        return ermAssignmentRepository.findByErmUserId(ermUserId).stream()
                .map(ErmAssignment::getUserId)
                .distinct()
                .filter(pid -> isCurrentErm(ermUserId, pid))
                .map(pid -> userRepository.findById(pid).orElse(null))
                .filter(Objects::nonNull)
                .map(p -> {
                    ProgramSelection prog = programSelectionRepository
                            .findFirstByUserIdOrderBySelectionDateDesc(p.getId())
                            .orElse(null);
                    LocalDateTime lastActivity = userRecordRepository
                            .findByUserIdOrderByCreatedAtDesc(p.getId()).stream()
                            .findFirst().map(UserRecord::getCreatedAt).orElse(null);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("userId", p.getId());
                    row.put("participantId", p.getParticipantId());
                    row.put("fullName", p.getFullName());
                    row.put("email", p.getEmail());
                    row.put("program", prog != null ? prog.getProgram() : null);
                    row.put("technology", prog != null ? prog.getSkillset() : p.getSelectedTechnology());
                    row.put("targetJobTitle", prog != null ? prog.getTargetJobTitle() : null);
                    row.put("currentStatus", p.getCurrentStatus());
                    row.put("lastActivity", lastActivity);
                    // Checklist 2.3: a signed agreement waiting for this ERM's review.
                    row.put("agreementToReview", agreementRepository.findByUserId(p.getId())
                            .map(a -> AgreementService.STATUS_VERIFIED.equals(a.getStatus())
                                    && ermUserId.equals(a.getErmRoutedTo()) && a.getErmReviewedAt() == null)
                            .orElse(false));
                    return row;
                })
                .toList();
    }

    /** Single-participant detail panel for the ERM dashboard. */
    @Transactional(readOnly = true)
    public Map<String, Object> participantDetail(Long ermUserId, Long participantId) {
        requireAssignment(ermUserId, participantId);
        User p = userRepository.findById(participantId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", participantId));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", p.getId());
        out.put("participantId", p.getParticipantId());
        out.put("fullName", p.getFullName());
        out.put("email", p.getEmail());
        out.put("phone", p.getPhone());
        out.put("location", p.getLocation());
        out.put("currentStatus", p.getCurrentStatus());

        ProgramSelection prog = programSelectionRepository
                .findFirstByUserIdOrderBySelectionDateDesc(participantId).orElse(null);
        if (prog != null) {
            Map<String, Object> pp = new LinkedHashMap<>();
            pp.put("program", prog.getProgram());
            pp.put("phase", prog.getPhase());
            pp.put("skillset", prog.getSkillset());
            pp.put("targetJobTitle", prog.getTargetJobTitle());
            pp.put("availability", prog.getAvailability());
            out.put("program", pp);
        }

        // Document statuses only — no file URLs to keep finance-only
        // pieces (check images) invisible.
        List<Map<String, Object>> docs = participantDocumentRepository
                .findAll().stream()
                .filter(d -> participantId.equals(d.getUserId()))
                .map(d -> Map.<String, Object>of(
                        "id", d.getId(),
                        "documentType", d.getDocumentType() == null ? "" : d.getDocumentType(),
                        "reviewStatus", d.getReviewStatus() == null ? "PENDING" : d.getReviewStatus(),
                        "uploadedAt", d.getUploadedAt() == null ? "" : d.getUploadedAt().toString()
                ))
                .toList();
        out.put("documents", docs);

        AgreementAcceptance agree = agreementRepository.findByUserId(participantId).orElse(null);
        if (agree != null) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("status", agree.getStatus() == null ? "" : agree.getStatus());
            a.put("signed", AgreementService.STATUS_VERIFIED.equals(agree.getStatus()));
            a.put("acceptedAt", agree.getAcceptedAt() == null ? "" : agree.getAcceptedAt().toString());
            a.put("version", agree.getAgreementVersion() == null ? "" : agree.getAgreementVersion());
            // Checklist 2.3: routed to this ERM, and reviewed by them.
            a.put("routedAt", agree.getErmRoutedAt() == null ? "" : agree.getErmRoutedAt().toString());
            a.put("reviewedAt", agree.getErmReviewedAt() == null ? "" : agree.getErmReviewedAt().toString());
            out.put("agreement", a);
        }

        out.put("reports", weeklyReportRepository.findByUserIdOrderByWeekStartDesc(participantId).stream()
                .filter(r -> !"PENDING".equals(r.getStatus()))
                .toList());
        out.put("coaches", coachAssignmentRepository
                .findByUserIdAndStatus(participantId, "ACTIVE").stream()
                .map(ca -> {
                    User c = ca.getCoachUserId() == null ? null
                            : userRepository.findById(ca.getCoachUserId()).orElse(null);
                    return Map.<String, Object>of(
                            "coachRole", ca.getCoachRole() == null ? "" : ca.getCoachRole(),
                            "name", c == null || c.getFullName() == null ? "" : c.getFullName(),
                            "email", c == null || c.getEmail() == null ? "" : c.getEmail()
                    );
                })
                .toList());

        ErmAssignment myRow = ermAssignmentRepository
                .findFirstByUserIdOrderByAssignedDateDesc(participantId).orElse(null);
        out.put("communicationNotes", myRow == null ? "" : (myRow.getCommunicationNotes() == null ? "" : myRow.getCommunicationNotes()));

        return out;
    }

    // ── Weekly reports ────────────────────────────────────────────

    /** One weekly report in the ERM's list (checklist 4.1). */
    public record ReportRow(Long id, Long userId, String participantName, String participantId,
                            java.time.LocalDate weekStart, java.time.LocalDate weekEnd, java.time.LocalDate dueDate,
                            String status, LocalDateTime submittedAt, boolean late, boolean needsHelp,
                            String reportData, String ermNotes, LocalDateTime ermReviewDate) {}

    private static final List<String> REPORT_ORDER = List.of("SUBMITTED", "OVERDUE", "REVIEWED");

    /**
     * Checklist 4.1: the weekly reports of the ERM's current participants,
     * with each participant's name and ID and the full report. Drafts
     * aren't shown (the participant hasn't submitted them). Order: reports
     * where the participant asked for help first, then submitted (to
     * review), overdue, reviewed; newest week first within each.
     */
    @Transactional(readOnly = true)
    public List<ReportRow> reportsForMyParticipants(Long ermUserId) {
        return ermAssignmentRepository.findByErmUserId(ermUserId).stream()
                .map(ErmAssignment::getUserId)
                .distinct()
                .filter(pid -> isCurrentErm(ermUserId, pid))
                .flatMap(pid -> {
                    User p = userRepository.findById(pid).orElse(null);
                    return weeklyReportRepository.findByUserIdOrderByWeekStartDesc(pid).stream()
                            .filter(r -> !"PENDING".equals(r.getStatus()))
                            .map(r -> new ReportRow(r.getId(), pid,
                                    p == null ? null : p.getFullName(), p == null ? null : p.getParticipantId(),
                                    r.getWeekStart(), r.getWeekEnd(), r.getSubmissionDueDate(), r.getStatus(),
                                    r.getSubmittedAt(), r.getOverdueFlaggedAt() != null,
                                    r.getEscalatedAt() != null, r.getReportData(), r.getErmNotes(), r.getErmReviewDate()));
                })
                .sorted(java.util.Comparator
                        .comparing((ReportRow r) -> !(r.needsHelp() && !"REVIEWED".equals(r.status())))
                        .thenComparingInt(r -> REPORT_ORDER.indexOf(r.status()))
                        .thenComparing(ReportRow::weekStart, java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                .toList();
    }

    @Transactional
    public WeeklyReport reviewReport(Long ermUserId, Long reportId, String notes) {
        WeeklyReport r = weeklyReportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("WeeklyReport", "id", reportId));
        requireAssignment(ermUserId, r.getUserId());
        // Only a submitted report can be reviewed: a draft or an overdue
        // week has nothing from the participant yet (checklist 4.1).
        if (!"SUBMITTED".equals(r.getStatus())) {
            throw new IllegalStateException("Only a submitted report can be marked reviewed.");
        }
        r.setStatus("REVIEWED");
        r.setErmNotes(notes == null ? "" : notes.trim());
        r.setErmReviewDate(LocalDateTime.now());
        WeeklyReport saved = weeklyReportRepository.save(r);
        recordService.logAction(r.getUserId(), RecordService.Category.ACCOUNT,
                "Weekly report reviewed by ERM",
                notes,
                Map.of("reportId", reportId, "ermUserId", ermUserId));
        return saved;
    }

    // ── Communication notes ──────────────────────────────────────

    @Transactional
    public ErmAssignment appendNote(Long ermUserId, Long participantId, String note, boolean escalation) {
        requireAssignment(ermUserId, participantId);
        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("Note text is required");
        }
        ErmAssignment row = ermAssignmentRepository
                .findFirstByUserIdOrderByAssignedDateDesc(participantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "ErmAssignment", "userId", participantId));
        String stamp = LocalDateTime.now().toString();
        String prefix = (escalation ? "[ESCALATION] " : "") + "[" + stamp + "] ";
        String existing = row.getCommunicationNotes();
        String updated = (existing == null || existing.isBlank())
                ? prefix + note.trim()
                : existing + "\n" + prefix + note.trim();
        row.setCommunicationNotes(updated);
        ErmAssignment saved = ermAssignmentRepository.save(row);
        recordService.logAction(participantId,
                escalation ? RecordService.Category.SECURITY : RecordService.Category.ACCOUNT,
                escalation ? "ERM logged an escalation" : "ERM note logged",
                note,
                Map.of("ermUserId", ermUserId, "escalation", escalation));
        return saved;
    }

    // ── Auth gate ─────────────────────────────────────────────────

    /**
     * The participant's CURRENT ERM only (an earlier, replaced ERM no longer
     * has access). 403 rather than 401: the ERM is signed in, just not
     * allowed here — a 401 made the website think the session had expired.
     */
    private void requireAssignment(Long ermUserId, Long participantId) {
        if (!isCurrentErm(ermUserId, participantId)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "You are not the assigned ERM for this participant.");
        }
    }

    private boolean isCurrentErm(Long ermUserId, Long participantId) {
        return ermAssignmentRepository.findFirstByUserIdOrderByAssignedDateDesc(participantId)
                .map(a -> ermUserId.equals(a.getErmUserId()))
                .orElse(false);
    }
}
