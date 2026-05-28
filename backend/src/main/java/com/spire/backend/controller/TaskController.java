package com.spire.backend.controller;

import com.spire.backend.dto.ApiResponse;
import com.spire.backend.dto.TaskRequest;
import com.spire.backend.entity.Task;
import com.spire.backend.entity.TaskProgress;
import com.spire.backend.service.TaskService;
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

@RestController
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;

    @PostMapping("/api/lessons/{lessonId}/tasks")
    @PreAuthorize("hasRole('ADMIN') or hasRole('INSTRUCTOR')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createTask(
            @PathVariable Long lessonId,
            @Valid @RequestBody TaskRequest dto,
            Authentication auth) {
        Long userId = Long.parseLong(auth.getPrincipal().toString());
        boolean isAdmin = auth.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_ADMIN"));
        Task task = taskService.createTask(lessonId, dto, userId, isAdmin);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Task created", Map.of(
                "id", task.getId(), "title", task.getTitle(), "type", task.getType().name()
        )));
    }

    @GetMapping("/api/lessons/{lessonId}/tasks")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getTasks(
            @PathVariable Long lessonId,
            Authentication auth) {
        Long userId = Long.parseLong(auth.getPrincipal().toString());
        return ResponseEntity.ok(ApiResponse.success(taskService.getTasksForLesson(lessonId, userId)));
    }

    @PostMapping("/api/tasks/{taskId}/complete")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Map<String, Object>>> completeTask(
            @PathVariable Long taskId,
            Authentication auth) {
        Long userId = Long.parseLong(auth.getPrincipal().toString());
        TaskProgress tp = taskService.completeTask(taskId, userId);
        return ResponseEntity.ok(ApiResponse.success("Task completed", Map.of(
                "taskId", taskId, "completed", tp.getCompleted()
        )));
    }
}
