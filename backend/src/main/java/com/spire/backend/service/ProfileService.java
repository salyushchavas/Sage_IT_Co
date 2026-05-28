package com.spire.backend.service;

import com.spire.backend.dto.ProfileDTO;
import com.spire.backend.dto.UpdateProfileRequest;
import com.spire.backend.entity.Certificate;
import com.spire.backend.entity.Enrollment;
import com.spire.backend.entity.Progress;
import com.spire.backend.entity.User;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.repository.CertificateRepository;
import com.spire.backend.repository.EnrollmentRepository;
import com.spire.backend.repository.LessonRepository;
import com.spire.backend.repository.ProgressRepository;
import com.spire.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Profile read + write. Reuses existing repositories for the learning-
 * stat counts. Update validates length but does not change email or role.
 */
@Service
@RequiredArgsConstructor
public class ProfileService {

    private final UserRepository userRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final LessonRepository lessonRepository;
    private final ProgressRepository progressRepository;
    private final CertificateRepository certificateRepository;
    private final com.spire.backend.repository.AgreementAcceptanceRepository agreementRepository;
    private final RecordService recordService;

    @Transactional(readOnly = true)
    public ProfileDTO getProfile(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        List<Enrollment> enrollments = enrollmentRepository.findByUserId(userId);
        int enrolled = enrollments.size();

        // Build the per-course summary list while we already have the data,
        // so admin oversight gets actual course titles + progress.
        List<ProfileDTO.CourseSummary> enrolledCourses = new java.util.ArrayList<>();
        int completed = 0;
        for (Enrollment e : enrollments) {
            Long courseId = e.getCourse().getId();
            int totalLessons = lessonRepository.findByCourseIdOrderByOrderIndex(courseId).size();
            long completedLessons = progressRepository.countCompletedLessons(userId, courseId);
            boolean isComplete = totalLessons > 0 && completedLessons >= totalLessons;
            if (isComplete) completed++;

            int progressPct = totalLessons > 0
                    ? (int) Math.round(100.0 * completedLessons / totalLessons)
                    : 0;
            enrolledCourses.add(ProfileDTO.CourseSummary.builder()
                    .id(courseId)
                    .title(e.getCourse().getTitle())
                    .type(e.getCourse().getType())
                    .progressPercent(progressPct)
                    .completedLessons((int) completedLessons)
                    .totalLessons(totalLessons)
                    .completed(isComplete)
                    .enrolledAt(e.getEnrolledAt())
                    .build());
        }

        List<Certificate> certs = certificateRepository.findByUserId(userId);
        int certificates = certs.size();
        List<ProfileDTO.CertSummary> certSummaries = certs.stream()
                .map(c -> ProfileDTO.CertSummary.builder()
                        .id(c.getId())
                        .certificateId(c.getCertificateId())
                        .courseTitle(c.getCourse() != null ? c.getCourse().getTitle() : null)
                        .certificateUrl(c.getCertificateUrl())
                        .issuedAt(c.getIssuedAt())
                        .build())
                .toList();

        // Activity analytics — derived from the user's per-lesson Progress rows.
        List<Progress> rows = progressRepository.findByUserId(userId);
        int streakDays = rows.stream()
                .map(Progress::getStreakDays)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);
        int totalLessonsCompleted = (int) rows.stream()
                .filter(p -> Boolean.TRUE.equals(p.getCompleted()))
                .count();
        int totalLearningMinutes = rows.stream()
                .filter(p -> Boolean.TRUE.equals(p.getCompleted()))
                .filter(p -> p.getLesson() != null && p.getLesson().getDurationMinutes() != null)
                .mapToInt(p -> p.getLesson().getDurationMinutes())
                .sum();
        LocalDateTime lastActiveAt = rows.stream()
                .map(Progress::getLastAccessed)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);

        // Daily contribution map for the heatmap. Counts lesson-completions
        // per day over the last 365 days.
        LocalDate cutoff = LocalDate.now().minusDays(365);
        Map<String, Integer> contributions = rows.stream()
                .filter(p -> Boolean.TRUE.equals(p.getCompleted()))
                .filter(p -> p.getLastAccessed() != null)
                .filter(p -> !p.getLastAccessed().toLocalDate().isBefore(cutoff))
                .collect(Collectors.groupingBy(
                        p -> p.getLastAccessed().toLocalDate().toString(),
                        Collectors.summingInt(p -> 1)));

        ProfileDTO dto = ProfileDTO.from(
                user, enrolled, completed, certificates,
                streakDays, totalLessonsCompleted, totalLearningMinutes,
                lastActiveAt, contributions, enrolledCourses, certSummaries);

        // Attach Terms of Service acceptance for the admin user-detail
        // panel + the user's own self-view. Pulled here so the
        // AgreementService doesn't have to know about ProfileDTO.
        agreementRepository.findByUserId(userId).ifPresent(a -> {
            int year = a.getAcceptedAt() != null
                    ? a.getAcceptedAt().getYear()
                    : (a.getCreatedAt() != null ? a.getCreatedAt().getYear() : LocalDate.now().getYear());
            String recordId = String.format("AGR-%d-%05d", year, a.getId());
            dto.setAgreement(ProfileDTO.AgreementSummary.builder()
                    .id(a.getId())
                    .accepted(Boolean.TRUE.equals(a.getCodeVerified()))
                    .status(a.getStatus())
                    .legalName(a.getLegalName())
                    .version(a.getAgreementVersion())
                    .acceptedAt(a.getAcceptedAt())
                    .agreementEmailSentAt(a.getAgreementEmailSentAt())
                    .userReplyReceivedAt(a.getUserReplyReceivedAt())
                    .userReplyContent(a.getUserReplyContent())
                    .verificationCodeSentAt(a.getVerificationCodeSentAt())
                    .verificationCodeVerifiedAt(a.getVerificationCodeVerifiedAt())
                    .ipAddress(a.getIpAddress())
                    .browser(a.getBrowser())
                    .os(a.getOs())
                    .recordId(recordId)
                    .signedAgreementPdfUrl(a.getSignedAgreementPdfUrl())
                    .signatureImage(a.getSignatureImage())
                    .signatureMethod(a.getSignatureMethod())
                    .build());
        });

        return dto;
    }

    @Transactional
    public ProfileDTO updateProfile(Long userId, UpdateProfileRequest dto) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        // Snapshot the old values BEFORE applying the patch so we have
        // a complete before/after pair for the audit record.
        java.util.Map<String, Object> oldValues = new java.util.LinkedHashMap<>();
        java.util.Map<String, Object> newValues = new java.util.LinkedHashMap<>();
        java.util.List<String> changed = new java.util.ArrayList<>();

        // fullName: required, never overwritten with blank
        if (dto.getFullName() != null && !dto.getFullName().isBlank()) {
            String fresh = dto.getFullName().trim();
            if (!java.util.Objects.equals(user.getFullName(), fresh)) {
                oldValues.put("fullName", user.getFullName());
                newValues.put("fullName", fresh);
                changed.add("fullName");
                user.setFullName(fresh);
            }
        }
        // avatarUrl: optional. Treat empty string as "clear" → null.
        if (dto.getAvatarUrl() != null) {
            String fresh = emptyToNull(dto.getAvatarUrl());
            if (!java.util.Objects.equals(user.getAvatarUrl(), fresh)) {
                oldValues.put("avatarUrl", user.getAvatarUrl());
                newValues.put("avatarUrl", fresh);
                changed.add("avatarUrl");
                user.setAvatarUrl(fresh);
            }
        }
        if (dto.getBio() != null) {
            String fresh = emptyToNull(dto.getBio());
            if (!java.util.Objects.equals(user.getBio(), fresh)) {
                oldValues.put("bio", user.getBio());
                newValues.put("bio", fresh);
                changed.add("bio");
                user.setBio(fresh);
            }
        }
        if (dto.getPhone() != null) {
            String fresh = emptyToNull(dto.getPhone().trim());
            if (!java.util.Objects.equals(user.getPhone(), fresh)) {
                oldValues.put("phone", user.getPhone());
                newValues.put("phone", fresh);
                changed.add("phone");
                user.setPhone(fresh);
            }
        }
        if (dto.getLocation() != null) {
            String fresh = emptyToNull(dto.getLocation().trim());
            if (!java.util.Objects.equals(user.getLocation(), fresh)) {
                oldValues.put("location", user.getLocation());
                newValues.put("location", fresh);
                changed.add("location");
                user.setLocation(fresh);
            }
        }

        userRepository.save(user);

        if (!changed.isEmpty()) {
            recordService.record(userId, "ACCOUNT_PROFILE_UPDATED", RecordService.Category.ACCOUNT,
                    "Profile updated",
                    "Changed: " + String.join(", ", changed),
                    java.util.Map.of(
                            "changedFields", changed,
                            "oldValues", oldValues,
                            "newValues", newValues
                    ));
        }

        return getProfile(userId); // refetch with counts
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
