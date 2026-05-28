package com.spire.backend.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateModuleRequest {

    @Size(max = 255)
    private String title;

    private String description;
    private Integer orderIndex;
}
