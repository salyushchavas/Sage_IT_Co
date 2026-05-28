package com.spire.backend.dto;

import com.spire.backend.entity.SalesInquiry;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Two shapes share this DTO:
 *  - List view: messages stays null, lastMessagePreview/lastMessageAt
 *    populate the "card" view on /messages and the instructor inbox.
 *  - Detail view: messages contains the full thread, ordered ASC.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SalesInquiryDTO {

    private Long id;
    private Long userId;
    private String studentName;
    private String studentEmail;
    private Long courseId;
    private String courseTitle;
    private String courseType;
    private Long instructorId;
    private String instructorName;
    private String status;
    private String subject;
    private String budgetRange;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime closedAt;

    private String lastMessagePreview;
    private String lastMessageSenderName;
    private LocalDateTime lastMessageAt;

    private List<SalesMessageDTO> messages;

    public static SalesInquiryDTO summary(SalesInquiry i, String preview, String previewSender,
                                          LocalDateTime previewAt) {
        return baseBuilder(i)
                .lastMessagePreview(preview)
                .lastMessageSenderName(previewSender)
                .lastMessageAt(previewAt)
                .build();
    }

    public static SalesInquiryDTO detail(SalesInquiry i, List<SalesMessageDTO> messages) {
        return baseBuilder(i).messages(messages).build();
    }

    private static SalesInquiryDTOBuilder baseBuilder(SalesInquiry i) {
        return SalesInquiryDTO.builder()
                .id(i.getId())
                .userId(i.getUser() != null ? i.getUser().getId() : null)
                .studentName(i.getUser() != null ? i.getUser().getFullName() : null)
                .studentEmail(i.getUser() != null ? i.getUser().getEmail() : null)
                .courseId(i.getCourse() != null ? i.getCourse().getId() : null)
                .courseTitle(i.getCourse() != null ? i.getCourse().getTitle() : null)
                .courseType(i.getCourse() != null ? i.getCourse().getType() : null)
                .instructorId(i.getCourse() != null && i.getCourse().getInstructor() != null
                        ? i.getCourse().getInstructor().getId() : null)
                .instructorName(i.getCourse() != null && i.getCourse().getInstructor() != null
                        ? i.getCourse().getInstructor().getFullName() : null)
                .status(i.getStatus())
                .subject(i.getSubject())
                .budgetRange(i.getBudgetRange())
                .createdAt(i.getCreatedAt())
                .updatedAt(i.getUpdatedAt())
                .closedAt(i.getClosedAt());
    }
}
