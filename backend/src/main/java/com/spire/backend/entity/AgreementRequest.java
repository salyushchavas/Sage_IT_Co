package com.spire.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * A participant clicked "I'm ready to sign the agreement" (after their
 * consent). The console ERMs then start the real agreement for them in
 * /agreements. The agreement itself lives in consultant_applications and
 * is found by the participant's email; this row only records the request.
 */
@Entity
@Table(name = "agreement_requests", indexes = {
        @Index(name = "idx_agreement_request_user", columnList = "user_id", unique = true)
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgreementRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @CreationTimestamp
    @Column(name = "requested_at", updatable = false)
    private LocalDateTime requestedAt;
}
