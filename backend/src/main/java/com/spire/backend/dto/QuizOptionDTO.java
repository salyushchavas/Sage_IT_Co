package com.spire.backend.dto;

import com.spire.backend.entity.QuizOption;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuizOptionDTO {

    private Long id;
    private String optionText;
    /** Whether this option is the/a correct answer. Server omits
     *  this in the student-taking view so the answer can't be
     *  inspected via the network response before submitting. */
    private Boolean isCorrect;
    private Integer orderIndex;

    public static QuizOptionDTO from(QuizOption opt, boolean revealCorrect) {
        return QuizOptionDTO.builder()
                .id(opt.getId())
                .optionText(opt.getOptionText())
                .isCorrect(revealCorrect ? opt.getIsCorrect() : null)
                .orderIndex(opt.getOrderIndex())
                .build();
    }
}
