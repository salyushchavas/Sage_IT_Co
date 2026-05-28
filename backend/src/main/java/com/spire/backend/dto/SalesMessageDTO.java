package com.spire.backend.dto;

import com.spire.backend.entity.QuoteResponse;
import com.spire.backend.entity.SalesMessage;
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
public class SalesMessageDTO {

    private Long id;
    private Long senderId;
    private String senderName;
    private String senderRole;
    private String message;
    private String attachmentUrl;
    // NOTE: must stay Boolean (not primitive boolean). Lombok generates
    // isQuote() for primitive boolean, and Jackson then strips the "is"
    // prefix and serializes the field as "quote" — which broke the
    // frontend QuoteCard rendering. With Boolean, Lombok generates
    // getIsQuote() and Jackson keeps the property name "isQuote".
    private Boolean isQuote;
    private BigDecimal quotedPrice;
    /** Raw JSON string of line items — frontend parses. */
    private String quotedItems;
    /** ACCEPTED / DECLINED / COUNTER_OFFERED, or null if not yet responded. */
    private String quoteStatus;
    private LocalDateTime createdAt;

    public static SalesMessageDTO from(SalesMessage m, QuoteResponse response) {
        return SalesMessageDTO.builder()
                .id(m.getId())
                .senderId(m.getSender() != null ? m.getSender().getId() : null)
                .senderName(m.getSender() != null ? m.getSender().getFullName() : null)
                .senderRole(m.getSender() != null && m.getSender().getRole() != null
                        ? m.getSender().getRole().getName() : null)
                .message(m.getMessage())
                .attachmentUrl(m.getAttachmentUrl())
                .isQuote(Boolean.TRUE.equals(m.getIsQuote()))
                .quotedPrice(m.getQuotedPrice())
                .quotedItems(m.getQuotedItems())
                .quoteStatus(response != null ? response.getStatus() : null)
                .createdAt(m.getCreatedAt())
                .build();
    }
}
