package com.spire.backend.dto;

import com.spire.backend.entity.Course;
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
public class CourseDTO {

    private Long id;
    private String title;
    private String slug;
    private String description;
    private String shortDescription;
    private String level;
    private BigDecimal price;
    private Boolean isFree;
    private Double durationHours;
    private String thumbnailUrl;
    private UserDTO instructor;
    private UserDTO trainer;
    private String type;
    private Integer lessonsCount;
    private Integer enrolledCount;
    private Double rating;
    private Integer ratingsCount;
    private String category;
    private String tags;
    private Boolean isPublished;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    /**
     * Number of modules attached to the course. Optional — only set
     * when the caller (e.g., instructor dashboard) needs a quick
     * count without a separate fetch.
     */
    private Integer modulesCount;

    public static CourseDTO from(Course course) {
        return CourseDTO.builder()
                .id(course.getId())
                .title(course.getTitle())
                .slug(course.getSlug())
                .description(course.getDescription())
                .shortDescription(course.getShortDescription())
                .level(course.getLevel().name())
                .price(course.getPrice())
                .isFree(course.getIsFree())
                .durationHours(course.getDurationHours())
                .thumbnailUrl(course.getThumbnailUrl())
                .instructor(UserDTO.from(course.getInstructor()))
                .trainer(course.getTrainer() != null ? UserDTO.from(course.getTrainer()) : null)
                .type(course.getType())
                .lessonsCount(course.getLessonsCount())
                .enrolledCount(course.getEnrolledCount())
                .rating(course.getRating())
                .ratingsCount(course.getRatingsCount())
                .category(course.getCategory())
                .tags(course.getTags())
                .isPublished(course.getIsPublished())
                .createdAt(course.getCreatedAt())
                .updatedAt(course.getUpdatedAt())
                .build();
    }
}
