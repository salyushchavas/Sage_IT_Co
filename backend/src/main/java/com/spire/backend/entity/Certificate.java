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
