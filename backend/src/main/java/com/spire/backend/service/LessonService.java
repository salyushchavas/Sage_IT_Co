package com.spire.backend.service;

import com.spire.backend.dto.LessonDTO;
import com.spire.backend.dto.LessonRequest;
import com.spire.backend.entity.Course;
import com.spire.backend.entity.Lesson;
import com.spire.backend.entity.Module;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.exception.UnauthorizedException;
import com.spire.backend.repository.CourseRepository;
import com.spire.backend.repository.LessonRepository;
import com.spire.backend.repository.ModuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LessonService {

    private final LessonRepository lessonRepository;
    private final CourseRepository courseRepository;
    private final ModuleRepository moduleRepository;

    public List<LessonDTO> getLessons(Long courseId, boolean includeVideoUrl) {
        return lessonRepository.findByCourseIdOrderByOrderIndex(courseId).stream()
                .map(l -> LessonDTO.from(l, includeVideoUrl || Boolean.TRUE.equals(l.getIsFree())))
                .collect(Collectors.toList());
    }

    @Transactional
    public LessonDTO createLesson(Long courseId, LessonRequest dto, Long userId, boolean isAdmin) {
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new ResourceNotFoundException("Course", "id", courseId));

        if (!isAdmin && !course.getInstructor().getId().equals(userId)) {
            throw new UnauthorizedException("You can only add lessons to your own courses");
        }

        // Resolve module if provided. Validate it belongs to this same
        // course — prevents an attacker from attaching a lesson to a
        // module they don't own.
        Module module = null;
        if (dto.getModuleId() != null) {
            module = moduleRepository.findById(dto.getModuleId())
                    .orElseThrow(() -> new ResourceNotFoundException("Module", "id", dto.getModuleId()));
            if (module.getCourse() == null || !module.getCourse().getId().equals(courseId)) {
                throw new IllegalArgumentException("Module does not belong to this course");
            }
        }

        int nextOrder = dto.getOrderIndex() != null ? dto.getOrderIndex()
                : lessonRepository.findByCourseIdOrderByOrderIndex(courseId).size() + 1;

        Lesson lesson = Lesson.builder()
                .course(course)
                .module(module)
                .title(dto.getTitle())
                .description(dto.getDescription())
                .videoUrl(dto.getVideoUrl())
                .orderIndex(nextOrder)
                .durationMinutes(dto.getDurationMinutes())
                .isFree(dto.getIsFree() != null ? dto.getIsFree() : false)
                .build();

        Lesson saved = lessonRepository.save(lesson);

        // Update course lesson count
        course.setLessonsCount(lessonRepository.findByCourseIdOrderByOrderIndex(courseId).size());
        courseRepository.save(course);

        return LessonDTO.from(saved, true);
    }

    @Transactional
    public LessonDTO updateLesson(Long lessonId, LessonRequest dto, Long userId, boolean isAdmin) {
        Lesson lesson = lessonRepository.findById(lessonId)
                .orElseThrow(() -> new ResourceNotFoundException("Lesson", "id", lessonId));

        if (!isAdmin && !lesson.getCourse().getInstructor().getId().equals(userId)) {
            throw new UnauthorizedException("You can only edit lessons in your own courses");
        }

        if (dto.getTitle() != null) lesson.setTitle(dto.getTitle());
        if (dto.getDescription() != null) lesson.setDescription(dto.getDescription());
        if (dto.getVideoUrl() != null) lesson.setVideoUrl(dto.getVideoUrl());
        if (dto.getOrderIndex() != null) lesson.setOrderIndex(dto.getOrderIndex());
        if (dto.getDurationMinutes() != null) lesson.setDurationMinutes(dto.getDurationMinutes());
        if (dto.getIsFree() != null) lesson.setIsFree(dto.getIsFree());
        // Allow moving a lesson between modules. Pass moduleId=0 (or null)
        // explicitly to detach the lesson from its module — useful when
        // an instructor wants to remove a lesson from a section without
        // deleting it.
        if (dto.getModuleId() != null) {
            if (dto.getModuleId() <= 0) {
                lesson.setModule(null);
            } else {
                Module module = moduleRepository.findById(dto.getModuleId())
                        .orElseThrow(() -> new ResourceNotFoundException("Module", "id", dto.getModuleId()));
                if (module.getCourse() == null
                        || !Objects.equals(module.getCourse().getId(), lesson.getCourse().getId())) {
                    throw new IllegalArgumentException("Module does not belong to this course");
                }
                lesson.setModule(module);
            }
        }

        return LessonDTO.from(lessonRepository.save(lesson), true);
    }

    /**
     * Reorder a flat list of lessons. Sets orderIndex 1, 2, 3, …
     * in the order received. Caller is expected to send only lessons
     * that share a single course; we still verify ownership per-lesson
     * to be safe.
     */
    @Transactional
    public void reorderLessons(List<Long> lessonIds, Long userId, boolean isAdmin) {
        if (lessonIds == null || lessonIds.isEmpty()) return;
        Map<Long, Integer> rank = new HashMap<>();
        for (int i = 0; i < lessonIds.size(); i++) rank.put(lessonIds.get(i), i + 1);

        for (Long id : lessonIds) {
            Lesson lesson = lessonRepository.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Lesson", "id", id));
            if (!isAdmin && !lesson.getCourse().getInstructor().getId().equals(userId)) {
                throw new UnauthorizedException("You can only reorder lessons in your own courses");
            }
            lesson.setOrderIndex(rank.get(id));
            lessonRepository.save(lesson);
        }
    }

    /**
     * Clear the videoUrl on a lesson. Doesn't currently delete the
     * Cloudinary asset — the upload service overwrites a deterministic
     * public_id, so the next upload replaces it in place. This keeps
     * the operation cheap and idempotent.
     */
    @Transactional
    public LessonDTO clearVideo(Long lessonId, Long userId, boolean isAdmin) {
        Lesson lesson = lessonRepository.findById(lessonId)
                .orElseThrow(() -> new ResourceNotFoundException("Lesson", "id", lessonId));
        if (!isAdmin && !lesson.getCourse().getInstructor().getId().equals(userId)) {
            throw new UnauthorizedException("You can only modify lessons in your own courses");
        }
        lesson.setVideoUrl(null);
        return LessonDTO.from(lessonRepository.save(lesson), true);
    }

    @Transactional
    public void deleteLesson(Long lessonId, Long userId, boolean isAdmin) {
        Lesson lesson = lessonRepository.findById(lessonId)
                .orElseThrow(() -> new ResourceNotFoundException("Lesson", "id", lessonId));

        if (!isAdmin && !lesson.getCourse().getInstructor().getId().equals(userId)) {
            throw new UnauthorizedException("You can only delete lessons in your own courses");
        }

        Course course = lesson.getCourse();
        lessonRepository.delete(lesson);

        // Update course lesson count
        course.setLessonsCount(lessonRepository.findByCourseIdOrderByOrderIndex(course.getId()).size());
        courseRepository.save(course);
    }
}
