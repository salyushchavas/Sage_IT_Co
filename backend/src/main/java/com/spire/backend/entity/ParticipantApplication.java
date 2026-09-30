package com.spire.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Roadmap step 1, before there is an account: someone applied on the
 * website with their basic details and the course (technology) they
 * want. An ERM confirms it, which emails them a one-time link to
 * register; registering creates the participant account, already
 * email-verified (the link proved the address), and starts the roadmap.
 *
 * Statuses: PENDING (waiting for an ERM), APPROVED (confirmed, link
 * emailed, not registered yet), REGISTERED (account created), DECLINED.
 */
@Entity
@Table(name = "participant_applications", indexes = {
        @Index(name = "idx_application_email", columnList = "email"),
        @Index(name = "idx_application_status", columnList = "status"),
        @Index(name = "idx_application_token", columnList = "registration_token_hash")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ParticipantApplication {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REGISTERED = "REGISTERED";
    public static final String DECLINED = "DECLINED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "full_name", nullable = false, length = 100)
    private String fullName;

    /** Stored lowercased. */
    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "phone_normalized", length = 20)
    private String phoneNormalized;

    /** The course (technology / skillset) they applied for. */
    @Column(name = "selected_technology", length = 255)
    private String selectedTechnology;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = PENDING;

    /** WEBSITE (they applied) or INVITE (Operations invited them). */
    @Column(name = "source", length = 20)
    @Builder.Default
    private String source = "WEBSITE";

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    /** The staff member who confirmed or declined it. */
    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "decline_reason", length = 500)
    private String declineReason;

    /** SHA-256 of the one-time registration link's token; never the token itself. */
    @Column(name = "registration_token_hash", length = 64)
    private String registrationTokenHash;

    @Column(name = "registration_token_expires_at")
    private LocalDateTime registrationTokenExpiresAt;

    /** When the registration email last went out (null: it never did). */
    @Column(name = "registration_email_sent_at")
    private LocalDateTime registrationEmailSentAt;

    /** The account created from this application. */
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "registered_at")
    private LocalDateTime registeredAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
