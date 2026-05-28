package com.spire.backend.controller;

import com.spire.backend.dto.ApiResponse;
import com.spire.backend.dto.CreateModuleRequest;
import com.spire.backend.dto.ModuleDTO;
import com.spire.backend.dto.UpdateModuleRequest;
import com.spire.backend.service.EnrollmentService;
import com.spire.backend.service.ModuleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class ModuleController {

    private final ModuleService moduleService;
    private final EnrollmentService enrollmentService;

    // ─── List modules for a course (public, includes lessons) ───────

    @GetMapping("/api/courses/{courseId}/modules")
    public ResponseEntity<ApiResponse<List<ModuleDTO>>> getModules(
            @PathVariable Long courseId, Authentication authentication) {
        boolean hasAccess = false;
        if (authentication != null) {
            Long userId = Long.parseLong(authentication.getPrincipal().toString());
            hasAccess = enrollmentService.isEnrolled(userId, courseId);
        }
        return ResponseEntity.ok(ApiResponse.success(
                moduleService.getModulesByCourse(courseId, hasAccess)));
    }

    // ─── Create module (course owner INSTRUCTOR or ADMIN) ───────────

    @PostMapping("/api/courses/{courseId}/modules")
    @PreAuthorize("hasRole('ADMIN') or (hasRole('INSTRUCTOR') and @courseSecurity.isOwner(#courseId, authentication))")
    public ResponseEntity<ApiResponse<ModuleDTO>> createModule(
            @PathVariable Long courseId,
            @Valid @RequestBody CreateModuleRequest dto,
            Authentication authentication) {
        Long userId = Long.parseLong(authentication.getPrincipal().toString());
        boolean isAdmin = authentication.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_ADMIN"));

        ModuleDTO created = moduleService.createModule(courseId, dto, userId, isAdmin);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Module created", created));
    }

    // ─── Update module (defense-in-depth ownership check in service) ─

    @PutMapping("/api/modules/{moduleId}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('INSTRUCTOR')")
    public ResponseEntity<ApiResponse<ModuleDTO>> updateModule(
            @PathVariable Long moduleId,
            @Valid @RequestBody UpdateModuleRequest dto,
            Authentication authentication) {
        Long userId = Long.parseLong(authentication.getPrincipal().toString());
        boolean isAdmin = authentication.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_ADMIN"));

        ModuleDTO updated = moduleService.updateModule(moduleId, dto, userId, isAdmin);
        return ResponseEntity.ok(ApiResponse.success("Module updated", updated));
    }

    // ─── Delete module ──────────────────────────────────────────────

    @DeleteMapping("/api/modules/{moduleId}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('INSTRUCTOR')")
    public ResponseEntity<ApiResponse<Void>> deleteModule(
            @PathVariable Long moduleId,
            Authentication authentication) {
        Long userId = Long.parseLong(authentication.getPrincipal().toString());
        boolean isAdmin = authentication.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_ADMIN"));

        moduleService.deleteModule(moduleId, userId, isAdmin);
        return ResponseEntity.ok(ApiResponse.success("Module deleted", null));
    }

    // ─── Reorder modules within a course ────────────────────────────

    @PutMapping("/api/courses/{courseId}/modules/reorder")
    @PreAuthorize("hasRole('ADMIN') or (hasRole('INSTRUCTOR') and @courseSecurity.isOwner(#courseId, authentication))")
    public ResponseEntity<ApiResponse<List<ModuleDTO>>> reorderModules(
            @PathVariable Long courseId,
            @RequestBody List<Long> moduleIds,
            Authentication authentication) {
        Long userId = Long.parseLong(authentication.getPrincipal().toString());
        boolean isAdmin = authentication.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_ADMIN"));

        List<ModuleDTO> reordered = moduleService.reorderModules(courseId, moduleIds, userId, isAdmin);
        return ResponseEntity.ok(ApiResponse.success("Modules reordered", reordered));
    }
}
