package com.spire.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One row per (user, coupon) redemption — used to prevent the same
 * user redeeming the same coupon twice and to give admins an audit
 * trail of who used which code.
 */
@Entity
@Table(
    name = "coupon_redemptions",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_coupon_user",
        columnNames = {"coupon_id", "user_id"}
    )
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CouponRedemption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "coupon_id", nullable = false)
    private Coupon coupon;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "discount_applied", precision = 10, scale = 2)
    private BigDecimal discountApplied;

    @Column(name = "order_total", precision = 10, scale = 2)
    private BigDecimal orderTotal;

    @CreationTimestamp
    @Column(name = "redeemed_at", updatable = false)
    private LocalDateTime redeemedAt;
}
