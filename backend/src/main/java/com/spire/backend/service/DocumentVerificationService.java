package com.spire.backend.service;

import com.spire.backend.entity.ParticipantDocument;
import com.spire.backend.entity.ProgramSelection;
import com.spire.backend.entity.User;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.repository.ParticipantDocumentRepository;
import com.spire.backend.repository.ProgramSelectionRepository;
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
 * Asked for on 30 Sep: after the participant submits their documents
 * (and chooses their program), an ERM verifies them; only then is the
 * agreement emailed and can it be signed.
 *
 * The queue is shared by every ERM (no ERM is assigned yet at this
 * point); Operations and System admins can act on it too. Verifying
 * approves the uploads that were still waiting for review. A document
 * can instead be sent back with a reason, which reopens the documents
 * step (DocumentService) and clears any earlier verification.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentVerificationService {

    static final Set<String> VERIFIERS = Set.of("ERM", "OPERATIONS_ADMIN", "SYSTEM_ADMIN");

    private final UserRepository userRepository;
    private final ParticipantDocumentRepository documentRepository;
    private final ProgramSelectionRepository programSelectionRepository;
    private final DocumentService documentService;
    private final EmailTemplateService emailTemplateService;
    private final RecordService recordService;
    private final PermissionService permissionService;

    /** One row of the verification queue. */
    public record Row(Long userId, String fullName, String email, String participantId,
                      String program, String skillset, LocalDateTime readySince,
                      LocalDateTime verifiedAt, String verifiedByName) {}

    /** A document as the verifier sees it (never the SSN document for an ERM). */
    public record Doc(Long id, String documentType, String label, String fileName, Long fileSize,
                      String reviewStatus, boolean notApplicable, String exceptionReason,
                      String reviewerNotes, LocalDateTime uploadedAt) {}

    /** Documents done and program chosen, not signed yet, not verified. */
    static boolean waiting(User u) {
        return isParticipant(u)
                && Boolean.TRUE.equals(u.getDocumentsComplete())
                && Boolean.TRUE.equals(u.getProgramSelectionComplete())
                && !Boolean.TRUE.equals(u.getAgreementComplete())
                && u.getErmVerifiedAt() == null;
    }

    /** Verified, agreement emailed, not signed yet. */
    static boolean agreementSent(User u) {
        return isParticipant(u) && u.getErmVerifiedAt() != null && !Boolean.TRUE.equals(u.getAgreementComplete());
    }

    private static boolean isParticipant(User u) {
        return !Boolean.FALSE.equals(u.getIsActive()) && u.getParticipantId() != null && !u.getParticipantId().isBlank();
    }

    /** {@code status}: WAITING (default) or SENT (verified, agreement not signed yet). */
    @Transactional(readOnly = true)
    public List<Row> queue(Long callerId, String status) {
        requireVerifier(callerId);
        boolean sent = "SENT".equalsIgnoreCase(status);
        List<User> users = userRepository.findAll().stream()
                .filter(u -> sent ? agreementSent(u) : waiting(u))
                .toList();
        Map<Long, String> names = new LinkedHashMap<>();
        userRepository.findAllById(users.stream().map(User::getErmVerifiedBy)
                        .filter(java.util.Objects::nonNull).distinct().toList())
                .forEach(u -> names.put(u.getId(), u.getFullName()));
        return users.stream()
                .map(u -> {
                    ProgramSelection ps = programSelectionRepository
                            .findFirstByUserIdOrderBySelectionDateDesc(u.getId()).orElse(null);
                    return new Row(u.getId(), u.getFullName(), u.getEmail(), u.getParticipantId(),
                            ps == null ? null : ps.getProgram(),
                            ps == null ? u.getSelectedTechnology() : ps.getSkillset(),
                            ps == null ? u.getUpdatedAt() : ps.getSelectionDate(),
                            u.getErmVerifiedAt(), names.get(u.getErmVerifiedBy()));
                })
                .sorted(Comparator.comparing(Row::readySince, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /** Everything the verifier needs: who, what they chose, and their documents. */
    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long callerId, Long participantId) {
        User caller = requireVerifier(callerId);
        User u = participant(participantId);
        boolean erm = "ERM".equals(permissionService.roleOf(caller));
        ProgramSelection ps = programSelectionRepository.findFirstByUserIdOrderBySelectionDateDesc(u.getId()).orElse(null);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", u.getId());
        out.put("fullName", u.getFullName());
        out.put("email", u.getEmail());
        out.put("phone", u.getPhone());
        out.put("participantId", u.getParticipantId());
        out.put("location", u.getLocation());
        out.put("waiting", waiting(u));
        out.put("verifiedAt", u.getErmVerifiedAt());
        out.put("agreementSigned", Boolean.TRUE.equals(u.getAgreementComplete()));
        Map<String, Object> program = new LinkedHashMap<>();
        if (ps != null) {
            program.put("program", ps.getProgram());
            program.put("phase", ps.getPhase());
            program.put("skillset", ps.getSkillset());
            program.put("targetJobTitle", ps.getTargetJobTitle());
            program.put("availability", ps.getAvailability());
        }
        out.put("programSelection", program);
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

    /** Verifies the documents and program: approves waiting uploads and emails the agreement. */
    @Transactional
    public boolean verify(Long callerId, Long participantId) {
        User caller = requireVerifier(callerId);
        User u = participant(participantId);
        if (Boolean.TRUE.equals(u.getAgreementComplete())) {
            throw new IllegalStateException("This participant has already signed the agreement.");
        }
        if (u.getErmVerifiedAt() != null) {
            throw new IllegalStateException("Already verified; the agreement was sent.");
        }
        if (!Boolean.TRUE.equals(u.getDocumentsComplete()) || !documentService.missingRequired(u.getId()).isEmpty()) {
            throw new IllegalStateException("Their documents aren't complete yet.");
        }
        if (!Boolean.TRUE.equals(u.getProgramSelectionComplete())) {
            throw new IllegalStateException("They haven't chosen their program yet.");
        }
        for (ParticipantDocument d : documentRepository.findByUserIdOrderByUploadedAtDesc(u.getId())) {
            if (DocumentService.PENDING.equals(d.getReviewStatus())) {
                documentService.review(d.getId(), callerId, DocumentService.APPROVED, null);
            }
        }
        u.setErmVerifiedAt(LocalDateTime.now());
        u.setErmVerifiedBy(callerId);
        userRepository.save(u);
        recordService.record(u.getId(), "DOCUMENTS_VERIFIED", RecordService.Category.DOCUMENT,
                "Documents verified; agreement sent",
                "Verified by " + caller.getFullName() + " (" + permissionService.roleOf(caller) + " user #" + callerId + ")",
                Map.of("verifiedBy", callerId));
        boolean sent = false;
        try {
            sent = emailTemplateService.sendAgreementReadyEmail(u);
        } catch (Exception e) {
            log.warn("Agreement-ready email for user {} failed: {}", u.getId(), e.getMessage());
        }
        log.info("User {} verified participant {}'s documents; agreement email sent: {}", callerId, u.getId(), sent);
        return sent;
    }

    /** Sends one document back with a reason (emailed); the documents step reopens. */
    @Transactional
    public void sendBack(Long callerId, Long participantId, Long documentId, String reason) {
        requireVerifier(callerId);
        User u = participant(participantId);
        ParticipantDocument d = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document", "id", documentId));
        if (!d.getUserId().equals(u.getId())) {
            throw new IllegalArgumentException("That document belongs to someone else.");
        }
        if (Boolean.TRUE.equals(u.getAgreementComplete())) {
            throw new IllegalStateException("This participant has already signed; ask Operations to review documents now.");
        }
        documentService.review(documentId, callerId, DocumentService.REJECTED, reason);
    }

    /**
     * Tells every active ERM that a participant is ready to be verified
     * (documents done and program chosen). Called when the last of the two
     * is finished, including after a document was sent back and fixed.
     */
    public void notifyReady(Long participantId) {
        User u = userRepository.findById(participantId).orElse(null);
        if (u == null || !waiting(u)) return;
        String program = programSelectionRepository.findFirstByUserIdOrderBySelectionDateDesc(u.getId())
                .map(ProgramSelection::getProgram).orElse(null);
        userRepository.findAll().stream()
                .filter(s -> !Boolean.FALSE.equals(s.getIsActive()))
                .filter(s -> "ERM".equals(permissionService.roleOf(s)))
                .forEach(erm -> {
                    try {
                        emailTemplateService.sendDocumentsToVerifyEmail(erm, u, program);
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
            throw new AccessDeniedException("Only an ERM or an Operations admin can verify documents.");
        }
        return caller;
    }
}
