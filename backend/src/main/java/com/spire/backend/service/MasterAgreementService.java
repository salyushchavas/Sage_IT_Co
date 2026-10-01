package com.spire.backend.service;

import com.spire.backend.entity.AgreementRequest;
import com.spire.backend.entity.AgreementUser;
import com.spire.backend.entity.AgreementUserRole;
import com.spire.backend.entity.ConsultantApplication;
import com.spire.backend.entity.ProgramSelection;
import com.spire.backend.entity.User;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.repository.AgreementRequestRepository;
import com.spire.backend.repository.AgreementUserRepository;
import com.spire.backend.repository.ConsultantApplicationRepository;
import com.spire.backend.repository.ProgramSelectionRepository;
import com.spire.backend.repository.UserRepository;
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

import static com.spire.backend.entity.ConsultantApplication.Status;

/**
 * The real agreement step for participants: after the program consent,
 * the participant clicks "I'm ready to sign the agreement"; a console ERM
 * starts the agreement in /agreements from the participant's details and
 * fills their side; the participant fills theirs through the console's own
 * link. This class only reads the console's agreements (by email) and
 * records the request; the console itself is unchanged.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MasterAgreementService {

    /** Console statuses that end an agreement without it being signed; a new one can be started. */
    private static final Set<String> CLOSED = Set.of(Status.CANCELLED.name(), Status.EXPIRED.name());

    private final AgreementRequestRepository requestRepository;
    private final ConsultantApplicationRepository applicationRepository;
    private final AgreementUserRepository agreementUserRepository;
    private final UserRepository userRepository;
    private final ProgramSelectionRepository programSelectionRepository;
    private final EmailTemplateService emailTemplateService;
    private final RecordService recordService;

    @Value("${app.url:https://sageitco.com}")
    private String appUrl;

    /** Where the console's agreement is, in its own five steps. */
    public record Progress(int step, String stage, boolean yourTurn) {}

    /** The console's five-step pipeline, from the participant's side. */
    static Progress progressOf(String status) {
        if (status == null) return new Progress(1, "Sent to you", true);
        return switch (Status.valueOf(status)) {
            case DRAFT, SUBMITTED -> new Progress(1, "Ready for you to fill and sign", true);
            case REVISION_REQUESTED -> new Progress(1, "Changes requested: please update and sign again", true);
            case UPDATED, VERIFIED -> new Progress(2, "Signed by you; your ERM is checking it", false);
            case AWAITING_APPROVALS, APPROVAL_REVISION_REQUESTED -> new Progress(3, "Internal approval", false);
            case READY_TO_SIGN -> new Progress(4, "Waiting for the countersignature", false);
            case SIGNED, COMPLETED -> new Progress(5, "Executed", false);
            case CANCELLED, EXPIRED -> new Progress(0, "Closed", false);
        };
    }

    /** The participant's current agreement in the console, if one was started (newest open one). */
    Optional<ConsultantApplication> agreementFor(User user) {
        return applicationRepository.findByConsultantEmailIgnoreCaseAndDeletedFalseOrderByCreatedAtDesc(user.getEmail())
                .stream().filter(a -> !CLOSED.contains(a.getStatus())).findFirst();
    }

    /** What the participant's dashboard shows for the agreement step. */
    @Transactional(readOnly = true)
    public Map<String, Object> status(Long userId) {
        User user = user(userId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("consentSigned", Boolean.TRUE.equals(user.getAgreementComplete()));
        Optional<AgreementRequest> request = requestRepository.findByUserId(userId);
        out.put("requested", request.isPresent());
        out.put("requestedAt", request.map(AgreementRequest::getRequestedAt).orElse(null));
        agreementFor(user).ifPresentOrElse(a -> {
            Progress p = progressOf(a.getStatus());
            Map<String, Object> ag = new LinkedHashMap<>();
            ag.put("step", p.step());
            ag.put("totalSteps", 5);
            ag.put("stage", p.stage());
            ag.put("yourTurn", p.yourTurn());
            ag.put("executed", p.step() == 5);
            ag.put("link", appUrl + "/consultant/" + a.getApplicationId() + "/login");
            ag.put("updatedAt", a.getUpdatedAt());
            out.put("agreement", ag);
        }, () -> out.put("agreement", null));
        return out;
    }

    /** "I'm ready to sign the agreement": records it once and tells the console ERMs. */
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
            String startLink = appUrl + "/agreements/new?participant=" + userId;
            for (AgreementUser cu : agreementUserRepository.findAll()) {
                if (!cu.isActive()) continue;
                if (cu.getRole() != AgreementUserRole.ERM && cu.getRole() != AgreementUserRole.SUPER_ADMIN) continue;
                try {
                    emailTemplateService.sendAgreementRequestedEmail(cu.getEmail(), cu.getFullName(), user, startLink);
                } catch (Exception e) {
                    log.warn("Couldn't tell console user {} about participant {}: {}", cu.getId(), userId, e.getMessage());
                }
            }
        }
        return status(userId);
    }

    /** One participant as the console's create form uses it (their details, filled in). */
    public record ConsoleRow(Long userId, String participantId, String fullName, String firstName, String middleName,
                             String lastName, String email, String phone, String technology, String program,
                             String targetJobTitle, LocalDateTime requestedAt) {}

    /** Participants who asked for their agreement and don't have an open one in the console yet. */
    @Transactional(readOnly = true)
    public List<ConsoleRow> waitingForConsole() {
        return requestRepository.findAllByOrderByRequestedAtAsc().stream()
                .map(r -> userRepository.findById(r.getUserId()).map(u -> Map.entry(r, u)).orElse(null))
                .filter(java.util.Objects::nonNull)
                .filter(e -> !Boolean.FALSE.equals(e.getValue().getIsActive()))
                .filter(e -> agreementFor(e.getValue()).isEmpty())
                .map(e -> consoleRow(e.getValue(), e.getKey().getRequestedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public ConsoleRow consoleRow(Long userId) {
        User u = user(userId);
        return consoleRow(u, requestRepository.findByUserId(userId).map(AgreementRequest::getRequestedAt).orElse(null));
    }

    private ConsoleRow consoleRow(User u, LocalDateTime requestedAt) {
        String[] parts = u.getFullName() == null ? new String[0] : u.getFullName().trim().split("\\s+");
        String first = parts.length > 0 ? parts[0] : "";
        String last = parts.length > 1 ? parts[parts.length - 1] : "";
        String middle = parts.length > 2 ? String.join(" ", java.util.Arrays.copyOfRange(parts, 1, parts.length - 1)) : "";
        ProgramSelection ps = programSelectionRepository.findFirstByUserIdOrderBySelectionDateDesc(u.getId()).orElse(null);
        return new ConsoleRow(u.getId(), u.getParticipantId(), u.getFullName(), first, middle, last, u.getEmail(),
                u.getPhone(),
                ps != null && ps.getSkillset() != null ? ps.getSkillset() : u.getSelectedTechnology(),
                ps == null ? null : ps.getProgram(),
                ps == null ? null : ps.getTargetJobTitle(),
                requestedAt);
    }

    private User user(Long id) {
        return userRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("User", "id", id));
    }
}
