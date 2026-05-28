package com.spire.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;



@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProgressDTO {

    private Long courseId;
    private Long lessonId;
    private Double completionPercent;
    private Boolean completed;
    private Integer streakDays;
    private Integer videoPositionSec;
}
