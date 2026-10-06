package com.spire.backend.service;

import com.spire.backend.entity.AgreementRequest;
import com.spire.backend.entity.ProgramSelection;
import com.spire.backend.entity.User;
import com.spire.backend.entity.WebAgreement;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.repository.AgreementRequestRepository;
import com.spire.backend.repository.ProgramSelectionRepository;
import com.spire.backend.repository.UserRepository;
import com.spire.backend.repository.WebAgreementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The real agreement step for participants: after the program consent,
 * the participant clicks "I'm ready to sign the agreement"; a website ERM
 * starts the agreement from the ERM dashboard's Agreements tab
 * ({@link WebAgreementStaffService}) and the participant fills and signs it
 * inside the website. It never reads or writes the office's agreements
 * console, and sends no emails until the two are merged
 * ({@link WebAgreementSettings}).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MasterAgreementService {

    /** Where the participant fills and signs a website agreement. */
    static final String WEBSITE_LINK = "/dashboard/agreement";

    private final AgreementRequestRepository requestRepository;
    private final WebAgreementRepository webAgreementRepository;
    private final UserRepository userRepository;
    private final ProgramSelectionRepository programSelectionRepository;
    private final EmailTemplateService emailTemplateService;
    private final RecordService recordService;
    private final WebAgreementSettings settings;

    @Value("${app.url:https://sageitco.com}")
    private String appUrl;

    /** Where the agreement is, in its five steps. */
    public record Progress(int step, String stage, boolean yourTurn) {}

    /**
     * The website agreement's steps, from the participant's side. VERIFIED
     * is split by the ERM's verify: signed and being checked (step 2), or
     * verified (step 3, internal approval comes next). The approval and
     * countersign statuses aren't used yet.
     */
    static Progress websiteProgressOf(WebAgreement agreement) {
        String status = agreement.getStatus();
        if (status == null) return new Progress(1, "Ready for you to fill and sign", true);
        return switch (WebAgreement.Status.valueOf(status)) {
            case SUBMITTED -> new Progress(1, "Ready for you to fill and sign", true);
            case REVISION_REQUESTED -> new Progress(1, "Your ERM asked for changes", true);
            case VERIFIED -> Boolean.TRUE.equals(agreement.getConsultantCopyReleased())
                    ? new Progress(3, "Verified by your ERM. Internal approval comes next", false)
                    : new Progress(2, "Signed by you; your ERM is checking it", false);
            case AWAITING_APPROVALS, APPROVAL_REVISION_REQUESTED -> new Progress(3, "Internal approval", false);
            case READY_TO_SIGN -> new Progress(4, "Waiting for the countersignature", false);
            case COMPLETED -> new Progress(5, "Executed", false);
            case CANCELLED -> new Progress(0, "Closed", false);
        };
    }

    /** The participant's current website agreement (newest live, non-cancelled one). */
    Optional<WebAgreement> websiteAgreementFor(Long userId) {
        if (userId == null) return Optional.empty();
        return webAgreementRepository.findByParticipantUserIdAndDeletedFalseOrderByCreatedAtDesc(userId)
                .stream().filter(a -> !WebAgreement.Status.CANCELLED.name().equals(a.getStatus())).findFirst();
    }

    /** What the participant's dashboard shows for the agreement step (their website agreement). */
    @Transactional(readOnly = true)
    public Map<String, Object> status(Long userId) {
        User user = user(userId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("consentSigned", Boolean.TRUE.equals(user.getAgreementComplete()));
        Optional<AgreementRequest> request = requestRepository.findByUserId(userId);
        out.put("requested", request.isPresent());
        out.put("requestedAt", request.map(AgreementRequest::getRequestedAt).orElse(null));
        out.put("agreement", websiteAgreementFor(userId)
                .map(a -> agreementView(websiteProgressOf(a), "WEBSITE", WEBSITE_LINK, a.getUpdatedAt()))
                .orElse(null));
        return out;
    }

    private static Map<String, Object> agreementView(Progress p, String source, String link, LocalDateTime updatedAt) {
        Map<String, Object> ag = new LinkedHashMap<>();
        ag.put("step", p.step());
        ag.put("totalSteps", 5);
        ag.put("stage", p.stage());
        ag.put("yourTurn", p.yourTurn());
        ag.put("executed", p.step() == 5);
        ag.put("link", link);
        ag.put("updatedAt", updatedAt);
        ag.put("source", source);
        return ag;
    }

    /**
     * "I'm ready to sign the agreement": records it once. The participant then
     * shows on the ERM dashboard's Agreements tab; the website ERMs are only
     * emailed once website-agreement emails are switched on.
     */
    @Transactional
    public Map<String, Object> request(Long userId) {
        User user = user(userId);
        if (!Boolean.TRUE.equals(user.getAgreementComplete())) {
            throw new IllegalStateException("Sign your consent first.");
        }
        if (requestRepository.findByUserId(userId).isEmpty()) {
            requestRepository.save(AgreementRequest.builder().userId(userId).build());
            recordService.record(userId, "AGREEMENT_REQUESTED", RecordService.Category.ACCOUNT,
                    "Ready to sign the agreement", "The participant asked for their agreement", Map.of());
            // Every active ERM (no ERM is assigned yet at this point), like the documents check.
            for (User erm : settings.emailsEnabled() ? userRepository.findAll() : List.<User>of()) {
                if (Boolean.FALSE.equals(erm.getIsActive()) || !"ERM".equals(roleOf(erm))) continue;
                try {
                    emailTemplateService.sendWebAgreementRequestedEmail(erm, user);
                } catch (Exception e) {
                    log.warn("Couldn't tell ERM {} about participant {}: {}", erm.getId(), userId, e.getMessage());
                }
            }
        }
        return status(userId);
    }

    /** One participant as the ERM's create form uses it (their details, filled in). */
    public record ReadyRow(Long userId, String participantId, String fullName, String firstName, String middleName,
                           String lastName, String email, String phone, String technology, String program,
                           String targetJobTitle, LocalDateTime requestedAt) {}

    /**
     * Participants who asked for their agreement and have no open one yet
     * (no live, non-cancelled website agreement).
     */
    @Transactional(readOnly = true)
    public List<ReadyRow> readyForAgreement() {
        List<AgreementRequest> requests = requestRepository.findAllByOrderByRequestedAtAsc();
        if (requests.isEmpty()) return List.of();
        Set<Long> withWebsiteAgreement = webAgreementRepository
                .findByParticipantUserIdInAndDeletedFalse(requests.stream().map(AgreementRequest::getUserId).toList())
                .stream()
                .filter(a -> !WebAgreement.Status.CANCELLED.name().equals(a.getStatus()))
                .map(WebAgreement::getParticipantUserId)
                .collect(Collectors.toSet());
        return requests.stream()
                .filter(r -> !withWebsiteAgreement.contains(r.getUserId()))
                .map(r -> userRepository.findById(r.getUserId()).map(u -> Map.entry(r, u)).orElse(null))
                .filter(java.util.Objects::nonNull)
                .filter(e -> !Boolean.FALSE.equals(e.getValue().getIsActive()))
                .map(e -> readyRow(e.getValue(), e.getKey().getRequestedAt()))
                .toList();
    }

    /**
     * One participant's details for the create form: only someone on the
     * ready list (an active participant who asked for their agreement and
     * has none open). Anyone else is "not found", so the form can't be used
     * to read other accounts.
     */
    @Transactional(readOnly = true)
    public ReadyRow readyRow(Long userId) {
        User u = userId == null ? null : userRepository.findById(userId).orElse(null);
        Optional<AgreementRequest> request = u == null ? Optional.empty() : requestRepository.findByUserId(userId);
        if (u == null || u.getParticipantId() == null || u.getParticipantId().isBlank()
                || Boolean.FALSE.equals(u.getIsActive()) || request.isEmpty()
                || websiteAgreementFor(userId).isPresent()) {
            throw new ResourceNotFoundException("This participant isn't waiting for an agreement.");
        }
        return readyRow(u, request.get().getRequestedAt());
    }

    private ReadyRow readyRow(User u, LocalDateTime requestedAt) {
        String[] parts = u.getFullName() == null ? new String[0] : u.getFullName().trim().split("\\s+");
        String first = parts.length > 0 ? parts[0] : "";
        String last = parts.length > 1 ? parts[parts.length - 1] : "";
        String middle = parts.length > 2 ? String.join(" ", java.util.Arrays.copyOfRange(parts, 1, parts.length - 1)) : "";
        ProgramSelection ps = programSelectionRepository.findFirstByUserIdOrderBySelectionDateDesc(u.getId()).orElse(null);
        return new ReadyRow(u.getId(), u.getParticipantId(), u.getFullName(), first, middle, last, u.getEmail(),
                u.getPhone(),
                ps != null && ps.getSkillset() != null ? ps.getSkillset() : u.getSelectedTechnology(),
                ps == null ? null : ps.getProgram(),
                ps == null ? null : ps.getTargetJobTitle(),
                requestedAt);
    }

    private static String roleOf(User u) {
        return u.getRole() == null || u.getRole().getName() == null ? "" : u.getRole().getName().toUpperCase();
    }

    private User user(Long id) {
        return userRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("User", "id", id));
    }
}
