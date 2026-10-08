package com.spire.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spire.backend.controller.WebAgreementAdminController;
import com.spire.backend.controller.WebAgreementTeamController;
import com.spire.backend.dto.ApiResponse;
import com.spire.backend.entity.Role;
import com.spire.backend.entity.User;
import com.spire.backend.entity.WebAgreement;
import com.spire.backend.entity.WebAgreementEvent;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.exception.StorageUnavailableException;
import com.spire.backend.repository.UserRepository;
import com.spire.backend.repository.WebAgreementEventRepository;
import com.spire.backend.repository.WebAgreementRepository;
import com.spire.backend.repository.WebAgreementVersionRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The System Admin's agreement tools (the console's super-admin delete,
 * regenerate and revoke): only a System Admin; a delete is a soft delete at
 * any status with an event, after which the agreement is "not found"; both
 * bulk tools are a dry run unless told otherwise and a dry run writes
 * nothing; regenerate stores new files, repoints the keys and the hash and
 * mints no version row; revoke sends every executed agreement back to
 * VERIFIED keeping the ERM's name and title. One failure is counted and the
 * run goes on. Nothing is deleted from storage; no email.
 */
class WebAgreementMaintenanceServiceTest {

    private static final long PAT = 10L, ERM = 20L, OPS = 30L, SYS = 31L, LEGACY_ADMIN = 32L, OFF_SYS = 33L,
            MGR = 50L;

    private static final String BUSY = "The document service is busy. Please try again in a minute.";

    private final List<User> users = new ArrayList<>();
    private final List<WebAgreement> agreements = new ArrayList<>();
    private final List<WebAgreementEvent> events = new ArrayList<>();
    /** The website storage: stored value → bytes. */
    private final Map<String, byte[]> stored = new HashMap<>();
    /** Every agreement save, by application id. */
    private final List<String> saves = new ArrayList<>();
    private final ObjectMapper mapper = new ObjectMapper();

    private DocumentStorageService storage;
    private WebAgreementRenderer renderer;
    private WebAgreementVersionRepository versions;
    private WebAgreementRepository repo;
    private WebAgreementAccess access;
    private WebAgreementMaintenanceService service;
    private WebAgreementAdminController controller;
    private MockHttpServletRequest request;
    private int uploads;

    @BeforeEach
    void setUp() throws Exception {
        user(PAT, "PARTICIPANT", "Pat Lee", true);
        user(ERM, "ERM", "Erin Manager", true);
        user(OPS, "OPERATIONS_ADMIN", "Ops Admin", true);
        user(SYS, "SYSTEM_ADMIN", "Sys Admin", true);
        user(LEGACY_ADMIN, "ADMIN", "Old Admin", true);
        user(OFF_SYS, "SYSTEM_ADMIN", "Off Sys", false);
        user(MGR, "MANAGER", "Mona Manager", true);

        UserRepository userRepo = mock(UserRepository.class);
        when(userRepo.findById(anyLong())).thenAnswer(inv -> users.stream()
                .filter(u -> u.getId().equals(inv.getArgument(0))).findFirst());

        repo = mock(WebAgreementRepository.class);
        when(repo.save(any())).thenAnswer(inv -> {
            WebAgreement a = inv.getArgument(0);
            saves.add(a.getApplicationId());
            return a;
        });
        when(repo.findByApplicationId(anyString())).thenAnswer(inv -> agreements.stream()
                .filter(a -> a.getApplicationId().equals(inv.getArgument(0))).findFirst());
        when(repo.findByStatusAndDeletedFalse(anyString())).thenAnswer(inv -> agreements.stream()
                .filter(a -> a.getStatus().equals(inv.getArgument(0)) && !Boolean.TRUE.equals(a.getDeleted()))
                .toList());

        WebAgreementEventRepository eventRepo = mock(WebAgreementEventRepository.class);
        when(eventRepo.save(any())).thenAnswer(inv -> {
            events.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(eventRepo.findByAgreementIdOrderByCreatedAtDesc(any())).thenAnswer(inv -> events.stream()
                .filter(e -> e.getAgreementId().equals(inv.getArgument(0))).toList());

        // The real file service over a fake website storage.
        storage = mock(DocumentStorageService.class);
        when(storage.upload(anyLong(), anyString(), any(), anyString())).thenAnswer(inv -> {
            String key = "s3:participant-documents/" + inv.getArgument(0) + "/" + inv.getArgument(1)
                    + "#" + (++uploads);
            stored.put(key, inv.getArgument(2));
            return new DocumentStorageService.StoredFile(key, key);
        });
        when(storage.readBytes(anyString())).thenAnswer(inv -> stored.get(inv.<String>getArgument(0)));
        WebAgreementFileService files = new WebAgreementFileService(storage, mock(HeicTranscoder.class));

        renderer = mock(WebAgreementRenderer.class);
        when(renderer.renderFinalPdf(any())).thenReturn(pdf(3));
        when(renderer.renderErmPreviewPdf(any())).thenReturn(pdf(2));

        // The real version service and certificate; the version rows are watched.
        versions = mock(WebAgreementVersionRepository.class);
        WebAgreementVersionService versionService = new WebAgreementVersionService(renderer,
                new WebAgreementCertificateService(eventRepo), files, versions);

        access = new WebAgreementAccess(repo, userRepo);
        service = new WebAgreementMaintenanceService(repo, renderer, files, versionService,
                new WebAgreementEventService(eventRepo), access);
        controller = new WebAgreementAdminController(service);

        request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.9");
    }

    // ── Fixtures ─────────────────────────────────────────────────────

    private void user(long id, String role, String name, boolean active) {
        users.add(User.builder().id(id).fullName(name).email(name.replace(' ', '.').toLowerCase() + "@sageitco.com")
                .role(Role.builder().name(role).build()).isActive(active).build());
    }

    private WebAgreement agreement(String status) {
        WebAgreement a = WebAgreementRulesTest.complete();
        a.setId((long) agreements.size() + 1);
        a.setApplicationId("app-" + a.getId());
        a.setParticipantUserId(PAT);
        a.setOwnerUserId(ERM);
        a.setStatus(status);
        a.setPhase(1);
        a.setConsultantName("Pat Lee");
        a.setEffectiveDate(LocalDate.of(2026, 9, 1));
        agreements.add(a);
        return a;
    }

    /** An executed agreement: verified, countersigned, both PDFs stored. */
    private WebAgreement executed(Integer phase) {
        WebAgreement a = agreement("COMPLETED");
        String id = a.getApplicationId();
        a.setPhase(phase);
        a.setConsultantCopyReleased(true);
        a.setConsultantCopyReleasedAt(LocalDateTime.of(2026, 9, 10, 9, 0));
        a.setConsultantCopyReleasedBy(String.valueOf(ERM));
        a.setConsultantPdfS3Key("s3:old/" + id + "/consultant-version.pdf");
        a.setDocumentHash("old-hash-" + id);
        a.setApprovalVersionNumber(1);
        a.setErmName("Erin Manager");
        a.setErmTitle("Engagement Manager");
        a.setErmSignatureS3Key("s3:old/" + id + "/erm.png");
        a.setErmSignatureDate(LocalDateTime.of(2026, 9, 20, 15, 0));
        a.setS3Key("s3:old/" + id + "/final.pdf");
        a.setPhase1FinalPdfS3Key("s3:old/" + id + "/final-p1.pdf");
        return a;
    }

    private List<WebAgreementEvent> eventsOf(WebAgreement a, WebAgreementEvent.EventType type) {
        return events.stream()
                .filter(e -> e.getAgreementId().equals(a.getId()) && e.getEventType().equals(type.name()))
                .toList();
    }

    private JsonNode meta(WebAgreementEvent e) throws IOException {
        return mapper.readTree(e.getMetadata());
    }

    private static Authentication auth(long userId) {
        return new UsernamePasswordAuthenticationToken(userId, null);
    }

    private static WebAgreementAdminController.RegenerateBody body(Boolean dryRun) {
        WebAgreementAdminController.RegenerateBody b = new WebAgreementAdminController.RegenerateBody();
        b.dryRun = dryRun;
        return b;
    }

    private static byte[] pdf(int pages) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) doc.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private void assertNothingWritten() {
        assertTrue(saves.isEmpty(), "no agreement saved");
        assertTrue(events.isEmpty(), "no event");
        verify(storage, never()).upload(anyLong(), anyString(), any(), anyString());
        verifyNoInteractions(renderer);
        verify(versions, never()).save(any());
    }

    // ── Who ──────────────────────────────────────────────────────────

    @Test
    void onlyAnActiveSystemAdminCanUseTheTools() {
        WebAgreement a = executed(1);
        for (long caller : new long[] {OPS, LEGACY_ADMIN, ERM, MGR, PAT, OFF_SYS, 12345L}) {
            for (Executable call : List.<Executable>of(
                    () -> service.archive(a.getApplicationId(), caller, request),
                    () -> service.regenerateCompleted(true, caller, request),
                    () -> service.regenerateCompleted(false, caller, request),
                    () -> service.revokeErmSignatures(true, caller, request),
                    () -> service.revokeErmSignatures(false, caller, request))) {
                AccessDeniedException e = assertThrows(AccessDeniedException.class, call, "caller " + caller);
                assertEquals("Only a System Admin can do this.", e.getMessage());
            }
        }
        assertFalse(a.getDeleted());
        assertEquals("COMPLETED", a.getStatus());
        assertNothingWritten();
    }

    // ── Delete (archive) ─────────────────────────────────────────────

    @Test
    void aDeleteIsASoftDeleteAtAnyStatusWithAnEvent() throws Exception {
        for (WebAgreement.Status status : WebAgreement.Status.values()) {
            WebAgreement a = status == WebAgreement.Status.COMPLETED ? executed(1) : agreement(status.name());
            LocalDateTime before = LocalDateTime.now();
            service.archive(a.getApplicationId(), SYS, request);

            assertTrue(a.getDeleted(), status.name());
            assertNotNull(a.getDeletedAt());
            assertFalse(a.getDeletedAt().isBefore(before));
            assertEquals(SYS, a.getDeletedBy(), "the System Admin's users.id");
            assertEquals(status.name(), a.getStatus(), "the status is kept");

            List<WebAgreementEvent> archived = eventsOf(a, WebAgreementEvent.EventType.APPLICATION_ARCHIVED);
            assertEquals(1, archived.size());
            WebAgreementEvent e = archived.get(0);
            assertEquals("ERM", e.getActorType());
            assertEquals(SYS, e.getActorUserId(), "the real id, not the console's 0");
            assertEquals("203.0.113.9", e.getIpAddress());
            JsonNode m = meta(e);
            assertEquals(String.valueOf(SYS), m.path("deletedBy").asText());
            assertEquals(status.name(), m.path("previousStatus").asText());
        }
        assertEquals(WebAgreement.Status.values().length, saves.size());
        // The row and its files stay: nothing is removed from storage.
        verify(storage, never()).delete(anyString());
    }

    @Test
    void aDeletedAgreementIsNotFoundEverywhereAndCantBeDeletedTwice() {
        WebAgreement a = executed(1);
        String app = a.getApplicationId();
        assertSame(a, access.requireAccess(app, ERM), "the owner sees it before");

        ResponseEntity<Void> res = controller.deleteAgreement(app, auth(SYS), request);
        assertEquals(204, res.getStatusCode().value());
        assertNull(res.getBody());

        for (long caller : new long[] {ERM, OPS, SYS}) {
            assertThrows(ResourceNotFoundException.class, () -> access.requireAccess(app, caller));
        }
        assertEquals(0, service.regenerateCompleted(true, SYS, request).get("matched"), "the bulk tools skip it");

        ResourceNotFoundException twice = assertThrows(ResourceNotFoundException.class,
                () -> service.archive(app, SYS, request));
        assertEquals("Agreement not found.", twice.getMessage());
        ResourceNotFoundException unknown = assertThrows(ResourceNotFoundException.class,
                () -> service.archive("no-such-agreement", SYS, request));
        assertEquals("Agreement not found.", unknown.getMessage());
        assertEquals(1, saves.size());
        assertEquals(1, eventsOf(a, WebAgreementEvent.EventType.APPLICATION_ARCHIVED).size());
    }

    // ── Dry runs ─────────────────────────────────────────────────────

    @Test
    void aDryRunOnlyCountsTheExecutedAgreementsAndWritesNothing() {
        executed(1);
        executed(2);
        executed(1).setDeleted(true);
        agreement("READY_TO_SIGN");
        agreement("VERIFIED");

        Map<String, Object> regen = service.regenerateCompleted(true, SYS, request);
        assertEquals(List.of("dryRun", "status", "matched", "processed", "regenerated", "failed", "errors"),
                List.copyOf(regen.keySet()));
        assertEquals(true, regen.get("dryRun"));
        assertEquals("COMPLETED", regen.get("status"));
        assertEquals(2, regen.get("matched"), "deleted and other statuses are left out");
        assertEquals(2, regen.get("processed"), "the console counts every match as processed on a dry run");
        assertEquals(0, regen.get("regenerated"));
        assertEquals(0, regen.get("failed"));
        assertEquals(List.of(), regen.get("errors"));

        Map<String, Object> revoke = service.revokeErmSignatures(true, SYS, request);
        assertEquals(List.of("dryRun", "status", "matched", "processed", "reverted", "failed", "errors"),
                List.copyOf(revoke.keySet()));
        assertEquals(true, revoke.get("dryRun"));
        assertEquals(2, revoke.get("matched"));
        assertEquals(2, revoke.get("processed"));
        assertEquals(0, revoke.get("reverted"));

        assertNothingWritten();
        assertTrue(agreements.stream().filter(a -> "COMPLETED".equals(a.getStatus())).allMatch(
                a -> a.getErmSignatureS3Key() != null && a.getS3Key().startsWith("s3:old/")));
    }

    @Test
    void bothToolsAreADryRunUnlessTheBodySaysFalse() {
        executed(1);
        for (WebAgreementAdminController.RegenerateBody b : new WebAgreementAdminController.RegenerateBody[] {
                null, body(null), body(true)}) {
            ResponseEntity<ApiResponse<Map<String, Object>>> regen =
                    controller.regenerateCompletedAgreements(b, auth(SYS), request);
            assertEquals("Dry run — no changes made", regen.getBody().getMessage());
            assertEquals(true, regen.getBody().getData().get("dryRun"));
            ResponseEntity<ApiResponse<Map<String, Object>>> revoke =
                    controller.revokeErmSignatures(b, auth(SYS), request);
            assertEquals("Dry run — no changes made", revoke.getBody().getMessage());
            assertEquals(true, revoke.getBody().getData().get("dryRun"));
        }
        assertNothingWritten();

        ResponseEntity<ApiResponse<Map<String, Object>>> regen =
                controller.regenerateCompletedAgreements(body(false), auth(SYS), request);
        assertEquals("Regeneration complete", regen.getBody().getMessage());
        assertEquals(false, regen.getBody().getData().get("dryRun"));
        assertEquals(1, regen.getBody().getData().get("regenerated"));
        ResponseEntity<ApiResponse<Map<String, Object>>> revoke =
                controller.revokeErmSignatures(body(false), auth(SYS), request);
        assertEquals("ERM signatures revoked", revoke.getBody().getMessage());
        assertEquals(1, revoke.getBody().getData().get("reverted"));
    }

    // ── Regenerate ───────────────────────────────────────────────────

    @Test
    void regenerateRepointsTheKeysAndTheHashWithNoNewVersionRow() throws Exception {
        WebAgreement p1 = executed(1);
        WebAgreement p2 = executed(2);
        WebAgreement unreleased = executed(null);
        unreleased.setConsultantCopyReleased(false);
        WebAgreement waiting = agreement("READY_TO_SIGN");
        String p1OldVersion = p1.getConsultantPdfS3Key();
        String p2Phase1Copy = p2.getPhase1FinalPdfS3Key();
        String unreleasedVersion = unreleased.getConsultantPdfS3Key();

        Map<String, Object> r = service.regenerateCompleted(false, SYS, request);
        assertEquals(false, r.get("dryRun"));
        assertEquals(3, r.get("matched"));
        assertEquals(3, r.get("processed"));
        assertEquals(3, r.get("regenerated"));
        assertEquals(0, r.get("failed"));
        assertEquals(List.of(), r.get("errors"));

        // Phase 1: a new final PDF is both keys; the released version is re-rendered with a new hash.
        assertTrue(p1.getS3Key().contains("/web-agreement-final-p1-"), p1.getS3Key());
        assertEquals(p1.getS3Key(), p1.getPhase1FinalPdfS3Key());
        assertNotEquals(p1OldVersion, p1.getConsultantPdfS3Key());
        assertTrue(p1.getConsultantPdfS3Key().contains("/web-agreement-consultant-version-p1-"));
        byte[] version = stored.get(p1.getConsultantPdfS3Key());
        assertEquals(WebAgreementCertificateService.sha256Hex(version), p1.getDocumentHash(),
                "the hash of the new stored file");
        try (PDDocument doc = Loader.loadPDF(version)) {
            assertEquals(3, doc.getNumberOfPages(), "the ERM preview body + the certificate page");
        }
        try (PDDocument doc = Loader.loadPDF(stored.get(p1.getS3Key()))) {
            assertEquals(3, doc.getNumberOfPages(), "the final render as stored");
        }

        // Phase 2: only s3Key; the Phase 1 copy stays.
        assertTrue(p2.getS3Key().contains("/web-agreement-final-p2-"), p2.getS3Key());
        assertEquals(p2Phase1Copy, p2.getPhase1FinalPdfS3Key());
        assertTrue(p2.getConsultantPdfS3Key().contains("/web-agreement-consultant-version-p2-"));

        // Never released: the version and its hash are left alone; a null phase counts as Phase 1.
        assertTrue(unreleased.getS3Key().contains("/web-agreement-final-p1-"));
        assertEquals(unreleased.getS3Key(), unreleased.getPhase1FinalPdfS3Key());
        assertEquals(unreleasedVersion, unreleased.getConsultantPdfS3Key());
        assertEquals("old-hash-" + unreleased.getApplicationId(), unreleased.getDocumentHash());

        // Status, signatures and the verification are untouched; nothing else was rendered.
        for (WebAgreement a : List.of(p1, p2, unreleased)) {
            assertEquals("COMPLETED", a.getStatus());
            assertNotNull(a.getErmSignatureS3Key());
            assertEquals(1, a.getApprovalVersionNumber());
        }
        assertEquals("READY_TO_SIGN", waiting.getStatus());
        assertNull(waiting.getS3Key());
        verify(renderer, times(3)).renderFinalPdf(any());
        verify(renderer, times(2)).renderErmPreviewPdf(any());
        verify(renderer, never()).renderFinalPdf(waiting);

        // No version row; old files are kept (new files only, nothing deleted).
        verify(versions, never()).save(any());
        verify(storage, never()).delete(anyString());
        assertEquals(5, uploads);
        assertEquals(List.of(p1.getApplicationId(), p2.getApplicationId(), unreleased.getApplicationId()), saves);

        // The event, with the console's reason string.
        WebAgreementEvent e = eventsOf(p1, WebAgreementEvent.EventType.PDF_GENERATED).get(0);
        assertEquals("ERM", e.getActorType());
        assertEquals(SYS, e.getActorUserId());
        assertEquals("203.0.113.9", e.getIpAddress());
        JsonNode m = meta(e);
        assertEquals("regenerated", m.path("kind").asText());
        assertEquals("template-correction-build-AA", m.path("reason").asText());
        assertEquals("s3:old/" + p1.getApplicationId() + "/final.pdf", m.path("oldS3Key").asText());
        assertEquals(p1.getS3Key(), m.path("newS3Key").asText());
        assertEquals("old-hash-" + p1.getApplicationId(), m.path("oldDocumentHash").asText());
        assertEquals(p1.getDocumentHash(), m.path("newDocumentHash").asText());
        JsonNode u = meta(eventsOf(unreleased, WebAgreementEvent.EventType.PDF_GENERATED).get(0));
        assertEquals(u.path("oldDocumentHash").asText(), u.path("newDocumentHash").asText());
        assertEquals(3, events.size());
    }

    @Test
    void aFailedAgreementIsCountedLeftAsItWasAndTheRunGoesOn() {
        WebAgreement first = executed(1);
        WebAgreement busy = executed(1);
        WebAgreement badVersion = executed(1);
        WebAgreement last = executed(1);
        String busyKey = busy.getS3Key();
        String badKey = badVersion.getS3Key();
        String badPhase1 = badVersion.getPhase1FinalPdfS3Key();
        String badHash = badVersion.getDocumentHash();

        // The render permit timed out for one; the version render failed for another.
        when(renderer.renderFinalPdf(busy)).thenThrow(new StorageUnavailableException(BUSY, null));
        when(renderer.renderErmPreviewPdf(badVersion)).thenThrow(
                new WebAgreementRenderer.RenderException(new IllegalStateException("LibreOffice is not installed")));

        Map<String, Object> r = service.regenerateCompleted(false, SYS, request);
        assertEquals(4, r.get("matched"));
        assertEquals(4, r.get("processed"));
        assertEquals(2, r.get("regenerated"));
        assertEquals(2, r.get("failed"));
        assertEquals(List.of(busy.getApplicationId() + ": " + BUSY,
                badVersion.getApplicationId() + ": LibreOffice is not installed"), r.get("errors"));

        // The failed ones are untouched: no key repointed, no save, no event.
        assertEquals(busyKey, busy.getS3Key());
        assertEquals(badKey, badVersion.getS3Key());
        assertEquals(badPhase1, badVersion.getPhase1FinalPdfS3Key());
        assertEquals(badHash, badVersion.getDocumentHash());
        assertEquals(List.of(first.getApplicationId(), last.getApplicationId()), saves);
        assertTrue(eventsOf(busy, WebAgreementEvent.EventType.PDF_GENERATED).isEmpty());
        assertTrue(eventsOf(badVersion, WebAgreementEvent.EventType.PDF_GENERATED).isEmpty());
        assertFalse(first.getS3Key().startsWith("s3:old/"));
        assertFalse(last.getS3Key().startsWith("s3:old/"));
        verify(versions, never()).save(any());
    }

    // ── Revoke ───────────────────────────────────────────────────────

    @Test
    void revokeSendsEveryExecutedAgreementBackToVerifiedKeepingTheNameAndTitle() throws Exception {
        WebAgreement p1 = executed(1);
        WebAgreement p2 = executed(2);
        WebAgreement waiting = agreement("READY_TO_SIGN");
        waiting.setErmName("Kept");
        String p1Signature = p1.getErmSignatureS3Key();
        String p1S3Key = p1.getS3Key();
        String p1Phase1 = p1.getPhase1FinalPdfS3Key();
        String p1Version = p1.getConsultantPdfS3Key();
        String p1Hash = p1.getDocumentHash();

        Map<String, Object> r = service.revokeErmSignatures(false, SYS, request);
        assertEquals(false, r.get("dryRun"));
        assertEquals("COMPLETED", r.get("status"));
        assertEquals(2, r.get("matched"));
        assertEquals(2, r.get("processed"));
        assertEquals(2, r.get("reverted"));
        assertEquals(0, r.get("failed"));
        assertEquals(List.of(), r.get("errors"));

        for (WebAgreement a : List.of(p1, p2)) {
            assertEquals("VERIFIED", a.getStatus());
            assertNull(a.getErmSignatureS3Key(), "the countersignature is cleared");
            assertNull(a.getErmSignatureDate());
            assertEquals("Erin Manager", a.getErmName(), "kept as the re-sign prefill");
            assertEquals("Engagement Manager", a.getErmTitle());
            assertTrue(a.getConsultantCopyReleased(), "still verified, so the ERM can re-send");
            assertEquals(1, a.getApprovalVersionNumber());
        }
        assertEquals(p1S3Key, p1.getS3Key(), "the stored PDFs stay");
        assertEquals(p1Phase1, p1.getPhase1FinalPdfS3Key());
        assertEquals(p1Version, p1.getConsultantPdfS3Key());
        assertEquals(p1Hash, p1.getDocumentHash());
        assertEquals(2, p2.getPhase());
        assertEquals("READY_TO_SIGN", waiting.getStatus(), "only executed agreements");
        assertEquals("Kept", waiting.getErmName());
        assertEquals(List.of(p1.getApplicationId(), p2.getApplicationId()), saves);
        verifyNoInteractions(renderer);
        verify(storage, never()).upload(anyLong(), anyString(), any(), anyString());
        verify(storage, never()).delete(anyString());

        WebAgreementEvent e = eventsOf(p1, WebAgreementEvent.EventType.ERM_SIGNATURE_REVOKED).get(0);
        assertEquals("ERM", e.getActorType());
        assertEquals(SYS, e.getActorUserId());
        assertEquals("203.0.113.9", e.getIpAddress());
        JsonNode m = meta(e);
        assertEquals("COMPLETED", m.path("fromStatus").asText());
        assertEquals("VERIFIED", m.path("toStatus").asText());
        assertEquals(p1Signature, m.path("clearedErmSignatureKey").asText());
        assertEquals("re-sign-after-template-correction", m.path("reason").asText());
        assertEquals(2, events.size());

        // Nothing executed is left, so a second run finds nothing.
        assertEquals(0, service.revokeErmSignatures(false, SYS, request).get("matched"));
    }

    @Test
    void aRevokeThatCantBeSavedIsCountedAndTheRunGoesOn() {
        WebAgreement broken = executed(1);
        WebAgreement fine = executed(1);
        doThrow(new IllegalStateException("database unavailable")).when(repo).save(broken);

        Map<String, Object> r = service.revokeErmSignatures(false, SYS, request);
        assertEquals(1, r.get("reverted"));
        assertEquals(1, r.get("failed"));
        assertEquals(List.of(broken.getApplicationId() + ": database unavailable"), r.get("errors"));
        assertTrue(eventsOf(broken, WebAgreementEvent.EventType.ERM_SIGNATURE_REVOKED).isEmpty());
        assertEquals("VERIFIED", fine.getStatus());
        assertEquals(1, eventsOf(fine, WebAgreementEvent.EventType.ERM_SIGNATURE_REVOKED).size());
    }

    // ── Transactions and the routes ──────────────────────────────────

    @Test
    void theDeleteIsOneTransactionAndTheBulkToolsSaveEachAgreementOnItsOwn() throws Exception {
        Transactional archive = WebAgreementMaintenanceService.class
                .getMethod("archive", String.class, Long.class, HttpServletRequest.class)
                .getAnnotation(Transactional.class);
        assertNotNull(archive);
        assertFalse(archive.readOnly());
        for (String name : List.of("regenerateCompleted", "revokeErmSignatures")) {
            Method m = WebAgreementMaintenanceService.class.getMethod(name, boolean.class, Long.class,
                    HttpServletRequest.class);
            assertNull(m.getAnnotation(Transactional.class), name + " is not one transaction, as the console");
        }
        assertNull(WebAgreementMaintenanceService.class.getAnnotation(Transactional.class));
    }

    @Test
    void theControllerIsSystemAdminOnlyWithItsThreeRoutesBesideTheTeams() {
        Class<WebAgreementAdminController> c = WebAgreementAdminController.class;
        assertArrayEquals(new String[] {"/api/web-agreement-admin"}, c.getAnnotation(RequestMapping.class).value());
        assertEquals("hasRole('SYSTEM_ADMIN')", c.getAnnotation(PreAuthorize.class).value(),
                "never hasRole('ADMIN'), which legacy ADMIN also holds");
        assertArrayEquals(c.getAnnotation(RequestMapping.class).value(),
                WebAgreementTeamController.class.getAnnotation(RequestMapping.class).value());

        Set<String> ours = routes(c);
        assertEquals(new TreeSet<>(List.of("DELETE /agreements/{appId}", "POST /regenerate-completed-agreements",
                "POST /revoke-erm-signatures")), ours);
        Set<String> teams = routes(WebAgreementTeamController.class);
        assertFalse(teams.isEmpty());
        assertTrue(teams.stream().noneMatch(ours::contains), "no route shared with the team controller");
    }

    @Test
    void theRoutesAnswerOverHttpWithTheConsolesShapes() throws Exception {
        WebAgreement a = executed(1);
        WebAgreement b = executed(1);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller,
                new WebAgreementTeamController(mock(WebAgreementAssignmentService.class))).build();

        // No body: a dry run.
        MvcResult dry = mvc.perform(MockMvcRequestBuilders.post("/api/web-agreement-admin/revoke-erm-signatures")
                .principal(auth(SYS))).andReturn();
        assertEquals(200, dry.getResponse().getStatus());
        JsonNode dryJson = mapper.readTree(dry.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals("Dry run — no changes made", dryJson.path("message").asText());
        assertEquals(2, dryJson.path("data").path("matched").asInt());
        assertTrue(saves.isEmpty());

        MvcResult run = mvc.perform(MockMvcRequestBuilders.post("/api/web-agreement-admin/regenerate-completed-agreements")
                .principal(auth(SYS)).contentType(MediaType.APPLICATION_JSON).content("{\"dryRun\": false}"))
                .andReturn();
        assertEquals(200, run.getResponse().getStatus());
        JsonNode runJson = mapper.readTree(run.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals("Regeneration complete", runJson.path("message").asText());
        assertEquals(2, runJson.path("data").path("regenerated").asInt());

        MvcResult deleted = mvc.perform(MockMvcRequestBuilders.delete("/api/web-agreement-admin/agreements/"
                + a.getApplicationId()).principal(auth(SYS))).andReturn();
        assertEquals(204, deleted.getResponse().getStatus());
        assertEquals(0, deleted.getResponse().getContentLength());
        assertTrue(a.getDeleted());
        assertFalse(b.getDeleted());
    }

    private static Set<String> routes(Class<?> c) {
        Set<String> routes = new TreeSet<>();
        for (Method m : c.getDeclaredMethods()) {
            GetMapping get = m.getAnnotation(GetMapping.class);
            PostMapping post = m.getAnnotation(PostMapping.class);
            PutMapping put = m.getAnnotation(PutMapping.class);
            DeleteMapping delete = m.getAnnotation(DeleteMapping.class);
            if (get != null) routes.add("GET " + get.value()[0]);
            if (post != null) routes.add("POST " + post.value()[0]);
            if (put != null) routes.add("PUT " + put.value()[0]);
            if (delete != null) routes.add("DELETE " + delete.value()[0]);
        }
        return routes;
    }
}
