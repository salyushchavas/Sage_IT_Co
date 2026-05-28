package com.spire.backend.service;

import com.spire.backend.dto.CourseProgressDTO;
import com.spire.backend.dto.ProgressDTO;
import com.spire.backend.entity.Course;
import com.spire.backend.entity.Enrollment;
import com.spire.backend.entity.Lesson;
import com.spire.backend.entity.Module;
import com.spire.backend.entity.Progress;
import com.spire.backend.entity.User;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.exception.UnauthorizedException;
import com.spire.backend.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProgressService {

    private final ProgressRepository progressRepository;
    private final UserRepository userRepository;
    private final CourseRepository courseRepository;
    private final LessonRepository lessonRepository;
    private final ModuleRepository moduleRepository;
    private final EnrollmentRepository enrollmentRepository;

    public List<ProgressDTO> getUserProgress(Long userId) {
        return progressRepository.findByUserId(userId).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    public List<ProgressDTO> getCourseProgress(Long userId, Long courseId) {
        return progressRepository.findByUserIdAndCourseId(userId, courseId).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Transactional
    public ProgressDTO updateProgress(Long userId, Long courseId, ProgressDTO dto) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new ResourceNotFoundException("Course", "id", courseId));

        Lesson lesson = null;
        if (dto.getLessonId() != null) {
            lesson = lessonRepository.findById(dto.getLessonId())
                    .orElseThrow(() -> new ResourceNotFoundException("Lesson", "id", dto.getLessonId()));
        }

        Progress progress;
        if (dto.getLessonId() != null) {
            progress = progressRepository.findByUserIdAndLessonId(userId, dto.getLessonId())
                    .orElse(Progress.builder().user(user).course(course).lesson(lesson).build());
        } else {
            var existing = progressRepository.findByUserIdAndCourseId(userId, courseId);
            progress = existing.isEmpty()
                    ? Progress.builder().user(user).course(course).build()
                    : existing.get(0);
        }

        if (dto.getCompletionPercent() != null) progress.setCompletionPercent(dto.getCompletionPercent());
        if (dto.getCompleted() != null) progress.setCompleted(dto.getCompleted());
        if (dto.getStreakDays() != null) progress.setStreakDays(dto.getStreakDays());
        if (dto.getVideoPositionSec() != null) progress.setVideoPositionSec(dto.getVideoPositionSec());
        progress.setLastAccessed(LocalDateTime.now());

        return toDTO(progressRepository.save(progress));
    }

    /**
     * Structured course progress for the course-detail page: overall %,
     * plus module-level breakdown with per-lesson completion flags.
     * 403s if the user isn't enrolled in the course.
     */
    @Transactional(readOnly = true)
    public CourseProgressDTO getCourseProgressDetail(Long userId, Long courseId) {
        Enrollment enrollment = enrollmentRepository.findByUserIdAndCourseId(userId, courseId)
                .orElseThrow(() -> new UnauthorizedException("You are not enrolled in this course"));

        List<Lesson> lessons = lessonRepository.findByCourseIdOrderByOrderIndex(courseId);
        List<Module> modules = moduleRepository.findByCourseIdOrderByOrderIndexAsc(courseId);

        List<Progress> rows = progressRepository.findByUserIdAndCourseId(userId, courseId);
        Set<Long> completedIds = rows.stream()
                .filter(p -> Boolean.TRUE.equals(p.getCompleted()))
                .filter(p -> p.getLesson() != null)
                .map(p -> p.getLesson().getId())
                .collect(Collectors.toSet());
        java.util.Map<Long, Integer> positionByLessonId = rows.stream()
                .filter(p -> p.getLesson() != null && p.getVideoPositionSec() != null)
                .collect(Collectors.toMap(
                        p -> p.getLesson().getId(),
                        Progress::getVideoPositionSec,
                        (a, b) -> a));

        List<CourseProgressDTO.ModuleProgress> moduleProgress = modules.stream()
                .map(m -> {
                    List<CourseProgressDTO.LessonProgress> ls = lessons.stream()
                            .filter(l -> l.getModule() != null
                                    && Objects.equals(l.getModule().getId(), m.getId()))
                            .sorted(Comparator.comparing(Lesson::getOrderIndex))
                            .map(l -> CourseProgressDTO.LessonProgress.builder()
                                    .lessonId(l.getId())
                                    .title(l.getTitle())
                                    .orderIndex(l.getOrderIndex())
                                    .completed(completedIds.contains(l.getId()))
                                    .videoPositionSec(positionByLessonId.getOrDefault(l.getId(), 0))
                                    .build())
                            .toList();
                    int total = ls.size();
                    int done = (int) ls.stream().filter(CourseProgressDTO.LessonProgress::isCompleted).count();
                    return CourseProgressDTO.ModuleProgress.builder()
                            .moduleId(m.getId())
                            .moduleTitle(m.getTitle())
                            .orderIndex(m.getOrderIndex())
                            .totalLessons(total)
                            .completedLessons(done)
                            .progressPercent(percent(done, total))
                            .lessons(ls)
                            .build();
                })
                .toList();

        List<CourseProgressDTO.LessonProgress> orphans = lessons.stream()
                .filter(l -> l.getModule() == null)
                .sorted(Comparator.comparing(Lesson::getOrderIndex))
                .map(l -> CourseProgressDTO.LessonProgress.builder()
                        .lessonId(l.getId())
                        .title(l.getTitle())
                        .orderIndex(l.getOrderIndex())
                        .completed(completedIds.contains(l.getId()))
                        .videoPositionSec(positionByLessonId.getOrDefault(l.getId(), 0))
                        .build())
                .toList();

        int totalLessons = lessons.size();
        int completedLessons = (int) lessons.stream()
                .filter(l -> completedIds.contains(l.getId()))
                .count();

        return CourseProgressDTO.builder()
                .courseId(courseId)
                .enrollmentId(enrollment.getId())
                .totalLessons(totalLessons)
                .completedLessons(completedLessons)
                .progressPercent(percent(completedLessons, totalLessons))
                .modules(moduleProgress)
                .orphanLessons(orphans)
                .build();
    }

    private static int percent(int completed, int total) {
        if (total <= 0) return 0;
        return (int) Math.round(100.0 * completed / total);
    }

    public int getStreakDays(Long userId) {
        return progressRepository.findByUserId(userId).stream()
                .mapToInt(Progress::getStreakDays)
                .max()
                .orElse(0);
    }

    private ProgressDTO toDTO(Progress p) {
        return ProgressDTO.builder()
                .courseId(p.getCourse().getId())
                .lessonId(p.getLesson() != null ? p.getLesson().getId() : null)
                .completionPercent(p.getCompletionPercent())
                .completed(p.getCompleted())
                .streakDays(p.getStreakDays())
                .videoPositionSec(p.getVideoPositionSec())
                .build();
    }
}
