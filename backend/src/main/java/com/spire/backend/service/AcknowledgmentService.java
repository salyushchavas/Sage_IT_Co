package com.spire.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spire.backend.dto.AcknowledgmentSubmitRequest;
import com.spire.backend.entity.Acknowledgment;
import com.spire.backend.entity.User;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.exception.UnauthorizedException;
import com.spire.backend.repository.AcknowledgmentRepository;
import com.spire.backend.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phase 2A — handles submission of the participant's
 * "Acknowledgment of Interest and Program Acceptance" form
 * (Step 4 of the 9-step lifecycle).
 *
 * Responsibilities:
 *   1. Gate the call on workflow state (status &gt;= ID_EMAIL_SENT).
 *   2. Validate the three consent flags + legal name + signature.
 *   3. Persist an immutable {@link Acknowledgment} row carrying the
 *      typed name, captured signature, consent JSON, and request
 *      audit fields (IP / UA / device).
 *   4. Transition the user to ACKNOWLEDGMENT_ACCEPTED.
 *
 * Idempotent: a re-submission while the user is already at
 * ACKNOWLEDGMENT_ACCEPTED or later returns the existing row rather
 * than writing a duplicate.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AcknowledgmentService {

    /** Free-form audit value stored on the {@link Acknowledgment} row. */
    public static final String TYPE_INTEREST_AND_ACCEPTANCE = "INTEREST_AND_ACCEPTANCE";

    private final AcknowledgmentRepository acknowledgmentRepository;
    private final UserRepository userRepository;
    private final WorkflowService workflowService;
    private final RecordService recordService;
    private final ProfileCompletionService profileCompletionService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional
    public Acknowledgment submit(Long userId,
                                 AcknowledgmentSubmitRequest req,
                                 HttpServletRequest httpRequest) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        // ── Gate ────────────────────────────────────────────────────
        if (!workflowService.isStatusAtLeast(user, WorkflowService.Status.ID_EMAIL_SENT)) {
            throw new UnauthorizedException(
                    "Complete email verification and receive your participant ID first.");
        }

        // ── Idempotent return if already accepted ───────────────────
        if (workflowService.isStatusAtLeast(user, WorkflowService.Status.ACKNOWLEDGMENT_ACCEPTED)) {
            return acknowledgmentRepository
                    .findByUserIdAndAcknowledgmentType(userId, TYPE_INTEREST_AND_ACCEPTANCE)
                    .stream()
                    .findFirst()
                    .orElseGet(() -> persist(user, req, httpRequest));
        }

        // ── Validate ────────────────────────────────────────────────
        if (req.getLegalName() == null
                || req.getLegalName().trim().split("\\s+").length < 2) {
            throw new IllegalArgumentException(
                    "Please enter your full legal name (first and last).");
        }
        if (req.getSignatureImage() == null || req.getSignatureImage().isBlank()) {
            throw new IllegalArgumentException(
                    "Please add your digital signature before submitting.");
        }
        if (!req.getSignatureImage().startsWith("data:image/")) {
            throw new IllegalArgumentException("Signature must be an image (PNG / JPG).");
        }
        // 2 MB cap on the encoded payload — matches the agreement flow.
        if (req.getSignatureImage().length() > 2_800_000) {
            throw new IllegalArgumentException("Signature image is too large (max 2 MB).");
        }
        if (!Boolean.TRUE.equals(req.getInterestAccepted())
                || !Boolean.TRUE.equals(req.getDocumentationConsent())
                || !Boolean.TRUE.equals(req.getCommunicationConsent())) {
            throw new IllegalArgumentException(
                    "All three consents are required to proceed.");
        }

        Acknowledgment saved = persist(user, req, httpRequest);

        // ── Workflow transition ─────────────────────────────────────
        workflowService.transition(user,
                WorkflowService.Status.ACKNOWLEDGMENT_ACCEPTED,
                "acknowledgment");

        // ── Progressive profile flag (Phase 1C) ─────────────────────
        profileCompletionService.markStepComplete(user, "ACKNOWLEDGMENT");

        recordService.record(user.getId(), "ACKNOWLEDGMENT_ACCEPTED",
                RecordService.Category.ACCOUNT,
                "Acknowledgment accepted",
                "User accepted acknowledgment " + req.getAcknowledgmentVersion(),
                Map.of(
                        "acknowledgmentId", saved.getId(),
                        "version", req.getAcknowledgmentVersion(),
                        "legalName", req.getLegalName(),
                        "signatureMethod", req.getSignatureMethod() == null
                                ? "draw" : req.getSignatureMethod()
                ),
                httpRequest);

        log.info("Acknowledgment {} accepted by user {} (version {})",
                saved.getId(), user.getId(), req.getAcknowledgmentVersion());
        return saved;
    }

    /**
     * Builds + saves the {@link Acknowledgment} row. Split out from
     * {@code submit} so the idempotent re-call path can reuse it
     * without re-running validation.
     */
    private Acknowledgment persist(User user,
                                   AcknowledgmentSubmitRequest req,
                                   HttpServletRequest httpRequest) {
        String consentsJson = serialiseConsents(req);
        String ip = clientIp(httpRequest);
        String ua = httpRequest == null ? null : httpRequest.getHeader("User-Agent");
        String device = deviceFromUa(ua);
        String method = req.getSignatureMethod() == null ? "draw" : req.getSignatureMethod().toLowerCase();
        if (!"draw".equals(method) && !"upload".equals(method)) method = "draw";

        Acknowledgment row = Acknowledgment.builder()
                .userId(user.getId())
                .acknowledgmentType(TYPE_INTEREST_AND_ACCEPTANCE)
                .legalName(req.getLegalName().trim())
                .acceptedTextVersion(req.getAcknowledgmentVersion())
                .consentFlags(consentsJson)
                .ipAddress(ip)
                .userAgent(ua)
                .device(device)
                .signatureImage(req.getSignatureImage())
                .signatureMethod(method)
                .build();
        return acknowledgmentRepository.save(row);
    }

    private String serialiseConsents(AcknowledgmentSubmitRequest req) {
        Map<String, Object> consents = new LinkedHashMap<>();
        consents.put("interest", Boolean.TRUE.equals(req.getInterestAccepted()));
        consents.put("documentation", Boolean.TRUE.equals(req.getDocumentationConsent()));
        consents.put("communication", Boolean.TRUE.equals(req.getCommunicationConsent()));
        try {
            return objectMapper.writeValueAsString(consents);
        } catch (JsonProcessingException e) {
            return "{\"interest\":true,\"documentation\":true,\"communication\":true}";
        }
    }

    /**
     * Pulls the client IP from {@code X-Forwarded-For} (first hop)
     * or falls back to the socket remote. Mirrors the agreement
     * controller's helper so the audit trail is consistent.
     */
    private static String clientIp(HttpServletRequest request) {
        if (request == null) return null;
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return request.getRemoteAddr();
    }

    /** Crude device classification — same idea as AgreementService.parseUserAgent. */
    private static String deviceFromUa(String ua) {
        if (ua == null || ua.isBlank()) return null;
        if (ua.contains("Mobile") || ua.contains("Android") || ua.contains("iPhone")) return "mobile";
        if (ua.contains("iPad") || ua.contains("Tablet")) return "tablet";
        return "desktop";
    }
}
