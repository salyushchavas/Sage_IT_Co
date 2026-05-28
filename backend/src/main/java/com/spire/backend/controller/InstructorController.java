package com.spire.backend.controller;

import com.spire.backend.dto.ApiResponse;
import com.spire.backend.dto.InstructorStudentDTO;
import com.spire.backend.service.EnrollmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/instructor")
@RequiredArgsConstructor
public class InstructorController {

    private final EnrollmentService enrollmentService;

    @GetMapping("/students")
    @PreAuthorize("hasRole('INSTRUCTOR')")
    public ResponseEntity<ApiResponse<List<InstructorStudentDTO>>> getStudents(Authentication authentication) {
        Long instructorId = Long.parseLong(authentication.getPrincipal().toString());
        List<InstructorStudentDTO> students = enrollmentService.getStudentsForInstructor(instructorId);
        return ResponseEntity.ok(ApiResponse.success(students));
    }
}
