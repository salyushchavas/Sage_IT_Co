package com.spire.backend.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.spire.backend.entity.Course;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.exception.UnauthorizedException;
import com.spire.backend.repository.CourseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * Course thumbnail uploads. Same Cloudinary pattern as
 * {@link VideoUploadService} — overwrite a deterministic
 * public_id per course so re-uploads replace the previous image.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ThumbnailUploadService {

    private static final long MAX_BYTES = 5L * 1024 * 1024; // 5MB

    private final Cloudinary cloudinary;
    private final CourseRepository courseRepository;

    @Transactional
    public String uploadThumbnail(Long courseId, MultipartFile file, Long userId, boolean isAdmin) {
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new ResourceNotFoundException("Course", "id", courseId));

        if (!isAdmin && !course.getInstructor().getId().equals(userId)) {
            throw new UnauthorizedException("You can only upload a thumbnail for your own courses");
        }

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is empty");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new IllegalArgumentException("Thumbnail must be 5MB or smaller");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new IllegalArgumentException("Only image files are allowed");
        }

        try {
            Map<?, ?> result = cloudinary.uploader().upload(file.getBytes(), ObjectUtils.asMap(
                    "resource_type", "image",
                    "folder", "spire/courses",
                    "public_id", "course_" + courseId,
                    "overwrite", true,
                    // Thumbnails always render in a fixed-aspect card; let
                    // Cloudinary do the resize/crop server-side so we don't
                    // ship 4MB JPEGs to every visitor.
                    "transformation", new com.cloudinary.Transformation()
                            .width(1280).height(720).crop("fill").quality("auto").fetchFormat("auto")
            ));

            String url = (String) result.get("secure_url");
            course.setThumbnailUrl(url);
            courseRepository.save(course);
            log.info("Thumbnail uploaded for course {}: {}", courseId, url);
            return url;
        } catch (IOException e) {
            log.error("Failed to upload thumbnail: {}", e.getMessage(), e);
            throw new RuntimeException("Thumbnail upload failed: " + e.getMessage());
        }
    }
}
