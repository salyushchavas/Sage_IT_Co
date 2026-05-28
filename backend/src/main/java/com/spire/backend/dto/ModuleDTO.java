package com.spire.backend.dto;

import com.spire.backend.entity.Module;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;


@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ModuleDTO {

    private Long id;
    private Long courseId;
    private String title;
    private String description;
    private Integer orderIndex;
    private List<LessonDTO> lessons;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ModuleDTO from(Module module, boolean includeVideoUrls) {
        List<LessonDTO> lessonDtos = module.getLessons() != null
                ? module.getLessons().stream()
                    .map(l -> LessonDTO.from(l, includeVideoUrls || Boolean.TRUE.equals(l.getIsFree())))
                    .collect(Collectors.toList())
                : List.of();

        return ModuleDTO.builder()
                .id(module.getId())
                .courseId(module.getCourse().getId())
                .title(module.getTitle())
                .description(module.getDescription())
                .orderIndex(module.getOrderIndex())
                .lessons(lessonDtos)
                .createdAt(module.getCreatedAt())
                .updatedAt(module.getUpdatedAt())
                .build();
    }
}
