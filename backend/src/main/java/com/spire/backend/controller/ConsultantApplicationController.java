package com.spire.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.spire.backend.dto.AgreementContent;
import com.spire.backend.dto.ApiResponse;
import com.spire.backend.entity.ConsultantApplication;
import com.spire.backend.entity.ConsultantApplicationEvent;
import com.spire.backend.entity.ConsultantApplicationRevision;
import com.spire.backend.exception.UnauthorizedException;
import com.spire.backend.security.AgreementAuthz;
import com.spire.backend.security.ConsultantRateLimiter;
import com.spire.backend.security.JwtService;
import com.spire.backend.service.AgreementContentService;
import com.spire.backend.service.AgreementDocumentService;
import com.spire.backend.service.ConsultantApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Two scoping segments:
 *   /api/agreement-erm/applications/**     -- operator console, gated
 *                                              by ROLE_AGREEMENT_ERM.
 *   /api/consultant/applications/**        -- consultant himself,
 *                                              public + rate-limited.
 *                                              The UUID applicationId
 *                                              is the credential.
 *
 * Both bases are deliberately separate from the marketing site and
 * from Sage's regular /erm-dashboard surface. The consultant base
 * has no auth header at all -- a leak of an application UUID gives
 * an attacker access to that single application until it expires
 * (7 days) or is cancelled. The rate limiter and audit log mitigate
 * the blast radius.
 */
@RestController
@RequiredArgsConstructor
public class ConsultantApplicationController {

    private final ConsultantApplicationService consultantService;
    private final ConsultantRateLimiter rateLimiter;
    private final AgreementDocumentService agreementDocumentService;
    private final AgreementContentService agreementContentService;
    private final JwtService jwtService;

    /**
     * Portal-phase consultant gate. The token represents the verified
     * PERSON (email), not a single appId. Authorization is per-request:
     * the token's email claim must match the addressed application's
     * {@code consultantEmail} (case-insensitive). Mismatch is treated
     * as "agreement does not exist" -- 404, not 403 -- so a token
     * holder can't probe for other consultants' agreement IDs.
     *
     * Returns the verified, lower-cased email so the caller can also
     * stamp audit / access metadata against it.
     */
    private String requireConsultantToken(String appId, HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            throw new UnauthorizedException("Verification required.");
        }
        String token = auth.substring(7);
        if (!jwtService.isTokenValid(token)
                || !"consultant".equals(jwtService.extractTokenType(token))) {
            throw new UnauthorizedException("Verification required.");
        }
        String tokenEmail = jwtService.extractConsultantEmail(token);
        if (tokenEmail == null || tokenEmail.isBlank()) {
            throw new UnauthorizedException("Verification required.");
        }
        String normalised = tokenEmail.trim().toLowerCase();
        // Email-match guard: an agreement addressed to a different
        // consultant looks like it doesn't exist to this token holder.
        ConsultantApplication app = consultantService.getByApplicationId(appId);
        // Build L — soft-deleted agreements are gone for the consultant
        // too. The row + audit are still in the DB so an operator can
        // recover them, but every consultant-side endpoint behaves as
        // if the agreement never existed.
        if (Boolean.TRUE.equals(app.getDeleted())) {
            throw new com.spire.backend.exception.ResourceNotFoundException(
                    "ConsultantApplication", "applicationId", appId);
        }
        String onRecord = app.getConsultantEmail();
        if (onRecord == null
                || !onRecord.trim().toLowerCase().equals(normalised)) {
            throw new com.spire.backend.exception.ResourceNotFoundException(
                    "ConsultantApplication", "applicationId", appId);
        }
        return normalised;
    }

    // ── Agreement-ERM-side (requires ROLE_AGREEMENT_ERM) ────────────

    @PostMapping("/api/agreement-erm/applications")
    @PreAuthorize("hasRole('AGREEMENT_ERM')")
    public ResponseEntity<ApiResponse<ConsultantApplication>> create(
            @RequestBody CreateBody body,
            HttpServletRequest request) {
        ConsultantApplication app = consultantService.createApplication(
                body.consultantEmail,
                body.consultantName,
                body.firstName,
                body.middleName,
                body.lastName,
                body.consultantPhone,
                body.ratePeriod1,
                body.rateAmount1,
                body.ratePeriod2,
                body.rateAmount2,
                body.visaStatus,
                body.visaStatusOther,
                body.requireAppendix1,
                body.requireAppendix2,
                body.requireAppendix3,
                body.requireAppendix4,
                body.requireAppendix5,
                body.requireSsn,
                body.achDebitDates,
                body.achDebitAmounts,
                body.technologyTrack,
                body.customScopeNotes,
                body.payload,
                // Authenticated agreement user, stamped as the owner.
                AgreementAuthz.userId(request),
                request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Consultant application created", app));
    }

    @GetMapping("/api/agreement-erm/applications")
    @PreAuthorize("hasRole('AGREEMENT_ERM')")
    public ResponseEntity<ApiResponse<PageResponse<ConsultantApplication>>> list(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            HttpServletRequest request) {
        Pageable pageable = PageRequest.of(
                Math.max(0, page),
                Math.min(100, Math.max(1, size)),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        // Phase B — per-ERM isolation: an ERM gets only their own rows;
        // the super-admin gets all.
        Page<ConsultantApplication> result = consultantService.listApplications(
                status, pageable,
                AgreementAuthz.userId(request), AgreementAuthz.roleEnum(request));
        return ResponseEntity.ok(ApiResponse.success(PageResponse.from(result)));
    }

    /**
     * 3B — approval status board: agreements awaiting / in-revision / ready
     * across the approval gate, with their per-approver history. Owner-
     * scoped (super-admin sees all). The frontend splits Phase 1 / Phase 2.
     */
    @GetMapping("/api/agreement-erm/approval-board")
    @PreAuthorize("hasRole('AGREEMENT_ERM')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> approvalBoard(
            HttpServletRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                consultantService.approvalBoard(
                        AgreementAuthz.userId(request),
                        AgreementAuthz.roleEnum(request))));
    }

    @GetMapping("/api/agreement-erm/applications/{appId}")
    @PreAuthorize("hasRole('AGREEMENT_ERM')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> get(
            @PathVariable String appId,
            HttpServletRequest request) {
        ConsultantApplication app = consultantService.getByApplicationId(appId);
        // Non-owner ERM is treated as if the application doesn't exist (404).
        consultantService.assertErmCanAccess(app, request);
        List<ConsultantApplicationEvent> events = consultantService.listEvents(appId);
        List<ConsultantApplicationRevision> revisions =
                consultantService.listRevisions(appId);

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("application", app);
        view.put("events", events);
        view.put("revisions", revisions);
        // 3B — per-approver gate history (Manager/Accounts decisions).
        view.put("approvals", consultantService.listApprovals(appId));
        return ResponseEntity.ok(ApiResponse.success(view));
    }

    @PutMapping("/api/agreement-erm/applications/{appId}")
    @PreAuthorize("hasRole('AGREEMENT_ERM')")
    public ResponseEntity<ApiResponse<ConsultantApplication>> update(
            @PathVariable String appId,
            @RequestBody UpdateBody body,
            HttpServletRequest request) {
        ConsultantApplication app = consultantService.updateApplication(
                appId,
                body.consultantEmail, body.consultantName, body.consultantPhone,
                body.payload, request);
        return ResponseEntity.ok(ApiResponse.success("Updated", app));
    }

    @PostMapping("/api/agreement-erm/applications/{appId}/cancel")
    @PreAuthorize("hasRole('AGREEMENT_ERM')")
    public ResponseEntity<ApiResponse<ConsultantApplication>> cancel(
            @PathVariable String appId,
            HttpServletRequest request) {
        ConsultantApplication app = consultantService.cancel(appId, request);
        return ResponseEntity.ok(ApiResponse.success("Cancelled", app));
    }

    @PostMapping("/api/agreement-erm/applications/{appId}/resend-invite")
    @PreAuthorize("hasRole('AGREEMENT_ERM')")
    public ResponseEntity<ApiResponse<Map<String, String>>> resendInvite(
            @PathVariable String appId,
            HttpServletRequest request) {
        consultantService.resendInvite(appId, request);
        return ResponseEntity.ok(ApiResponse.success(
                Map.of("message", "Invite re-sent")));
    }

    /**
     * Phase C feature 1 — owner ERM (or super-admin) fixes a wrong
     * consultant email / name so a typo'd address never leaves a
     * consultant stuck. Ownership-gated (non-owner → 404).
     */
    @PatchMapping("/api/agreement-erm/applications/{appId}/consultant-contact")
    @PreAuthorize("hasRole('AGREEMENT_ERM')")
    public ResponseEntity<ApiResponse<ConsultantApplication>> updateConsultantContact(
            @PathVariable String appId,
            @RequestBody ConsultantContactBody body,
            HttpServletRequest request) {
        ConsultantApplication app = consultantService.updateConsultantContact(
                appId,
                body == null ? null : body.consultantEmail,
                body == null ? null : body.consultantName,
                request);
        return ResponseEntity.ok(ApiResponse.success("Consultant contact updated", app));
    }

    // ── Agreement-ERM side: two-stage workflow (Phase 3) ────────────

    @PostMapping("/api/agreement-erm/applications/{appId}/request-revision")
    @PreAuthorize("hasRole('AGREEMENT_ERM')")
    public ResponseEntity<ApiResponse<ConsultantApplication>> ermRequestRevision(
            @PathVariable String appId,
            @RequestBody RequestRevisionBody body,
            HttpServletRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Revision requested",
                consultantService.ermRequestRevision(
                        appId, body == null ? null : body.sections, request)));
    }

    /**
     * 3B — ERM "Send for Approval". Routes a consultant-signed,
     * consultant-version-released agreement to the phase's required
     * approvers (Phase 1 = Manager; Phase 2 = Manager + Accounts). Also
     * used to RE-SEND after an approver requested a revision (resets all
     * required approvers to PENDING for a fresh round).
     */
    @PostMapping("/api/agreement-erm/applications/{appId}/send-for-approval")
    @PreAuthorize("hasRole('AGREEMENT_ERM')")
    public ResponseEntity<ApiResponse<ConsultantApplication>> sendForApproval(
            @PathVariable String appId,
            HttpServletRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Sent for approval",
                consultantService.sendForApproval(appId, request)));
    }

    @PostMapping("/api/agreement-erm/applications/{appId}/approve-and-sign")
    @PreAuthorize("hasRole('AGREEMENT_ERM')")
    public ResponseEntity<ApiResponse<ConsultantApplication>> approveAndSign(
            @PathVariable String appId,
            @RequestBody ApproveAndSignBody body,
            HttpServletRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Approved and signed",
                consultantService.ermApproveAndSign(
                        appId, body.ermName, body.ermTitle,
                        body.ermSignatureBase64, request)));
    }

    /**
     * Build T — ERM releases the CONSULTANT-VERSION PDF (consultant
     * signatures only, NO ERM signature) with an appended Certificate
     * of Completion. Distinct from {@link #approveAndSign}: this does
     * NOT countersign and does NOT move the row to COMPLETED — the
     * state stays VERIFIED until the ERM separately countersigns.
     */
    @PostMapping("/api/agreement-erm/applications/{appId}/approve-consultant-version")
    @PreAuthorize("hasRole('AGREEMENT_ERM')")
    public ResponseEntity<ApiResponse<ConsultantApplication>> approveConsultantVersion(
            @PathVariable String appId,
            HttpServletRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Consultant copy released",
                consultantService.ermApproveConsultantVersion(appId, request)));
    }

    /**
     * Build M — owner ERM (or super-admin) advances a Phase-1
     * COMPLETED agreement to Phase 2 on the SAME document. Promoted
     * sections flip to required; data is preserved; signatures +
     * affirmations clear; status transitions back to SUBMITTED with
     * the 15-day invite window restarted.
     */
    @PostMapping("/api/agreement-erm/applications/{appId}/advance-to-phase-2")
    @PreAuthorize("hasRole('AGREEMENT_ERM')")
    public ResponseEntity<ApiResponse<ConsultantApplication>> advanceToPhase2(
            @PathVariable String appId,
            @RequestBody(required = false) ConsultantApplicationService.Phase2Promotion body,
            HttpServletRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Advanced to Phase 2",
                consultantService.advanceToPhase2(appId, body, request)));
    }

    @PostMapping("/api/agreement-erm/applications/{appId}/send-email")
    @PreAuthorize("hasRole('AGREEMENT_ERM')")
    public ResponseEntity<Void> sendEmail(
            @PathVariable String appId,
            @RequestBody SendEmailBody body,
            HttpServletRequest request) {
        consultantService.sendPdfToCustomRecipient(
                appId, body.recipientEmail, body.note, request);
        return ResponseEntity.noContent().build();
    }

    /**
     * Streams the PDF bytes through the backend. The Cloudinary URL
     * never leaves the server, so View Inline / Download buttons can't
     * leak a token-in-URL to anyone watching the operator's browser
     * (right-click "Copy link", history scrape, address-bar copy,
     * shoulder-surf).
     *
     * Disposition default is {@code inline} -- browsers render the PDF
     * in-tab. Pass {@code ?disposition=attachment} to force a save
     * dialog. Either way the filename is the human-readable
     * {@code SageITCO-Agreement_Name_Track.pdf} pattern.
     *
     * Falls back to the pre-Phase-7 {@code finalPdfUrl} when no
     * {@code finalPdfPublicId} is on the row, and to the legacy
     * single-stage {@code signedPdfUrl} when neither final field is
     * set. 404 when nothing's available.
     */
    @GetMapping("/api/agreement-erm/applications/{appId}/download-pdf")
    @PreAuthorize("hasRole('AGREEMENT_ERM')")
    public ResponseEntity<byte[]> downloadPdf(
            @PathVariable String appId,
            @RequestParam(value = "disposition", required = false) String disposition,
            HttpServletRequest request) {
        ConsultantApplication app = consultantService.getByApplicationId(appId);
        // Phase B — the critical ownership check: an authenticated ERM-B
        // streaming ERM-A's appId must get 404 (treated as nonexistent),
        // even though the bytes are backend-streamed.
        consultantService.assertErmCanAccess(app, request);

        // Final PDF first; only fall back to the legacy intermediate
        // signedPdfUrl when no final PDF exists at all (pre-Phase-3
        // single-stage rows). Always re-sign on the fly: stored
        // {@code finalPdfUrl} carries a per-upload signature that
        // 401s on later GET in prod.
        boolean hasFinal = app.getFinalPdfPublicId() != null
                || (app.getFinalPdfUrl() != null && !app.getFinalPdfUrl().isBlank());
        String sourceUrl;
        if (hasFinal) {
            String publicId = app.getFinalPdfPublicId();
            if (publicId == null || publicId.isBlank()) {
                publicId = AgreementDocumentService.derivePublicId(appId);
            }
            sourceUrl = agreementDocumentService.signedPdfUrl(
                    publicId, java.time.Duration.ofMinutes(5));
        } else if (app.getSignedPdfUrl() != null && !app.getSignedPdfUrl().isBlank()) {
            sourceUrl = app.getSignedPdfUrl();
        } else {
            return ResponseEntity.notFound().build();
        }

        byte[] bytes;
        try {
            java.net.URLConnection conn = new java.net.URL(sourceUrl).openConnection();
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(30_000);
            try (java.io.InputStream in = conn.getInputStream()) {
                bytes = in.readAllBytes();
            }
        } catch (java.io.IOException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }

        String filename = AgreementDocumentService.buildPdfFilename(app);
        String dispositionMode = "attachment".equalsIgnoreCase(disposition)
                ? "attachment"
                : "inline";
        // Cache-Control: private,no-store -- corporate proxies and
        // shared-browser histories don't retain the bytes.
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(bytes.length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        dispositionMode + "; filename=\"" + filename + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(bytes);
    }

    // ── Build G: Appendix 5 cheque upload + ERM view ────────────────

    /**
     * Consultant uploads their Appendix 5 security cheque. Email-scoped
     * token + ownership-gated (a token holder addressing someone else's
     * appId gets 404 via {@link #requireConsultantToken}). Stores the
     * bytes in Cloudinary, persists the public_id + content type, and
     * audits CHEQUE_UPLOADED with the caller IP.
     */
    @PostMapping("/api/consultant/applications/{appId}/cheque")
    public ResponseEntity<ApiResponse<Map<String, String>>> uploadCheque(
            @PathVariable String appId,
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request) {
        requireConsultantToken(appId, request);
        if (!rateLimiter.allowWrite(appId)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error("Too many requests. Try again in a minute."));
        }
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("File is required."));
        }
        try {
            consultantService.uploadCheque(
                    appId, file.getBytes(), file.getContentType(), request);
        } catch (java.io.IOException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(ApiResponse.error("Couldn't read uploaded file."));
        }
        return ResponseEntity.ok(ApiResponse.success(
                Map.of("message", "Cheque uploaded.")));
    }

    /** ERM-side inline view / download of the consultant's cheque. */
    @GetMapping("/api/agreement-erm/applications/{appId}/cheque")
    @PreAuthorize("hasRole('AGREEMENT_ERM')")
    public ResponseEntity<byte[]> ermViewCheque(
            @PathVariable String appId,
            @RequestParam(value = "disposition", required = false) String disposition,
            HttpServletRequest request) {
        ConsultantApplication app = consultantService.getByApplicationId(appId);
        consultantService.assertErmCanAccess(app, request);
        return streamCheque(appId, disposition);
    }

    /** Consultant-side preview of their own uploaded cheque (audit + re-confirm UX). */
    @GetMapping("/api/consultant/applications/{appId}/cheque")
    public ResponseEntity<byte[]> consultantViewCheque(
            @PathVariable String appId,
            @RequestParam(value = "disposition", required = false) String disposition,
            HttpServletRequest request) {
        requireConsultantToken(appId, request);
        return streamCheque(appId, disposition);
    }

    /**
     * Build U — per-cheque upload. The consultant /fill page POSTs one
     * call per cheque entry (index 0, 1, …). Each entry lands at
     * {@code agreements/{appId}-cheque-{index}} in Cloudinary; the
     * cheques JSON column on the row tracks per-cheque metadata.
     */
    @PostMapping("/api/consultant/applications/{appId}/cheques/{index}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> uploadChequeAtIndex(
            @PathVariable String appId,
            @PathVariable int index,
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request) {
        requireConsultantToken(appId, request);
        if (!rateLimiter.allowWrite(appId)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error("Too many requests. Try again in a minute."));
        }
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("File is required."));
        }
        try {
            consultantService.uploadChequeAt(
                    appId, index, file.getBytes(), file.getContentType(), request);
        } catch (java.io.IOException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(ApiResponse.error("Couldn't read uploaded file."));
        }
        return ResponseEntity.ok(ApiResponse.success(
                Map.of("message", "Cheque " + (index + 1) + " uploaded.",
                        "index", index)));
    }

    /**
     * Build U — per-cheque metadata patch. Updates only the number +
     * date for the given index; the uploaded bytes (if any) survive
     * the patch.
     */
    @PutMapping("/api/consultant/applications/{appId}/cheques/{index}")
    public ResponseEntity<ApiResponse<ConsultantApplication>> patchChequeMetadata(
            @PathVariable String appId,
            @PathVariable int index,
            @RequestBody ConsultantApplicationService.ChequeMetadataPatch body,
            HttpServletRequest request) {
        requireConsultantToken(appId, request);
        if (!rateLimiter.allowWrite(appId)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error("Too many requests. Try again in a minute."));
        }
        return ResponseEntity.ok(ApiResponse.success(
                "Saved",
                consultantService.setChequeMetadata(appId, index, body, request)));
    }

    /** Consultant-side preview of one specific cheque by index. */
    @GetMapping("/api/consultant/applications/{appId}/cheques/{index}")
    public ResponseEntity<byte[]> consultantViewChequeAtIndex(
            @PathVariable String appId,
            @PathVariable int index,
            @RequestParam(value = "disposition", required = false) String disposition,
            HttpServletRequest request) {
        requireConsultantToken(appId, request);
        return streamChequeAt(appId, index, disposition);
    }

    /** ERM-side inline view / download of one specific cheque by index. */
    @GetMapping("/api/agreement-erm/applications/{appId}/cheques/{index}")
    @PreAuthorize("hasRole('AGREEMENT_ERM')")
    public ResponseEntity<byte[]> ermViewChequeAtIndex(
            @PathVariable String appId,
            @PathVariable int index,
            @RequestParam(value = "disposition", required = false) String disposition,
            HttpServletRequest request) {
        ConsultantApplication app = consultantService.getByApplicationId(appId);
        consultantService.assertErmCanAccess(app, request);
        return streamChequeAt(appId, index, disposition);
    }

    // ── Build W — Appendix 1 work-authorization document upload ───────

    /** Consultant uploads their Appendix 1 work-authorization copy. */
    @PostMapping("/api/consultant/applications/{appId}/workauth")
    public ResponseEntity<ApiResponse<Map<String, Object>>> uploadWorkAuthDoc(
            @PathVariable String appId,
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request) {
        requireConsultantToken(appId, request);
        if (!rateLimiter.allowWrite(appId)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error("Too many requests. Try again in a minute."));
        }
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("File is required."));
        }
        try {
            consultantService.uploadWorkAuthDoc(
                    appId, file.getBytes(), file.getContentType(), request);
        } catch (java.io.IOException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(ApiResponse.error("Couldn't read uploaded file."));
        }
        return ResponseEntity.ok(ApiResponse.success(
                Map.of("message", "Work-authorization document uploaded.")));
    }

    /** Consultant-side preview of their uploaded work-authorization doc. */
    @GetMapping("/api/consultant/applications/{appId}/workauth")
    public ResponseEntity<byte[]> consultantViewWorkAuthDoc(
            @PathVariable String appId,
            @RequestParam(value = "disposition", required = false) String disposition,
            HttpServletRequest request) {
        requireConsultantToken(appId, request);
        return streamWorkAuthDoc(appId, disposition);
    }

    /** ERM-side inline view / download of the work-authorization doc. */
    @GetMapping("/api/agreement-erm/applications/{appId}/workauth")
    @PreAuthorize("hasRole('AGREEMENT_ERM')")
    public ResponseEntity<byte[]> ermViewWorkAuthDoc(
            @PathVariable String appId,
            @RequestParam(value = "disposition", required = false) String disposition,
            HttpServletRequest request) {
        ConsultantApplication app = consultantService.getByApplicationId(appId);
        consultantService.assertErmCanAccess(app, request);
        return streamWorkAuthDoc(appId, disposition);
    }

    private ResponseEntity<byte[]> streamWorkAuthDoc(String appId, String disposition) {
        ConsultantApplicationService.ChequeBytes doc;
        try {
            doc = consultantService.fetchWorkAuthDocBytes(appId);
        } catch (java.io.IOException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
        if (doc == null || doc.bytes() == null || doc.bytes().length == 0) {
            return ResponseEntity.notFound().build();
        }
        String contentType = doc.contentType();
        if (contentType == null || contentType.isBlank()) {
            contentType = "application/octet-stream";
        }
        boolean isPdf = "application/pdf".equalsIgnoreCase(contentType);
        String ext = isPdf ? ".pdf" : ".img";
        String filename = "SageITCO-WorkAuth_" + appId + ext;
        String dispositionMode = "attachment".equalsIgnoreCase(disposition)
                ? "attachment" : "inline";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(doc.bytes().length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        dispositionMode + "; filename=\"" + filename + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(doc.bytes());
    }

    // ── Build I — Phase 2 Employment offer-letter upload ──────────────

    /** Consultant uploads their Phase 2 Employment offer letter. */
    @PostMapping("/api/consultant/applications/{appId}/offer-letter")
    public ResponseEntity<ApiResponse<Map<String, Object>>> uploadOfferLetter(
            @PathVariable String appId,
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request) {
        requireConsultantToken(appId, request);
        if (!rateLimiter.allowWrite(appId)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error("Too many requests. Try again in a minute."));
        }
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("File is required."));
        }
        try {
            consultantService.uploadOfferLetter(
                    appId, file.getBytes(), file.getContentType(), request);
        } catch (java.io.IOException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(ApiResponse.error("Couldn't read uploaded file."));
        }
        return ResponseEntity.ok(ApiResponse.success(
                Map.of("message", "Offer letter uploaded.")));
    }

    /** Consultant-side preview of their uploaded offer letter. */
    @GetMapping("/api/consultant/applications/{appId}/offer-letter")
    public ResponseEntity<byte[]> consultantViewOfferLetter(
            @PathVariable String appId,
            @RequestParam(value = "disposition", required = false) String disposition,
            HttpServletRequest request) {
        requireConsultantToken(appId, request);
        return streamOfferLetter(appId, disposition);
    }

    /** ERM-side inline view / download of the offer letter. */
    @GetMapping("/api/agreement-erm/applications/{appId}/offer-letter")
    @PreAuthorize("hasRole('AGREEMENT_ERM')")
    public ResponseEntity<byte[]> ermViewOfferLetter(
            @PathVariable String appId,
            @RequestParam(value = "disposition", required = false) String disposition,
            HttpServletRequest request) {
        ConsultantApplication app = consultantService.getByApplicationId(appId);
        consultantService.assertErmCanAccess(app, request);
        return streamOfferLetter(appId, disposition);
    }

    private ResponseEntity<byte[]> streamOfferLetter(String appId, String disposition) {
        ConsultantApplicationService.ChequeBytes doc;
        try {
            doc = consultantService.fetchOfferLetterBytes(appId);
        } catch (java.io.IOException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
        if (doc == null || doc.bytes() == null || doc.bytes().length == 0) {
            return ResponseEntity.notFound().build();
        }
        String contentType = doc.contentType();
        if (contentType == null || contentType.isBlank()) {
            contentType = "application/octet-stream";
        }
        return streamDoc(doc, "SageITCO-OfferLetter_" + appId, disposition);
    }

    // ── Build J — Background Check document uploads (DL/State-ID + SSN) ──

    /** Consultant uploads their Driver's-License / State-ID document. */
    @PostMapping("/api/consultant/applications/{appId}/dl-doc")
    public ResponseEntity<ApiResponse<Map<String, Object>>> uploadDlDoc(
            @PathVariable String appId,
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request) {
        requireConsultantToken(appId, request);
        if (!rateLimiter.allowWrite(appId)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error("Too many requests. Try again in a minute."));
        }
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("File is required."));
        }
        try {
            consultantService.uploadDlDoc(appId, file.getBytes(), file.getContentType(), request);
        } catch (java.io.IOException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(ApiResponse.error("Couldn't read uploaded file."));
        }
        return ResponseEntity.ok(ApiResponse.success(
                Map.of("message", "Driver's License / State ID document uploaded.")));
    }

    @GetMapping("/api/consultant/applications/{appId}/dl-doc")
    public ResponseEntity<byte[]> consultantViewDlDoc(
            @PathVariable String appId,
            @RequestParam(value = "disposition", required = false) String disposition,
            HttpServletRequest request) {
        requireConsultantToken(appId, request);
        return streamDlDoc(appId, disposition);
    }

    @GetMapping("/api/agreement-erm/applications/{appId}/dl-doc")
    @PreAuthorize("hasRole('AGREEMENT_ERM')")
    public ResponseEntity<byte[]> ermViewDlDoc(
            @PathVariable String appId,
            @RequestParam(value = "disposition", required = false) String disposition,
            HttpServletRequest request) {
        ConsultantApplication app = consultantService.getByApplicationId(appId);
        consultantService.assertErmCanAccess(app, request);
        return streamDlDoc(appId, disposition);
    }

    private ResponseEntity<byte[]> streamDlDoc(String appId, String disposition) {
        ConsultantApplicationService.ChequeBytes doc;
        try {
            doc = consultantService.fetchDlDocBytes(appId);
        } catch (java.io.IOException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
        return streamDoc(doc, "SageITCO-IDDoc_" + appId, disposition);
    }

    /** Consultant uploads their (optional) SSN document. */
    @PostMapping("/api/consultant/applications/{appId}/ssn-doc")
    public ResponseEntity<ApiResponse<Map<String, Object>>> uploadSsnDoc(
            @PathVariable String appId,
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request) {
        requireConsultantToken(appId, request);
        if (!rateLimiter.allowWrite(appId)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error("Too many requests. Try again in a minute."));
        }
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("File is required."));
        }
        try {
            consultantService.uploadSsnDoc(appId, file.getBytes(), file.getContentType(), request);
        } catch (java.io.IOException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(ApiResponse.error("Couldn't read uploaded file."));
        }
        return ResponseEntity.ok(ApiResponse.success(
                Map.of("message", "SSN document uploaded.")));
    }

    @GetMapping("/api/consultant/applications/{appId}/ssn-doc")
    public ResponseEntity<byte[]> consultantViewSsnDoc(
            @PathVariable String appId,
            @RequestParam(value = "disposition", required = false) String disposition,
            HttpServletRequest request) {
        requireConsultantToken(appId, request);
        return streamSsnDoc(appId, disposition);
    }

    @GetMapping("/api/agreement-erm/applications/{appId}/ssn-doc")
    @PreAuthorize("hasRole('AGREEMENT_ERM')")
    public ResponseEntity<byte[]> ermViewSsnDoc(
            @PathVariable String appId,
            @RequestParam(value = "disposition", required = false) String disposition,
            HttpServletRequest request) {
        ConsultantApplication app = consultantService.getByApplicationId(appId);
        consultantService.assertErmCanAccess(app, request);
        return streamSsnDoc(appId, disposition);
    }

    private ResponseEntity<byte[]> streamSsnDoc(String appId, String disposition) {
        ConsultantApplicationService.ChequeBytes doc;
        try {
            doc = consultantService.fetchSsnDocBytes(appId);
        } catch (java.io.IOException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
        return streamDoc(doc, "SageITCO-SSNDoc_" + appId, disposition);
    }

    /** Shared streamer for an uploaded document (inline by default). */
    private ResponseEntity<byte[]> streamDoc(
            ConsultantApplicationService.ChequeBytes doc, String filenameBase, String disposition) {
        if (doc == null || doc.bytes() == null || doc.bytes().length == 0) {
            return ResponseEntity.notFound().build();
        }
        String contentType = doc.contentType();
        if (contentType == null || contentType.isBlank()) {
            contentType = "application/octet-stream";
        }
        boolean isPdf = "application/pdf".equalsIgnoreCase(contentType);
        String filename = filenameBase + (isPdf ? ".pdf" : ".img");
        String dispositionMode = "attachment".equalsIgnoreCase(disposition)
                ? "attachment" : "inline";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(doc.bytes().length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        dispositionMode + "; filename=\"" + filename + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(doc.bytes());
    }

    private ResponseEntity<byte[]> streamChequeAt(String appId, int index, String disposition) {
        ConsultantApplicationService.ChequeBytes cheque;
        try {
            cheque = consultantService.fetchChequeBytesAt(appId, index);
        } catch (java.io.IOException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
        if (cheque == null || cheque.bytes() == null || cheque.bytes().length == 0) {
            return ResponseEntity.notFound().build();
        }
        String contentType = cheque.contentType();
        if (contentType == null || contentType.isBlank()) {
            contentType = "application/octet-stream";
        }
        boolean isPdf = "application/pdf".equalsIgnoreCase(contentType);
        String ext = isPdf ? ".pdf" : ".img";
        String filename = "SageITCO-Cheque-" + (index + 1) + "_" + appId + ext;
        String dispositionMode = "attachment".equalsIgnoreCase(disposition)
                ? "attachment" : "inline";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(cheque.bytes().length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        dispositionMode + "; filename=\"" + filename + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(cheque.bytes());
    }

    private ResponseEntity<byte[]> streamCheque(String appId, String disposition) {
        ConsultantApplicationService.ChequeBytes cheque;
        try {
            cheque = consultantService.fetchChequeBytes(appId);
        } catch (java.io.IOException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
        if (cheque == null || cheque.bytes() == null || cheque.bytes().length == 0) {
            return ResponseEntity.notFound().build();
        }
        String contentType = cheque.contentType();
        if (contentType == null || contentType.isBlank()) {
            contentType = "application/octet-stream";
        }
        boolean isPdf = "application/pdf".equalsIgnoreCase(contentType);
        String ext = isPdf ? ".pdf" : ".img";
        String filename = "SageITCO-Cheque_" + appId + ext;
        String dispositionMode = "attachment".equalsIgnoreCase(disposition)
                ? "attachment" : "inline";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(cheque.bytes().length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        dispositionMode + "; filename=\"" + filename + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(cheque.bytes());
    }

    // ── Consultant portal auth (PUBLIC — no session token) ──────────

    /**
     * Build V — appId-bound portal request-otp. The consultant lands
     * via the per-agreement invitation link (carries {appId}); the
     * backend derives the consultantEmail from the row and sends the
     * OTP there. NO email is accepted from the client — the only way
     * to make a code go to a non-ERM-set address is for the ERM to
     * have set the wrong address in the first place.
     */
    @PostMapping("/api/consultant/applications/{appId}/auth/request-otp")
    public ResponseEntity<ApiResponse<Map<String, String>>> portalRequestOtpForApp(
            @PathVariable String appId,
            HttpServletRequest request) {
        if (!rateLimiter.allowOtpRequest("portal|" + appId + "|" + clientIp(request))) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error("Too many requests. Try again in a minute."));
        }
        String message = consultantService.requestPortalOtpForApp(appId, request);
        return ResponseEntity.ok(ApiResponse.success(Map.of("message", message)));
    }

    /**
     * Build V — appId-bound verify-otp. Body carries only the 6-digit
     * code; the email is resolved from the agreement row. Success
     * returns an email-scoped consultant session token; the dashboard
     * still lists every agreement addressed to the verified email.
     */
    @PostMapping("/api/consultant/applications/{appId}/auth/verify-otp")
    public ResponseEntity<ApiResponse<Map<String, String>>> portalVerifyOtpForApp(
            @PathVariable String appId,
            @RequestBody(required = false) PortalOtpVerifyBody body,
            HttpServletRequest request) {
        if (!rateLimiter.allowOtpVerify("portal|" + appId + "|" + clientIp(request))) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error("Too many requests. Try again in a minute."));
        }
        String token = consultantService.verifyPortalOtpForApp(
                appId,
                body == null ? null : body.otp,
                request);
        return ResponseEntity.ok(ApiResponse.success(Map.of("token", token)));
    }

    /**
     * Build V — masked-email hint for the login page so the consultant
     * sees where the code will go (never the full address, never
     * editable). NO authentication required; returns a neutral mask
     * when the row is missing/cancelled so existence can't be probed.
     */
    @GetMapping("/api/consultant/applications/{appId}/auth/email-hint")
    public ResponseEntity<ApiResponse<Map<String, String>>> portalEmailHint(
            @PathVariable String appId,
            HttpServletRequest request) {
        if (!rateLimiter.allowRead(clientIp(request))) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error("Too many requests. Try again in a minute."));
        }
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "maskedEmail", consultantService.maskedConsultantEmail(appId))));
    }

    /**
     * The blank-form preview of the master agreement -- the real
     * document with underscore placeholders where the consultant's
     * data will eventually land. Consumed by the wizard's "View full
     * agreement" reference button so the consultant can read the
     * binding text alongside the plain-language section summaries.
     *
     * The bytes are consultant-independent and cached in memory after
     * the first render (see
     * {@link AgreementDocumentService#getBlankPreviewPdfBytes()}).
     */
    @GetMapping("/api/consultant/agreement-template-pdf")
    public ResponseEntity<byte[]> agreementTemplatePdf(HttpServletRequest request) {
        // Re-uses the portal token guard: any verified consultant can
        // read the template, not just the one whose appId would gate
        // the per-agreement download endpoint.
        requireConsultantEmail(request);
        byte[] bytes;
        try {
            bytes = agreementDocumentService.getBlankPreviewPdfBytes();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(bytes.length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"SageITCO-Agreement-Template.pdf\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=0, no-store")
                .body(bytes);
    }

    /**
     * Portal dashboard list. Returns every actionable / in-flight /
     * completed agreement addressed to the verified email. Sorted so
     * the action items surface first.
     */
    @GetMapping("/api/consultant/agreements")
    public ResponseEntity<ApiResponse<List<ConsultantAgreementSummary>>> portalDashboard(
            HttpServletRequest request) {
        String email = requireConsultantEmail(request);
        if (!rateLimiter.allowRead(clientIp(request))) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error("Too many requests. Try again in a minute."));
        }
        List<ConsultantAgreementSummary> items = consultantService.listForConsultant(email)
                .stream()
                .map(ConsultantAgreementSummary::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.success(items));
    }

    /**
     * Validate a portal token and return the verified email. Used by
     * the dashboard endpoint (no appId in path so it can't use the
     * email-match guard).
     */
    private String requireConsultantEmail(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            throw new UnauthorizedException("Verification required.");
        }
        String token = auth.substring(7);
        if (!jwtService.isTokenValid(token)
                || !"consultant".equals(jwtService.extractTokenType(token))) {
            throw new UnauthorizedException("Verification required.");
        }
        String emailClaim = jwtService.extractConsultantEmail(token);
        if (emailClaim == null || emailClaim.isBlank()) {
            throw new UnauthorizedException("Verification required.");
        }
        return emailClaim.trim().toLowerCase();
    }

    /**
     * Build K — the consultant PDF download endpoint is intentionally
     * gone. Consultants never download or view the generated PDF; the
     * post-submit experience is status-only. A 403 stub stays here so
     * any cached frontend, bookmarked email, or stale link surfaces
     * as a clear "no longer available" rather than a 404 (which would
     * be confusing — the agreement does exist, the action does not).
     */
    @GetMapping("/api/consultant/applications/{appId}/pdf")
    public ResponseEntity<ApiResponse<Map<String, String>>> consultantDownloadPdfRemoved(
            @PathVariable String appId,
            HttpServletRequest request) {
        // Honour the email-scoped token guard so this can't be probed
        // anonymously for existence.
        requireConsultantToken(appId, request);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                ApiResponse.error(
                        "The consultant PDF download has been retired. "
                                + "Check your dashboard for the current status."));
    }

    // ── Consultant-side (consultant-token gated, rate-limited) ──────

    @GetMapping("/api/consultant/applications/{appId}")
    public ResponseEntity<ApiResponse<ConsultantApplication>> consultantGet(
            @PathVariable String appId,
            HttpServletRequest request) {
        requireConsultantToken(appId, request);
        if (!rateLimiter.allowRead(clientIp(request))) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error("Too many requests. Try again in a minute."));
        }
        return ResponseEntity.ok(ApiResponse.success(
                consultantService.getForConsultant(appId, request)));
    }

    /**
     * F-3 — the full agreement clauses, partitioned per wizard section,
     * parsed from the master template (single source of truth) plus this
     * app's non-editable values. The frontend renders the real clauses
     * inline and fills consultant-editable placeholders live from form
     * state. Ownership-gated (non-owner email → 404).
     */
    @GetMapping("/api/consultant/applications/{appId}/agreement-content")
    public ResponseEntity<ApiResponse<AgreementContent>> agreementContent(
            @PathVariable String appId,
            HttpServletRequest request) {
        requireConsultantToken(appId, request);
        if (!rateLimiter.allowRead(clientIp(request))) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error("Too many requests. Try again in a minute."));
        }
        ConsultantApplication app = consultantService.getByApplicationId(appId);
        AgreementContent content = new AgreementContent(
                agreementContentService.getSections(),
                agreementDocumentService.nonEditableDisplayValues(app));
        return ResponseEntity.ok(ApiResponse.success(content));
    }

    @PostMapping("/api/consultant/applications/{appId}/verify-details")
    public ResponseEntity<ApiResponse<ConsultantApplication>> consultantVerifyDetails(
            @PathVariable String appId,
            HttpServletRequest request) {
        requireConsultantToken(appId, request);
        if (!rateLimiter.allowWrite(appId)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error("Too many requests. Try again in a minute."));
        }
        return ResponseEntity.ok(ApiResponse.success(
                "Details verified",
                consultantService.verifyDetails(appId, request)));
    }

    @PostMapping("/api/consultant/applications/{appId}/request-revision")
    public ResponseEntity<ApiResponse<ConsultantApplication>> consultantRequestRevision(
            @PathVariable String appId,
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        requireConsultantToken(appId, request);
        if (!rateLimiter.allowWrite(appId)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error("Too many requests. Try again in a minute."));
        }
        return ResponseEntity.ok(ApiResponse.success(
                "Revision requested",
                consultantService.requestRevision(appId, body.get("reason"), request)));
    }

    @PostMapping("/api/consultant/applications/{appId}/sign")
    public ResponseEntity<ApiResponse<ConsultantApplication>> consultantSign(
            @PathVariable String appId,
            @RequestBody SignBody body,
            HttpServletRequest request) {
        requireConsultantToken(appId, request);
        if (!rateLimiter.allowWrite(appId)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error("Too many requests. Try again in a minute."));
        }
        return ResponseEntity.ok(ApiResponse.success(
                "Signed",
                consultantService.sign(appId, body.legalName, body.signatureImage, request)));
    }

    @PostMapping("/api/consultant/applications/{appId}/request-copy")
    public ResponseEntity<ApiResponse<Map<String, String>>> consultantRequestCopy(
            @PathVariable String appId,
            HttpServletRequest request) {
        requireConsultantToken(appId, request);
        if (!rateLimiter.allowWrite(appId)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error("Too many requests. Try again in a minute."));
        }
        consultantService.requestCopy(appId, request);
        return ResponseEntity.ok(ApiResponse.success(
                Map.of("message", "A copy is on its way to your inbox.")));
    }

    /**
     * Build T — captures the consultant's e-sign consent at the gate
     * shown BEFORE the wizard. Idempotent: second call is a no-op
     * server-side and the response reflects the persisted timestamp.
     */
    @PostMapping("/api/consultant/applications/{appId}/consent")
    public ResponseEntity<ApiResponse<ConsultantApplication>> consultantConsent(
            @PathVariable String appId,
            HttpServletRequest request) {
        requireConsultantToken(appId, request);
        if (!rateLimiter.allowWrite(appId)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error("Too many requests. Try again in a minute."));
        }
        return ResponseEntity.ok(ApiResponse.success(
                "Consent recorded",
                consultantService.recordConsent(appId, request)));
    }

    /**
     * Build T — request a fresh OTP to download the released
     * consultant-version PDF. Generic response regardless of release
     * state so a token holder can't probe.
     */
    @PostMapping("/api/consultant/applications/{appId}/request-download-otp")
    public ResponseEntity<ApiResponse<Map<String, String>>> consultantRequestDownloadOtp(
            @PathVariable String appId,
            HttpServletRequest request) {
        requireConsultantToken(appId, request);
        if (!rateLimiter.allowOtpRequest("download|" + appId + "|" + clientIp(request))) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error("Too many requests. Try again in a minute."));
        }
        String message = consultantService.requestDownloadOtp(appId, request);
        return ResponseEntity.ok(ApiResponse.success(Map.of("message", message)));
    }

    /**
     * Build T — exchange a fresh OTP for the consultant-version PDF
     * bytes. ONLY serves the released consultant-version (the ERM-
     * signed COMPLETED PDF is NEVER served by this path).
     */
    @PostMapping("/api/consultant/applications/{appId}/download")
    public ResponseEntity<?> consultantDownload(
            @PathVariable String appId,
            @RequestBody(required = false) DownloadBody body,
            HttpServletRequest request) {
        requireConsultantToken(appId, request);
        if (!rateLimiter.allowOtpVerify("download|" + appId + "|" + clientIp(request))) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error("Too many requests. Try again in a minute."));
        }
        byte[] bytes;
        try {
            bytes = consultantService.downloadConsultantCopy(
                    appId, body == null ? null : body.otp, request);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.error(e.getMessage()));
        }
        ConsultantApplication app = consultantService.getByApplicationId(appId);
        String filename = AgreementDocumentService.buildPdfFilename(app);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(bytes.length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(bytes);
    }

    // ── Consultant-side: two-stage workflow (Phase 3) ───────────────

    /**
     * Partial-save endpoint for the consultant /fill page. Body is any
     * subset of the 38 fillable fields -- missing keys are left
     * untouched on the entity. Idempotent: a request with no field
     * changes returns the unchanged entity and emits no audit event.
     */
    @PutMapping("/api/consultant/applications/{appId}/fill")
    public ResponseEntity<ApiResponse<ConsultantApplication>> consultantFill(
            @PathVariable String appId,
            @RequestBody ConsultantApplicationService.ConsultantFillPatch body,
            HttpServletRequest request) {
        requireConsultantToken(appId, request);
        if (!rateLimiter.allowWrite(appId)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error("Too many requests. Try again in a minute."));
        }
        return ResponseEntity.ok(ApiResponse.success(
                "Saved",
                consultantService.consultantFill(appId, body, request)));
    }

    /**
     * Consultant signs and submits. Uploads the signature image to
     * Cloudinary, locks the legal name, and transitions to VERIFIED.
     * The final PDF is NOT rendered here -- it's produced once when
     * the ERM countersigns to avoid ~10s of LibreOffice cold start
     * on the consultant's click.
     */
    @PostMapping("/api/consultant/applications/{appId}/submit")
    public ResponseEntity<ApiResponse<ConsultantApplication>> consultantSubmit(
            @PathVariable String appId,
            @RequestBody ConsultantSubmitBody body,
            HttpServletRequest request) {
        requireConsultantToken(appId, request);
        if (!rateLimiter.allowWrite(appId)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error("Too many requests. Try again in a minute."));
        }
        return ResponseEntity.ok(ApiResponse.success(
                "Submitted",
                consultantService.consultantSubmit(
                        appId,
                        body.signatureBase64,
                        body.finalSignatureBase64,
                        body.signedLegalName,
                        request)));
    }

    // ── Build G: in-memory PDF previews (no Cloudinary fetch) ──────

    /**
     * Consultant-side review-step preview. Renders the agreement PDF
     * from CURRENT entity data + the primary signature the consultant
     * just drew (passed as a data: URL since it hasn't been uploaded
     * yet). Streams the bytes inline -- no Cloudinary upload, no
     * persistence; the bytes are throw-away for this preview.
     *
     * Status guard mirrors the consultant fill flow (SUBMITTED |
     * REVISION_REQUESTED); COMPLETED rows use the existing
     * {@code /pdf} endpoint.
     */
    @PostMapping("/api/consultant/applications/{appId}/preview-pdf")
    public ResponseEntity<byte[]> consultantPreviewPdf(
            @PathVariable String appId,
            @RequestBody(required = false) PreviewPdfBody body,
            HttpServletRequest request) {
        requireConsultantToken(appId, request);
        if (!rateLimiter.allowRead(clientIp(request))) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        ConsultantApplication app = consultantService.getByApplicationId(appId);
        String status = app.getStatus();
        // Build K — preview is reachable ONLY while the consultant can
        // still edit / submit. Once they've signed (VERIFIED) or the
        // ERM has approved (COMPLETED), there is no PDF view at all.
        if (!ConsultantApplication.Status.SUBMITTED.name().equals(status)
                && !ConsultantApplication.Status.REVISION_REQUESTED.name().equals(status)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        String primarySig = body == null ? null : body.primarySignatureBase64;
        byte[] bytes;
        try {
            bytes = agreementDocumentService.renderPdfBytes(
                    app,
                    AgreementDocumentService.consultantPreviewOverrides(primarySig));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        String filename = AgreementDocumentService.buildPdfFilename(app);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(bytes.length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"preview-" + filename + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(bytes);
    }

    /**
     * Build I — locked-down consultant preview. Renders the agreement
     * to PDF in-memory (no Cloudinary), then PDFBox-rasterises each
     * page into a watermarked PNG (viewer email + UTC timestamp +
     * CONFIDENTIAL baked in). Response is a JSON envelope of base64
     * PNGs, one per page; the frontend renders them in a scrollable
     * read-only view. The underlying PDF bytes never reach the
     * client, so there is no downloadable file -- this replaces the
     * old /preview-pdf for the consultant review step.
     */
    @PostMapping("/api/consultant/applications/{appId}/preview-images")
    public ResponseEntity<ApiResponse<Map<String, Object>>> consultantPreviewImages(
            @PathVariable String appId,
            @RequestBody(required = false) PreviewPdfBody body,
            HttpServletRequest request) {
        String viewerEmail = requireConsultantToken(appId, request);
        if (!rateLimiter.allowRead(clientIp(request))) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error("Too many requests. Try again in a minute."));
        }
        ConsultantApplication app = consultantService.getByApplicationId(appId);
        String status = app.getStatus();
        // Build K — preview is reachable ONLY while the consultant can
        // still edit / submit. Once they've signed (VERIFIED) or the
        // ERM has approved (COMPLETED), there is no PDF view at all.
        if (!ConsultantApplication.Status.SUBMITTED.name().equals(status)
                && !ConsultantApplication.Status.REVISION_REQUESTED.name().equals(status)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        String primarySig = body == null ? null : body.primarySignatureBase64;
        List<String> pages;
        try {
            byte[] pdfBytes = agreementDocumentService.renderPdfBytes(
                    app, AgreementDocumentService.consultantPreviewOverrides(primarySig));
            java.util.List<byte[]> images = agreementDocumentService
                    .renderWatermarkedPageImages(pdfBytes, viewerEmail);
            pages = new java.util.ArrayList<>(images.size());
            for (byte[] png : images) {
                pages.add(java.util.Base64.getEncoder().encodeToString(png));
            }
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Couldn't render the preview."));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pages", pages);
        payload.put("pageCount", pages.size());
        payload.put("viewerEmail", viewerEmail);
        return ResponseEntity.ok(ApiResponse.success("Preview ready", payload));
    }

    /**
     * ERM-side inline preview before countersigning. Renders the PDF
     * at the consultant-submitted state (both consultant signatures
     * present, ERM signature blank) by streaming the bytes through
     * the render-to-bytes path -- no Cloudinary round-trip. Limited
     * to VERIFIED (consultant submitted, ERM hasn't countersigned)
     * since that's the only state where the preview adds value.
     */
    @GetMapping("/api/agreement-erm/applications/{appId}/preview-pdf")
    @PreAuthorize("hasRole('AGREEMENT_ERM')")
    public ResponseEntity<byte[]> ermPreviewPdf(
            @PathVariable String appId,
            HttpServletRequest request) {
        ConsultantApplication app = consultantService.getByApplicationId(appId);
        consultantService.assertErmCanAccess(app, request);
        // 3B — preview is useful across the whole approval window (the
        // consultant has signed, the ERM hasn't countersigned yet).
        String st = app.getStatus();
        boolean previewable =
                ConsultantApplication.Status.VERIFIED.name().equals(st)
                || ConsultantApplication.Status.AWAITING_APPROVALS.name().equals(st)
                || ConsultantApplication.Status.APPROVAL_REVISION_REQUESTED.name().equals(st)
                || ConsultantApplication.Status.READY_TO_SIGN.name().equals(st);
        if (!previewable) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        byte[] bytes;
        try {
            bytes = agreementDocumentService.renderPdfBytes(
                    app, AgreementDocumentService.ermPreviewOverrides());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        String filename = AgreementDocumentService.buildPdfFilename(app);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(bytes.length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"preview-" + filename + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(bytes);
    }

    private static String clientIp(HttpServletRequest request) {
        if (request == null) return null;
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return request.getRemoteAddr();
    }

    // ── DTOs ────────────────────────────────────────────────────────

    public static class CreateBody {
        public String consultantEmail;
        // Build W — structured name. consultantName kept for back-compat
        // (composed server-side from first/middle/last when provided).
        public String consultantName;
        public String firstName;
        public String middleName;
        public String lastName;
        public String consultantPhone;
        // Rate card -- two free-form pairs (e.g. period="hourly",
        // amount="$60") set by the ERM at create time and rendered
        // into the Word template's Section 11 + Appendix 1 cells.
        public String ratePeriod1;
        public String rateAmount1;
        public String ratePeriod2;
        public String rateAmount2;
        // F-4: ERM-set visa / work-authorization status. Persisted into
        // workAuthCategory; the wizard renders it read-only on the
        // cover step. Build W — visaStatusOther carries the custom value
        // when visaStatus == "Others".
        public String visaStatus;
        public String visaStatusOther;
        // F-4: per-agreement requirement flags. The ERM ticks which
        // appendices THIS consultant must complete; the wizard +
        // submit validation use these to decide required vs
        // optional-skippable.
        public Boolean requireAppendix1;
        public Boolean requireAppendix2;
        public Boolean requireAppendix3;
        public Boolean requireAppendix4;
        public Boolean requireAppendix5;
        // F-4: SSN inside Appendix 3 is mandatory iff this is true AND
        // Appendix 3 is being completed (i.e. required, or
        // optional-but-touched).
        public Boolean requireSsn;
        // Build Y — ERM-filled single ACH debit date(s)/amount(s) free-text
        // (e.g. "15th of every month" / "$416.67"); read-only to the
        // consultant, rendered into the ${achDebitDates}/${achDebitAmounts}
        // placeholders.
        public String achDebitDates;
        public String achDebitAmounts;
        // Build I — ERM-set Service Track (Exhibit A). technologyTrack
        // required to send; customScopeNotes optional. Read-only to the
        // consultant; rendered via ${technologyTrack}/${customScopeNotes}.
        public String technologyTrack;
        public String customScopeNotes;
        // Legacy free-form payload, preserved for backward compat
        // with the existing /agreement-erm/new form. New flow ignores it.
        public JsonNode payload;
    }

    public static class RequestRevisionBody {
        // Build Y — section-picker revision: a JSON array of
        // {key (section id), note (optional)} rows. No free-text is
        // required; a revision can be sent with notes empty.
        public JsonNode sections;
    }

    public static class ApproveAndSignBody {
        public String ermName;
        public String ermTitle;
        /** data:image/png;base64,... */
        public String ermSignatureBase64;
    }

    public static class SendEmailBody {
        public String recipientEmail;
        public String note;
    }

    public static class ConsultantContactBody {
        public String consultantEmail;
        public String consultantName;
    }

    /**
     * Build G — body for the consultant review-step preview. The
     * primary signature has not been uploaded yet (Cloudinary upload
     * happens at submit), so the wizard sends the data: URL inline.
     */
    public static class PreviewPdfBody {
        public String primarySignatureBase64;
    }

    public static class ConsultantSubmitBody {
        /** data:image/png;base64,... — primary signature drawn on the main-agreement step. */
        public String signatureBase64;
        /** data:image/png;base64,... — final signature drawn on the review step (F-4). */
        public String finalSignatureBase64;
        public String signedLegalName;
    }

    public static class UpdateBody {
        public String consultantEmail;
        public String consultantName;
        public String consultantPhone;
        public JsonNode payload;
    }

    public static class SignBody {
        public String legalName;
        public String signatureImage;
    }

    /**
     * Build V — portal verify-otp body. {@code otp} only; the email is
     * resolved server-side from the {appId} path segment and is never
     * accepted from the client.
     */
    public static class PortalOtpVerifyBody {
        public String otp;
    }

    /** Build T — body for the consultant OTP-gated download exchange. */
    public static class DownloadBody {
        public String otp;
    }

    /**
     * Dashboard row served to the verified consultant. Each item carries
     * the raw status (drives PDF download eligibility) plus a
     * consultant-friendly label + an action enum the frontend switches
     * on to render the right button.
     */
    public static class ConsultantAgreementSummary {
        public String appId;
        public String agreementTitle;
        public String technologyTrack;
        public String status;
        public String statusLabel;
        public String action;
        public String createdAt;
        public String updatedAt;
        /** Build M — current phase of the two-phase agreement (1 or 2). */
        public Integer phase;
        /** Build T — ERM has released a consultant-version copy. */
        public boolean consultantCopyReleased;
        /** Build T — downloadAvailable = released AND the public_id is on the row. */
        public boolean downloadAvailable;
        /** Build T — release timestamp, surfaced for "released N days ago" hints. */
        public String consultantCopyReleasedAt;

        public static ConsultantAgreementSummary from(ConsultantApplication app) {
            ConsultantAgreementSummary r = new ConsultantAgreementSummary();
            r.appId = app.getApplicationId();
            r.agreementTitle =
                    "Integrated Two-Phase Coaching & Post-Offer Support Agreement";
            r.technologyTrack = app.getTechnologyTrack();
            r.status = app.getStatus();
            r.phase = app.getPhase();
            // Build T — surface release state so the dashboard can route
            // VERIFIED + released rows to the OTP-gated download instead
            // of the passive "Sent for verification" pill.
            r.consultantCopyReleased = Boolean.TRUE.equals(app.getConsultantCopyReleased());
            r.downloadAvailable = r.consultantCopyReleased
                    && app.getConsultantPdfPublicId() != null
                    && !app.getConsultantPdfPublicId().isBlank();
            r.consultantCopyReleasedAt = app.getConsultantCopyReleasedAt() == null
                    ? null
                    : app.getConsultantCopyReleasedAt().toString();

            String s = app.getStatus();
            // Build K — post-submit states are status-only. Build T —
            // VERIFIED + released and COMPLETED + released switch to the
            // DOWNLOAD action so the dashboard row routes to the OTP-
            // gated download screen. The ERM-signed PDF is never served
            // to the consultant; the download path serves only the
            // consultant-version copy.
            if (ConsultantApplication.Status.SUBMITTED.name().equals(s)) {
                r.statusLabel = "Awaiting your signature";
                r.action = "SIGN";
            } else if (ConsultantApplication.Status.REVISION_REQUESTED.name().equals(s)) {
                r.statusLabel = "Changes requested — review & re-sign";
                r.action = "REVIEW_AND_SIGN";
            } else if (ConsultantApplication.Status.UPDATED.name().equals(s)
                    || ConsultantApplication.Status.VERIFIED.name().equals(s)
                    || ConsultantApplication.Status.SIGNED.name().equals(s)) {
                if (r.downloadAvailable) {
                    r.statusLabel = "Approved — download your copy";
                    r.action = "DOWNLOAD";
                } else {
                    r.statusLabel = "Sent for verification";
                    r.action = "NONE";
                }
            } else if (ConsultantApplication.Status.COMPLETED.name().equals(s)) {
                if (r.downloadAvailable) {
                    r.statusLabel = "Accepted — download your copy";
                    r.action = "DOWNLOAD";
                } else {
                    r.statusLabel = "Accepted";
                    r.action = "NONE";
                }
            } else if (ConsultantApplication.Status.EXPIRED.name().equals(s)) {
                // Build L — invite went stale (15-day window).
                r.statusLabel = "Invitation expired";
                r.action = "NONE";
            } else {
                r.statusLabel = s;
                r.action = "NONE";
            }
            r.createdAt = app.getCreatedAt() == null ? null : app.getCreatedAt().toString();
            r.updatedAt = app.getUpdatedAt() == null ? null : app.getUpdatedAt().toString();
            return r;
        }
    }

    public static class PageResponse<T> {
        public List<T> content;
        public int page;
        public int size;
        public long totalElements;
        public int totalPages;
        public boolean hasNext;

        public static <T> PageResponse<T> from(Page<T> page) {
            PageResponse<T> r = new PageResponse<>();
            r.content = page.getContent();
            r.page = page.getNumber();
            r.size = page.getSize();
            r.totalElements = page.getTotalElements();
            r.totalPages = page.getTotalPages();
            r.hasNext = page.hasNext();
            return r;
        }
    }
}
