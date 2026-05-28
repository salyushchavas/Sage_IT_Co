package com.spire.backend.controller;

import com.spire.backend.dto.AnnouncementDTO;
import com.spire.backend.dto.ApiResponse;
import com.spire.backend.service.AnnouncementService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Public read endpoint for the active banner; admin-only CRUD for
 * managing announcements. Method-level @PreAuthorize keeps the
 * mutating endpoints behind ROLE_ADMIN.
 */
@RestController
@RequestMapping("/api/announcements")
@RequiredArgsConstructor
public class AnnouncementController {

    private final AnnouncementService announcementService;

    @GetMapping("/active")
    public ResponseEntity<ApiResponse<List<AnnouncementDTO>>> getActive() {
        return ResponseEntity.ok(ApiResponse.success(announcementService.getActiveAnnouncements()));
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<AnnouncementDTO>>> getAll() {
        return ResponseEntity.ok(ApiResponse.success(announcementService.getAllAnnouncements()));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<AnnouncementDTO>> create(
            @RequestBody Map<String, Object> body,
            Authentication authentication) {
        Long adminId = Long.parseLong(authentication.getPrincipal().toString());
        return ResponseEntity.ok(ApiResponse.success(
                "Announcement created",
                announcementService.create(body, adminId)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<AnnouncementDTO>> update(
            @PathVariable Long id, @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.success(
                "Announcement updated", announcementService.update(id, body)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<String>> delete(@PathVariable Long id) {
        announcementService.delete(id);
        return ResponseEntity.ok(ApiResponse.success("Announcement deleted"));
    }
}
