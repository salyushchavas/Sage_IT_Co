package com.spire.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Immutable;

import java.time.LocalDateTime;

/**
 * Permanent, immutable record of a user action on the platform.
 *
 * The user_records table is APPEND-ONLY — the @Immutable annotation
 * tells Hibernate to ignore any UPDATE attempts at the entity level,
 * and the JpaRepository's mutating methods are overridden in
 * UserRecordRepository to throw UnsupportedOperationException.
 *
 * Records serve as evidence for verification, disputes, and audits.
 * Each row is self-contained — the description field reads as a full
 * sentence so the record makes sense without joining to other tables.
 */
@Entity
@Table(
    name = "user_records",
    indexes = {
        @Index(name = "idx_records_user", columnList = "user_id"),
        @Index(name = "idx_records_type", columnList = "record_type"),
        @Index(name = "idx_records_date", columnList = "created_at"),
        @Index(name = "idx_records_category", columnList = "category")
    }
)
@Immutable
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "record_type", nullable = false, length = 60)
    private String recordType;

    @Column(name = "category", nullable = false, length = 30)
    private String category;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    /** Free-form JSON payload (serialized) — context-specific evidence. */
    @Column(columnDefinition = "TEXT")
    private String details;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "device_type", length = 20)
    private String deviceType;

    @Column(length = 100)
    private String browser;

    @Column(length = 50)
    private String os;

    @Column(length = 100)
    private String city;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;
}
