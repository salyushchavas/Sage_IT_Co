package com.spire.backend.controller;

import com.spire.backend.dto.ApiResponse;
import com.spire.backend.dto.QuizAttemptDTO;
import com.spire.backend.dto.QuizDTO;
import com.spire.backend.dto.QuizQuestionDTO;
import com.spire.backend.dto.QuizQuestionRequest;
import com.spire.backend.dto.QuizRequest;
import com.spire.backend.dto.QuizSubmitRequest;
import com.spire.backend.dto.QuizSubmitResult;
import com.spire.backend.service.QuizService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Quiz endpoints split by audience:
 *  - /api/instructor/* — instructor builder + course owner edits
 *  - /api/quizzes/*    — student taking + attempt history
 *
 * The legacy endpoints (POST /api/lessons/{id}/quiz, etc.) were
 * removed alongside the schema migration to the normalized
 * QuizOption / QuizAnswer model. The old QuizSection / QuizBuilder
 * frontend components are also retired in favor of the new pages.
 */
@RestController
@RequiredArgsConstructor
public class QuizController {

    private final QuizService quizService;

    // ─── Instructor: quiz CRUD ──────────────────────────────────────

    @PostMapping("/api/instructor/quizzes")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    public ResponseEntity<ApiResponse<QuizDTO>> createQuiz(
            @Valid @RequestBody QuizRequest dto, Authentication auth) {
        QuizDTO created = quizService.createQuiz(dto, uid(auth), isAdmin(auth));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Quiz created", created));
    }

    @GetMapping("/api/instructor/courses/{courseId}/quizzes")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    public ResponseEntity<ApiResponse<List<QuizDTO>>> listForCourse(
            @PathVariable Long courseId, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(
                quizService.listQuizzesForCourse(courseId, uid(auth), isAdmin(auth))));
    }

    @GetMapping("/api/instructor/quizzes/{quizId}")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    public ResponseEntity<ApiResponse<QuizDTO>> getForInstructor(
            @PathVariable Long quizId, Authentication auth) {
        // Reuse the student method but reveal correct answers since
        // it's the instructor view. mustOwnQuiz() inside the service
        // catches non-owner instructors.
        QuizDTO dto = quizService.getQuizForInstructor(quizId, uid(auth), isAdmin(auth));
        return ResponseEntity.ok(ApiResponse.success(dto));
    }

    @PutMapping("/api/instructor/quizzes/{quizId}")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    public ResponseEntity<ApiResponse<QuizDTO>> updateQuiz(
            @PathVariable Long quizId, @RequestBody QuizRequest dto, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success("Quiz updated",
                quizService.updateQuiz(quizId, dto, uid(auth), isAdmin(auth))));
    }

    @DeleteMapping("/api/instructor/quizzes/{quizId}")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteQuiz(
            @PathVariable Long quizId, Authentication auth) {
        quizService.deleteQuiz(quizId, uid(auth), isAdmin(auth));
        return ResponseEntity.ok(ApiResponse.success("Quiz deleted", null));
    }

    // ─── Instructor: questions ──────────────────────────────────────

    @PostMapping("/api/instructor/quizzes/{quizId}/questions")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    public ResponseEntity<ApiResponse<QuizQuestionDTO>> addQuestion(
            @PathVariable Long quizId, @RequestBody QuizQuestionRequest req, Authentication auth) {
        QuizQuestionDTO created = quizService.addQuestion(quizId, req, uid(auth), isAdmin(auth));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Question added", created));
    }

    @PutMapping("/api/instructor/questions/{questionId}")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    public ResponseEntity<ApiResponse<QuizQuestionDTO>> updateQuestion(
            @PathVariable Long questionId, @RequestBody QuizQuestionRequest req, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success("Question updated",
                quizService.updateQuestion(questionId, req, uid(auth), isAdmin(auth))));
    }

    @DeleteMapping("/api/instructor/questions/{questionId}")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteQuestion(
            @PathVariable Long questionId, Authentication auth) {
        quizService.deleteQuestion(questionId, uid(auth), isAdmin(auth));
        return ResponseEntity.ok(ApiResponse.success("Question deleted", null));
    }

    @PutMapping("/api/instructor/quizzes/{quizId}/questions/reorder")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    public ResponseEntity<ApiResponse<Void>> reorderQuestions(
            @PathVariable Long quizId,
            @RequestBody Map<String, Object> body,
            Authentication auth) {
        @SuppressWarnings("unchecked")
        List<Object> raw = body.get("questionIds") instanceof List
                ? (List<Object>) body.get("questionIds") : List.of();
        List<Long> ids = raw.stream().map(o -> Long.parseLong(o.toString())).toList();
        quizService.reorderQuestions(quizId, ids, uid(auth), isAdmin(auth));
        return ResponseEntity.ok(ApiResponse.success("Questions reordered", null));
    }

    // ─── Student: take quiz ─────────────────────────────────────────

    @GetMapping("/api/courses/{courseId}/quizzes")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<QuizDTO>>> listForStudent(
            @PathVariable Long courseId, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(
                quizService.listActiveQuizzesForCourse(courseId, uid(auth))));
    }

    @GetMapping("/api/quizzes/{quizId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<QuizDTO>> getForStudent(
            @PathVariable Long quizId, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(
                quizService.getQuizForStudent(quizId, uid(auth))));
    }

    @PostMapping("/api/quizzes/{quizId}/submit")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<QuizSubmitResult>> submit(
            @PathVariable Long quizId,
            @RequestBody QuizSubmitRequest req,
            Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success("Quiz submitted",
                quizService.submitQuiz(quizId, req, uid(auth))));
    }

    @GetMapping("/api/quizzes/{quizId}/attempts")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<QuizAttemptDTO>>> myAttempts(
            @PathVariable Long quizId, Authentication auth) {
        List<QuizAttemptDTO> attempts = quizService.getMyAttempts(quizId, uid(auth)).stream()
                .map(QuizAttemptDTO::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.success(attempts));
    }

    private Long uid(Authentication a) { return Long.parseLong(a.getPrincipal().toString()); }
    private boolean isAdmin(Authentication a) {
        return a.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
