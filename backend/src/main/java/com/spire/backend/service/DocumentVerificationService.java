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

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * After a participant submits their documents, an ERM checks every one and
 * confirms them (or sends one back with a reason); only then does the
 * program step open (ProgramSelectionService). "Verified" is worked out
 * from the documents' review statuses (DocumentService.documentsVerified),
 * so confirming simply approves the files that were waiting.
 *
 * Every ERM sees the queue (no ERM is assigned yet at this point);
 * Operations and System admins can act on it too.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentVerificationService {

    static final Set<String> VERIFIERS = Set.of("ERM", "OPERATIONS_ADMIN", "SYSTEM_ADMIN");

    private final UserRepository userRepository;
    private final ParticipantDocumentRepository documentRepository;
    private final DocumentService documentService;
    private final EmailTemplateService emailTemplateService;
    private final RecordService recordService;
    private final PermissionService permissionService;

    /** One row of the queue. */
    public record Row(Long userId, String fullName, String email, String participantId,
                      String course, LocalDateTime submittedAt, int documentCount, int sentBack) {}

    /** A document as the ERM sees it (never the SSN document for an ERM). */
    public record Doc(Long id, String documentType, String label, String fileName, Long fileSize,
                      String reviewStatus, boolean notApplicable, String exceptionReason,
                      String reviewerNotes, LocalDateTime uploadedAt) {}

    /** Documents submitted, program not chosen yet. */
    private static boolean atThisStep(User u) {
        return !Boolean.FALSE.equals(u.getIsActive())
                && u.getParticipantId() != null && !u.getParticipantId().isBlank()
                && Boolean.TRUE.equals(u.getDocumentsComplete())
                && !Boolean.TRUE.equals(u.getProgramSelectionComplete());
    }

    /** {@code status}: WAITING (default; still to check) or VERIFIED (confirmed, program not chosen yet). */
    @Transactional(readOnly = true)
    public List<Row> queue(Long callerId, String status) {
        requireVerifier(callerId);
        boolean verified = "VERIFIED".equalsIgnoreCase(status);
        return userRepository.findAll().stream()
                .filter(DocumentVerificationService::atThisStep)
                .filter(u -> documentService.documentsVerified(u.getId()) == verified)
                .map(u -> {
                    List<ParticipantDocument> docs = documentRepository.findByUserIdOrderByUploadedAtDesc(u.getId());
                    LocalDateTime latest = docs.stream().map(ParticipantDocument::getUploadedAt)
                            .filter(java.util.Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
                    return new Row(u.getId(), u.getFullName(), u.getEmail(), u.getParticipantId(),
                            u.getSelectedTechnology(), latest, (int) docs.stream()
                                    .filter(d -> !Boolean.TRUE.equals(d.getNotApplicable())).count(),
                            documentService.sentBackCount(u.getId()));
                })
                .sorted(Comparator.comparing(Row::submittedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long callerId, Long participantId) {
        User caller = requireVerifier(callerId);
        User u = participant(participantId);
        boolean erm = "ERM".equals(permissionService.roleOf(caller));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", u.getId());
        out.put("fullName", u.getFullName());
        out.put("email", u.getEmail());
        out.put("phone", u.getPhone());
        out.put("participantId", u.getParticipantId());
        out.put("course", u.getSelectedTechnology());
        out.put("location", u.getLocation());
        out.put("verified", documentService.documentsVerified(u.getId()));
        out.put("documents", documentRepository.findByUserIdOrderByUploadedAtDesc(u.getId()).stream()
                .filter(d -> !erm || !PermissionService.ERM_HIDDEN_DOCUMENT_TYPES.contains(d.getDocumentType()))
                .map(d -> new Doc(d.getId(), d.getDocumentType(), DocumentService.labelFor(d.getDocumentType()),
                        d.getFileName(), d.getFileSize(), d.getReviewStatus(),
                        Boolean.TRUE.equals(d.getNotApplicable()), d.getExceptionReason(),
                        d.getReviewerNotes(), d.getUploadedAt()))
                .toList());
        out.put("missing", documentService.missingRequired(u.getId()).stream().map(DocumentService::labelFor).toList());
        return out;
    }

    /**
     * Confirms the documents: approves every file still waiting for review.
     * Returns whether the "documents verified" email went out.
     */
    @Transactional
    public boolean confirm(Long callerId, Long participantId) {
        User caller = requireVerifier(callerId);
        User u = participant(participantId);
        if (!atThisStep(u)) {
            throw new IllegalStateException(Boolean.TRUE.equals(u.getProgramSelectionComplete())
                    ? "They've already moved on to their program."
                    : "Their documents aren't submitted yet.");
        }
        if (documentService.sentBackCount(u.getId()) > 0) {
            throw new IllegalStateException("A document was sent back. Wait for the participant's new copy.");
        }
        if (!documentService.missingRequired(u.getId()).isEmpty()) {
            throw new IllegalStateException("A required document is missing or was sent back.");
        }
        if (documentService.documentsVerified(u.getId())) {
            throw new IllegalStateException("Already confirmed.");
        }
        for (ParticipantDocument d : documentRepository.findByUserIdOrderByUploadedAtDesc(u.getId())) {
            if (DocumentService.PENDING.equals(d.getReviewStatus())) {
                documentService.review(d.getId(), callerId, DocumentService.APPROVED, null);
            }
        }
        recordService.record(u.getId(), "DOCUMENTS_VERIFIED", RecordService.Category.DOCUMENT,
                "Documents confirmed",
                "Confirmed by " + caller.getFullName() + " (" + permissionService.roleOf(caller) + " user #" + callerId + ")",
                Map.of("confirmedBy", callerId));
        boolean sent = false;
        try {
            sent = emailTemplateService.sendDocumentsVerifiedEmail(u);
        } catch (Exception e) {
            log.warn("Documents-verified email for user {} failed: {}", u.getId(), e.getMessage());
        }
        return sent;
    }

    /** Sends one document back with a reason (emailed, and shown on the dashboard). */
    @Transactional
    public void sendBack(Long callerId, Long participantId, Long documentId, String reason) {
        requireVerifier(callerId);
        User u = participant(participantId);
        ParticipantDocument d = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document", "id", documentId));
        if (!d.getUserId().equals(u.getId())) {
            throw new IllegalArgumentException("That document belongs to someone else.");
        }
        if (Boolean.TRUE.equals(d.getNotApplicable())) {
            throw new IllegalArgumentException("That is a not-applicable request; Operations decides those.");
        }
        documentService.review(documentId, callerId, DocumentService.REJECTED, reason);
    }

    /** Tells every active ERM that a participant's documents are waiting to be checked. */
    public void notifyErms(Long participantId) {
        User u = userRepository.findById(participantId).orElse(null);
        if (u == null || !atThisStep(u) || documentService.documentsVerified(u.getId())) return;
        userRepository.findAll().stream()
                .filter(s -> !Boolean.FALSE.equals(s.getIsActive()))
                .filter(s -> "ERM".equals(permissionService.roleOf(s)))
                .forEach(erm -> {
                    try {
                        emailTemplateService.sendDocumentsToVerifyEmail(erm, u);
                    } catch (Exception e) {
                        log.warn("Couldn't tell ERM {} about participant {}: {}", erm.getId(), u.getId(), e.getMessage());
                    }
                });
    }

    private User participant(Long id) {
        User u = userRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("User", "id", id));
        if (u.getParticipantId() == null || u.getParticipantId().isBlank()) {
            throw new IllegalArgumentException("That isn't a participant.");
        }
        return u;
    }

    private User requireVerifier(Long callerId) {
        User caller = callerId == null ? null : userRepository.findById(callerId).orElse(null);
        if (caller == null || !VERIFIERS.contains(permissionService.roleOf(caller))) {
            throw new AccessDeniedException("Only an ERM or an Operations admin can check documents.");
        }
        return caller;
    }
}
