package com.spire.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;


@Entity
@Table(name = "session_requests")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SessionRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mentor_assignment_id", nullable = false)
    private MentorAssignment mentorAssignment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requested_by", nullable = false)
    private User requestedBy;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING";

    @Column(length = 500)
    private String topic;

    @CreationTimestamp
    private LocalDateTime requestedAt;

    private LocalDateTime scheduledAt;

    @Column(length = 500)
    private String meetingUrl;

    @Column(columnDefinition = "TEXT")
    private String notes;

    private LocalDateTime completedAt;
}
