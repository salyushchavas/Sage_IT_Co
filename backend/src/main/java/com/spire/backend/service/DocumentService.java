package com.spire.backend.service;

import com.spire.backend.entity.ParticipantDocument;
import com.spire.backend.entity.User;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.repository.ParticipantDocumentRepository;
import com.spire.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase 2B participant-side document vault. Owns:
 *
 *   - upload validation (file size + content type + auth)
 *   - per-type persistence to the {@code documents} table
 *   - Not-Applicable markers and exception requests
 *   - completeness check + workflow transition to DOCUMENTS_SUBMITTED
 *   - Operations review (approve / reject with a reason, emailed)
 *
 * Actual byte-level storage is delegated to
 * {@link DocumentStorageService}. The split keeps the file-system /
 * Cloudinary concern out of the row-management code.
 *
 * Required document types (roadmap step 5: "minimum required documents
 * uploaded or exception approved"):
 *   GOVERNMENT_ID, WORK_AUTHORIZATION, RESUME
 * Each needs an upload that isn't rejected, or an exception the
 * participant asked for with a reason and Operations approved.
 *
 * Optional types (informational; do not gate progression; can simply
 * be marked not applicable):
 *   SSN_DOCUMENT, DRIVERS_LICENSE, OTHER
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentService {

    private static final long MAX_FILE_BYTES = 10L * 1024 * 1024; // 10 MB
    private static final Set<String> ACCEPTED_TYPES = Set.of(
            "application/pdf", "image/jpeg", "image/jpg", "image/png"
    );
    private static final Set<String> ACCEPTED_EXTENSIONS = Set.of(
            "pdf", "jpg", "jpeg", "png"
    );

    public static final List<String> REQUIRED_DOCUMENT_TYPES = List.of(
            "GOVERNMENT_ID", "WORK_AUTHORIZATION", "RESUME"
    );
    public static final List<String> OPTIONAL_DOCUMENT_TYPES = List.of(
            "SSN_DOCUMENT", "DRIVERS_LICENSE", "OTHER"
    );
    public static final Set<String> ALL_DOCUMENT_TYPES;
    static {
        Set<String> all = new HashSet<>();
        all.addAll(REQUIRED_DOCUMENT_TYPES);
        all.addAll(OPTIONAL_DOCUMENT_TYPES);
        ALL_DOCUMENT_TYPES = Set.copyOf(all);
    }

    // Review statuses. A file is PENDING, APPROVED or REJECTED; a
    // "not applicable" marker is NOT_APPLICABLE (optional documents) or,
    // for a required document, an exception Operations decides.
    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String NOT_APPLICABLE = "NOT_APPLICABLE";
    public static final String EXCEPTION_REQUESTED = "EXCEPTION_REQUESTED";
    public static final String EXCEPTION_APPROVED = "EXCEPTION_APPROVED";
    public static final String EXCEPTION_DECLINED = "EXCEPTION_DECLINED";

    /** What Operations still has to decide. */
    public static final List<String> NEEDS_REVIEW = List.of(PENDING, EXCEPTION_REQUESTED);

    static final int MIN_REASON = 5;
    static final int MAX_REASON = 1000;

    private final ParticipantDocumentRepository documentRepository;
    private final UserRepository userRepository;
    private final DocumentStorageService storageService;
    private final WorkflowService workflowService;
    private final RecordService recordService;
    private final ProfileCompletionService profileCompletionService;
    private final PermissionService permissionService;
    private final EmailTemplateService emailTemplateService;

    // ── Upload ───────────────────────────────────────────────────

    @Transactional
    public ParticipantDocument upload(Long userId, String documentType, MultipartFile file) {
        User user = requireGatedUser(userId);
        validateDocumentType(documentType);
        validateFile(file);

        // For single-instance required types we replace any existing
        // file rather than accumulate duplicates. The OTHER bucket
        // allows multiple, so we leave previous rows alone.
        boolean multiAllowed = "OTHER".equals(documentType);
        List<ParticipantDocument> replaced = multiAllowed ? List.of()
                : documentRepository.findByUserIdAndDocumentType(userId, documentType);

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            throw new IllegalArgumentException("Couldn't read uploaded file: " + e.getMessage());
        }
        String originalName = file.getOriginalFilename() == null
                ? "document" : file.getOriginalFilename();
        DocumentStorageService.StoredFile stored = storageService.upload(
                userId, originalName, bytes, file.getContentType());

        ParticipantDocument row = ParticipantDocument.builder()
                .userId(userId)
                .documentType(documentType)
                .fileName(originalName)
                .fileUrl(stored.url())
                .fileSize(file.getSize())
                .storagePath(stored.storagePath())
                .reviewStatus("PENDING")
                .retentionCategory(retentionFor(documentType))
                .notApplicable(false)
                .build();
        ParticipantDocument saved = documentRepository.save(row);

        // The new file is stored and its row saved: only now is the old one
        // replaced (it used to be deleted first, so a failed upload lost both).
        // Its file is removed once the change is committed.
        for (ParticipantDocument old : replaced) {
            documentRepository.delete(old);
            deleteFileAfterCommit(old.getStoragePath());
        }

        recordService.logAction(user.getId(), RecordService.Category.DOCUMENT,
                "Document uploaded: " + documentType,
                "user=" + userId + " name=" + originalName + " size=" + file.getSize(),
                Map.of(
                        "documentId", saved.getId(),
                        "documentType", documentType,
                        "fileName", originalName,
                        "fileSize", file.getSize()
                ));
        log.info("Document uploaded user={} type={} id={}", userId, documentType, saved.getId());
        return saved;
    }

    /** Removes a stored file once the current transaction commits (or now, outside one). */
    private void deleteFileAfterCommit(String storagePath) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            storageService.delete(storagePath);
                        }
                    });
        } else {
            storageService.delete(storagePath);
        }
    }

    // ── List / view / delete / N/A ───────────────────────────────

    @Transactional(readOnly = true)
    public List<ParticipantDocument> listForUser(Long userId) {
        return documentRepository.findByUserIdOrderByUploadedAtDesc(userId);
    }

    /**
     * Resolve a document for viewing. Who may see it is decided by
     * {@link PermissionService#canViewDocumentsOf}: the owner, the owner's
     * currently assigned ERM, or an Operations/System admin. Anyone else
     * (other participants, unassigned ERMs, coaches, finance) gets 403.
     * Every view by someone other than the owner is recorded on the
     * participant's audit trail — so this is NOT a read-only transaction:
     * the record is written in it, and a read-only connection refuses the
     * insert. If the record can't be written, the view fails (no silent
     * unlogged access to identity documents).
     */
    @Transactional
    public ParticipantDocument get(Long documentId, Long callerId) {
        ParticipantDocument doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document", "id", documentId));
        User viewer = callerId == null ? null : userRepository.findById(callerId).orElse(null);
        if (!permissionService.canViewDocument(viewer, doc.getUserId(), doc.getDocumentType())) {
            throw new AccessDeniedException("Not allowed to view this document");
        }
        if (!doc.getUserId().equals(callerId)) {
            String viewerRole = permissionService.roleOf(viewer);
            recordService.record(doc.getUserId(), "DOCUMENT_VIEWED", RecordService.Category.DOCUMENT,
                    "Document viewed by staff",
                    viewerRole + " user #" + callerId + " viewed the "
                            + (doc.getDocumentType() == null ? "document" : doc.getDocumentType()) + " document",
                    Map.of("documentId", documentId,
                            "documentType", doc.getDocumentType() == null ? "" : doc.getDocumentType(),
                            "viewerId", callerId,
                            "viewerRole", viewerRole));
        }
        return doc;
    }

    @Transactional
    public void delete(Long documentId, Long callerId) {
        ParticipantDocument doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document", "id", documentId));
        if (!doc.getUserId().equals(callerId)) {
            // 403, not 401: the website treats 401 as "sign in again".
            throw new org.springframework.security.access.AccessDeniedException("Not allowed to delete this document");
        }
        if (APPROVED.equals(doc.getReviewStatus())) {
            throw new IllegalArgumentException(
                    "This document has already been approved by Operations and can't be removed.");
        }
        User user = userRepository.findById(callerId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", callerId));
        // Once the agreement is signed, a required document can be
        // replaced but not taken away (the record stays audit-ready).
        if (Boolean.TRUE.equals(user.getAgreementComplete())
                && REQUIRED_DOCUMENT_TYPES.contains(doc.getDocumentType())
                && !REJECTED.equals(doc.getReviewStatus())
                && !EXCEPTION_DECLINED.equals(doc.getReviewStatus())) {
            throw new IllegalArgumentException(
                    "Your agreement is signed, so required documents can be replaced but not removed.");
        }
        storageService.delete(doc.getStoragePath());
        documentRepository.delete(doc);
        recordService.logAction(callerId, RecordService.Category.DOCUMENT,
                "Document removed: " + doc.getDocumentType(),
                "documentId=" + documentId, null);
        reopenIfIncomplete(user, "documents_reopened_removed",
                labelFor(doc.getDocumentType()) + " removed by the participant");
    }

    /**
     * "Not applicable". For an optional document it simply marks it
     * (NOT_APPLICABLE). A required document can't be skipped on the
     * participant's say-so: it becomes an exception request with their
     * reason, which counts only once Operations approves it.
     */
    @Transactional
    public ParticipantDocument markNotApplicable(Long userId, String documentType, String reason) {
        User user = requireGatedUser(userId);
        validateDocumentType(documentType);
        if ("OTHER".equals(documentType)) {
            throw new IllegalArgumentException(
                    "Additional documents are optional; just leave that section empty.");
        }
        boolean required = REQUIRED_DOCUMENT_TYPES.contains(documentType);
        String why = reason == null ? "" : reason.trim();
        if (required && why.length() < MIN_REASON) {
            throw new IllegalArgumentException(
                    "This document is required. Tell Operations why it doesn't apply to you, "
                            + "and they'll review your request.");
        }
        if (why.length() > MAX_REASON) {
            throw new IllegalArgumentException(
                    "Please keep the reason under " + MAX_REASON + " characters.");
        }
        List<ParticipantDocument> existing =
                documentRepository.findByUserIdAndDocumentType(userId, documentType);
        if (existing.stream().anyMatch(d -> APPROVED.equals(d.getReviewStatus())
                || EXCEPTION_APPROVED.equals(d.getReviewStatus()))) {
            throw new IllegalArgumentException(
                    "This document has already been approved by Operations and can't be changed.");
        }
        // Clear any previously-uploaded file (or earlier request) for the
        // same type — flipping to N/A means there's no file to present.
        for (ParticipantDocument old : existing) {
            storageService.delete(old.getStoragePath());
            documentRepository.delete(old);
        }
        ParticipantDocument row = ParticipantDocument.builder()
                .userId(user.getId())
                .documentType(documentType)
                .reviewStatus(required ? EXCEPTION_REQUESTED : NOT_APPLICABLE)
                .exceptionReason(why.isEmpty() ? null : why)
                .retentionCategory(retentionFor(documentType))
                .notApplicable(true)
                .build();
        ParticipantDocument saved = documentRepository.save(row);
        if (required) {
            recordService.logAction(userId, RecordService.Category.DOCUMENT,
                    "Document exception requested: " + documentType,
                    "documentId=" + saved.getId(),
                    Map.of("documentId", saved.getId(),
                            "documentType", documentType,
                            "reason", why));
        } else {
            recordService.logAction(userId, RecordService.Category.DOCUMENT,
                    "Document marked N/A: " + documentType,
                    "documentId=" + saved.getId(), null);
        }
        reopenIfIncomplete(user, "documents_reopened_exception",
                labelFor(documentType) + " is waiting for an Operations decision");
        return saved;
    }

    // ── Completeness + workflow transition ───────────────────────

    /**
     * Whether a row fulfils its document type: an upload that isn't
     * rejected, or an exception Operations approved. A pending or
     * declined exception, and an old unreviewed "N/A" on a required
     * document, don't count.
     */
    static boolean satisfiesRequirement(ParticipantDocument d) {
        if (Boolean.TRUE.equals(d.getNotApplicable())) {
            return EXCEPTION_APPROVED.equals(d.getReviewStatus());
        }
        return !REJECTED.equals(d.getReviewStatus());
    }

    /**
     * Returns the list of required types that are still missing for
     * the user (see {@link #satisfiesRequirement}). Empty list means
     * "ready to continue".
     */
    @Transactional(readOnly = true)
    public List<String> missingRequired(Long userId) {
        List<ParticipantDocument> docs = documentRepository.findByUserIdOrderByUploadedAtDesc(userId);
        Set<String> satisfied = new HashSet<>();
        for (ParticipantDocument d : docs) {
            if (satisfiesRequirement(d)) satisfied.add(d.getDocumentType());
        }
        return REQUIRED_DOCUMENT_TYPES.stream()
                .filter(t -> !satisfied.contains(t))
                .toList();
    }

    @Transactional
    public Map<String, Object> complete(Long userId) {
        User user = requireGatedUser(userId);
        List<String> missing = missingRequired(userId);
        if (!missing.isEmpty()) {
            boolean waiting = documentRepository.findByUserIdOrderByUploadedAtDesc(userId).stream()
                    .anyMatch(d -> missing.contains(d.getDocumentType())
                            && EXCEPTION_REQUESTED.equals(d.getReviewStatus()));
            return Map.of(
                    "success", false,
                    "missing", missing,
                    "message", waiting
                            ? "Operations still has to approve your \"not applicable\" request. "
                                    + "We'll email you when they do, or you can upload the document instead."
                            : "Please upload all required documents before continuing."
            );
        }
        profileCompletionService.markStepComplete(user, "DOCUMENTS");
        // The status the flags have really reached: DOCUMENTS_SUBMITTED
        // the first time, or further along when the step was reopened
        // after a program had already been chosen. Never moves back.
        workflowService.transition(user,
                WorkflowService.statusFromProfile(user),
                "docs_complete");
        String nextStep = !Boolean.TRUE.equals(user.getProgramSelectionComplete()) ? "/program-selection"
                : !Boolean.TRUE.equals(user.getAgreementComplete()) ? "/agreement"
                : "/dashboard";
        return Map.of(
                "success", true,
                "missing", List.of(),
                "nextStep", nextStep
        );
    }

    // ── Operations review ────────────────────────────────────────

    /**
     * Operations' decision on a document. {@code decision} is APPROVED or
     * REJECTED; on an exception request those mean approve / decline.
     * Sending something back needs a reason: the participant is emailed
     * which document and why, sees the reason on the upload page, and —
     * while the agreement isn't signed — the documents step reopens, so
     * the agreement waits for the fix (roadmap: "do not continue to
     * agreement until resolved or exception approved").
     */
    @Transactional
    public ParticipantDocument review(Long documentId, Long reviewerId,
                                      String decision, String notes) {
        if (!APPROVED.equals(decision) && !REJECTED.equals(decision)) {
            throw new IllegalArgumentException("Status must be APPROVED or REJECTED");
        }
        String reason = notes == null ? "" : notes.trim();
        if (reason.length() > MAX_REASON) {
            throw new IllegalArgumentException(
                    "Please keep the note under " + MAX_REASON + " characters.");
        }
        ParticipantDocument doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document", "id", documentId));
        String current = doc.getReviewStatus();
        boolean exception = Boolean.TRUE.equals(doc.getNotApplicable());
        if (exception && (current == null || !current.startsWith("EXCEPTION_"))) {
            throw new IllegalArgumentException(
                    "Nothing to review: this optional document was simply marked not applicable.");
        }
        String newStatus = !exception ? decision
                : APPROVED.equals(decision) ? EXCEPTION_APPROVED : EXCEPTION_DECLINED;
        boolean sendBack = REJECTED.equals(newStatus) || EXCEPTION_DECLINED.equals(newStatus);
        if (sendBack && reason.isEmpty()) {
            throw new IllegalArgumentException(
                    "Give the participant a reason. It's emailed to them and shown on their upload page.");
        }
        if (newStatus.equals(current) && reason.equals(doc.getReviewerNotes() == null ? "" : doc.getReviewerNotes())) {
            return doc;   // same decision again (double click): nothing new to record or send
        }

        doc.setReviewStatus(newStatus);
        doc.setReviewerId(reviewerId);
        doc.setReviewerNotes(reason.isEmpty() ? null : reason);
        doc.setReviewedAt(LocalDateTime.now());
        ParticipantDocument saved = documentRepository.save(doc);

        recordService.logAction(doc.getUserId(), RecordService.Category.DOCUMENT,
                "Document " + newStatus.toLowerCase().replace('_', ' ') + ": " + doc.getDocumentType(),
                "reviewer=" + reviewerId + (reason.isEmpty() ? "" : " notes=" + reason),
                Map.of(
                        "documentId", documentId,
                        "newStatus", newStatus,
                        "reviewerId", reviewerId,
                        "notes", reason
                ));

        User owner = userRepository.findById(doc.getUserId()).orElse(null);
        if (owner == null) return saved;
        String label = labelFor(doc.getDocumentType());
        if (sendBack) {
            if (REQUIRED_DOCUMENT_TYPES.contains(doc.getDocumentType())) {
                reopenIfIncomplete(owner, "documents_reopened_review",
                        label + " sent back by Operations: " + reason);
            }
            emailTemplateService.sendDocumentResubmitEmail(owner, label, reason,
                    EXCEPTION_DECLINED.equals(newStatus));
        } else if (EXCEPTION_APPROVED.equals(newStatus)) {
            emailTemplateService.sendDocumentExceptionApprovedEmail(owner, label);
        }
        return saved;
    }

    /** One row of the Operations document review screen. */
    public record ReviewRow(Long id, Long userId, String participantName, String participantEmail,
                            String participantId, String documentType, String documentLabel,
                            boolean required, String fileName, Long fileSize, String reviewStatus,
                            boolean notApplicable, String exceptionReason, String reviewerNotes,
                            LocalDateTime uploadedAt, LocalDateTime reviewedAt) {}

    /**
     * The Operations review screen. {@code filter}: NEEDS_REVIEW (uploads
     * waiting for a decision and exception requests, oldest first), ALL
     * (the latest 500), or a single review status.
     */
    @Transactional(readOnly = true)
    public List<ReviewRow> reviewQueue(String filter) {
        String f = filter == null || filter.isBlank() ? "NEEDS_REVIEW" : filter.trim().toUpperCase();
        List<ParticipantDocument> docs = switch (f) {
            case "NEEDS_REVIEW" -> documentRepository.findByReviewStatusInOrderByUploadedAtAsc(NEEDS_REVIEW);
            case "ALL" -> documentRepository.findTop500ByOrderByUploadedAtDesc();
            default -> documentRepository.findByReviewStatusInOrderByUploadedAtAsc(List.of(f));
        };
        Map<Long, User> owners = new HashMap<>();
        userRepository.findAllById(docs.stream().map(ParticipantDocument::getUserId).distinct().toList())
                .forEach(u -> owners.put(u.getId(), u));
        return docs.stream().map(d -> {
            User u = owners.get(d.getUserId());
            return new ReviewRow(d.getId(), d.getUserId(),
                    u == null ? null : u.getFullName(), u == null ? null : u.getEmail(),
                    u == null ? null : u.getParticipantId(),
                    d.getDocumentType(), labelFor(d.getDocumentType()),
                    REQUIRED_DOCUMENT_TYPES.contains(d.getDocumentType()),
                    d.getFileName(), d.getFileSize(), d.getReviewStatus(),
                    Boolean.TRUE.equals(d.getNotApplicable()), d.getExceptionReason(),
                    d.getReviewerNotes(), d.getUploadedAt(), d.getReviewedAt());
        }).toList();
    }

    /**
     * Keeps "documents step done" honest: while the agreement isn't
     * signed, a required document that is missing again (sent back,
     * waiting on an exception, or removed) reopens the step, and the
     * status goes back to what the flags really reach. A signed
     * agreement isn't unwound; the participant just uploads again.
     */
    public void reopenIfIncomplete(User user, String trigger, String notes) {
        if (!Boolean.TRUE.equals(user.getDocumentsComplete())) return;
        if (Boolean.TRUE.equals(user.getAgreementComplete())) return;
        if (missingRequired(user.getId()).isEmpty()) return;
        profileCompletionService.reopenStep(user, "DOCUMENTS", notes);
        WorkflowService.Status real = WorkflowService.statusFromProfile(user);
        if (workflowService.currentStatus(user).ordinal() > real.ordinal()) {
            workflowService.repair(user, real, trigger, notes);
        }
    }

    /** The name the upload page shows for a document type. */
    static String labelFor(String type) {
        if (type == null) return "document";
        return switch (type) {
            case "GOVERNMENT_ID" -> "Government-issued ID";
            case "WORK_AUTHORIZATION" -> "Work Authorization / Visa";
            case "RESUME" -> "Resume / CV";
            case "SSN_DOCUMENT" -> "SSN Document";
            case "DRIVERS_LICENSE" -> "Driving Licence";
            default -> "Additional Supporting Document";
        };
    }

    // ── Internals ────────────────────────────────────────────────

    private User requireGatedUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        // The acknowledgment step must really be done (its flag), not just
        // the status (which used to be jumped ahead at sign-up).
        if (!Boolean.TRUE.equals(user.getAcknowledgmentComplete())) {
            throw new IllegalStateException(
                    "Complete the acknowledgment step before uploading documents.");
        }
        return user;
    }

    private static void validateDocumentType(String documentType) {
        if (documentType == null || !ALL_DOCUMENT_TYPES.contains(documentType)) {
            throw new IllegalArgumentException("Unknown documentType: " + documentType);
        }
    }

    private static void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is required");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("File too large (max 10 MB)");
        }
        String ct = file.getContentType();
        if (ct != null && !ACCEPTED_TYPES.contains(ct.toLowerCase())) {
            // Some browsers send application/octet-stream for PDFs;
            // fall through to the extension check before rejecting.
            String name = file.getOriginalFilename();
            String ext = name != null && name.contains(".")
                    ? name.substring(name.lastIndexOf('.') + 1).toLowerCase()
                    : "";
            if (!ACCEPTED_EXTENSIONS.contains(ext)) {
                throw new IllegalArgumentException("Only PDF, JPG, or PNG files are allowed.");
            }
        }
        // Production-readiness review: check the content itself, since the
        // browser's label can be anything (or missing).
        try {
            if (DocumentStorageService.sniffContentType(file.getBytes()) == null) {
                throw new IllegalArgumentException("That file isn't a readable PDF, PNG or JPG.");
            }
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Couldn't read the uploaded file.");
        }
    }

    /**
     * Tags each document type with a retention bucket so future
     * compliance jobs (data-retention purges, GDPR exports) can
     * filter rows easily. Free-text — no enum constraint yet.
     */
    private static String retentionFor(String type) {
        return switch (type) {
            case "SSN_DOCUMENT" -> "SENSITIVE_PII";
            case "GOVERNMENT_ID", "DRIVERS_LICENSE", "WORK_AUTHORIZATION" -> "IDENTITY";
            case "RESUME" -> "PROFESSIONAL";
            default -> "GENERAL";
        };
    }
}
