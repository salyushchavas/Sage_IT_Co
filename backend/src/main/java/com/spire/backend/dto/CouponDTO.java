package com.spire.backend.dto;

import com.spire.backend.entity.Coupon;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CouponDTO {

    private Long id;
    private String code;
    private String discountType;
    private BigDecimal discountValue;
    private BigDecimal minOrderAmount;
    private Integer maxUses;
    private Integer usesCount;
    private LocalDateTime expiresAt;
    private Boolean isActive;
    private LocalDateTime createdAt;

    public static CouponDTO from(Coupon c) {
        return CouponDTO.builder()
                .id(c.getId())
                .code(c.getCode())
                .discountType(c.getDiscountType() != null ? c.getDiscountType().name() : "PERCENT")
                .discountValue(c.getDiscountValue())
                .minOrderAmount(c.getMinOrderAmount())
                .maxUses(c.getMaxUses())
                .usesCount(c.getUsesCount() != null ? c.getUsesCount() : 0)
                .expiresAt(c.getExpiresAt())
                .isActive(Boolean.TRUE.equals(c.getIsActive()))
                .createdAt(c.getCreatedAt())
                .build();
    }
}
