package com.spire.backend.service;

import com.spire.backend.dto.TaskRequest;
import com.spire.backend.entity.*;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.exception.UnauthorizedException;
import com.spire.backend.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TaskService {

    private final TaskRepository taskRepository;
    private final TaskProgressRepository taskProgressRepository;
    private final LessonRepository lessonRepository;
    private final UserRepository userRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final ProgressRepository progressRepository;

    @Transactional
    public Task createTask(Long lessonId, TaskRequest dto, Long userId, boolean isAdmin) {
        Lesson lesson = lessonRepository.findById(lessonId)
                .orElseThrow(() -> new ResourceNotFoundException("Lesson", "id", lessonId));

        if (!isAdmin && !lesson.getCourse().getInstructor().getId().equals(userId)) {
            throw new UnauthorizedException("You can only add tasks to your own course lessons");
        }

        Task task = Task.builder()
                .title(dto.getTitle())
                .description(dto.getDescription())
                .instruction(dto.getInstruction())
                .type(dto.getType() != null ? Task.TaskType.valueOf(dto.getType().toUpperCase()) : Task.TaskType.PRACTICE)
                .lesson(lesson)
                .orderIndex(dto.getOrderIndex() != null ? dto.getOrderIndex() : 0)
                .build();

        return taskRepository.save(task);
    }

    public List<Map<String, Object>> getTasksForLesson(Long lessonId, Long userId) {
        // Check if lesson is completed by this user
        boolean lessonCompleted = progressRepository
                .existsByUserIdAndLessonIdAndCompletedTrue(userId, lessonId);

        List<Task> tasks = taskRepository.findByLessonIdOrderByOrderIndex(lessonId);

        return tasks.stream().map(t -> {
            boolean taskCompleted = taskProgressRepository.existsByUserIdAndTaskId(userId, t.getId());

            Map<String, Object> map = new HashMap<>();
            map.put("id", t.getId());
            map.put("title", t.getTitle());
            map.put("description", t.getDescription());
            map.put("instruction", t.getInstruction());
            map.put("type", t.getType().name());
            map.put("orderIndex", t.getOrderIndex());
            map.put("unlocked", lessonCompleted);
            map.put("completed", taskCompleted);
            return map;
        }).toList();
    }

    @Transactional
    public TaskProgress completeTask(Long taskId, Long userId) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task", "id", taskId));

        Long courseId = task.getLesson().getCourse().getId();
        boolean isEnrolled = enrollmentRepository.existsByUserIdAndCourseId(userId, courseId);
        boolean isOwner = task.getLesson().getCourse().getInstructor().getId().equals(userId);
        if (!isEnrolled && !isOwner) {
            throw new UnauthorizedException("You must be enrolled to complete tasks");
        }

        // Check lesson is completed first
        if (!progressRepository.existsByUserIdAndLessonIdAndCompletedTrue(userId, task.getLesson().getId())) {
            throw new IllegalArgumentException("Complete the lesson before attempting tasks");
        }

        // Prevent duplicate
        if (taskProgressRepository.existsByUserIdAndTaskId(userId, taskId)) {
            return taskProgressRepository.findByUserIdAndTaskId(userId, taskId).get();
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        TaskProgress progress = TaskProgress.builder()
                .user(user)
                .task(task)
                .completed(true)
                .build();

        return taskProgressRepository.save(progress);
    }
}
