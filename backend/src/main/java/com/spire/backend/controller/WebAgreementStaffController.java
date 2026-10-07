package com.spire.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.spire.backend.dto.ApiResponse;
import com.spire.backend.entity.WebAgreement;
import com.spire.backend.service.MasterAgreementService;
import com.spire.backend.service.WebAgreementFileService;
import com.spire.backend.service.WebAgreementRenderer;
import com.spire.backend.service.WebAgreementRules;
import com.spire.backend.service.WebAgreementStaffService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * The staff side of the website agreement (the console's
 * /api/agreement-erm/** for website ERMs): the participants who are ready,
 * create, the list, the detail, and the review actions. Operations and
 * System admins see and act on every agreement; an ERM only on the ones they
 * created (anything else is "not found"). The service enforces that.
 *
 * The preview needs LibreOffice; when it is missing (a dev laptop) it fails
 * with 500 and a short reason on {@code X-Preview-Error}, like the console.
 */
@RestController
@RequestMapping("/api/web-agreements")
@PreAuthorize("hasAnyRole('ERM','OPERATIONS_ADMIN','SYSTEM_ADMIN')")
@RequiredArgsConstructor
@Slf4j
public class WebAgreementStaffController {

    /** The five single-file document paths (cheques have their own). */
    private static final String DOC_PATHS = "{doc:workauth|offer-letter|dl-doc|state-id-doc|ssn-doc}";

    private final WebAgreementStaffService staffService;

    // ── Participants ready for their agreement ───────────────────────

    @GetMapping("/requests")
    public ResponseEntity<ApiResponse<List<MasterAgreementService.ReadyRow>>> requests(Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(staffService.requests(userId(auth))));
    }

    /** One participant's details, to prefill the create form. */
    @GetMapping("/requests/{userId}")
    public ResponseEntity<ApiResponse<MasterAgreementService.ReadyRow>> request(
            @PathVariable Long userId, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(staffService.request(userId(auth), userId)));
    }

    // ── Create, list, detail ─────────────────────────────────────────

    @PostMapping
    public ResponseEntity<ApiResponse<WebAgreement>> create(
            @RequestBody WebAgreementStaffService.CreateBody body,
            Authentication auth,
            HttpServletRequest request) {
        WebAgreement created = staffService.create(body, userId(auth), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Agreement created", created));
    }

    /**
     * Newest first; size capped at 100. q searches every page (participant
     * email or name, agreement id); released splits VERIFIED into "signed by
     * the participant" (false) and "verified" (true).
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<WebAgreement>>> list(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "released", required = false) Boolean released,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            Authentication auth) {
        Pageable pageable = PageRequest.of(
                Math.max(0, page),
                Math.min(100, Math.max(1, size)),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(ApiResponse.success(
                PageResponse.from(staffService.list(status, q, released, pageable, userId(auth)))));
    }

    /** {application, events}. */
    @GetMapping("/{appId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> get(
            @PathVariable String appId, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(staffService.detail(appId, userId(auth))));
    }

    // ── Actions ──────────────────────────────────────────────────────

    @PostMapping("/{appId}/cancel")
    public ResponseEntity<ApiResponse<WebAgreement>> cancel(
            @PathVariable String appId, Authentication auth, HttpServletRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Cancelled", staffService.cancel(appId, userId(auth), request)));
    }

    @PatchMapping("/{appId}/contact")
    public ResponseEntity<ApiResponse<WebAgreement>> updateContact(
            @PathVariable String appId,
            @RequestBody(required = false) ContactBody body,
            Authentication auth,
            HttpServletRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Consultant contact updated",
                staffService.updateContact(appId,
                        body == null ? null : body.consultantEmail,
                        body == null ? null : body.consultantName,
                        userId(auth), request)));
    }

    @PostMapping("/{appId}/request-revision")
    public ResponseEntity<ApiResponse<WebAgreement>> requestRevision(
            @PathVariable String appId,
            @RequestBody(required = false) RequestRevisionBody body,
            Authentication auth,
            HttpServletRequest request) {
        RequestRevisionBody b = body == null ? new RequestRevisionBody() : body;
        return ResponseEntity.ok(ApiResponse.success(
                "Revision requested",
                staffService.requestRevision(appId, b.sections,
                        b.achDebitDates, b.achDebitAmounts,
                        b.ratePeriod1, b.rateAmount1, b.ratePeriod2, b.rateAmount2,
                        b.phase2DeliverablePeriod,
                        userId(auth), request)));
    }

    @PostMapping("/{appId}/request-signature-revision")
    public ResponseEntity<ApiResponse<WebAgreement>> requestSignatureRevision(
            @PathVariable String appId,
            @RequestBody(required = false) SignatureRevisionBody body,
            Authentication auth,
            HttpServletRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Signature re-sign requested",
                staffService.requestSignatureRevision(appId, body == null ? null : body.note,
                        userId(auth), request)));
    }

    @PostMapping("/{appId}/request-document-revision")
    public ResponseEntity<ApiResponse<WebAgreement>> requestDocumentRevision(
            @PathVariable String appId,
            @RequestBody(required = false) DocumentRevisionBody body,
            Authentication auth,
            HttpServletRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Document re-upload requested",
                staffService.requestDocumentRevision(appId,
                        body == null ? null : body.docKeys,
                        body == null ? null : body.note,
                        userId(auth), request)));
    }

    /** Takes back the open change request; refused once the participant has acted on it. */
    @PostMapping("/{appId}/revoke-revision")
    public ResponseEntity<ApiResponse<WebAgreement>> revokeRevision(
            @PathVariable String appId, Authentication auth, HttpServletRequest request) {
        WebAgreement a = staffService.revokeRevision(appId, userId(auth), request);
        return ResponseEntity.ok(ApiResponse.success(
                "Change request withdrawn", staffService.decorateRevokeState(a)));
    }

    /** The ERM verifies the signed agreement. */
    @PostMapping("/{appId}/verify")
    public ResponseEntity<ApiResponse<WebAgreement>> verify(
            @PathVariable String appId, Authentication auth, HttpServletRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Verified", staffService.verify(appId, userId(auth), request)));
    }

    // ── Preview and documents ────────────────────────────────────────

    /** The signed agreement as a PDF (inline, never cached). */
    @GetMapping("/{appId}/preview-pdf")
    public ResponseEntity<byte[]> previewPdf(@PathVariable String appId, Authentication auth) {
        WebAgreementFileService.Download pdf;
        try {
            pdf = staffService.previewPdf(appId, userId(auth));
        } catch (WebAgreementRenderer.RenderException e) {
            log.error("Web agreement ERM preview render failed for {}", appId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .header("X-Preview-Error", WebAgreementRenderer.previewErrorReason(e))
                    .build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(pdf.bytes().length)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + pdf.filename() + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(pdf.bytes());
    }

    @GetMapping("/{appId}/" + DOC_PATHS)
    public ResponseEntity<byte[]> viewDoc(
            @PathVariable String appId,
            @PathVariable String doc,
            @RequestParam(value = "disposition", required = false) String disposition,
            Authentication auth) {
        WebAgreementRules.Doc kind = WebAgreementRules.Doc.fromPath(doc);
        return stream(staffService.readDoc(appId, kind, userId(auth)), disposition);
    }

    @GetMapping("/{appId}/cheques/{index}")
    public ResponseEntity<byte[]> viewCheque(
            @PathVariable String appId,
            @PathVariable int index,
            @RequestParam(value = "disposition", required = false) String disposition,
            Authentication auth) {
        return stream(staffService.readCheque(appId, index, userId(auth)), disposition);
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

    public static class ContactBody {
        public String consultantEmail;
        public String consultantName;
    }

    public static class RequestRevisionBody {
        // [{key (section id), note (optional)}, …].
        public JsonNode sections;
        // Optional ERM corrections; null = leave unchanged. A CHANGED value
        // is saved and opens its section.
        public String achDebitDates;
        public String achDebitAmounts;
        public String ratePeriod1;
        public String rateAmount1;
        public String ratePeriod2;
        public String rateAmount2;
        public String phase2DeliverablePeriod;
    }

    public static class SignatureRevisionBody {
        public String note;
    }

    /** Keys: doc:workauth, doc:offer-letter, doc:dl-doc, doc:state-id, doc:ssn-doc, doc:cheque. */
    public static class DocumentRevisionBody {
        public List<String> docKeys;
        public String note;
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
