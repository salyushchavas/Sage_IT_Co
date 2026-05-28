package com.spire.backend.service;

import com.spire.backend.entity.*;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.repository.InstructorRequestRepository;
import com.spire.backend.repository.RoleRepository;
import com.spire.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class InstructorRequestService {

    private final InstructorRequestRepository requestRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;

    @Transactional
    public InstructorRequest requestInstructor(Long userId) {
        log.info("[InstructorRequest] User {} requesting instructor status", userId);

        // 1. Fetch user
        User user = userRepository.findById(userId)
                .orElseThrow(() -> {
                    log.error("[InstructorRequest] User not found: {}", userId);
                    return new ResourceNotFoundException("User", "id", userId);
                });

        log.info("[InstructorRequest] User found: {} | Role: {} | Approved: {}",
                user.getEmail(), user.getRole().getName(), user.getInstructorApproved());

        // 2. Only students can request
        if (!"STUDENT".equals(user.getRole().getName())) {
            log.warn("[InstructorRequest] Rejected — user {} has role {}, not STUDENT",
                    userId, user.getRole().getName());
            throw new IllegalArgumentException("Only students can request instructor status");
        }

        // 3. Already approved?
        if (Boolean.TRUE.equals(user.getInstructorApproved())) {
            log.warn("[InstructorRequest] Rejected — user {} is already an approved instructor", userId);
            throw new IllegalArgumentException("You are already an approved instructor");
        }

        // 4. Duplicate pending request?
        boolean hasPending = requestRepository.existsByUserIdAndStatus(userId, RequestStatus.PENDING);
        log.info("[InstructorRequest] Pending request exists: {}", hasPending);
        if (hasPending) {
            throw new IllegalArgumentException("You already have a pending instructor request");
        }

        // 5. Rejected recently? (7-day cooldown)
        requestRepository.findByUserIdAndStatus(userId, RequestStatus.REJECTED)
                .ifPresent(rejected -> {
                    LocalDateTime cooldownEnd = rejected.getCreatedAt().plusDays(7);
                    if (cooldownEnd.isAfter(LocalDateTime.now())) {
                        log.warn("[InstructorRequest] Rejected — user {} in 7-day cooldown until {}", userId, cooldownEnd);
                        throw new IllegalArgumentException(
                                "Your previous request was rejected. Please wait 7 days before re-applying.");
                    }
                });

        // 6. Create and save
        InstructorRequest request = InstructorRequest.builder()
                .user(user)
                .status(RequestStatus.PENDING)
                .build();

        InstructorRequest saved = requestRepository.save(request);
        log.info("[InstructorRequest] Request created successfully — ID: {}, User: {}", saved.getId(), userId);
        return saved;
    }

    public List<InstructorRequest> getPendingRequests() {
        List<InstructorRequest> requests = requestRepository.findByStatus(RequestStatus.PENDING);
        log.info("[InstructorRequest] Fetched {} pending requests", requests.size());
        return requests;
    }

    @Transactional
    public InstructorRequest approveInstructor(Long requestId) {
        log.info("[InstructorRequest] Approving request {}", requestId);

        InstructorRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("InstructorRequest", "id", requestId));

        if (request.getStatus() != RequestStatus.PENDING) {
            throw new IllegalArgumentException("Request is already " + request.getStatus());
        }

        Role instructorRole = roleRepository.findByName("INSTRUCTOR")
                .orElseThrow(() -> new IllegalStateException("INSTRUCTOR role not found in database"));

        User user = request.getUser();
        user.setRole(instructorRole);
        user.setInstructorApproved(true);
        userRepository.save(user);

        request.setStatus(RequestStatus.APPROVED);
        InstructorRequest saved = requestRepository.save(request);
        log.info("[InstructorRequest] Approved — User {} is now INSTRUCTOR", user.getId());
        return saved;
    }

    @Transactional
    public InstructorRequest rejectInstructor(Long requestId) {
        log.info("[InstructorRequest] Rejecting request {}", requestId);

        InstructorRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("InstructorRequest", "id", requestId));

        if (request.getStatus() != RequestStatus.PENDING) {
            throw new IllegalArgumentException("Request is already " + request.getStatus());
        }

        request.setStatus(RequestStatus.REJECTED);
        InstructorRequest saved = requestRepository.save(request);
        log.info("[InstructorRequest] Rejected — Request {} for user {}", requestId, request.getUser().getId());
        return saved;
    }
}
