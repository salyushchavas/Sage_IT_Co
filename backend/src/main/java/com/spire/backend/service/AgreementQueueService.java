package com.spire.backend.service;

import com.spire.backend.entity.AgreementAcceptance;
import com.spire.backend.entity.ProgramSelection;
import com.spire.backend.entity.User;
import com.spire.backend.repository.AgreementAcceptanceRepository;
import com.spire.backend.repository.ProgramSelectionRepository;
import com.spire.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Checklist 2.5: Operations' agreement queue, built from where each
 * participant really is (the old one filtered on two statuses nobody ever
 * stays in, so it was always empty). Stages, the ones needing action first:
 *
 *   DECLINED    the participant declined to sign (with their reason)
 *   EXPIRED     waiting to sign for more than {@code app.agreement.expiry-days}
 *   NEEDS_ERM   signed and finished, but no ERM to send it to (step 10 waits)
 *   WAITING     program chosen, agreement not signed yet
 *   CHECK_STEP  signed; the check step (step 8) is still open
 *   ERM_REVIEW  with the ERM, not marked reviewed yet
 *
 * A signed agreement the ERM has reviewed leaves the queue.
 */
@Service
@RequiredArgsConstructor
public class AgreementQueueService {

    static final Set<String> PARTICIPANT_ROLES = Set.of("PARTICIPANT", "STUDENT");
    private static final List<String> ORDER =
            List.of("DECLINED", "EXPIRED", "NEEDS_ERM", "WAITING", "CHECK_STEP", "ERM_REVIEW");

    private final UserRepository userRepository;
    private final AgreementAcceptanceRepository agreementRepository;
    private final ProgramSelectionRepository programSelectionRepository;
    private final ErmAssignmentService ermAssignmentService;

    @Value("${app.agreement.expiry-days:14}")
    private int expiryDays = 14;

    public record Row(Long userId, String participantId, String fullName, String email, String stage,
                      LocalDateTime since, String detail) {}

    @Transactional(readOnly = true)
    public List<Row> queue() {
        List<Row> rows = new ArrayList<>();
        LocalDateTime expiry = LocalDateTime.now().minusDays(expiryDays);
        for (User u : userRepository.findAll()) {
            if (u.getRole() == null || !PARTICIPANT_ROLES.contains(u.getRole().getName())) continue;
            if (u.getParticipantId() == null || !Boolean.TRUE.equals(u.getIsActive())) continue;
            if (!Boolean.TRUE.equals(u.getProgramSelectionComplete())) continue;
            AgreementAcceptance a = agreementRepository.findByUserId(u.getId()).orElse(null);
            boolean signed = Boolean.TRUE.equals(u.getAgreementComplete())
                    || (a != null && AgreementService.STATUS_VERIFIED.equals(a.getStatus()));
            if (!signed) {
                if (a != null && AgreementService.STATUS_DECLINED.equals(a.getStatus())) {
                    rows.add(row(u, "DECLINED", a.getDeclinedAt(), a.getDeclineReason()));
                    continue;
                }
                LocalDateTime since = programSelectionRepository.findFirstByUserIdOrderBySelectionDateDesc(u.getId())
                        .map(ProgramSelection::getSelectionDate).orElse(null);
                boolean expired = since != null && since.isBefore(expiry);
                rows.add(row(u, expired ? "EXPIRED" : "WAITING", since,
                        expired ? "Not signed after " + expiryDays + " days" : null));
                continue;
            }
            LocalDateTime signedAt = a == null ? null : a.getAcceptedAt();
            if (!Boolean.TRUE.equals(u.getCheckUploadComplete())) {
                rows.add(row(u, "CHECK_STEP", signedAt, null));
                continue;
            }
            if (a == null || a.getErmRoutedAt() == null) {
                // A participant who finished before routing existed has an
                // ERM already: the agreement is in that ERM's dashboard.
                if (ermAssignmentService.getAssignedErm(u.getId()).isEmpty()) {
                    rows.add(row(u, "NEEDS_ERM", signedAt, "No ERM assigned yet"));
                }
                continue;
            }
            if (a.getErmReviewedAt() == null) {
                String erm = userRepository.findById(a.getErmRoutedTo()).map(User::getFullName).orElse(null);
                rows.add(row(u, "ERM_REVIEW", a.getErmRoutedAt(), erm == null ? null : "With " + erm));
            }
        }
        rows.sort(Comparator.comparingInt((Row r) -> ORDER.indexOf(r.stage()))
                .thenComparing(Row::since, Comparator.nullsLast(Comparator.naturalOrder())));
        return rows;
    }

    private static Row row(User u, String stage, LocalDateTime since, String detail) {
        return new Row(u.getId(), u.getParticipantId(), u.getFullName(), u.getEmail(), stage, since, detail);
    }
}
