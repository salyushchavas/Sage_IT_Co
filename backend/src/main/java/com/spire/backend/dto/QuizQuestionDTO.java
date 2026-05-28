package com.spire.backend.dto;

import com.spire.backend.entity.Question;
import com.spire.backend.entity.QuizOption;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Comparator;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuizQuestionDTO {

    private Long id;
    private String questionText;
    private String questionType;
    private Integer points;
    private Integer orderIndex;
    /** Only included when the viewer is allowed to see correct answers
     *  (instructor builder, or student post-submit). */
    private String explanation;
    private List<QuizOptionDTO> options;

    public static QuizQuestionDTO from(Question q, boolean revealCorrect) {
        List<QuizOption> opts = q.getOptions() != null ? q.getOptions() : List.of();
        List<QuizOptionDTO> dtoOpts = opts.stream()
                .sorted(Comparator.comparingInt(o -> o.getOrderIndex() != null ? o.getOrderIndex() : 0))
                .map(o -> QuizOptionDTO.from(o, revealCorrect))
                .toList();
        return QuizQuestionDTO.builder()
                .id(q.getId())
                .questionText(q.getQuestionText())
                .questionType(q.getQuestionType() != null ? q.getQuestionType().name() : "MULTIPLE_CHOICE")
                .points(q.getPoints())
                .orderIndex(q.getOrderIndex())
                .explanation(revealCorrect ? q.getExplanation() : null)
                .options(dtoOpts)
                .build();
    }
}
