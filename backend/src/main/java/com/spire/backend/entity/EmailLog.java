package com.spire.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Append-only log of every transactional email the platform tried to
 * send, with the outcome (checklist 1.4). Written by EmailService for
 * each attempt; the body is never stored (it can hold codes).
 * Used for audit + debugging — distinct from {@code user_records}
 * because email-send metadata (recipient, subject, status, trigger)
 * is structured and useful enough to keep in its own table rather
 * than buried in record details JSON.
 */
@Entity
@Table(name = "email_logs", indexes = {
        @Index(name = "idx_email_user_id", columnList = "user_id"),
        @Index(name = "idx_email_type", columnList = "email_type"),
        @Index(name = "idx_email_status", columnList = "status")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    /** WELCOME, VERIFICATION_CODE, AGREEMENT_REQUEST, SIGNED_PDF, ... */
    @Column(name = "email_type", nullable = false, length = 50)
    private String emailType;

    @Column(name = "recipient", length = 255)
    private String recipient;

    @Column(name = "subject", length = 500)
    private String subject;

    /**
     * SENT (the mail server accepted it), FAILED (it refused or couldn't
     * be reached) or SKIPPED (email isn't set up on this server).
     */
    @Column(name = "status", length = 20)
    @Builder.Default
    private String status = "SENT";

    /** Where the email was sent from in the code (Class.method). */
    @Column(name = "trigger_event", length = 100)
    private String triggerEvent;

    /** Why it failed (the mail server's message), when it did. */
    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "sent_at", updatable = false)
    private LocalDateTime sentAt;
}
