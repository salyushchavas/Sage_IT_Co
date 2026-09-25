package com.spire.backend.service;

import com.spire.backend.entity.AgreementAcceptance;
import com.spire.backend.entity.User;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.repository.AgreementAcceptanceRepository;
import com.spire.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/**
 * Checklist 2.2: the signed agreement PDF is kept safely and can be
 * downloaded — by the participant, their assigned ERM, and Operations /
 * System admins (the same people who may open their documents). Staff
 * downloads are recorded on the participant's audit trail.
 *
 * The PDF is kept through {@link DocumentStorageService} (Cloudinary when
 * configured), not on the server's own disk, which is wiped on every
 * deploy. An older signature whose PDF was lost is re-rendered from its
 * stored record on first download and kept from then on.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SignedAgreementService {

    static final String LEGACY_URL_PREFIX = "/api/agreement/signed-pdf/";
    private static final String LEGACY_DIR = "signed-agreements";

    private final AgreementAcceptanceRepository agreementRepository;
    private final UserRepository userRepository;
    private final PermissionService permissionService;
    private final DocumentStorageService storageService;
    private final AgreementPdfService agreementPdfService;
    private final RecordService recordService;
    private final EmailTemplateService emailTemplateService;

    /** A download: a short-lived link (Cloudinary) or the PDF bytes. */
    public record SignedPdf(String url, byte[] bytes, String fileName) {}

    /** Renders the signed PDF for a verified row, keeps it, and records where. Returns the bytes. */
    @Transactional
    public byte[] keep(AgreementAcceptance row) {
        byte[] bytes = agreementPdfService.renderSignedBytes(row);
        row.setPdfSha256(sha256(bytes));
        try {
            DocumentStorageService.StoredFile stored = storageService.upload(
                    row.getUser().getId(), "signed-agreement.pdf", bytes, "application/pdf");
            row.setSignedAgreementPdfUrl(stored.url());
            row.setPdfStoragePath(stored.storagePath());
        } catch (Exception e) {
            log.warn("Couldn't keep the signed agreement PDF for user {}: {}",
                    row.getUser().getId(), e.getMessage());
        }
        agreementRepository.save(row);
        return bytes;
    }

    /** The signed agreement of {@code participantUserId}, for {@code callerId}. */
    @Transactional
    public SignedPdf forDownload(Long participantUserId, Long callerId) {
        User viewer = callerId == null ? null : userRepository.findById(callerId).orElse(null);
        if (!permissionService.canViewDocumentsOf(viewer, participantUserId)) {
            throw new AccessDeniedException("Not allowed to open this agreement");
        }
        AgreementAcceptance row = agreementRepository.findByUserId(participantUserId)
                .filter(r -> AgreementService.STATUS_VERIFIED.equals(r.getStatus()))
                .orElseThrow(() -> new ResourceNotFoundException("Signed agreement", "userId", participantUserId));
        if (!participantUserId.equals(callerId)) {
            String role = permissionService.roleOf(viewer);
            recordService.record(participantUserId, "AGREEMENT_VIEWED", RecordService.Category.DOCUMENT,
                    "Signed agreement viewed by staff",
                    role + " user #" + callerId + " opened the signed agreement",
                    Map.of("viewerId", callerId, "viewerRole", role));
        }
        String fileName = "Sage-IT-Co-Agreement-"
                + (row.getParticipantIdSnapshot() != null ? row.getParticipantIdSnapshot() : participantUserId)
                + ".pdf";
        String stored = row.getSignedAgreementPdfUrl();
        DocumentStorageService.Retrieval kept = null;
        try {
            kept = storageService.retrieve(stored);
        } catch (Exception e) {
            log.warn("Couldn't read the kept agreement PDF for user {}: {}", participantUserId, e.getMessage());
        }
        if (kept != null && kept.url() != null) {
            return new SignedPdf(kept.url(), null, fileName);
        }
        byte[] bytes = kept != null ? kept.bytes() : readLocal(stored);
        if (bytes == null) {
            bytes = keep(row);
            recordService.logAction(participantUserId, RecordService.Category.DOCUMENT,
                    "Signed agreement PDF re-created",
                    "The kept copy was missing; re-rendered from the signed record", null);
        }
        return new SignedPdf(null, bytes, fileName);
    }

    /**
     * Roadmap step 10 (checklist 2.3): the signed agreement goes to the
     * participant's ERM — an email with a secure link to their dashboard,
     * where they can open it and mark it reviewed. Once per ERM: a new ERM
     * after a reassignment gets it too. False when there is no signed
     * agreement; the email's own outcome is in the email log.
     */
    @Transactional
    public boolean routeToErm(User participant, User erm) {
        if (participant == null || erm == null) return false;
        AgreementAcceptance row = agreementRepository.findByUserId(participant.getId())
                .filter(r -> AgreementService.STATUS_VERIFIED.equals(r.getStatus()))
                .orElse(null);
        if (row == null) return false;
        if (erm.getId().equals(row.getErmRoutedTo()) && row.getErmRoutedAt() != null) return true;
        String signedOn = row.getAcceptedAt() == null ? "" : row.getAcceptedAt().format(SIGNED_ON);
        boolean emailed = emailTemplateService.sendSignedAgreementToErmEmail(
                erm, participant, row.getProgramSnapshot(), signedOn);
        row.setErmRoutedTo(erm.getId());
        row.setErmRoutedAt(java.time.LocalDateTime.now());
        row.setErmNotified(true);
        row.setErmReviewedAt(null);   // a new ERM reviews it afresh
        row.setErmReviewedBy(null);
        agreementRepository.save(row);
        recordService.record(participant.getId(), "AGREEMENT_ROUTED_TO_ERM", RecordService.Category.ACCOUNT,
                "Signed agreement sent to ERM",
                "Routed to " + erm.getFullName() + (emailed ? "" : " (the email didn't go out; see the email log)"),
                Map.of("ermUserId", erm.getId(), "emailed", emailed));
        return true;
    }

    /** The assigned ERM confirms they reviewed the signed agreement. */
    @Transactional
    public AgreementAcceptance markReviewedByErm(Long participantUserId, Long ermUserId) {
        User erm = userRepository.findById(ermUserId).orElse(null);
        if (!permissionService.isAssignedErmFor(erm, participantUserId)) {
            throw new AccessDeniedException("You are not the assigned ERM for this participant.");
        }
        AgreementAcceptance row = agreementRepository.findByUserId(participantUserId)
                .filter(r -> AgreementService.STATUS_VERIFIED.equals(r.getStatus()))
                .orElseThrow(() -> new ResourceNotFoundException("Signed agreement", "userId", participantUserId));
        if (row.getErmReviewedAt() != null) return row;
        row.setErmReviewedAt(java.time.LocalDateTime.now());
        row.setErmReviewedBy(ermUserId);
        agreementRepository.save(row);
        recordService.record(participantUserId, "AGREEMENT_REVIEWED_BY_ERM", RecordService.Category.ACCOUNT,
                "Signed agreement reviewed by ERM",
                "ERM user #" + ermUserId + " reviewed the signed agreement",
                Map.of("ermUserId", ermUserId));
        return row;
    }

    private static final java.time.format.DateTimeFormatter SIGNED_ON =
            java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH);

    /** Bytes of a copy kept on local disk (DocumentStorageService or the old signed-agreements folder). */
    private static byte[] readLocal(String stored) {
        if (stored == null || stored.isBlank()) return null;
        String path = stored.startsWith(LEGACY_URL_PREFIX)
                ? LEGACY_DIR + "/" + stored.substring(stored.lastIndexOf('/') + 1)
                : stored;
        try {
            Path p = Path.of(path);
            return Files.isRegularFile(p) ? Files.readAllBytes(p) : null;
        } catch (Exception e) {
            return null;
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
