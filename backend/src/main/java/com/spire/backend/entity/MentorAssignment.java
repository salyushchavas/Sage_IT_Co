package com.spire.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;


@Entity
@Table(name = "mentor_assignments")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MentorAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "enrollment_id", nullable = false, unique = true)
    private Enrollment enrollment;

    // Nullable: when no mentor has capacity at enrollment time, the
    // assignment is created with mentor=null and status=PENDING_ASSIGNMENT.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mentor_id")
    private User mentor;

    @CreationTimestamp
    private LocalDateTime assignedAt;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "ACTIVE";
}
