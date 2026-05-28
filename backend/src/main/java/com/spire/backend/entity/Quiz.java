package com.spire.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A quiz attached to one of: a specific lesson, an end-of-module
 * assessment, or a course-final assessment. The three scope FKs
 * (lesson / module / course) are mutually-exclusive at the
 * application level — exactly one is set per quiz. Hibernate keeps
 * them as separate columns rather than a polymorphic discriminator
 * because the queries we run ("all quizzes for course X") want
 * raw column lookups.
 *
 * Schema migrated in-place: legacy quizzes had only `lesson_id` set
 * and no threshold/attempt config. ddl-auto=update adds the new
 * columns; the lesson_id column is loosened to nullable. Existing
 * rows get default threshold=60, maxAttempts=3, isActive=true via
 * the @Builder.Default values.
 */
@Entity
@Table(name = "quizzes")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Quiz {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** The course this quiz belongs to. Always set, even when also
     *  scoped to a module or lesson — needed for "list quizzes for
     *  course X" without traversing the parent chain. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id")
    private Course course;

    /** Set when the quiz is an end-of-module assessment. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "module_id")
    private Module module;

    /** Set when the quiz is attached to a single lesson. Used to be
     *  required pre-migration; now nullable so a quiz can scope to
     *  course or module instead. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lesson_id")
    private Lesson lesson;

    @Column(name = "pass_threshold", nullable = false)
    @Builder.Default
    private Integer passThreshold = 60;

    @Column(name = "time_limit_minutes")
    private Integer timeLimitMinutes;

    @Column(name = "max_attempts")
    @Builder.Default
    private Integer maxAttempts = 3;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "order_index", nullable = false)
    @Builder.Default
    private Integer orderIndex = 0;

    @OneToMany(mappedBy = "quiz", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Question> questions = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
