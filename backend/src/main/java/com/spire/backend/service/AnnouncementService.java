package com.spire.backend.service;

import com.spire.backend.dto.AnnouncementDTO;
import com.spire.backend.entity.Announcement;
import com.spire.backend.entity.User;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.repository.AnnouncementRepository;
import com.spire.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AnnouncementService {

    private final AnnouncementRepository announcementRepository;
    private final UserRepository userRepository;

    /**
     * Active = isActive flag is true AND expiresAt is null or in the future.
     * Used by the student dashboard banner.
     */
    @Transactional(readOnly = true)
    public List<AnnouncementDTO> getActiveAnnouncements() {
        LocalDateTime now = LocalDateTime.now();
        return announcementRepository.findAllByOrderByCreatedAtDesc().stream()
                .filter(a -> Boolean.TRUE.equals(a.getIsActive()))
                .filter(a -> a.getExpiresAt() == null || a.getExpiresAt().isAfter(now))
                .map(AnnouncementDTO::from)
                .toList();
    }

    /** Admin sees everything — active, inactive, and expired. */
    @Transactional(readOnly = true)
    public List<AnnouncementDTO> getAllAnnouncements() {
        return announcementRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(AnnouncementDTO::from)
                .toList();
    }

    @Transactional
    public AnnouncementDTO create(Map<String, Object> body, Long adminId) {
        User admin = adminId != null
                ? userRepository.findById(adminId).orElse(null)
                : null;

        Announcement.Type type;
        try {
            type = body.get("type") != null
                    ? Announcement.Type.valueOf(body.get("type").toString().toUpperCase())
                    : Announcement.Type.INFO;
        } catch (IllegalArgumentException ex) {
            type = Announcement.Type.INFO;
        }

        LocalDateTime expiresAt = null;
        Object rawExpires = body.get("expiresAt");
        if (rawExpires != null && !rawExpires.toString().isBlank()) {
            try {
                expiresAt = LocalDateTime.parse(rawExpires.toString());
            } catch (Exception ignored) {
                // accept date-only "yyyy-MM-dd" by appending end-of-day
                try {
                    expiresAt = LocalDateTime.parse(rawExpires.toString() + "T23:59:59");
                } catch (Exception ignored2) {
                    // leave null
                }
            }
        }

        String title = body.get("title") != null ? body.get("title").toString() : null;
        String message = body.get("message") != null ? body.get("message").toString() : null;
        if (title == null || title.isBlank() || message == null || message.isBlank()) {
            throw new IllegalArgumentException("Title and message are required");
        }

        boolean active = body.get("isActive") == null
                || Boolean.parseBoolean(body.get("isActive").toString());

        Announcement saved = announcementRepository.save(Announcement.builder()
                .title(title.trim())
                .message(message.trim())
                .type(type)
                .isActive(active)
                .expiresAt(expiresAt)
                .createdBy(admin)
                .build());
        return AnnouncementDTO.from(saved);
    }

    @Transactional
    public AnnouncementDTO update(Long id, Map<String, Object> body) {
        Announcement a = announcementRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Announcement", "id", id));

        if (body.get("title") != null) a.setTitle(body.get("title").toString().trim());
        if (body.get("message") != null) a.setMessage(body.get("message").toString().trim());
        if (body.get("type") != null) {
            try {
                a.setType(Announcement.Type.valueOf(body.get("type").toString().toUpperCase()));
            } catch (IllegalArgumentException ignored) {
                // keep previous
            }
        }
        if (body.get("isActive") != null) {
            a.setIsActive(Boolean.parseBoolean(body.get("isActive").toString()));
        }
        if (body.containsKey("expiresAt")) {
            Object raw = body.get("expiresAt");
            if (raw == null || raw.toString().isBlank()) {
                a.setExpiresAt(null);
            } else {
                try {
                    a.setExpiresAt(LocalDateTime.parse(raw.toString()));
                } catch (Exception ignored) {
                    try {
                        a.setExpiresAt(LocalDateTime.parse(raw.toString() + "T23:59:59"));
                    } catch (Exception ignored2) {
                        // keep previous
                    }
                }
            }
        }
        return AnnouncementDTO.from(announcementRepository.save(a));
    }

    @Transactional
    public void delete(Long id) {
        if (!announcementRepository.existsById(id)) {
            throw new ResourceNotFoundException("Announcement", "id", id);
        }
        announcementRepository.deleteById(id);
    }
}
