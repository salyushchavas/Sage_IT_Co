package com.spire.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Pairs a participant with their ERM (Enrollment Relationship Manager).
 * One active row per participant; historic rows stay for audit when
 * a participant is reassigned.
 */
@Entity
@Table(name = "erm_assignments", indexes = {
        @Index(name = "idx_erm_user_id", columnList = "user_id"),
        @Index(name = "idx_erm_erm_user_id", columnList = "erm_user_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ErmAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "erm_user_id")
    private Long ermUserId;

    @CreationTimestamp
    @Column(name = "assigned_date", updatable = false)
    private LocalDateTime assignedDate;

    /** PENDING, SENT, FAILED. */
    @Column(name = "intro_email_status", length = 20)
    @Builder.Default
    private String introEmailStatus = "PENDING";

    @Column(name = "communication_notes", columnDefinition = "TEXT")
    private String communicationNotes;
}
