package com.spire.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * A participant's saved-for-later courses / services. Browse-allowed
 * even when {@code profile_complete = false} — only the "Enroll all"
 * bulk path is gated, so users can curate a list while finishing
 * their profile. One row per user-per-target; the partial unique
 * indexes on the underlying table enforce no-dupes within each kind.
 */
@Entity
@Table(name = "wishlists",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_wishlist_user_course", columnNames = {"user_id", "course_id"}),
                @UniqueConstraint(name = "uk_wishlist_user_service", columnNames = {"user_id", "service_id"})
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Wishlist {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Either courseId OR serviceId is populated; the other is null. */
    @Column(name = "course_id")
    private Long courseId;

    @Column(name = "service_id")
    private Long serviceId;

    @CreationTimestamp
    @Column(name = "added_at", updatable = false)
    private LocalDateTime addedAt;
}
