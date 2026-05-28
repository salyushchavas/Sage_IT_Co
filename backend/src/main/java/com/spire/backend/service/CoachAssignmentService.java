package com.spire.backend.service;

import com.spire.backend.entity.CoachAssignment;
import com.spire.backend.entity.Role;
import com.spire.backend.entity.User;
import com.spire.backend.repository.CoachAssignmentRepository;
import com.spire.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Phase 4 Step 14 — picks coaches for a freshly-assigned participant
 * across four canonical roles:
 *
 *   CAREER_COACH        — general career guidance
 *   RESUME_SPECIALIST   — resume + LinkedIn / profile work
 *   TECHNICAL_ADVISOR   — skillset-matched technical mentor
 *   INTERVIEW_COACH     — mock interviews + interview support
 *
 * For each role, picks the least-loaded active candidate whose
 * coach_role matches. Falls back to "no candidate" when no
 * matching coach exists — that role surfaces as "Awaiting
 * assignment" in the participant dashboard and the operations
 * admin queue can fill it in later.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CoachAssignmentService {

    /** The four canonical coach roles + the user-facing label. */
    public static final Map<String, String> COACH_ROLES = new LinkedHashMap<>() {{
        put("CAREER_COACH",       "Career Coach");
        put("RESUME_SPECIALIST",  "Resume Specialist");
        put("TECHNICAL_ADVISOR",  "Technical Advisor");
        put("INTERVIEW_COACH",    "Interview Coach");
    }};

    /** Role names on the users table considered eligible candidates. */
    private static final Set<String> COACH_USER_ROLES = Set.of(
            "COACH", "TECHNICAL_ADVISOR"
    );

    private final CoachAssignmentRepository coachAssignmentRepository;
    private final UserRepository userRepository;

    /**
     * Assigns coaches across all four roles. Returns the resulting
     * map keyed by role label → coach display name (or
     * "Awaiting assignment" when no candidate was found). Used by
     * the email template and the welcome-status payload.
     */
    @Transactional
    public Map<String, String> assignCoaches(User participant) {
        Map<String, String> outcome = new LinkedHashMap<>();
        Map<Long, Integer> loadByCoach = currentLoad();
        List<User> coachPool = userRepository.findAll().stream()
                .filter(this::isActiveCoach)
                .toList();

        for (Map.Entry<String, String> roleEntry : COACH_ROLES.entrySet()) {
            String coachRole = roleEntry.getKey();
            String label = roleEntry.getValue();

            // Already assigned for this role? Reuse.
            Optional<CoachAssignment> already = coachAssignmentRepository
                    .findByUserIdAndStatus(participant.getId(), "ACTIVE")
                    .stream()
                    .filter(a -> coachRole.equals(a.getCoachRole()))
                    .findFirst();
            if (already.isPresent() && already.get().getCoachUserId() != null) {
                userRepository.findById(already.get().getCoachUserId())
                        .ifPresent(c -> outcome.put(label,
                                c.getFullName() == null ? "(Assigned)" : c.getFullName()));
                continue;
            }

            Optional<User> chosen = pickLeastLoaded(coachPool, loadByCoach, coachRole, participant);
            if (chosen.isEmpty()) {
                outcome.put(label, "Awaiting assignment");
                log.info("No {} available for participant {} — pending manual assign",
                        coachRole, participant.getId());
                continue;
            }
            User coach = chosen.get();
            CoachAssignment row = CoachAssignment.builder()
                    .userId(participant.getId())
                    .coachUserId(coach.getId())
                    .coachRole(coachRole)
                    .status("ACTIVE")
                    .build();
            coachAssignmentRepository.save(row);
            outcome.put(label, coach.getFullName() == null ? "(Assigned)" : coach.getFullName());
            // Update in-memory tally so subsequent roles don't all
            // pile onto the same coach.
            loadByCoach.merge(coach.getId(), 1, Integer::sum);
            log.info("Assigned coach {} ({}) to participant {}",
                    coach.getId(), coachRole, participant.getId());
        }
        return outcome;
    }

    /** True if the participant has at least one ACTIVE coach. */
    @Transactional(readOnly = true)
    public boolean hasAnyCoach(Long participantId) {
        return !coachAssignmentRepository
                .findByUserIdAndStatus(participantId, "ACTIVE")
                .isEmpty();
    }

    /** Snapshot of currently-assigned coaches for the welcome payload. */
    @Transactional(readOnly = true)
    public Map<String, String> getAssignedCoaches(Long participantId) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : COACH_ROLES.entrySet()) out.put(e.getValue(), "Awaiting assignment");
        for (CoachAssignment a : coachAssignmentRepository.findByUserIdAndStatus(participantId, "ACTIVE")) {
            String label = COACH_ROLES.getOrDefault(a.getCoachRole(), a.getCoachRole());
            String name = a.getCoachUserId() == null ? "Awaiting assignment"
                    : userRepository.findById(a.getCoachUserId())
                            .map(User::getFullName).orElse("(Assigned)");
            out.put(label, name);
        }
        return out;
    }

    // ── Internals ────────────────────────────────────────────────

    private Map<Long, Integer> currentLoad() {
        Map<Long, Integer> map = new HashMap<>();
        for (CoachAssignment row : coachAssignmentRepository.findAll()) {
            if (!"ACTIVE".equals(row.getStatus())) continue;
            if (row.getCoachUserId() == null) continue;
            map.merge(row.getCoachUserId(), 1, Integer::sum);
        }
        return map;
    }

    private Optional<User> pickLeastLoaded(List<User> pool, Map<Long, Integer> load,
                                           String coachRole, User participant) {
        // Match heuristic — for the technical advisor we prefer
        // coaches whose role name explicitly says TECHNICAL_ADVISOR;
        // for everything else any user with role=COACH is eligible.
        String wantedRole = "TECHNICAL_ADVISOR".equals(coachRole) ? "TECHNICAL_ADVISOR" : "COACH";
        String wantedSkill = participant.getSelectedTechnology();

        List<User> matching = pool.stream()
                .filter(u -> u.getRole() != null
                        && wantedRole.equalsIgnoreCase(u.getRole().getName()))
                .toList();
        if (matching.isEmpty()) return Optional.empty();

        // Skill-match preference for TECHNICAL_ADVISOR — bio field
        // is a cheap free-text place to flag the coach's stack.
        // If we can't tell from the bio, fall through to least-load.
        if ("TECHNICAL_ADVISOR".equals(coachRole) && wantedSkill != null && !wantedSkill.isBlank()) {
            String want = wantedSkill.toLowerCase();
            List<User> skillMatches = matching.stream()
                    .filter(u -> u.getBio() != null && u.getBio().toLowerCase().contains(want))
                    .toList();
            if (!skillMatches.isEmpty()) matching = skillMatches;
        }

        return matching.stream()
                .min(Comparator
                        .<User, Integer>comparing(u -> load.getOrDefault(u.getId(), 0))
                        .thenComparing(User::getId));
    }

    private boolean isActiveCoach(User u) {
        if (Boolean.FALSE.equals(u.getIsActive())) return false;
        Role role = u.getRole();
        if (role == null || role.getName() == null) return false;
        return COACH_USER_ROLES.contains(role.getName().toUpperCase());
    }
}
