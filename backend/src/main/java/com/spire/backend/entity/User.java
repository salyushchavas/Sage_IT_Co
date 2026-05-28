package com.spire.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Maps to the 'users' table in MySQL.
 * Uses role_id FK to 'roles' table instead of embedded enum.
 *
 * @DynamicUpdate so Hibernate emits UPDATE statements that only touch
 * the columns that actually changed. Without it, every save rewrites
 * all columns, which is brittle when running against multiple
 * environments (MySQL dev vs Postgres prod) where naming strategies
 * can differ subtly.
 */
@Entity
@Table(name = "users")
@DynamicUpdate
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 100)
    private String fullName;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;

    @Column(columnDefinition = "TEXT")
    private String bio;

    // Profile-page extras — explicit @Column(name=...) so the mapping
    // can't get tripped up by per-environment naming strategies.
    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "location", length = 255)
    private String location;

    @Column(name = "instructor_approved", nullable = false)
    @Builder.Default
    private Boolean instructorApproved = false;

    // Welcome-wizard flag. New signups land FALSE; flipped to TRUE the
    // first time the student dismisses the wizard or finishes the flow.
    @Column(name = "onboarding_completed", nullable = false)
    @Builder.Default
    private Boolean onboardingCompleted = false;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    /**
     * Stamped when an admin flips {@code isActive} to false (either
     * via /status with active=false or the soft-delete endpoint).
     * Cleared when an admin reactivates the account. Drives the
     * "Deactivated on …" column + banner in the admin UI.
     */
    @Column(name = "deactivated_at")
    private LocalDateTime deactivatedAt;

    // ─── Email verification ─────────────────────────────────────────
    // Default false on signup — flipped true after the user enters
    // the 6-digit code we email them. Code + expiry are cleared once
    // consumed.
    //
    // The legacy {@code verificationToken} pair (UUID-based magic
    // link) is kept on the column model for backward compatibility
    // with rows in flight when the OTP gate shipped, but is no longer
    // populated by new signups.
    @Column(name = "email_verified", nullable = false)
    @Builder.Default
    private Boolean emailVerified = false;

    @Column(name = "verification_token", length = 64)
    private String verificationToken;

    @Column(name = "verification_expires_at")
    private LocalDateTime verificationExpiresAt;

    /** 6-digit numeric OTP. Stored as a string to preserve leading zeros. */
    @Column(name = "verification_code", length = 6)
    private String verificationCode;

    @Column(name = "verification_code_expires_at")
    private LocalDateTime verificationCodeExpiresAt;

    /** Failed-attempt counter for the OTP gate. Resets on success. */
    @Column(name = "verification_failed_attempts", nullable = false)
    @Builder.Default
    private Integer verificationFailedAttempts = 0;

    /**
     * Set when the user trips the brute-force lockout (5 wrong codes).
     * Cleared on a successful verification or once the time elapses.
     */
    @Column(name = "verification_locked_until")
    private LocalDateTime verificationLockedUntil;

    /**
     * Stamps the last "resend code" call so the rate limiter (60s
     * cooldown) has somewhere to look. The frontend countdown is
     * authoritative for UX, but we re-check server-side too.
     */
    @Column(name = "last_verification_resend_at")
    private LocalDateTime lastVerificationResendAt;

    /**
     * True once the user has accepted the Terms of Service. Default
     * false on signup; flipped true when the user enters the
     * agreement-acceptance OTP. The agreement gate filter rejects
     * authenticated requests for any user with this still false,
     * so the frontend can route them to /agreement.
     *
     * Pre-existing accounts (already in the DB before the gate
     * shipped) are grandfathered to true by DataSeeder so they're
     * not retroactively locked out.
     */
    @Column(name = "agreement_accepted", nullable = false)
    @Builder.Default
    private Boolean agreementAccepted = false;

    // ─── Password reset ─────────────────────────────────────────────
    // Single-use token + expiry generated by /forgot-password and
    // consumed by /reset-password. Cleared after a successful reset.
    @Column(name = "reset_token", length = 64)
    private String resetToken;

    @Column(name = "reset_token_expires_at")
    private LocalDateTime resetTokenExpiresAt;

    // ─── Inactivity nudge throttle ──────────────────────────────────
    // Stamped by the scheduled job every time a "we miss you" email
    // goes out. The job rate-limits to once per 14 days per user so
    // a chronically-idle account doesn't get pinged daily.
    @Column(name = "last_nudge_sent_at")
    private LocalDateTime lastNudgeSentAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // ── Phase 1A: participant lifecycle ─────────────────────────────
    // The user becomes a "participant" once email is verified and an
    // ID is minted. participantId is the public-facing handle
    // ("SIT-2026-00042"). currentStatus drives the 26-state workflow
    // (see WorkflowService.Status); defaults to DRAFT_STARTED.

    @Column(name = "participant_id", length = 20, unique = true)
    private String participantId;

    @Column(name = "participant_id_created_at")
    private LocalDateTime participantIdCreatedAt;

    @Column(name = "availability", length = 100)
    private String availability;

    @Column(name = "selected_technology", length = 255)
    private String selectedTechnology;

    @Column(name = "target_experience_level", length = 50)
    private String targetExperienceLevel;

    @Column(name = "current_status", length = 50)
    @Builder.Default
    private String currentStatus = "DRAFT_STARTED";

    // ── Phase 1C: progressive profile completion ────────────────────
    // After the 2-step signup (enroll + verify), the participant lands
    // on the dashboard immediately. The remaining 6 lifecycle steps
    // (basic info, acknowledgment, documents, program selection,
    // agreement, check upload) become "Complete Your Profile" cards
    // — browse-allowed, enroll-gated. Each per-step boolean flips
    // when its endpoint reports done; profileComplete is the
    // computed "all six checked" gate that unlocks course enrollment
    // + triggers the welcome / ERM / coaches chain.
    //
    // Nullable on purpose: Hibernate ddl-auto runs ALTER TABLE on
    // existing prod rows, and Postgres refuses to add a NOT NULL
    // column to a table with existing rows unless a DEFAULT is
    // declared. Leaving these nullable lets the migration succeed;
    // every consumer wraps the read in {@code Boolean.TRUE.equals(...)}
    // so NULL behaves identically to false in business logic.

    @Column(name = "profile_complete")
    @Builder.Default
    private Boolean profileComplete = false;

    @Column(name = "profile_completion_pct")
    @Builder.Default
    private Integer profileCompletionPct = 0;

    @Column(name = "profile_completed_at")
    private LocalDateTime profileCompletedAt;

    @Column(name = "basic_info_complete")
    @Builder.Default
    private Boolean basicInfoComplete = false;

    @Column(name = "acknowledgment_complete")
    @Builder.Default
    private Boolean acknowledgmentComplete = false;

    @Column(name = "documents_complete")
    @Builder.Default
    private Boolean documentsComplete = false;

    @Column(name = "program_selection_complete")
    @Builder.Default
    private Boolean programSelectionComplete = false;

    @Column(name = "agreement_complete")
    @Builder.Default
    private Boolean agreementComplete = false;

    @Column(name = "check_upload_complete")
    @Builder.Default
    private Boolean checkUploadComplete = false;

    /**
     * How many profile-reminder emails we've sent. Capped at 3 by the
     * cron job so a stalled signup doesn't get pinged forever.
     * Nullable for the same migration reason as the per-step flags
     * above; ProfileReminderJob normalises NULL → 0 on read.
     */
    @Column(name = "profile_reminder_count")
    @Builder.Default
    private Integer profileReminderCount = 0;

    @Column(name = "last_profile_reminder_at")
    private LocalDateTime lastProfileReminderAt;
}
