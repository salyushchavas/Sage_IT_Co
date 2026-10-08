package com.spire.backend.controller;

import com.spire.backend.dto.ApiResponse;
import com.spire.backend.dto.WebAgreementContent;
import com.spire.backend.entity.WebAgreement;
import com.spire.backend.service.WebAgreementFileService;
import com.spire.backend.service.WebAgreementParticipantService;
import com.spire.backend.service.WebAgreementRenderer;
import com.spire.backend.service.WebAgreementRules;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * The participant's side of the website agreement (the console's
 * /api/consultant/applications/{appId}/** without the appId): the signed-in
 * user always works on their own agreement, so no email code and no link.
 * Under /api/participants/ so the website's terms gate never blocks it.
 *
 * Previews need LibreOffice; when it is missing (a dev laptop) they fail
 * with 500 and a short reason on {@code X-Preview-Error}, like the console.
 */
@RestController
@RequestMapping("/api/participants/web-agreement")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
@Slf4j
public class WebAgreementParticipantController {

    /** The five single-file upload paths (cheques have their own). */
    private static final String DOC_PATHS = "{doc:workauth|offer-letter|dl-doc|state-id-doc|ssn-doc}";

    private final WebAgreementParticipantService participantService;

    /** The caller's agreement with {@code effectiveRequirements}; {@code data} is absent when none yet. */
    @GetMapping
    public ResponseEntity<ApiResponse<WebAgreement>> mine(Authentication auth, HttpServletRequest request) {
        return ResponseEntity.ok(ApiResponse.success(participantService.getMine(userId(auth), request)));
    }

    /** The agreement clauses per wizard section + this agreement's fixed values. */
    @GetMapping("/content")
    public ResponseEntity<ApiResponse<WebAgreementContent>> content(Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(participantService.content(userId(auth))));
    }

    /** The blank template PDF ("View full agreement"). */
    @GetMapping("/template-pdf")
    public ResponseEntity<byte[]> templatePdf(Authentication auth) {
        Long userId = userId(auth);
        byte[] bytes;
        try {
            bytes = participantService.templatePdf(userId);
        } catch (WebAgreementRenderer.RenderException e) {
            log.error("Web agreement template render failed for user {}", userId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .header("X-Preview-Error", WebAgreementRenderer.previewErrorReason(e))
                    .build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(bytes.length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"SageITCO-Agreement-Template.pdf\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=0, no-store")
                .body(bytes);
    }

    /** E-sign consent before the wizard. Idempotent. */
    @PostMapping("/consent")
    public ResponseEntity<ApiResponse<WebAgreement>> consent(Authentication auth, HttpServletRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Consent recorded", participantService.recordConsent(userId(auth), request)));
    }

    /** Partial save: any subset of the fillable fields. */
    @PutMapping("/fill")
    public ResponseEntity<ApiResponse<WebAgreement>> fill(
            @RequestBody(required = false) WebAgreementRules.WebAgreementFillPatch body,
            Authentication auth,
            HttpServletRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Saved", participantService.fill(userId(auth), body, request)));
    }

    /** Sign and submit. 400 with {missingFields, missingAffirmations, missingSignature, missingFinalSignature} when incomplete. */
    @PostMapping("/submit")
    public ResponseEntity<ApiResponse<WebAgreement>> submit(
            @RequestBody(required = false) SubmitBody body,
            Authentication auth,
            HttpServletRequest request) {
        SubmitBody b = body == null ? new SubmitBody() : body;
        return ResponseEntity.ok(ApiResponse.success(
                "Submitted",
                participantService.submit(userId(auth),
                        b.signatureBase64, b.finalSignatureBase64, b.signedLegalName, request)));
    }

    /** The review step's watermarked page images: {pages, pageCount, viewerEmail}. */
    @PostMapping("/preview-images")
    public ResponseEntity<ApiResponse<Map<String, Object>>> previewImages(
            @RequestBody(required = false) PreviewBody body,
            Authentication auth) {
        Long userId = userId(auth);
        String primarySig = body == null ? null : body.primarySignatureBase64;
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    "Preview ready", participantService.previewImages(userId, primarySig)));
        } catch (WebAgreementRenderer.RenderException e) {
            log.error("Web agreement preview-images render failed for user {}", userId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .header("X-Preview-Error", WebAgreementRenderer.previewErrorReason(e))
                    .body(ApiResponse.error("Couldn't render the preview."));
        }
    }

    // ── Uploads ──────────────────────────────────────────────────────

    @PostMapping("/" + DOC_PATHS)
    public ResponseEntity<ApiResponse<WebAgreement>> uploadDoc(
            @PathVariable String doc,
            @RequestParam("file") MultipartFile file,
            Authentication auth,
            HttpServletRequest request) {
        WebAgreementRules.Doc kind = WebAgreementRules.Doc.fromPath(doc);
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("File is required."));
        }
        WebAgreement saved;
        try {
            saved = participantService.uploadDoc(
                    userId(auth), kind, file.getBytes(), file.getContentType(), request);
        } catch (java.io.IOException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(ApiResponse.error("Couldn't read uploaded file."));
        }
        return ResponseEntity.ok(ApiResponse.success(kind.uploadedMessage, saved));
    }

    @GetMapping("/" + DOC_PATHS)
    public ResponseEntity<byte[]> viewDoc(
            @PathVariable String doc,
            @RequestParam(value = "disposition", required = false) String disposition,
            Authentication auth) {
        WebAgreementRules.Doc kind = WebAgreementRules.Doc.fromPath(doc);
        return stream(participantService.readDoc(userId(auth), kind), disposition);
    }

    /** Upload the file of cheque #index (0–50). */
    @PostMapping("/cheques/{index}")
    public ResponseEntity<ApiResponse<WebAgreement>> uploadCheque(
            @PathVariable int index,
            @RequestParam("file") MultipartFile file,
            Authentication auth,
            HttpServletRequest request) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("File is required."));
        }
        WebAgreement saved;
        try {
            saved = participantService.uploadChequeAt(
                    userId(auth), index, file.getBytes(), file.getContentType(), request);
        } catch (java.io.IOException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(ApiResponse.error("Couldn't read uploaded file."));
        }
        return ResponseEntity.ok(ApiResponse.success("Cheque " + (index + 1) + " uploaded.", saved));
    }

    /** Cheque #index's number and date; the uploaded file survives. */
    @PutMapping("/cheques/{index}")
    public ResponseEntity<ApiResponse<WebAgreement>> chequeMetadata(
            @PathVariable int index,
            @RequestBody(required = false) WebAgreementRules.ChequeMetadataPatch body,
            Authentication auth,
            HttpServletRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Saved", participantService.setChequeMetadata(userId(auth), index, body, request)));
    }

    @GetMapping("/cheques/{index}")
    public ResponseEntity<byte[]> viewCheque(
            @PathVariable int index,
            @RequestParam(value = "disposition", required = false) String disposition,
            Authentication auth) {
        return stream(participantService.readCheque(userId(auth), index), disposition);
    }

    // ── Helpers ──────────────────────────────────────────────────────

    /** Streams a document inline (or as an attachment), never cached. 404 when there is none. */
    private static ResponseEntity<byte[]> stream(WebAgreementFileService.Download doc, String disposition) {
        if (doc == null || doc.bytes() == null || doc.bytes().length == 0) {
            return ResponseEntity.notFound().build();
        }
        String mode = "attachment".equalsIgnoreCase(disposition) ? "attachment" : "inline";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(doc.contentType()))
                .contentLength(doc.bytes().length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        mode + "; filename=\"" + doc.filename() + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(doc.bytes());
    }

    private static Long userId(Authentication auth) {
        return Long.parseLong(auth.getPrincipal().toString());
    }

    // ── Bodies ───────────────────────────────────────────────────────

    public static class SubmitBody {
        public String signatureBase64;
        public String finalSignatureBase64;
        public String signedLegalName;
    }

    public static class PreviewBody {
        public String primarySignatureBase64;
    }
}
