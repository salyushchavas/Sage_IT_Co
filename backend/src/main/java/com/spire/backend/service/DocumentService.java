package com.spire.backend.service;

import com.spire.backend.entity.ParticipantDocument;
import com.spire.backend.entity.User;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.exception.UnauthorizedException;
import com.spire.backend.repository.ParticipantDocumentRepository;
import com.spire.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase 2B participant-side document vault. Owns:
 *
 *   - upload validation (file size + content type + auth)
 *   - per-type persistence to the {@code documents} table
 *   - Not-Applicable marker insertion
 *   - completeness check + workflow transition to DOCUMENTS_SUBMITTED
 *
 * Actual byte-level storage is delegated to
 * {@link DocumentStorageService}. The split keeps the file-system /
 * Cloudinary concern out of the row-management code.
 *
 * Required document types (must be uploaded OR marked N/A before
 * Continue is allowed):
 *   GOVERNMENT_ID, WORK_AUTHORIZATION, RESUME
 *
 * Optional types (informational; do not gate progression):
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

    private final ParticipantDocumentRepository documentRepository;
    private final UserRepository userRepository;
    private final DocumentStorageService storageService;
    private final WorkflowService workflowService;
    private final RecordService recordService;
    private final ProfileCompletionService profileCompletionService;

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
        if (!multiAllowed) {
            List<ParticipantDocument> existing = documentRepository
                    .findByUserIdAndDocumentType(userId, documentType);
            for (ParticipantDocument old : existing) {
                storageService.delete(old.getStoragePath());
                documentRepository.delete(old);
            }
        }

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

    // ── List / view / delete / N/A ───────────────────────────────

    @Transactional(readOnly = true)
    public List<ParticipantDocument> listForUser(Long userId) {
        return documentRepository.findByUserIdOrderByUploadedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public ParticipantDocument get(Long documentId, Long callerId, boolean isAdmin) {
        ParticipantDocument doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document", "id", documentId));
        if (!isAdmin && !doc.getUserId().equals(callerId)) {
            throw new UnauthorizedException("Not allowed to view this document");
        }
        return doc;
    }

    @Transactional
    public void delete(Long documentId, Long callerId) {
        ParticipantDocument doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document", "id", documentId));
        if (!doc.getUserId().equals(callerId)) {
            throw new UnauthorizedException("Not allowed to delete this document");
        }
        if ("APPROVED".equals(doc.getReviewStatus())) {
            throw new IllegalArgumentException(
                    "This document has already been approved by Operations and can't be removed.");
        }
        storageService.delete(doc.getStoragePath());
        documentRepository.delete(doc);
        recordService.logAction(callerId, RecordService.Category.DOCUMENT,
                "Document removed: " + doc.getDocumentType(),
                "documentId=" + documentId, null);
    }

    @Transactional
    public ParticipantDocument markNotApplicable(Long userId, String documentType) {
        User user = requireGatedUser(userId);
        validateDocumentType(documentType);
        // Clear any previously-uploaded file for the same type — a
        // user flipping to N/A means they no longer have a file to
        // present.
        for (ParticipantDocument old : documentRepository.findByUserIdAndDocumentType(userId, documentType)) {
            storageService.delete(old.getStoragePath());
            documentRepository.delete(old);
        }
        ParticipantDocument row = ParticipantDocument.builder()
                .userId(user.getId())
                .documentType(documentType)
                .reviewStatus("NOT_APPLICABLE")
                .retentionCategory(retentionFor(documentType))
                .notApplicable(true)
                .build();
        ParticipantDocument saved = documentRepository.save(row);
        recordService.logAction(userId, RecordService.Category.DOCUMENT,
                "Document marked N/A: " + documentType,
                "documentId=" + saved.getId(), null);
        return saved;
    }

    // ── Completeness + workflow transition ───────────────────────

    /**
     * Returns the list of required types that are still missing for
     * the user (neither uploaded nor marked N/A). Empty list means
     * "ready to continue".
     */
    @Transactional(readOnly = true)
    public List<String> missingRequired(Long userId) {
        List<ParticipantDocument> docs = documentRepository.findByUserIdOrderByUploadedAtDesc(userId);
        Set<String> satisfied = new HashSet<>();
        for (ParticipantDocument d : docs) {
            // Any row (uploaded OR marked N/A) satisfies the type as
            // long as it isn't an outright REJECTED file.
            if (!"REJECTED".equals(d.getReviewStatus())) {
                satisfied.add(d.getDocumentType());
            }
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
            return Map.of(
                    "success", false,
                    "missing", missing,
                    "message", "Please upload all required documents before continuing."
            );
        }
        workflowService.transition(user,
                WorkflowService.Status.DOCUMENTS_SUBMITTED,
                "docs_complete");
        profileCompletionService.markStepComplete(user, "DOCUMENTS");
        return Map.of(
                "success", true,
                "missing", List.of(),
                "nextStep", "/program-selection"
        );
    }

    // ── Admin review ─────────────────────────────────────────────

    @Transactional
    public ParticipantDocument review(Long documentId, Long reviewerId,
                                      String newStatus, String notes) {
        if (!"APPROVED".equals(newStatus) && !"REJECTED".equals(newStatus)) {
            throw new IllegalArgumentException("Status must be APPROVED or REJECTED");
        }
        ParticipantDocument doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document", "id", documentId));
        doc.setReviewStatus(newStatus);
        doc.setReviewerId(reviewerId);
        doc.setReviewerNotes(notes);
        doc.setReviewedAt(LocalDateTime.now());
        ParticipantDocument saved = documentRepository.save(doc);

        recordService.logAction(doc.getUserId(), RecordService.Category.DOCUMENT,
                "Document " + newStatus.toLowerCase() + ": " + doc.getDocumentType(),
                "reviewer=" + reviewerId + (notes == null ? "" : " notes=" + notes),
                Map.of(
                        "documentId", documentId,
                        "newStatus", newStatus,
                        "reviewerId", reviewerId,
                        "notes", notes == null ? "" : notes
                ));
        return saved;
    }

    // ── Internals ────────────────────────────────────────────────

    private User requireGatedUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        if (!workflowService.isStatusAtLeast(user,
                WorkflowService.Status.ACKNOWLEDGMENT_ACCEPTED)) {
            throw new UnauthorizedException(
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
