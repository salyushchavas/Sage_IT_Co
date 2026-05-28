package com.spire.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Immutable record of a user's Terms of Service acceptance.
 *
 * One row per user (unique constraint on user_id). Created with
 * {@code codeVerified=false} the moment the user clicks Accept on
 * the agreement page; flipped to {@code codeVerified=true} after
 * the user enters the 6-digit OTP we email them. After verification
 * the row is treated as immutable — services must never UPDATE or
 * DELETE a verified acceptance, since these records are the legal
 * evidence trail.
 *
 * Captures IP / user-agent / browser / OS at acceptance time so an
 * audit can reconstruct the conditions under which the user agreed,
 * and stores the legal name typed into the page as a digital
 * signature.
 */
@Entity
@Table(name = "agreement_records",
        uniqueConstraints = @UniqueConstraint(columnNames = "user_id"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgreementAcceptance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    /** Typed by the user as a digital signature; min 2 words enforced server-side. */
    @Column(name = "legal_name", nullable = false, length = 255)
    private String legalName;

    @Column(name = "agreement_version", nullable = false, length = 20)
    @Builder.Default
    private String agreementVersion = "v1.0";

    /**
     * One of: WAITING_REPLY, CODE_SENT, VERIFIED.
     * Flow: row is created in WAITING_REPLY when the user clicks
     * Accept on the agreement page; flips to CODE_SENT when the
     * Vercel cron detects a "YES" reply from the user's email
     * inbox; flips to VERIFIED when the user enters the OTP we
     * email them at that point. {@code codeVerified} below stays
     * a redundant boolean so existing reads keep working.
     */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "WAITING_REPLY";

    /**
     * 6-digit OTP, populated by {@code processReply} after the
     * inbox monitor confirms the user's email reply. Nullable
     * because the row spends its first phase in WAITING_REPLY
     * with no code yet.
     */
    @Column(name = "acceptance_code", length = 6)
    private String acceptanceCode;

    @Column(name = "code_verified", nullable = false)
    @Builder.Default
    private Boolean codeVerified = false;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", columnDefinition = "TEXT")
    private String userAgent;

    @Column(name = "browser", length = 100)
    private String browser;

    @Column(name = "os", length = 100)
    private String os;

    /** Set when codeVerified flips to true. */
    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;

    @CreationTimestamp
    @Column(name = "code_sent_at", updatable = false)
    private LocalDateTime codeSentAt;

    /**
     * When the agreement-request email (the "Reply YES" prompt) was
     * delivered to the user. Distinct from {@code codeSentAt}
     * (which now refers to the verification code email sent after
     * the reply is detected).
     */
    @Column(name = "agreement_email_sent_at")
    private LocalDateTime agreementEmailSentAt;

    /**
     * Subject line of the agreement-request email — includes the
     * tracking marker {@code [AGREE-{userId}-{timestamp}]} the IMAP
     * cron uses to match incoming replies back to this row.
     */
    @Column(name = "agreement_email_subject", length = 255)
    private String agreementEmailSubject;

    /**
     * Hard expiry on the WAITING_REPLY phase — after 30 minutes the
     * pending row is considered stale and the user must restart.
     */
    @Column(name = "agreement_expires_at")
    private LocalDateTime agreementExpiresAt;

    /** Set by processReply when the IMAP cron detects the YES reply. */
    @Column(name = "user_reply_received_at")
    private LocalDateTime userReplyReceivedAt;

    /**
     * The exact word(s) the user replied — e.g. "YES", "agreed".
     * Stored verbatim for the audit trail.
     */
    @Column(name = "user_reply_content", length = 64)
    private String userReplyContent;

    /** When the OTP was emailed (post-reply). */
    @Column(name = "verification_code_sent_at")
    private LocalDateTime verificationCodeSentAt;

    /** When the user typed the OTP correctly — same instant as acceptedAt. */
    @Column(name = "verification_code_verified_at")
    private LocalDateTime verificationCodeVerifiedAt;

    @Column(name = "code_expires_at")
    private LocalDateTime codeExpiresAt;

    /**
     * Stamps the most recent /agreement/resend call so the rate
     * limiter (60s cooldown) has a server-side anchor. The frontend
     * countdown is just UX.
     */
    @Column(name = "last_resend_at")
    private LocalDateTime lastResendAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /**
     * Public download URL of the personalized signed agreement PDF
     * generated after the OTP verification completes. Populated by
     * {@code AgreementPdfService}; null until the PDF is written
     * (or for legacy rows accepted before the PDF flow shipped).
     */
    @Column(name = "signed_agreement_pdf_url", length = 512)
    private String signedAgreementPdfUrl;

    /**
     * Base64-encoded PNG of the user's digital signature, captured
     * either by drawing on a canvas or uploading an image. Stored
     * with the {@code data:image/png;base64,…} prefix so the value
     * can be dropped straight into an &lt;img src&gt; tag for the
     * admin display, while the OpenPDF embed strips the prefix
     * before decoding.
     *
     * Stored as TEXT (longer than VARCHAR can hold for a 200×80
     * canvas at full quality). Required for new acceptances; nullable
     * here to keep the column compatible with rows that pre-date the
     * signature feature.
     */
    @Column(name = "signature_image", columnDefinition = "TEXT")
    private String signatureImage;

    /** Either {@code "draw"} or {@code "upload"}. Audit value only. */
    @Column(name = "signature_method", length = 20)
    private String signatureMethod;

    /**
     * Phase 3B — set to true once the signed agreement has been
     * routed to the ERM queue (post-OTP-verify post-processing).
     * Drives the operations dashboard's "pending ERM assignment"
     * filter; nullable / default false for rows from the legacy
     * flow that never went through Phase 3B.
     */
    @Column(name = "erm_notified", nullable = false)
    @Builder.Default
    private Boolean ermNotified = false;
}
