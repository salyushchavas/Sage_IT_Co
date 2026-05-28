package com.spire.backend.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.spire.backend.entity.Lesson;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.exception.UnauthorizedException;
import com.spire.backend.repository.LessonRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class VideoUploadService {

    private final Cloudinary cloudinary;
    private final LessonRepository lessonRepository;

    @Transactional
    public Map<String, Object> uploadVideo(Long lessonId, MultipartFile file, Long userId, boolean isAdmin) {
        Lesson lesson = lessonRepository.findById(lessonId)
                .orElseThrow(() -> new ResourceNotFoundException("Lesson", "id", lessonId));

        // Ownership check
        if (!isAdmin && !lesson.getCourse().getInstructor().getId().equals(userId)) {
            throw new UnauthorizedException("You can only upload videos to your own course lessons");
        }

        // Validate file
        if (file.isEmpty()) {
            throw new IllegalArgumentException("File is empty");
        }

        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("video/")) {
            throw new IllegalArgumentException("Only video files are allowed");
        }

        try {
            // Upload to Cloudinary
            Map<?, ?> result = cloudinary.uploader().upload(file.getBytes(), ObjectUtils.asMap(
                    "resource_type", "video",
                    "folder", "spire/lessons/" + lesson.getCourse().getId(),
                    "public_id", "lesson_" + lessonId,
                    "overwrite", true
            ));

            String videoUrl = (String) result.get("secure_url");

            // Cloudinary returns "duration" as seconds (Number). We
            // store durationMinutes on the lesson, so round up to keep
            // a non-zero duration for clips under a minute.
            Integer durationMinutes = null;
            Object rawDuration = result.get("duration");
            if (rawDuration instanceof Number) {
                double seconds = ((Number) rawDuration).doubleValue();
                durationMinutes = (int) Math.max(1, Math.ceil(seconds / 60.0));
            }

            lesson.setVideoUrl(videoUrl);
            if (durationMinutes != null) {
                lesson.setDurationMinutes(durationMinutes);
            }
            lessonRepository.save(lesson);

            log.info("Video uploaded for lesson {}: {} ({} min)", lessonId, videoUrl, durationMinutes);

            Map<String, Object> response = new java.util.HashMap<>();
            response.put("lessonId", lessonId);
            response.put("videoUrl", videoUrl);
            response.put("durationMinutes", durationMinutes);
            return response;

        } catch (IOException e) {
            log.error("Failed to upload video: {}", e.getMessage(), e);
            throw new RuntimeException("Video upload failed: " + e.getMessage());
        }
    }
}
