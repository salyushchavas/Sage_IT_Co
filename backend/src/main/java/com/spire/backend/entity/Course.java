package com.spire.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;


@Entity
@Table(name = "courses")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Course {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(columnDefinition = "TEXT")
    private String description;

    private String shortDescription;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Level level = Level.BEGINNER;

    @Column(precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal price = BigDecimal.ZERO;

    @Builder.Default
    private Boolean isFree = true;

    private Double durationHours;

    private String thumbnailUrl;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "instructor_id", nullable = false)
    private User instructor;

    // Distinguishes regular learning courses from short, video-only services
    // (Resume Prep, Interview Training, etc.). Services skip mentorship,
    // assignments, quizzes, and certificates — same content tables, no extras.
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String type = "COURSE";

    // Author when type=SERVICE. instructor_id remains required (the user that
    // owns the row in the courses table); trainer_id is the editorial owner
    // surfaced to learners. Nullable for type=COURSE.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trainer_id")
    private User trainer;

    @Builder.Default
    private Integer lessonsCount = 0;

    @Builder.Default
    private Integer enrolledCount = 0;

    @Builder.Default
    private Double rating = 0.0;

    @Builder.Default
    private Integer ratingsCount = 0;

    private String category;

    private String tags;

    @Builder.Default
    private Boolean isPublished = false;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    public enum Level {
        BEGINNER, INTERMEDIATE, ADVANCED
    }

    public boolean isService() {
        return "SERVICE".equals(type);
    }
}
