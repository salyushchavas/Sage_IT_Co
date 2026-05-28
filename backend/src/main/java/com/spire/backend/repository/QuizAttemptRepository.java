package com.spire.backend.repository;

import com.spire.backend.entity.QuizAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface QuizAttemptRepository extends JpaRepository<QuizAttempt, Long> {
    Optional<QuizAttempt> findByQuizIdAndUserId(Long quizId, Long userId);
    boolean existsByQuizIdAndUserId(Long quizId, Long userId);

    List<QuizAttempt> findByQuizIdAndUserIdOrderByAttemptedAtDesc(Long quizId, Long userId);
    long countByQuizIdAndUserId(Long quizId, Long userId);
}
