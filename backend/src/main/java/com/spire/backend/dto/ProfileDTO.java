package com.spire.backend.dto;

import com.spire.backend.entity.User;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Extended user profile shown on /profile. Superset of UserDTO —
 * keeps the same field names (avatarUrl, role, etc.) so callers
 * that consumed UserDTO (auth-context) continue to work, and adds
 * profile-page extras: phone, location, createdAt, plus learning
 * stats (enrolled / completed / certificate counts).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProfileDTO {

    private Long id;
    private String email;
    private String fullName;
    private String role;
    private String avatarUrl;
    private String bio;
    private String phone;
    private String location;
    private LocalDateTime createdAt;
    private Boolean onboardingCompleted;

    // ── UserDTO parity fields ───────────────────────────────────
    // Critical for the auth-context refresh path: the frontend
    // calls /api/users/profile and stores the response as the
    // user object. Missing these would cascade into the dashboard
    // routing guard reading user.currentStatus = undefined and
    // sending the user to the "Complete your enrollment" fallback
    // even after they finish onboarding.
    private Boolean isActive;
    private Boolean instructorApproved;
    private Boolean agreementAccepted;
    private LocalDateTime deactivatedAt;
    private String participantId;
    private String currentStatus;
    private Boolean emailVerified;
    private String selectedTechnology;
    private String availability;

    // ── Phase 1C progressive-completion flags ───────────────────
    // Read by the dashboard banner / completion tab / gate modal.
    // Mirrored on both ProfileDTO and UserDTO so the auth context
    // can drive the gate without a separate /completion call on
    // every page render.
    private Boolean profileComplete;
    private Integer profileCompletionPct;
    private Boolean basicInfoComplete;
    private Boolean acknowledgmentComplete;
    private Boolean documentsComplete;
    private Boolean programSelectionComplete;
    private Boolean agreementComplete;
    private Boolean checkUploadComplete;

    private Integer enrolledCoursesCount;
    private Integer completedCoursesCount;
    private Integer certificatesCount;

    // Activity analytics — surfaced beside the profile card on /profile.
    private Integer streakDays;
    private Integer totalLessonsCompleted;
    private Integer totalLearningMinutes;
    private LocalDateTime lastActiveAt;

    // Per-day contribution map for the GitHub-style heatmap.
    // Keys are ISO local dates (YYYY-MM-DD); values are lessons completed
    // that day. Only days with activity are included; the frontend
    // fills in zeroes for the missing dates.
    private Map<String, Integer> contributions;

    // Detailed lists — populated for full oversight (admin user detail
    // page consumes them). The user's own /profile page can ignore them
    // and just use the counts; payload cost is small.
    private List<CourseSummary> enrolledCourses;
    private List<CertSummary> certificates;

    // Agreement (Terms of Service) acceptance — populated by
    // ProfileService for admin views and self-service profile reads.
    // Null when the user has never started the agreement flow.
    private AgreementSummary agreement;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CourseSummary {
        private Long id;
        private String title;
        private String type;        // COURSE | SERVICE
        private Integer progressPercent;
        private Integer completedLessons;
        private Integer totalLessons;
        private Boolean completed;
        private LocalDateTime enrolledAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CertSummary {
        private Long id;
        private String certificateId;   // public verification ID
        private String courseTitle;
        private String certificateUrl;
        private LocalDateTime issuedAt;
    }

    /**
     * Audit-ready snapshot of the user's Terms of Service acceptance.
     * Surfaced on the admin user-detail panel; included on the
     * self-service profile read too so the user can see their own
     * record. Null when the user has never started the flow (admin
     * UI renders an "Not yet accepted" pill in that case).
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AgreementSummary {
        private Long id;
        private Boolean accepted;       // status == VERIFIED
        private String status;          // WAITING_REPLY / CODE_SENT / VERIFIED
        private String legalName;
        private String version;
        private LocalDateTime acceptedAt;
        // Email-reply audit trail.
        private LocalDateTime agreementEmailSentAt;
        private LocalDateTime userReplyReceivedAt;
        private String userReplyContent;
        private LocalDateTime verificationCodeSentAt;
        private LocalDateTime verificationCodeVerifiedAt;
        private String ipAddress;
        private String browser;
        private String os;
        // Stable display id ("AGR-2026-00009") computed from the
        // backing row's primary key + creation year.
        private String recordId;
        // Personalized signed-agreement PDF URL (relative path).
        // Populated for verified rows once the PDF generator runs;
        // null for legacy verified rows that pre-date the flow.
        private String signedAgreementPdfUrl;
        // Base64-encoded PNG of the user's digital signature
        // (data-URL form, e.g. "data:image/png;base64,…"). Drives
        // the signature image displayed on the admin user-detail
        // page. Null for rows that pre-date the signature feature.
        private String signatureImage;
        // 'draw' or 'upload'. Surfaced for the admin display.
        private String signatureMethod;
    }

    public static ProfileDTO from(
            User user,
            int enrolledCoursesCount,
            int completedCoursesCount,
            int certificatesCount,
            int streakDays,
            int totalLessonsCompleted,
            int totalLearningMinutes,
            LocalDateTime lastActiveAt,
            Map<String, Integer> contributions,
            List<CourseSummary> enrolledCourses,
            List<CertSummary> certificates) {
        return ProfileDTO.builder()
                .id(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .role(user.getRole().getName())
                .avatarUrl(user.getAvatarUrl())
                .bio(user.getBio())
                .phone(user.getPhone())
                .location(user.getLocation())
                .createdAt(user.getCreatedAt())
                .onboardingCompleted(Boolean.TRUE.equals(user.getOnboardingCompleted()))
                .isActive(Boolean.TRUE.equals(user.getIsActive()))
                .instructorApproved(Boolean.TRUE.equals(user.getInstructorApproved()))
                .agreementAccepted(Boolean.TRUE.equals(user.getAgreementAccepted()))
                .deactivatedAt(user.getDeactivatedAt())
                .participantId(user.getParticipantId())
                .currentStatus(user.getCurrentStatus())
                .emailVerified(Boolean.TRUE.equals(user.getEmailVerified()))
                .selectedTechnology(user.getSelectedTechnology())
                .availability(user.getAvailability())
                .profileComplete(Boolean.TRUE.equals(user.getProfileComplete()))
                .profileCompletionPct(user.getProfileCompletionPct() == null
                        ? 0 : user.getProfileCompletionPct())
                .basicInfoComplete(Boolean.TRUE.equals(user.getBasicInfoComplete()))
                .acknowledgmentComplete(Boolean.TRUE.equals(user.getAcknowledgmentComplete()))
                .documentsComplete(Boolean.TRUE.equals(user.getDocumentsComplete()))
                .programSelectionComplete(Boolean.TRUE.equals(user.getProgramSelectionComplete()))
                .agreementComplete(Boolean.TRUE.equals(user.getAgreementComplete()))
                .checkUploadComplete(Boolean.TRUE.equals(user.getCheckUploadComplete()))
                .enrolledCoursesCount(enrolledCoursesCount)
                .completedCoursesCount(completedCoursesCount)
                .certificatesCount(certificatesCount)
                .streakDays(streakDays)
                .totalLessonsCompleted(totalLessonsCompleted)
                .totalLearningMinutes(totalLearningMinutes)
                .lastActiveAt(lastActiveAt)
                .contributions(contributions)
                .enrolledCourses(enrolledCourses)
                .certificates(certificates)
                .build();
    }
}
