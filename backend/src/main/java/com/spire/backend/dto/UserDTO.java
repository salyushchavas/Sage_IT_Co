package com.spire.backend.dto;

import com.spire.backend.entity.User;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;


@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserDTO {

    private Long id;
    private String email;
    private String fullName;
    private String role;
    private String avatarUrl;
    private String bio;
    private Boolean onboardingCompleted;
    private Boolean isActive;
    private Boolean instructorApproved;
    /**
     * True after the user has completed the OTP-confirmed Terms of
     * Service flow. The frontend reads this to decide whether to
     * route post-login users to /agreement.
     */
    private Boolean agreementAccepted;
    private LocalDateTime createdAt;
    /**
     * When the account was deactivated. Null for active accounts;
     * powers the "Deactivated on …" column on the admin Deactivated
     * Users tab and the deactivation banner on the user detail page.
     */
    private LocalDateTime deactivatedAt;

    /**
     * Phase 1A participant fields surfaced on every login / profile
     * read. The frontend reads {@code currentStatus} to route a user
     * to the correct onboarding step; {@code participantId} is the
     * SIT-2026-XXXXX handle shown on the participant-id page and in
     * every transactional email.
     */
    private String participantId;
    private String currentStatus;
    /** True after the user has completed the OTP-confirmed email gate. Drives onboarding routing. */
    private Boolean emailVerified;
    /** Pre-filled into the /program-selection form (the participant can override). */
    private String selectedTechnology;
    private String availability;
    /** Captured at enrollment; displayed on the agreement summary. */
    private String phone;
    /** Free-form city / region — editable from the dashboard Profile tab. */
    private String location;

    // ── Phase 1C progressive-completion flags ───────────────────
    // Read everywhere — the dashboard banner, gate modal, sidebar
    // badge, course enroll button. Lives on UserDTO so the auth
    // context can refresh once and have everything the gating UI
    // needs in one place.
    private Boolean profileComplete;
    private Integer profileCompletionPct;
    private Boolean basicInfoComplete;
    private Boolean acknowledgmentComplete;
    private Boolean documentsComplete;
    private Boolean programSelectionComplete;
    private Boolean agreementComplete;
    private Boolean checkUploadComplete;

    public static UserDTO from(User user) {
        return UserDTO.builder()
                .id(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .role(user.getRole().getName())
                .avatarUrl(user.getAvatarUrl())
                .bio(user.getBio())
                .onboardingCompleted(Boolean.TRUE.equals(user.getOnboardingCompleted()))
                .isActive(Boolean.TRUE.equals(user.getIsActive()))
                .instructorApproved(Boolean.TRUE.equals(user.getInstructorApproved()))
                .agreementAccepted(Boolean.TRUE.equals(user.getAgreementAccepted()))
                .createdAt(user.getCreatedAt())
                .deactivatedAt(user.getDeactivatedAt())
                .participantId(user.getParticipantId())
                .currentStatus(user.getCurrentStatus())
                .emailVerified(Boolean.TRUE.equals(user.getEmailVerified()))
                .selectedTechnology(user.getSelectedTechnology())
                .availability(user.getAvailability())
                .phone(user.getPhone())
                .location(user.getLocation())
                .profileComplete(Boolean.TRUE.equals(user.getProfileComplete()))
                .profileCompletionPct(user.getProfileCompletionPct() == null
                        ? 0 : user.getProfileCompletionPct())
                .basicInfoComplete(Boolean.TRUE.equals(user.getBasicInfoComplete()))
                .acknowledgmentComplete(Boolean.TRUE.equals(user.getAcknowledgmentComplete()))
                .documentsComplete(Boolean.TRUE.equals(user.getDocumentsComplete()))
                .programSelectionComplete(Boolean.TRUE.equals(user.getProgramSelectionComplete()))
                .agreementComplete(Boolean.TRUE.equals(user.getAgreementComplete()))
                .checkUploadComplete(Boolean.TRUE.equals(user.getCheckUploadComplete()))
                .build();
    }
}
