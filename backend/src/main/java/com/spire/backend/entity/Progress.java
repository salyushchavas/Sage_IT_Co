package com.spire.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;


@Entity
@Table(name = "progress")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Progress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lesson_id")
    private Lesson lesson;

    @Builder.Default
    private Double completionPercent = 0.0;

    @Builder.Default
    private Boolean completed = false;

    @Builder.Default
    private Integer streakDays = 0;

    // Seconds into the video — used by the focused player to resume.
    @Column(name = "video_position_sec", nullable = false)
    @Builder.Default
    private Integer videoPositionSec = 0;

    private LocalDateTime lastAccessed;
}
