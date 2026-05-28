package com.spire.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Display row for a participant's wishlist. Joined with the course /
 * service catalog at read time so the dashboard tab can render
 * thumbnails + "Enroll" buttons without a follow-up call.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WishlistItemDto {
    private Long id;
    private String kind;            // "COURSE" | "SERVICE"
    private Long targetId;          // courseId or serviceId
    private String title;
    private String thumbnailUrl;
    private BigDecimal price;
    private LocalDateTime addedAt;
}
