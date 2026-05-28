package com.spire.backend.service;

import com.spire.backend.entity.ErmAssignment;
import com.spire.backend.entity.Role;
import com.spire.backend.entity.User;
import com.spire.backend.repository.ErmAssignmentRepository;
import com.spire.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 4 Step 13 — picks an ERM for a fresh participant.
 *
 * Strategy: among active users with role=ERM, pick the one with the
 * fewest active assignments (least-loaded). Tie-break by user id
 * ascending for deterministic behaviour. Returns {@code null} when
 * no ERM users exist on the platform yet — the caller is expected
 * to leave the participant in ERM_ASSIGNED-pending state and the
 * operations admin queue will surface them for manual assignment.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ErmAssignmentService {

    private final ErmAssignmentRepository ermAssignmentRepository;
    private final UserRepository userRepository;

    @Transactional
    public Optional<ErmAssignment> assignErm(User participant) {
        // Skip if there's already an active assignment.
        Optional<ErmAssignment> existing = ermAssignmentRepository
                .findFirstByUserIdOrderByAssignedDateDesc(participant.getId());
        if (existing.isPresent() && existing.get().getErmUserId() != null) {
            log.debug("Participant {} already has ERM {}",
                    participant.getId(), existing.get().getErmUserId());
            return existing;
        }

        Optional<User> chosen = pickLeastLoadedErm();
        if (chosen.isEmpty()) {
            log.info("No active ERM available for participant {} — pending manual assign",
                    participant.getId());
            return Optional.empty();
        }
        User erm = chosen.get();
        ErmAssignment row = existing.orElseGet(() -> ErmAssignment.builder()
                .userId(participant.getId())
                .introEmailStatus("PENDING")
                .build());
        row.setErmUserId(erm.getId());
        row.setIntroEmailStatus("SENT");
        ErmAssignment saved = ermAssignmentRepository.save(row);
        log.info("Assigned ERM {} to participant {}", erm.getId(), participant.getId());
        return Optional.of(saved);
    }

    /** Lookup helper for the OnboardingService email step. */
    @Transactional(readOnly = true)
    public Optional<User> getAssignedErm(Long participantId) {
        return ermAssignmentRepository.findFirstByUserIdOrderByAssignedDateDesc(participantId)
                .map(ErmAssignment::getErmUserId)
                .flatMap(userRepository::findById);
    }

    // ── Internals ────────────────────────────────────────────────

    private Optional<User> pickLeastLoadedErm() {
        // Tally active assignments per ERM user. Cheap full-table
        // scan is fine until we hit hundreds of ERMs — well above
        // current scale.
        Map<Long, Integer> loadByErm = new HashMap<>();
        for (ErmAssignment row : ermAssignmentRepository.findAll()) {
            if (row.getErmUserId() == null) continue;
            loadByErm.merge(row.getErmUserId(), 1, Integer::sum);
        }
        List<User> candidates = userRepository.findAll().stream()
                .filter(this::isActiveErm)
                .toList();
        if (candidates.isEmpty()) return Optional.empty();
        return candidates.stream()
                .min(Comparator
                        .<User, Integer>comparing(u -> loadByErm.getOrDefault(u.getId(), 0))
                        .thenComparing(User::getId));
    }

    private boolean isActiveErm(User u) {
        if (Boolean.FALSE.equals(u.getIsActive())) return false;
        Role role = u.getRole();
        if (role == null || role.getName() == null) return false;
        String name = role.getName().toUpperCase();
        return "ERM".equals(name);
    }
}
