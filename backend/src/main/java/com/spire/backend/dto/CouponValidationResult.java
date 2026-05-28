package com.spire.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CouponValidationResult {

    private String code;
    private String discountType;
    private BigDecimal discountValue;
    private BigDecimal cartTotal;
    private BigDecimal discountAmount;
    private BigDecimal finalTotal;
}
