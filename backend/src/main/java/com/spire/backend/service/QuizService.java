package com.spire.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spire.backend.dto.QuizDTO;
import com.spire.backend.dto.QuizQuestionDTO;
import com.spire.backend.dto.QuizQuestionRequest;
import com.spire.backend.dto.QuizRequest;
import com.spire.backend.dto.QuizSubmitRequest;
import com.spire.backend.dto.QuizSubmitResult;
import com.spire.backend.entity.Course;
import com.spire.backend.entity.Lesson;
import com.spire.backend.entity.Module;
import com.spire.backend.entity.Question;
import com.spire.backend.entity.Quiz;
import com.spire.backend.entity.QuizAnswer;
import com.spire.backend.entity.QuizAttempt;
import com.spire.backend.entity.QuizOption;
import com.spire.backend.entity.User;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.exception.UnauthorizedException;
import com.spire.backend.repository.CourseRepository;
import com.spire.backend.repository.EnrollmentRepository;
import com.spire.backend.repository.LessonRepository;
import com.spire.backend.repository.ModuleRepository;
import com.spire.backend.repository.QuestionRepository;
import com.spire.backend.repository.QuizAnswerRepository;
import com.spire.backend.repository.QuizAttemptRepository;
import com.spire.backend.repository.QuizOptionRepository;
import com.spire.backend.repository.QuizRepository;
import com.spire.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Quiz lifecycle: instructors build quizzes (with questions and
 * options), students take them and get scored against the
 * configured pass threshold.
 *
 * Migrated from a fixed 4-option model to normalized
 * {@link QuizOption} rows that support MULTIPLE_CHOICE / TRUE_FALSE
 * / MULTI_SELECT. Per-attempt answers persist as {@link QuizAnswer}
 * rows so we can replay results without re-grading.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QuizService {

    private final QuizRepository quizRepository;
    private final QuestionRepository questionRepository;
    private final QuizOptionRepository optionRepository;
    private final QuizAttemptRepository attemptRepository;
    private final QuizAnswerRepository answerRepository;
    private final CourseRepository courseRepository;
    private final ModuleRepository moduleRepository;
    private final LessonRepository lessonRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final UserRepository userRepository;
    private final RecordService recordService;
    private final ObjectMapper objectMapper;

    private static final int DEFAULT_PASS_THRESHOLD = 60;

    // ─── Instructor: quiz CRUD ──────────────────────────────────────

    @Transactional
    public QuizDTO createQuiz(QuizRequest dto, Long userId, boolean isAdmin) {
        if (dto.getCourseId() == null) {
            throw new IllegalArgumentException("courseId is required");
        }
        Course course = courseRepository.findById(dto.getCourseId())
                .orElseThrow(() -> new ResourceNotFoundException("Course", "id", dto.getCourseId()));
        if (!isAdmin && !course.getInstructor().getId().equals(userId)) {
            throw new UnauthorizedException("You can only create quizzes on your own courses");
        }

        Module module = null;
        Lesson lesson = null;
        if (dto.getModuleId() != null && dto.getLessonId() != null) {
            throw new IllegalArgumentException("A quiz can attach to a module OR a lesson, not both");
        }
        if (dto.getModuleId() != null) {
            module = moduleRepository.findById(dto.getModuleId())
                    .orElseThrow(() -> new ResourceNotFoundException("Module", "id", dto.getModuleId()));
            if (!Objects.equals(module.getCourse().getId(), course.getId())) {
                throw new IllegalArgumentException("Module does not belong to this course");
            }
        }
        if (dto.getLessonId() != null) {
            lesson = lessonRepository.findById(dto.getLessonId())
                    .orElseThrow(() -> new ResourceNotFoundException("Lesson", "id", dto.getLessonId()));
            if (!Objects.equals(lesson.getCourse().getId(), course.getId())) {
                throw new IllegalArgumentException("Lesson does not belong to this course");
            }
        }

        Quiz quiz = Quiz.builder()
                .course(course)
                .module(module)
                .lesson(lesson)
                .title(dto.getTitle().trim())
                .description(dto.getDescription())
                .passThreshold(dto.getPassThreshold() != null ? dto.getPassThreshold() : DEFAULT_PASS_THRESHOLD)
                .timeLimitMinutes(dto.getTimeLimitMinutes())
                .maxAttempts(dto.getMaxAttempts() != null ? dto.getMaxAttempts() : 3)
                .isActive(dto.getIsActive() != null ? dto.getIsActive() : true)
                .orderIndex(dto.getOrderIndex() != null ? dto.getOrderIndex() : 0)
                .build();

        Quiz saved = quizRepository.save(quiz);
        return QuizDTO.summary(saved, 0);
    }

    @Transactional(readOnly = true)
    public List<QuizDTO> listQuizzesForCourse(Long courseId, Long userId, boolean isAdmin) {
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new ResourceNotFoundException("Course", "id", courseId));
        if (!isAdmin && !course.getInstructor().getId().equals(userId)) {
            throw new UnauthorizedException("You can only view quizzes on your own courses");
        }
        return quizRepository.findByCourseIdOrderByOrderIndexAsc(courseId).stream()
                .map(q -> QuizDTO.summary(q, questionRepository.findByQuizId(q.getId()).size()))
                .toList();
    }

    /**
     * Student-facing listing — only returns active quizzes for the
     * course, with each quiz's question count and the caller's
     * attempt history merged in so the QuizCard can render
     * "Attempts: 1/3, best 80%" without N+1 follow-ups.
     */
    @Transactional(readOnly = true)
    public List<QuizDTO> listActiveQuizzesForCourse(Long courseId, Long userId) {
        return quizRepository.findByCourseIdOrderByOrderIndexAsc(courseId).stream()
                .filter(q -> Boolean.TRUE.equals(q.getIsActive()))
                .map(q -> {
                    QuizDTO dto = QuizDTO.summary(q, questionRepository.findByQuizId(q.getId()).size());
                    long attempts = attemptRepository.countByQuizIdAndUserId(q.getId(), userId);
                    Integer best = attemptRepository
                            .findByQuizIdAndUserIdOrderByAttemptedAtDesc(q.getId(), userId)
                            .stream()
                            .map(a -> a.getScorePercent() != null
                                    ? a.getScorePercent().intValue()
                                    : (a.getPercentage() != null ? a.getPercentage() : 0))
                            .max(Integer::compareTo).orElse(null);
                    dto.setAttemptCount((int) attempts);
                    dto.setBestScorePercent(best);
                    return dto;
                })
                .toList();
    }

    @Transactional
    public QuizDTO updateQuiz(Long quizId, QuizRequest dto, Long userId, boolean isAdmin) {
        Quiz quiz = mustOwnQuiz(quizId, userId, isAdmin);
        if (dto.getTitle() != null && !dto.getTitle().isBlank()) quiz.setTitle(dto.getTitle().trim());
        if (dto.getDescription() != null) quiz.setDescription(dto.getDescription());
        if (dto.getPassThreshold() != null) quiz.setPassThreshold(dto.getPassThreshold());
        if (dto.getTimeLimitMinutes() != null) quiz.setTimeLimitMinutes(dto.getTimeLimitMinutes());
        if (dto.getMaxAttempts() != null) quiz.setMaxAttempts(dto.getMaxAttempts());
        if (dto.getIsActive() != null) quiz.setIsActive(dto.getIsActive());
        if (dto.getOrderIndex() != null) quiz.setOrderIndex(dto.getOrderIndex());
        Quiz saved = quizRepository.save(quiz);
        return QuizDTO.summary(saved, questionRepository.findByQuizId(quizId).size());
    }

    @Transactional
    public void deleteQuiz(Long quizId, Long userId, boolean isAdmin) {
        Quiz quiz = mustOwnQuiz(quizId, userId, isAdmin);
        quizRepository.delete(quiz);
    }

    // ─── Instructor: questions ──────────────────────────────────────

    @Transactional
    public QuizQuestionDTO addQuestion(Long quizId, QuizQuestionRequest req, Long userId, boolean isAdmin) {
        Quiz quiz = mustOwnQuiz(quizId, userId, isAdmin);
        Question.QuestionType type = parseType(req.getQuestionType());
        validateOptionsForType(type, req.getOptions());

        // Place new questions at the end of the existing list — keeps
        // instructor's mental model "I added it at the bottom".
        int nextOrder = questionRepository.findByQuizIdOrderByOrderIndexAsc(quizId).size();

        Question question = Question.builder()
                .quiz(quiz)
                .questionText(req.getQuestionText().trim())
                .questionType(type)
                .points(req.getPoints() != null && req.getPoints() > 0 ? req.getPoints() : 1)
                .orderIndex(nextOrder)
                .explanation(req.getExplanation())
                .build();

        Question savedQuestion = questionRepository.save(question);
        persistOptions(savedQuestion, req.getOptions());

        return QuizQuestionDTO.from(savedQuestion, true);
    }

    @Transactional
    public QuizQuestionDTO updateQuestion(Long questionId, QuizQuestionRequest req, Long userId, boolean isAdmin) {
        Question question = questionRepository.findById(questionId)
                .orElseThrow(() -> new ResourceNotFoundException("Question", "id", questionId));
        mustOwnQuiz(question.getQuiz().getId(), userId, isAdmin);

        Question.QuestionType type = parseType(req.getQuestionType());
        validateOptionsForType(type, req.getOptions());

        question.setQuestionText(req.getQuestionText().trim());
        question.setQuestionType(type);
        if (req.getPoints() != null && req.getPoints() > 0) question.setPoints(req.getPoints());
        question.setExplanation(req.getExplanation());

        // Replace options wholesale — easier than diffing, and quiz
        // edits aren't hot enough to matter perf-wise.
        List<QuizOption> existing = optionRepository.findByQuestionIdOrderByOrderIndexAsc(questionId);
        optionRepository.deleteAll(existing);
        optionRepository.flush();
        Question saved = questionRepository.save(question);
        persistOptions(saved, req.getOptions());

        return QuizQuestionDTO.from(saved, true);
    }

    @Transactional
    public void deleteQuestion(Long questionId, Long userId, boolean isAdmin) {
        Question question = questionRepository.findById(questionId)
                .orElseThrow(() -> new ResourceNotFoundException("Question", "id", questionId));
        mustOwnQuiz(question.getQuiz().getId(), userId, isAdmin);
        questionRepository.delete(question);
    }

    @Transactional
    public void reorderQuestions(Long quizId, List<Long> questionIds, Long userId, boolean isAdmin) {
        mustOwnQuiz(quizId, userId, isAdmin);
        if (questionIds == null) return;
        for (int i = 0; i < questionIds.size(); i++) {
            Long id = questionIds.get(i);
            Question q = questionRepository.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Question", "id", id));
            if (!Objects.equals(q.getQuiz().getId(), quizId)) {
                throw new IllegalArgumentException("Question " + id + " is not part of quiz " + quizId);
            }
            q.setOrderIndex(i);
            questionRepository.save(q);
        }
    }

    /**
     * Same as the student-taking view but reveals correct flags and
     * explanations — for the instructor question editor.
     */
    @Transactional(readOnly = true)
    public QuizDTO getQuizForInstructor(Long quizId, Long userId, boolean isAdmin) {
        Quiz quiz = mustOwnQuiz(quizId, userId, isAdmin);
        List<Question> questions = questionRepository.findByQuizIdOrderByOrderIndexAsc(quizId);
        List<QuizQuestionDTO> questionDtos = questions.stream()
                .map(q -> QuizQuestionDTO.from(q, true))
                .toList();
        return QuizDTO.detail(quiz, questionDtos);
    }

    // ─── Student: take quiz ─────────────────────────────────────────

    /**
     * Quiz payload for the student-facing player. Strips correctness
     * info from options and explanations from questions — those only
     * appear after submit.
     */
    @Transactional(readOnly = true)
    public QuizDTO getQuizForStudent(Long quizId, Long userId) {
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz", "id", quizId));
        if (!Boolean.TRUE.equals(quiz.getIsActive())) {
            throw new IllegalArgumentException("This quiz is not currently active");
        }

        List<Question> questions = questionRepository.findByQuizIdOrderByOrderIndexAsc(quizId);
        List<QuizQuestionDTO> questionDtos = questions.stream()
                .map(q -> QuizQuestionDTO.from(q, false))
                .toList();
        QuizDTO dto = QuizDTO.detail(quiz, questionDtos);

        long attempts = attemptRepository.countByQuizIdAndUserId(quizId, userId);
        Integer best = attemptRepository
                .findByQuizIdAndUserIdOrderByAttemptedAtDesc(quizId, userId)
                .stream()
                .map(a -> a.getScorePercent() != null
                        ? a.getScorePercent().intValue()
                        : (a.getPercentage() != null ? a.getPercentage() : 0))
                .max(Integer::compareTo)
                .orElse(null);

        dto.setAttemptCount((int) attempts);
        dto.setBestScorePercent(best);
        return dto;
    }

    @Transactional
    public QuizSubmitResult submitQuiz(Long quizId, QuizSubmitRequest req, Long userId) {
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz", "id", quizId));
        if (!Boolean.TRUE.equals(quiz.getIsActive())) {
            throw new IllegalArgumentException("This quiz is not currently active");
        }

        // Ownership: student must be enrolled in the parent course.
        Long courseId = quiz.getCourse() != null ? quiz.getCourse().getId() : null;
        if (courseId == null && quiz.getLesson() != null) {
            courseId = quiz.getLesson().getCourse().getId();
        }
        if (courseId != null && !enrollmentRepository.existsByUserIdAndCourseId(userId, courseId)) {
            throw new UnauthorizedException("You must be enrolled in the course to take this quiz");
        }

        // Attempt limit — null maxAttempts means unlimited.
        long alreadyAttempted = attemptRepository.countByQuizIdAndUserId(quizId, userId);
        if (quiz.getMaxAttempts() != null && alreadyAttempted >= quiz.getMaxAttempts()) {
            throw new IllegalArgumentException("You've used all your attempts for this quiz");
        }

        List<Question> questions = questionRepository.findByQuizIdOrderByOrderIndexAsc(quizId);
        if (questions.isEmpty()) {
            throw new IllegalArgumentException("This quiz has no questions");
        }

        // Index submitted answers by question id for fast lookup.
        Map<Long, List<Long>> selectedByQuestion = new HashMap<>();
        if (req != null && req.getAnswers() != null) {
            for (QuizSubmitRequest.QuestionAnswer a : req.getAnswers()) {
                if (a.getQuestionId() != null) {
                    selectedByQuestion.put(a.getQuestionId(),
                            a.getSelectedOptionIds() != null ? a.getSelectedOptionIds() : List.of());
                }
            }
        }

        int totalPoints = 0;
        int earnedPoints = 0;
        int correctCount = 0;

        // First pass: grade and gather per-question results, but defer
        // QuizAnswer persistence until after we've created the
        // QuizAttempt row to avoid an awkward two-phase commit.
        List<QuestionGrading> gradings = new ArrayList<>();
        for (Question q : questions) {
            int points = q.getPoints() != null ? q.getPoints() : 1;
            totalPoints += points;
            List<QuizOption> options = optionRepository.findByQuestionIdOrderByOrderIndexAsc(q.getId());
            Set<Long> correctIds = new HashSet<>();
            for (QuizOption o : options) {
                if (Boolean.TRUE.equals(o.getIsCorrect())) correctIds.add(o.getId());
            }
            List<Long> selected = selectedByQuestion.getOrDefault(q.getId(), List.of());
            Set<Long> selectedSet = new HashSet<>(selected);
            // Match definition:
            //  - MULTIPLE_CHOICE / TRUE_FALSE: selected set must equal
            //    correct set (single-element).
            //  - MULTI_SELECT: same — every correct id selected, no
            //    extras. Partial credit isn't awarded.
            boolean correct = !correctIds.isEmpty() && correctIds.equals(selectedSet);
            if (correct) {
                earnedPoints += points;
                correctCount++;
            }
            gradings.add(new QuestionGrading(q, selected, correctIds.stream().toList(), correct));
        }

        BigDecimal scorePercent = totalPoints > 0
                ? BigDecimal.valueOf(earnedPoints * 100.0 / totalPoints)
                        .setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        int threshold = quiz.getPassThreshold() != null ? quiz.getPassThreshold() : DEFAULT_PASS_THRESHOLD;
        boolean passed = scorePercent.compareTo(BigDecimal.valueOf(threshold)) >= 0;

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        QuizAttempt attempt = QuizAttempt.builder()
                .quiz(quiz)
                .user(user)
                .scorePercent(scorePercent)
                .passed(passed)
                .attemptNumber((int) alreadyAttempted + 1)
                .startedAt(LocalDateTime.now())
                .completedAt(LocalDateTime.now())
                .timeTakenSeconds(req != null ? req.getTimeTakenSeconds() : null)
                // Populate legacy ints for backwards-compat reads.
                .score(correctCount)
                .totalQuestions(questions.size())
                .percentage(scorePercent.intValue())
                .build();
        QuizAttempt savedAttempt = attemptRepository.save(attempt);

        // Now persist per-question answers + build the result payload.
        List<QuizSubmitResult.QuestionResult> resultRows = new ArrayList<>();
        for (QuestionGrading g : gradings) {
            String selectedJson;
            try {
                selectedJson = objectMapper.writeValueAsString(g.selected);
            } catch (JsonProcessingException e) {
                selectedJson = "[]";
            }
            answerRepository.save(QuizAnswer.builder()
                    .attempt(savedAttempt)
                    .question(g.question)
                    .selectedOptionIds(selectedJson)
                    .isCorrect(g.correct)
                    .build());

            resultRows.add(QuizSubmitResult.QuestionResult.builder()
                    .questionId(g.question.getId())
                    .correct(g.correct)
                    .selectedOptionIds(g.selected)
                    .correctOptionIds(g.correctIds)
                    .explanation(g.question.getExplanation())
                    .build());
        }

        // Audit trail — these records keep an immutable per-user log.
        Map<String, Object> details = new HashMap<>();
        details.put("quizId", quiz.getId());
        details.put("quizTitle", quiz.getTitle());
        details.put("score", scorePercent);
        details.put("passed", passed);
        details.put("totalQuestions", questions.size());
        details.put("correctAnswers", correctCount);
        details.put("attemptNumber", attempt.getAttemptNumber());
        try {
            recordService.record(userId, "QUIZ_ATTEMPTED", RecordService.Category.ASSESSMENT,
                    "Attempted quiz: " + quiz.getTitle(),
                    "Scored " + scorePercent + "% (" + (passed ? "passed" : "failed") + ") on '" + quiz.getTitle() + "'",
                    details);
            if (passed) {
                recordService.record(userId, "QUIZ_PASSED", RecordService.Category.ASSESSMENT,
                        "Passed quiz: " + quiz.getTitle(),
                        "Passed '" + quiz.getTitle() + "' with " + scorePercent + "%",
                        details);
            }
        } catch (Exception e) {
            log.warn("Couldn't write quiz audit record for user {} quiz {}", userId, quiz.getId(), e);
        }

        Integer attemptsRemaining = quiz.getMaxAttempts() != null
                ? Math.max(0, quiz.getMaxAttempts() - (int) alreadyAttempted - 1)
                : null;

        return QuizSubmitResult.builder()
                .attemptId(savedAttempt.getId())
                .scorePercent(scorePercent)
                .passed(passed)
                .passThreshold(threshold)
                .attemptNumber(attempt.getAttemptNumber())
                .attemptsRemaining(attemptsRemaining)
                .totalQuestions(questions.size())
                .correctCount(correctCount)
                .timeTakenSeconds(attempt.getTimeTakenSeconds())
                .results(resultRows)
                .build();
    }

    // ─── Helpers ────────────────────────────────────────────────────

    private Quiz mustOwnQuiz(Long quizId, Long userId, boolean isAdmin) {
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz", "id", quizId));
        Long instructorId = quiz.getCourse() != null && quiz.getCourse().getInstructor() != null
                ? quiz.getCourse().getInstructor().getId()
                : (quiz.getLesson() != null ? quiz.getLesson().getCourse().getInstructor().getId() : null);
        if (!isAdmin && (instructorId == null || !instructorId.equals(userId))) {
            throw new UnauthorizedException("You can only manage quizzes on your own courses");
        }
        return quiz;
    }

    private Question.QuestionType parseType(String raw) {
        if (raw == null || raw.isBlank()) return Question.QuestionType.MULTIPLE_CHOICE;
        try {
            return Question.QuestionType.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown question type: " + raw);
        }
    }

    private void validateOptionsForType(Question.QuestionType type, List<QuizQuestionRequest.OptionRequest> opts) {
        if (opts == null || opts.isEmpty()) {
            throw new IllegalArgumentException("Options are required");
        }
        long correct = opts.stream().filter(o -> Boolean.TRUE.equals(o.getIsCorrect())).count();
        for (QuizQuestionRequest.OptionRequest o : opts) {
            if (o.getOptionText() == null || o.getOptionText().isBlank()) {
                throw new IllegalArgumentException("Every option needs text");
            }
        }
        switch (type) {
            case TRUE_FALSE -> {
                if (opts.size() != 2) throw new IllegalArgumentException("True/False must have exactly 2 options");
                if (correct != 1) throw new IllegalArgumentException("True/False must have exactly 1 correct option");
            }
            case MULTIPLE_CHOICE -> {
                if (opts.size() < 2 || opts.size() > 6)
                    throw new IllegalArgumentException("Multiple choice must have 2–6 options");
                if (correct != 1)
                    throw new IllegalArgumentException("Multiple choice must have exactly 1 correct option");
            }
            case MULTI_SELECT -> {
                if (opts.size() < 2 || opts.size() > 6)
                    throw new IllegalArgumentException("Multi-select must have 2–6 options");
                if (correct < 1)
                    throw new IllegalArgumentException("Multi-select must have at least 1 correct option");
            }
        }
    }

    private void persistOptions(Question question, List<QuizQuestionRequest.OptionRequest> opts) {
        for (int i = 0; i < opts.size(); i++) {
            QuizQuestionRequest.OptionRequest o = opts.get(i);
            optionRepository.save(QuizOption.builder()
                    .question(question)
                    .optionText(o.getOptionText().trim())
                    .isCorrect(Boolean.TRUE.equals(o.getIsCorrect()))
                    .orderIndex(i)
                    .build());
        }
    }

    /**
     * Returns student attempt history for a quiz, newest first.
     */
    @Transactional(readOnly = true)
    public List<QuizAttempt> getMyAttempts(Long quizId, Long userId) {
        return attemptRepository.findByQuizIdAndUserIdOrderByAttemptedAtDesc(quizId, userId);
    }

    private record QuestionGrading(Question question, List<Long> selected, List<Long> correctIds, boolean correct) {}

    /** Used by {@link #parseSelectedIds} from the read side. */
    @SuppressWarnings("unused")
    public List<Long> parseSelectedIds(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<Long>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    // Legacy compatibility shims — old QuestionRequest path is no
    // longer reachable from the new frontend, but the old controller
    // endpoint still exists. Surface a clear error so anyone hitting
    // it knows to migrate.
    @Deprecated
    public Quiz createQuizLegacy() {
        throw new UnsupportedOperationException(
                "Legacy quiz endpoint removed — use POST /api/instructor/quizzes");
    }

    @Deprecated
    public Question addQuestionLegacy() {
        throw new UnsupportedOperationException(
                "Legacy quiz endpoint removed — use POST /api/instructor/quizzes/{id}/questions");
    }
}
