package com.spire.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A login account for the consultant-agreement console.
 *
 * This is its own small user directory, intentionally separate from the
 * platform-wide {@code users} table -- the agreement console is a hidden
 * internal surface with only two roles ({@link AgreementUserRole}). The
 * single SUPER_ADMIN is bootstrapped from env vars by {@code
 * DataSeeder.ensureSuperAdmin}; every other row is an ERM minted through
 * {@code AgreementAdminController}.
 *
 * DDL_MODE=update auto-creates this table from the entity on boot, so no
 * manual DDL is needed for the table itself.
 *
 * The primary key is a String UUID (mirrors the {@code applicationId}
 * convention on {@link ConsultantApplication}) so it can be stored as a
 * plain VARCHAR in {@code consultant_applications.owner_erm_id} and in
 * {@link #createdBy} without an FK coupling.
 */
@Entity
@Table(name = "agreement_user", indexes = {
        @Index(name = "idx_agreement_user_email", columnList = "email", unique = true),
        @Index(name = "idx_agreement_user_role", columnList = "role")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgreementUser {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 255)
    private String fullName;

    /** Renders in the agreement signature block as Name / Title. */
    @Column(nullable = false, length = 255)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AgreementUserRole role;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** Id of the creating super-admin; null for the bootstrapped one. */
    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }
}
