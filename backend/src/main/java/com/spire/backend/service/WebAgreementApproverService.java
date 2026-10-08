package com.spire.backend.service;

import com.spire.backend.entity.User;
import com.spire.backend.entity.WebAgreement;
import com.spire.backend.entity.WebAgreementApproval;
import com.spire.backend.entity.WebAgreementEvent;
import com.spire.backend.entity.WebAgreementVersion;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.repository.WebAgreementApprovalRepository;
import com.spire.backend.repository.WebAgreementRepository;
import com.spire.backend.repository.WebAgreementVersionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The approvers' half of the approval gate on website agreements, the
 * website copy of the console's approver surface (AgreementApproverController
 * and ConsultantApplicationService's approverQueue, approverApplications,
 * approverApprovedRecords, getForApprover*, the version readers and
 * approverDecision; all untouched). The ERM's half is
 * {@link WebAgreementApprovalService}.
 *
 * Who: a website MANAGER or ACCOUNTS user acts on their own gate; the System
 * Admin (the console's super-admin) names the gate it acts on with
 * {@code role}. A gate routed to a named approver is theirs alone; an
 * un-routed one (after a purge) is open to every approver of that role.
 * Anything the caller may not see is "not found", so ids can't be probed,
 * and an archived agreement is always "not found".
 *
 * What approvers see: the frozen version the ERM routed, as clean PNG pages
 * (never the PDF), or a live ERM-preview render when no version was routed;
 * the only PDFs they get are the downloads of an agreement they approved.
 * Agreement JSON from the lists never carries the SSN, ID or bank numbers
 * (the decision response is stripped by the controller, after commit).
 *
 * Decisions: approve, or ask for a revision (note required), which goes back
 * to the ERM, not the participant. Once every gate of the round is approved
 * the agreement is READY_TO_SIGN. No email, and no lock (as the console).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebAgreementApproverService {

    private static final String MANAGER = WebAgreementApproval.ApproverRole.MANAGER.name();
    private static final String ACCOUNTS = WebAgreementApproval.ApproverRole.ACCOUNTS.name();
    private static final String PENDING = WebAgreementApproval.Decision.PENDING.name();
    private static final String APPROVED = WebAgreementApproval.Decision.APPROVED.name();
    private static final String REVISION_REQUESTED = WebAgreementApproval.Decision.REVISION_REQUESTED.name();

    static final String MANAGER_ONLY = "The Phase 1 signed agreement is available to the Manager only.";
    static final String NOT_SIGNED_YET = "The signed agreement is available once the ERM has signed it.";
    static final String NO_PHASE1_COPY = "No Phase 1 signed agreement on file for this agreement.";

    /** Newest change first, unknown last (the console's list order). */
    private static final Comparator<WebAgreement> NEWEST_FIRST = Comparator.comparing(
            WebAgreement::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder()));

    private final WebAgreementRepository agreementRepository;
    private final WebAgreementApprovalRepository approvalRepository;
    private final WebAgreementVersionRepository versionRepository;
    private final WebAgreementFileService fileService;
    private final WebAgreementRenderer renderer;
    private final WebAgreementEventService eventService;
    private final WebAgreementAccess access;
    private final WebAgreementApprovalSummary approvalSummary;

    /** Detaches list rows before their PII is removed (open-in-view is on). */
    @PersistenceContext
    private EntityManager entityManager;

    /** Who is asking, and the gate (MANAGER or ACCOUNTS) they act on. */
    record Gate(User caller, String role) {
        Long callerId() {
            return caller.getId();
        }
    }

    /**
     * A refusal the console answers with its own status and text rather than
     * an exception: the controller sends it as JSON on the preview routes and
     * on the {@code X-Preview-Error} header on the download.
     */
    public static class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final int status;

        public Refused(int status, String message) {
            super(message);
            this.status = status;
        }

        public int getStatus() {
            return status;
        }
    }

    /**
     * Reading, rendering or rasterising a document failed after the gate let
     * the caller in. The message is the console's text for the preview
     * routes; the cause gives the download's {@code X-Preview-Error} reason.
     */
    public static class DocumentFailed extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public DocumentFailed(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // ── Which gate ───────────────────────────────────────────────────

    /**
     * The gate the caller acts on (the console's resolveRole): a Manager's
     * or an Accounts approver's own; the System Admin must name one with
     * {@code role} (400 otherwise). Anyone else, and a deactivated account,
     * gets 403 "Approver role required.".
     */
    Gate resolveGate(Long callerId, String role) {
        User caller = access.requireApprover(callerId);
        String own = WebAgreementAccess.roleOf(caller);
        if (MANAGER.equals(own) || ACCOUNTS.equals(own)) {
            return new Gate(caller, own);
        }
        // The System Admin, the only other role requireApprover lets through.
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("role (MANAGER|ACCOUNTS) is required for the super-admin.");
        }
        String named = role.trim().toUpperCase(Locale.ROOT);
        if (!MANAGER.equals(named) && !ACCOUNTS.equals(named)) {
            throw new IllegalArgumentException("role must be MANAGER or ACCOUNTS.");
        }
        return new Gate(caller, named);
    }

    // ── Lists ────────────────────────────────────────────────────────

    /**
     * The Pending tab: agreements awaiting my gate in their current round,
     * routed to me or un-routed, newest change first. Each item is
     * {application, approvals (every round, oldest first), myRole}.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> queue(String role, Long callerId) {
        Gate gate = resolveGate(callerId, role);
        List<WebAgreementApproval> rows = new ArrayList<>();
        for (WebAgreementApproval row : approvalRepository.findByStatusAndRole(PENDING, gate.role())) {
            // A routed gate is its approver's alone; an un-routed one is role-wide.
            if (row.getApproverUserId() != null && !row.getApproverUserId().equals(gate.callerId())) {
                continue;
            }
            rows.add(row);
        }
        // The agreements and their current rounds in one query each, not per row.
        Set<Long> ids = new LinkedHashSet<>();
        for (WebAgreementApproval row : rows) ids.add(row.getAgreementId());
        Map<Long, WebAgreement> byId = new HashMap<>();
        Map<Long, Integer> maxRounds = new HashMap<>();
        if (!ids.isEmpty()) {
            for (WebAgreement a : agreementRepository.findAllById(ids)) byId.put(a.getId(), a);
            for (Object[] r : approvalRepository.maxRounds(ids)) {
                maxRounds.put(((Number) r[0]).longValue(), r[1] == null ? null : ((Number) r[1]).intValue());
            }
        }
        List<WebAgreement> agreements = new ArrayList<>();
        for (WebAgreementApproval row : rows) {
            WebAgreement a = byId.get(row.getAgreementId());
            if (a == null || Boolean.TRUE.equals(a.getDeleted())) continue;
            if (!WebAgreement.Status.AWAITING_APPROVALS.name().equals(a.getStatus())) continue;
            Integer maxRound = maxRounds.get(a.getId());
            if (maxRound != null && !maxRound.equals(row.getRound())) continue;
            agreements.add(a);
        }
        agreements.sort(NEWEST_FIRST);
        WebAgreementRules.stripSensitivePii(entityManager, agreements);
        Map<Long, List<WebAgreementApproval>> approvals = agreements.isEmpty() ? Map.of()
                : WebAgreementApprovalSummary.oldestFirstByAgreement(approvalRepository.findByAgreementIdIn(
                        agreements.stream().map(WebAgreement::getId).distinct().toList()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (WebAgreement a : agreements) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("application", a);
            item.put("approvals", approvals.getOrDefault(a.getId(), List.of()));
            item.put("myRole", gate.role());
            out.add(item);
        }
        return out;
    }

    /**
     * The All agreements tab: every agreement with a gate of my role routed
     * to me, in any round and status, newest change first, with the owner's
     * name and the approval summary the ERM list shows.
     */
    @Transactional(readOnly = true)
    public List<WebAgreement> applications(String role, Long callerId) {
        Gate gate = resolveGate(callerId, role);
        Set<Long> ids = new LinkedHashSet<>();
        for (WebAgreementApproval row : approvalRepository.findByRoleAndApproverUserId(gate.role(), gate.callerId())) {
            if (row.getAgreementId() != null) ids.add(row.getAgreementId());
        }
        if (ids.isEmpty()) return List.of();
        List<WebAgreement> agreements = new ArrayList<>();
        for (WebAgreement a : agreementRepository.findAllById(ids)) {
            if (!Boolean.TRUE.equals(a.getDeleted())) agreements.add(a);
        }
        access.populateOwnerNames(agreements);
        approvalSummary.populate(agreements);
        agreements.sort(NEWEST_FIRST);
        WebAgreementRules.stripSensitivePii(entityManager, agreements);
        return agreements;
    }

    /**
     * The Approved agreements tab: one record per agreement I approved in
     * this role (my latest approval), newest first: {appId, consultantName,
     * consultantEmail, ermId, ermName, phase (of that gate), decidedAt,
     * status (now), hasPhase1Signed (Manager only)}.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> approved(String role, Long callerId) {
        Gate gate = resolveGate(callerId, role);
        List<WebAgreementApproval> rows = approvalRepository
                .findByStatusAndRoleAndDecidedByOrderByDecidedAtDesc(APPROVED, gate.role(), gate.callerId());
        Set<Long> seen = new HashSet<>();
        List<WebAgreementApproval> latest = new ArrayList<>();
        List<WebAgreement> agreements = new ArrayList<>();
        for (WebAgreementApproval row : rows) {
            if (!seen.add(row.getAgreementId())) continue; // the latest only (ordered newest first)
            WebAgreement a = agreementRepository.findById(row.getAgreementId()).orElse(null);
            if (a == null || Boolean.TRUE.equals(a.getDeleted())) continue;
            latest.add(row);
            agreements.add(a);
        }
        access.populateOwnerNames(agreements);
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < agreements.size(); i++) {
            WebAgreement a = agreements.get(i);
            WebAgreementApproval row = latest.get(i);
            String ermName = a.getOwnerName();
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("appId", a.getApplicationId());
            rec.put("consultantName", a.getConsultantName());
            rec.put("consultantEmail", a.getConsultantEmail());
            rec.put("ermId", a.getOwnerUserId());
            rec.put("ermName", ermName == null || ermName.isBlank() ? "(unassigned ERM)" : ermName);
            rec.put("phase", row.getPhase());
            rec.put("decidedAt", row.getDecidedAt() == null ? null : row.getDecidedAt().toString());
            rec.put("status", a.getStatus());
            // A Phase 1 signed copy to preview: the Manager's only.
            rec.put("hasPhase1Signed", MANAGER.equals(gate.role())
                    && WebAgreementRules.nonBlank(a.getPhase1FinalPdfS3Key()));
            out.add(rec);
        }
        return out;
    }

    // ── Documents ────────────────────────────────────────────────────

    /**
     * The Pending tab's preview: the version the ERM routed for this round
     * as clean page images {pages, pageCount, viewerEmail, versionNumber},
     * or a live ERM-preview render when no version was routed. Current-round
     * gate only. Any failure: 500 "Couldn't render the selected version.".
     */
    public Map<String, Object> versionPreviewImages(String applicationId, String role, Long callerId) {
        Gate gate = resolveGate(callerId, role);
        WebAgreement a = requireCurrentGate(applicationId, gate);
        Integer versionNumber = a.getApprovalVersionNumber();
        List<String> pages;
        try {
            byte[] pdf = routedVersionBytes(a);
            if (pdf == null) {
                // A round with no version (verified before versions existed): live render.
                pdf = renderer.renderErmPreviewPdf(a);
            }
            pages = pngPages(pdf, gate);
        } catch (Exception e) {
            throw failed("Couldn't render the selected version.", applicationId, e);
        }
        Map<String, Object> payload = pagesPayload(pages, gate);
        payload.put("versionNumber", versionNumber);
        return payload;
    }

    /**
     * The All agreements tab's preview: the latest version (whichever round
     * reviewed it), or a live ERM-preview render when there is none. Anyone
     * routed this agreement in my role, in any round. Any failure: 500
     * "Couldn't render the latest version.".
     */
    public Map<String, Object> latestVersionPreviewImages(String applicationId, String role, Long callerId) {
        Gate gate = resolveGate(callerId, role);
        WebAgreement a = requireRoutedAnyRound(applicationId, gate);
        List<String> pages;
        try {
            byte[] pdf = latestVersionBytes(a);
            if (pdf == null) {
                pdf = renderer.renderErmPreviewPdf(a);
            }
            pages = pngPages(pdf, gate);
        } catch (Exception e) {
            throw failed("Couldn't render the latest version.", applicationId, e);
        }
        return pagesPayload(pages, gate);
    }

    /**
     * The executed agreement (every signature, a live final render) as page
     * images, once the ERM has countersigned (409 before). Current-round
     * gate only. Any failure: 500 "Couldn't render the signed agreement.".
     */
    public Map<String, Object> signedPreviewImages(String applicationId, String role, Long callerId) {
        Gate gate = resolveGate(callerId, role);
        WebAgreement a = requireCurrentGate(applicationId, gate);
        if (!WebAgreement.Status.COMPLETED.name().equals(a.getStatus())) {
            throw new Refused(409, NOT_SIGNED_YET);
        }
        List<String> pages;
        try {
            pages = pngPages(renderer.renderFinalPdf(a), gate);
        } catch (Exception e) {
            throw failed("Couldn't render the signed agreement.", applicationId, e);
        }
        return pagesPayload(pages, gate);
    }

    /**
     * The stored Phase 1 signed copy (taken at the Phase 1 countersign) as
     * page images. Managers only (403), and only one who approved this
     * agreement in any round; 409 when no copy is on file. Any failure: 500
     * "Couldn't render the Phase 1 signed agreement.".
     */
    public Map<String, Object> phase1SignedPreviewImages(String applicationId, String role, Long callerId) {
        Gate gate = resolveGate(callerId, role);
        if (!MANAGER.equals(gate.role())) {
            throw new Refused(403, MANAGER_ONLY);
        }
        WebAgreement a = requireApprovedByCaller(applicationId, gate);
        String key = a.getPhase1FinalPdfS3Key();
        if (!WebAgreementRules.nonBlank(key)) {
            throw new Refused(409, NO_PHASE1_COPY);
        }
        List<String> pages;
        try {
            pages = pngPages(storedPdf(key), gate);
        } catch (Exception e) {
            throw failed("Couldn't render the Phase 1 signed agreement.", applicationId, e);
        }
        return pagesPayload(pages, gate);
    }

    /**
     * The approver's own PDF of an agreement they approved in this role (any
     * round), the only PDF the approver surface hands out:
     * <ul>
     *   <li>{@code final} (also no {@code doc}): the executed agreement, once
     *       COMPLETED (409 before): the stored final PDF, or a live final
     *       render when it is missing or unreadable;</li>
     *   <li>{@code phase1}: the stored Phase 1 signed copy, Managers only
     *       (403), 409 when none is on file;</li>
     *   <li>{@code approved}: the LATEST version (not necessarily the one I
     *       reviewed), or a live ERM-preview render when there is none.</li>
     * </ul>
     * Anything else is 400. Refusals are {@link Refused}; a read or render
     * failure is {@link DocumentFailed}. The download is logged, with no event.
     */
    public WebAgreementFileService.Download download(String applicationId, String doc, String role, Long callerId) {
        Gate gate = resolveGate(callerId, role);
        String kind = doc == null || doc.isBlank() ? "final" : doc.trim().toLowerCase(Locale.ROOT);
        if (!"final".equals(kind) && !"phase1".equals(kind) && !"approved".equals(kind)) {
            throw new Refused(400, "doc must be final, phase1 or approved.");
        }
        if ("phase1".equals(kind) && !MANAGER.equals(gate.role())) {
            throw new Refused(403, MANAGER_ONLY);
        }
        WebAgreement a = requireApprovedByCaller(applicationId, gate);
        if ("phase1".equals(kind) && !WebAgreementRules.nonBlank(a.getPhase1FinalPdfS3Key())) {
            throw new Refused(409, NO_PHASE1_COPY);
        }
        if ("final".equals(kind) && !WebAgreement.Status.COMPLETED.name().equals(a.getStatus())) {
            throw new Refused(409, NOT_SIGNED_YET);
        }

        byte[] bytes;
        String prefix;
        try {
            if ("phase1".equals(kind)) {
                bytes = storedPdf(a.getPhase1FinalPdfS3Key());
                prefix = "phase1-signed-";
            } else if ("approved".equals(kind)) {
                bytes = latestVersionBytes(a);
                if (bytes == null) {
                    bytes = renderer.renderErmPreviewPdf(a);
                }
                prefix = "approved-copy-";
            } else {
                bytes = storedFinalPdf(a);
                if (bytes == null) {
                    bytes = renderer.renderFinalPdf(a);
                }
                prefix = "signed-";
            }
        } catch (Exception e) {
            log.error("Approver download ({}) failed for web agreement {}", kind, applicationId, e);
            throw new DocumentFailed("Couldn't prepare the " + kind + " PDF.", e);
        }

        // The approver surface is otherwise image-only, so leave a trace.
        log.info("Approver {} ({}) downloaded '{}' for agreement {}", gate.callerId(), gate.role(), kind, applicationId);
        String filename = prefix + WebAgreementDocumentEngine.buildPdfFilename(a);
        return new WebAgreementFileService.Download(bytes, "application/pdf", filename);
    }

    // ── Decisions ────────────────────────────────────────────────────

    /**
     * Approve ({@code approve=true}) or ask for a revision (note required)
     * on my gate in the current round (the console's approverDecision, one
     * transaction). Records who, when and from where. Approving the last
     * pending gate of the round makes the agreement READY_TO_SIGN; a
     * revision request makes it APPROVAL_REVISION_REQUESTED with the note,
     * prefixed "[ROLE] ", for the ERM, and changes no other gate (in Phase 2
     * the other gate stays PENDING). Returns the agreement unstripped: the
     * controller removes the PII after this has committed.
     */
    @Transactional
    public WebAgreement decide(String applicationId, boolean approve, String note, String role,
                               Long callerId, HttpServletRequest request) {
        Gate gate = resolveGate(callerId, role);
        WebAgreement a = live(applicationId);
        if (!WebAgreement.Status.AWAITING_APPROVALS.name().equals(a.getStatus())) {
            throw new IllegalStateException(
                    "This agreement is not awaiting approvals (status=" + a.getStatus() + ").");
        }
        Integer round = approvalRepository.maxRound(a.getId());
        if (round == null) {
            throw new IllegalStateException("No approval round is open.");
        }
        // 404 (not 409), so it can't be told apart from a gate routed to someone else.
        WebAgreementApproval row = approvalRepository
                .findFirstByAgreementIdAndRoleAndRound(a.getId(), gate.role(), round)
                .orElseThrow(WebAgreementApproverService::notFound);
        if (!PENDING.equals(row.getStatus())) {
            throw new IllegalStateException("This gate has already been decided.");
        }
        if (row.getApproverUserId() != null && !row.getApproverUserId().equals(gate.callerId())) {
            throw notFound();
        }
        if (!approve && (note == null || note.isBlank())) {
            throw new IllegalArgumentException("A note is required when requesting a revision.");
        }

        String approverName = gate.caller().getFullName();
        String ip = AcknowledgmentService.clientIp(request);
        row.setDecidedBy(gate.callerId());
        row.setDecidedByName(approverName);
        row.setDecidedAt(LocalDateTime.now());
        row.setDecidedIp(ip);
        row.setNote(note);

        if (approve) {
            row.setStatus(APPROVED);
            approvalRepository.save(row);
            eventService.append(a.getId(),
                    WebAgreementEvent.EventType.APPROVAL_APPROVED,
                    WebAgreementEvent.ActorType.ERM, gate.callerId(),
                    Map.of("role", gate.role(), "round", round,
                            "approver", approverName == null ? "" : approverName,
                            "ip", ip == null ? "" : ip),
                    request);
            List<WebAgreementApproval> rows = approvalRepository.findByAgreementIdAndRound(a.getId(), round);
            boolean allApproved = !rows.isEmpty() && rows.stream().allMatch(r -> APPROVED.equals(r.getStatus()));
            if (allApproved) {
                a.setStatus(WebAgreement.Status.READY_TO_SIGN.name());
                agreementRepository.save(a);
            }
        } else {
            row.setStatus(REVISION_REQUESTED);
            approvalRepository.save(row);
            a.setStatus(WebAgreement.Status.APPROVAL_REVISION_REQUESTED.name());
            a.setCurrentRevisionRemarks("[" + gate.role() + "] " + note);
            agreementRepository.save(a);
            eventService.append(a.getId(),
                    WebAgreementEvent.EventType.APPROVAL_REVISION_REQUESTED,
                    WebAgreementEvent.ActorType.ERM, gate.callerId(),
                    Map.of("role", gate.role(), "round", round,
                            "approver", approverName == null ? "" : approverName,
                            "note", note),
                    request);
        }
        return a;
    }

    // ── Access gates (all "not found", so ids can't be probed) ───────

    /**
     * The console's getForApprover: my gate exists in the agreement's
     * current round and is routed to me or to no one.
     */
    WebAgreement requireCurrentGate(String applicationId, Gate gate) {
        WebAgreement a = live(applicationId);
        Integer round = approvalRepository.maxRound(a.getId());
        Optional<WebAgreementApproval> row = round == null ? Optional.empty()
                : approvalRepository.findFirstByAgreementIdAndRoleAndRound(a.getId(), gate.role(), round);
        boolean canView = row.isPresent()
                && (row.get().getApproverUserId() == null
                    || row.get().getApproverUserId().equals(gate.callerId()));
        if (!canView) throw notFound();
        return a;
    }

    /** The console's getForApproverAnyRound: a gate of my role was routed to me in some round. */
    WebAgreement requireRoutedAnyRound(String applicationId, Gate gate) {
        WebAgreement a = live(applicationId);
        if (!approvalRepository.existsByAgreementIdAndRoleAndApproverUserId(a.getId(), gate.role(), gate.callerId())) {
            throw notFound();
        }
        return a;
    }

    /** The console's getForApproverWhoApproved: I approved a gate of my role in some round. */
    WebAgreement requireApprovedByCaller(String applicationId, Gate gate) {
        WebAgreement a = live(applicationId);
        if (!approvalRepository.existsByAgreementIdAndRoleAndStatusAndDecidedBy(
                a.getId(), gate.role(), APPROVED, gate.callerId())) {
            throw notFound();
        }
        return a;
    }

    /** The agreement, unless it doesn't exist or is archived (404). */
    private WebAgreement live(String applicationId) {
        WebAgreement a = agreementRepository.findByApplicationId(applicationId)
                .orElseThrow(WebAgreementApproverService::notFound);
        if (Boolean.TRUE.equals(a.getDeleted())) throw notFound();
        return a;
    }

    private static ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("Agreement not found.");
    }

    // ── Helpers ──────────────────────────────────────────────────────

    /**
     * The stored PDF of the version routed for this round, or null when none
     * was routed or that version doesn't exist (the caller renders live).
     */
    private byte[] routedVersionBytes(WebAgreement a) {
        Integer n = a.getApprovalVersionNumber();
        if (n == null) return null;
        return versionRepository.findByAgreementIdAndVersionNumber(a.getId(), n)
                .map(v -> storedPdf(v.getS3Key()))
                .orElse(null);
    }

    /** The stored PDF of the highest version, or null when there is none (the caller renders live). */
    private byte[] latestVersionBytes(WebAgreement a) {
        return versionRepository.findTopByAgreementIdOrderByVersionNumberDesc(a.getId())
                .map(WebAgreementVersion::getS3Key)
                .map(this::storedPdf)
                .orElse(null);
    }

    /**
     * A stored PDF's bytes. A file that can't be found is a failure (the
     * console's read of a missing object throws), never a live render.
     */
    private byte[] storedPdf(String stored) {
        byte[] bytes = fileService.readBytes(stored);
        if (bytes == null || bytes.length == 0) {
            throw new IllegalStateException("The stored PDF could not be found.");
        }
        return bytes;
    }

    /**
     * The stored executed PDF, or null when there is none or it can't be
     * read, so the caller renders it live from the agreement as it is.
     */
    private byte[] storedFinalPdf(WebAgreement a) {
        String key = a.getS3Key();
        if (!WebAgreementRules.nonBlank(key)) return null;
        try {
            byte[] bytes = fileService.readBytes(key);
            if (bytes != null && bytes.length > 0) return bytes;
            log.warn("Stored final PDF missing for web agreement {}; falling back to a live render",
                    a.getApplicationId());
        } catch (RuntimeException e) {
            log.warn("Stored final PDF unreadable for web agreement {} ({}); falling back to a live render",
                    a.getApplicationId(), e.toString());
        }
        return null;
    }

    /** One clean PNG per page (110 DPI, no watermark), base64-encoded. */
    private List<String> pngPages(byte[] pdf, Gate gate) {
        List<byte[]> images = renderer.renderCleanPageImages(pdf, gate.caller().getEmail());
        List<String> pages = new ArrayList<>(images.size());
        for (byte[] png : images) {
            pages.add(Base64.getEncoder().encodeToString(png));
        }
        return pages;
    }

    private static Map<String, Object> pagesPayload(List<String> pages, Gate gate) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pages", pages);
        payload.put("pageCount", pages.size());
        payload.put("viewerEmail", gate.caller().getEmail());
        return payload;
    }

    private static DocumentFailed failed(String message, String applicationId, Exception e) {
        log.error("Approver preview failed for web agreement {}: {}", applicationId, e.getMessage(), e);
        return new DocumentFailed(message, e);
    }
}
