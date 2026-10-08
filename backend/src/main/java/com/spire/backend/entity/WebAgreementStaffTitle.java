package com.spire.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * A staff member's title as printed on agreements (the console user's
 * {@code title}), e.g. "Engagement Manager". One row per website user,
 * kept in its own table so nothing is added to {@code users}. Prefills
 * the ERM's countersign and is set from the System Admin's staff screens.
 */
@Entity
@Table(name = "web_agreement_staff_titles",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_web_agreement_staff_title_user",
                columnNames = {"user_id"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebAgreementStaffTitle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** users.id of the staff member. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "title", length = 255)
    private String title;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** users.id of whoever last set the title. */
    @Column(name = "updated_by")
    private Long updatedBy;
}
