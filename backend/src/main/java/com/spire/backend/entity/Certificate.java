package com.spire.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Issued once per (user, course) pair. {@code certificateId} doubles
 * as both the public verification slug and the human-readable cert
 * number — generated as {@code SIT-<COURSE>-<INITIALS>-<DDMMYY>} for
 * new certificates. Pre-existing rows hold UUIDs and remain valid.
 */
@Entity
@Table(name = "certificates", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"user_id", "course_id"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Certificate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "certificate_id", nullable = false, unique = true, updatable = false, length = 64)
    @Builder.Default
    private String certificateId = UUID.randomUUID().toString();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @Column(name = "certificate_url", nullable = false)
    private String certificateUrl;

    /**
     * Phase 1 (S3) — storage key for the S3-backed certificate PDF
     * (e.g. {@code certificates/{userId}/certificate-20260612-101500.pdf}).
     * Non-null for certificates issued after the S3 cutover; older rows keep
     * it null and are served from the on-disk file. The download endpoints
     * branch on this (present → presigned S3 GET, streamed server-side).
     */
    @Column(name = "s3_key", length = 512)
    private String s3Key;

    /**
     * Final score % (0–100). Average of the student's best quiz
     * percentages on this course. Null when the course had no quizzes,
     * which shows as "Completed" rather than a numeric score.
     */
    @Column(name = "final_score")
    private Double finalScore;

    @CreationTimestamp
    @Column(name = "issued_at", updatable = false)
    private LocalDateTime issuedAt;
}
