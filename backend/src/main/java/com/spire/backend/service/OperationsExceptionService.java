package com.spire.backend.service;

import com.spire.backend.entity.CheckDocument;
import com.spire.backend.entity.CheckTracking;
import com.spire.backend.entity.CoachAssignment;
import com.spire.backend.entity.EmailLog;
import com.spire.backend.entity.ParticipantDocument;
import com.spire.backend.entity.PaymentPlan;
import com.spire.backend.entity.User;
import com.spire.backend.entity.WeeklyReport;
import com.spire.backend.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Checklist 6.2 (roadmap §14): every exception case in one list for
 * Operations, each with who it's about (name and Participant ID), what's
 * wrong, since when, and where to fix it:
 * <ol>
 *   <li>duplicate email or phone</li>
 *   <li>email verification failed</li>
 *   <li>missing documents (and document exceptions waiting for a decision)</li>
 *   <li>invalid or unreadable upload (sent back, not replaced)</li>
 *   <li>agreement declined or expired</li>
 *   <li>check copy missing or unclear</li>
 *   <li>ERM not assigned</li>
 *   <li>coach unavailable (empty coach slots)</li>
 *   <li>weekly report overdue</li>
 *   <li>payment plan not accepted</li>
 *   <li>physical check not received</li>
 * </ol>
 * plus emails that couldn't be delivered. Everything is worked out from the
 * records each time, so an exception disappears once it's resolved.
 */
@Service
@RequiredArgsConstructor
public class OperationsExceptionService {

    /** Grace periods before something counts as an exception. */
    static final int VERIFY_GRACE_HOURS = 24;
    static final int VERIFY_GIVE_UP_DAYS = 30;
    static final int DOCUMENTS_GRACE_DAYS = 3;
    static final int REVIEW_GRACE_DAYS = 2;
    static final int CHECK_COPY_GRACE_DAYS = 3;
    static final int COACH_GRACE_HOURS = 24;
    static final int PLAN_GRACE_DAYS = 7;
    static final int CHECK_IN_TRANSIT_DAYS = 14;
    static final int EMAIL_LOOKBACK_DAYS = 14;

    /** The order the types are listed in, and their plain names. */
    public static final Map<String, String> TYPES = new LinkedHashMap<>();
    static {
        TYPES.put("DUPLICATE_CONTACT", "Duplicate phone number");
        TYPES.put("EMAIL_NOT_VERIFIED", "Email not verified");
        TYPES.put("DOCUMENTS_MISSING", "Documents missing");
        TYPES.put("DOCUMENT_EXCEPTION", "Document exception to decide");
        TYPES.put("DOCUMENT_REVIEW_WAITING", "Documents waiting for review");
        TYPES.put("DOCUMENT_RESUBMIT", "Document sent back, not replaced");
        TYPES.put("FILE_MISSING", "Uploaded file missing");
        TYPES.put("AGREEMENT_DECLINED", "Agreement declined");
        TYPES.put("AGREEMENT_EXPIRED", "Agreement not signed in time");
        TYPES.put("CHECK_COPY", "Check copy missing or unclear");
        TYPES.put("ERM_NOT_ASSIGNED", "No ERM assigned");
        TYPES.put("COACH_MISSING", "Coach slot empty");
        TYPES.put("WEEKLY_OVERDUE", "Weekly report overdue");
        TYPES.put("PLAN_NOT_ACCEPTED", "Payment plan not accepted");
        TYPES.put("CHECK_NOT_RECEIVED", "Mailed check not received");
        TYPES.put("EMAIL_FAILED", "Email not delivered");
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);

    private final UserRepository userRepository;
    private final ParticipantDocumentRepository documentRepository;
    private final DocumentService documentService;
    private final AgreementQueueService agreementQueueService;
    private final CheckDocumentRepository checkDocumentRepository;
    private final ErmAssignmentService ermAssignmentService;
    private final CoachAssignmentRepository coachAssignmentRepository;
    private final WeeklyReportRepository weeklyReportRepository;
    private final PaymentPlanRepository planRepository;
    private final CheckTrackingRepository trackingRepository;
    private final EmailLogRepository emailLogRepository;
    private final WorkflowService workflowService;
    private final BusinessClock clock;

    public record Row(String type, String label, Long userId, String participantId, String fullName,
                      String detail, LocalDateTime since, String where) {}

    @Transactional(readOnly = true)
    public List<Row> all() {
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = clock.today();
        List<Row> rows = new ArrayList<>();
        Map<Long, User> people = new HashMap<>();
        List<User> participants = new ArrayList<>();
        for (User u : userRepository.findAll()) {
            people.put(u.getId(), u);
            if (u.getRole() != null && AgreementQueueService.PARTICIPANT_ROLES.contains(u.getRole().getName())
                    && !Boolean.FALSE.equals(u.getIsActive())) {
                participants.add(u);
            }
        }

        // 1. Duplicate phone numbers among active accounts (new sign-ups are blocked; old ones may remain).
        Map<String, List<User>> byPhone = new HashMap<>();
        for (User u : people.values()) {
            if (Boolean.FALSE.equals(u.getIsActive()) || u.getPhoneNormalized() == null) continue;
            byPhone.computeIfAbsent(u.getPhoneNormalized(), k -> new ArrayList<>()).add(u);
        }
        for (List<User> same : byPhone.values()) {
            if (same.size() < 2) continue;
            for (User u : same) {
                String others = same.stream().filter(o -> !o.getId().equals(u.getId()))
                        .map(o -> nz(o.getFullName()) + (o.getParticipantId() == null ? "" : " (" + o.getParticipantId() + ")"))
                        .reduce((a, b) -> a + ", " + b).orElse("");
                rows.add(row("DUPLICATE_CONTACT", u, "Same phone number as " + others, u.getCreatedAt(),
                        "Check both accounts; a System Admin can deactivate the duplicate (Admin → Users)"));
            }
        }

        for (User u : participants) {
            // 2. Email verification failed: locked out, or never verified.
            if (!Boolean.TRUE.equals(u.getEmailVerified()) && u.getCreatedAt() != null
                    && u.getCreatedAt().isAfter(now.minusDays(VERIFY_GIVE_UP_DAYS))) {
                boolean locked = u.getVerificationLockedUntil() != null && u.getVerificationLockedUntil().isAfter(now);
                if (locked || u.getCreatedAt().isBefore(now.minusHours(VERIFY_GRACE_HOURS))) {
                    rows.add(row("EMAIL_NOT_VERIFIED", u,
                            locked ? "Locked out after too many wrong codes" : "Signed up but never entered the email code",
                            u.getCreatedAt(), "Contact them and confirm their email address"));
                }
            }
            // 3. Documents missing after the acknowledgment.
            if (Boolean.TRUE.equals(u.getAcknowledgmentComplete()) && !Boolean.TRUE.equals(u.getDocumentsComplete())
                    && u.getCreatedAt() != null && u.getCreatedAt().isBefore(now.minusDays(DOCUMENTS_GRACE_DAYS))) {
                List<String> missing = documentService.missingRequired(u.getId()).stream()
                        .map(DocumentService::labelFor).toList();
                if (!missing.isEmpty()) {
                    rows.add(row("DOCUMENTS_MISSING", u, "Missing: " + String.join(", ", missing), u.getCreatedAt(),
                            "Reminders go out daily; contact them if it stays"));
                }
            }
            // 8. Coach slots still empty once the ERM is in place.
            if (workflowService.isStatusAtLeast(u, WorkflowService.Status.ERM_ASSIGNED)
                    && ermAssignmentService.getAssignedErm(u.getId()).isPresent()) {
                Set<String> filled = new HashSet<>();
                LocalDateTime lastAssigned = null;
                for (CoachAssignment a : coachAssignmentRepository.findByUserIdAndStatus(u.getId(), "ACTIVE")) {
                    filled.add(a.getCoachRole());
                    if (a.getAssignedDate() != null && (lastAssigned == null || a.getAssignedDate().isAfter(lastAssigned))) {
                        lastAssigned = a.getAssignedDate();
                    }
                }
                List<String> empty = CoachAssignmentService.COACH_ROLES.entrySet().stream()
                        .filter(e -> !filled.contains(e.getKey())).map(Map.Entry::getValue).toList();
                LocalDateTime since = lastAssigned != null ? lastAssigned : u.getCreatedAt();
                if (!empty.isEmpty() && since != null && since.isBefore(now.minusHours(COACH_GRACE_HOURS))) {
                    rows.add(row("COACH_MISSING", u, "No " + String.join(", ", empty), since,
                            "Operations → Assignments: pick a coach"));
                }
            }
        }

        // 3b/4. Documents: exceptions to decide, uploads waiting for review, and sent-back ones not replaced.
        for (ParticipantDocument d : documentRepository.findByReviewStatusInOrderByUploadedAtAsc(DocumentService.NEEDS_REVIEW)) {
            User u = people.get(d.getUserId());
            if (u == null || Boolean.FALSE.equals(u.getIsActive())) continue;
            if (DocumentService.EXCEPTION_REQUESTED.equals(d.getReviewStatus())) {
                rows.add(row("DOCUMENT_EXCEPTION", u, DocumentService.labelFor(d.getDocumentType())
                        + (d.getExceptionReason() == null ? "" : ": \"" + d.getExceptionReason() + "\""),
                        d.getUploadedAt(), "Operations → Documents: approve or decline"));
            } else if (d.getUploadedAt() != null && d.getUploadedAt().isBefore(now.minusDays(REVIEW_GRACE_DAYS))) {
                rows.add(row("DOCUMENT_REVIEW_WAITING", u, DocumentService.labelFor(d.getDocumentType()),
                        d.getUploadedAt(), "Operations → Documents: review it"));
            }
        }
        for (ParticipantDocument d : documentRepository.findByReviewStatus(DocumentService.REJECTED)) {
            User u = people.get(d.getUserId());
            if (u == null || Boolean.FALSE.equals(u.getIsActive())) continue;
            // Replaced when a newer, non-rejected upload of the same type exists.
            boolean replaced = documentRepository.findByUserIdAndDocumentType(d.getUserId(), d.getDocumentType()).stream()
                    .anyMatch(o -> !o.getId().equals(d.getId()) && !DocumentService.REJECTED.equals(o.getReviewStatus())
                            && o.getUploadedAt() != null && d.getUploadedAt() != null && o.getUploadedAt().isAfter(d.getUploadedAt()));
            if (!replaced) {
                rows.add(row("DOCUMENT_RESUBMIT", u, DocumentService.labelFor(d.getDocumentType())
                        + (d.getReviewerNotes() == null ? "" : ": " + d.getReviewerNotes()),
                        d.getReviewedAt() != null ? d.getReviewedAt() : d.getUploadedAt(),
                        "They were emailed; follow up if they don't upload"));
            }
        }

        // Uploads saved on the server's own disk that are no longer there
        // (Railway wipes it at every deploy): the participant must upload
        // again. S3 and Cloudinary files don't go missing like this.
        for (ParticipantDocument d : documentRepository.findTop500ByOrderByUploadedAtDesc()) {
            if (!localFileMissing(d.getFileUrl())) continue;
            User u = people.get(d.getUserId());
            if (u == null || Boolean.FALSE.equals(u.getIsActive())) continue;
            rows.add(row("FILE_MISSING", u, DocumentService.labelFor(d.getDocumentType()) + " (" + nz(d.getFileName()) + ")",
                    d.getUploadedAt(), "Ask them to upload it again (the server's disk was wiped by a deploy)"));
        }
        for (User u : participants) {
            for (CheckDocument c : checkDocumentRepository.findByUserIdOrderByUploadedAtDesc(u.getId())) {
                if (localFileMissing(c.getFileUrl())) {
                    rows.add(row("FILE_MISSING", u, "Check copy", c.getUploadedAt(),
                            "Ask them to upload the check copy again"));
                }
            }
        }

        // 5–7. The agreement queue: declined, expired, check step, no ERM.
        for (AgreementQueueService.Row a : agreementQueueService.queue()) {
            User u = people.get(a.userId());
            if (u == null) continue;
            switch (a.stage()) {
                case "DECLINED" -> rows.add(row("AGREEMENT_DECLINED", u,
                        a.detail() == null ? "Declined" : "Reason: " + a.detail(), a.since(),
                        "Operations → Agreements: contact them, resend or close"));
                case "EXPIRED" -> rows.add(row("AGREEMENT_EXPIRED", u, nz(a.detail()), a.since(),
                        "Operations → Agreements: contact them and decide"));
                case "CHECK_STEP" -> {
                    if (a.since() != null && a.since().isBefore(now.minusDays(CHECK_COPY_GRACE_DAYS))) {
                        rows.add(row("CHECK_COPY", u, "Signed, but no check copy uploaded", a.since(),
                                "Remind them to upload the check copy"));
                    }
                }
                case "NEEDS_ERM" -> rows.add(row("ERM_NOT_ASSIGNED", u, "Signed; waiting for an ERM", a.since(),
                        "Operations → Assignments: assign an ERM"));
                default -> { }
            }
        }
        // 6b. Check copies Finance sent back that haven't been replaced.
        for (CheckDocument c : checkDocumentRepository.findByReviewStatus("REJECTED")) {
            User u = people.get(c.getUserId());
            if (u == null || Boolean.FALSE.equals(u.getIsActive())) continue;
            boolean replaced = checkDocumentRepository.findByUserIdOrderByUploadedAtDesc(c.getUserId()).stream()
                    .anyMatch(o -> c.getId().equals(o.getReplacesCheckId()));
            if (!replaced) {
                rows.add(row("CHECK_COPY", u, "Sent back by Finance" + (c.getReviewNotes() == null ? "" : ": " + c.getReviewNotes()),
                        c.getReviewedAt() != null ? c.getReviewedAt() : c.getUploadedAt(),
                        "They were emailed; Finance follows up"));
            }
        }

        // 9. Weekly reports marked overdue (the ERM's queue shows them too).
        for (WeeklyReport r : weeklyReportRepository.findByStatus("OVERDUE")) {
            User u = people.get(r.getUserId());
            if (u == null || Boolean.FALSE.equals(u.getIsActive())) continue;
            String erm = ermAssignmentService.getAssignedErm(u.getId()).map(User::getFullName).orElse(null);
            rows.add(row("WEEKLY_OVERDUE", u, "Week of " + r.getWeekStart().format(DAY)
                            + (erm == null ? "" : " · ERM " + erm),
                    r.getOverdueFlaggedAt(), "The ERM follows up and logs it"));
        }

        // 10. Payment plans waiting too long for the participant's acceptance.
        for (PaymentPlan p : planRepository.findByStatus("PENDING")) {
            User u = people.get(p.getUserId());
            if (u == null || p.getAcceptedAt() != null) continue;
            LocalDateTime since = p.getCreatedAt();
            // Plans from before created_at existed have no date: they're old, so they count.
            if (since == null || since.isBefore(now.minusDays(PLAN_GRACE_DAYS))) {
                rows.add(row("PLAN_NOT_ACCEPTED", u, "Plan " + p.getPlanId() + " · " + Money.usd(p.getTotalAmount()),
                        since, "Finance or the ERM follows up; no invoices until accepted"));
            }
        }

        // 11. Mailed checks: marked as a problem, or still in transit past the expected date.
        for (CheckTracking t : trackingRepository.findAll()) {
            String status = t.getStatus() == null ? "" : t.getStatus();
            boolean problem = Set.of("EXCEPTION", "LOST", "RETURNED").contains(status);
            LocalDate due = t.getExpectedReceiptDate() != null ? t.getExpectedReceiptDate()
                    : t.getMailedDate() == null ? null : t.getMailedDate().plusDays(CHECK_IN_TRANSIT_DAYS);
            boolean late = ("IN_TRANSIT".equals(status) || "MAILED".equals(status) || "PENDING".equals(status))
                    && due != null && due.isBefore(today);
            if (!problem && !late) continue;
            User u = planRepository.findById(t.getPaymentPlanId()).map(PaymentPlan::getUserId).map(people::get).orElse(null);
            if (u == null) continue;
            rows.add(row("CHECK_NOT_RECEIVED", u,
                    (problem ? "Marked " + status.toLowerCase() : "Not received; expected by " + due.format(DAY))
                            + " · " + nz(t.getCarrier()) + " " + nz(t.getPhysicalTrackingId()),
                    t.getMailedDate() == null ? null : t.getMailedDate().atStartOfDay(),
                    "Finance → Check Tracking: follow up with the carrier"));
        }

        // Emails that failed and weren't sent successfully since.
        Map<String, LocalDateTime> lastSent = new HashMap<>();
        List<EmailLog> recent = emailLogRepository.findTop300ByOrderBySentAtDesc();
        for (EmailLog e : recent) {
            if ("SENT".equals(e.getStatus()) && e.getSentAt() != null) {
                lastSent.merge(e.getEmailType() + "|" + nz(e.getRecipient()).toLowerCase(), e.getSentAt(),
                        (a, b) -> a.isAfter(b) ? a : b);
            }
        }
        Set<String> reported = new HashSet<>();
        for (EmailLog e : recent) {
            if (!"FAILED".equals(e.getStatus()) || e.getSentAt() == null
                    || e.getSentAt().isBefore(now.minusDays(EMAIL_LOOKBACK_DAYS))) continue;
            String key = e.getEmailType() + "|" + nz(e.getRecipient()).toLowerCase();
            LocalDateTime later = lastSent.get(key);
            if ((later != null && later.isAfter(e.getSentAt())) || !reported.add(key)) continue;
            User u = e.getUserId() != null ? people.get(e.getUserId())
                    : userRepository.findByEmail(e.getRecipient()).orElse(null);
            rows.add(new Row("EMAIL_FAILED", TYPES.get("EMAIL_FAILED"), u == null ? null : u.getId(),
                    u == null ? null : u.getParticipantId(), u == null ? e.getRecipient() : u.getFullName(),
                    e.getEmailType() + (e.getErrorMessage() == null ? "" : " · " + e.getErrorMessage()),
                    e.getSentAt(), "Operations → Email log: check the address, then resend"));
        }

        List<String> order = new ArrayList<>(TYPES.keySet());
        rows.sort(Comparator.comparingInt((Row r) -> order.indexOf(r.type()))
                .thenComparing(Row::since, Comparator.nullsLast(Comparator.naturalOrder())));
        return rows;
    }

    /** A file that was saved on the server's disk and isn't there any more. */
    static boolean localFileMissing(String stored) {
        if (stored == null || !stored.startsWith("participant-documents/") || stored.contains("..")) return false;
        return !new java.io.File(stored).isFile();
    }

    private static Row row(String type, User u, String detail, LocalDateTime since, String where) {
        return new Row(type, TYPES.get(type), u.getId(), u.getParticipantId(), u.getFullName(), detail, since, where);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
