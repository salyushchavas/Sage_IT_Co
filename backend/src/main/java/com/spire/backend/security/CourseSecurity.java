package com.spire.backend.security;

import com.spire.backend.repository.CourseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Used in @PreAuthorize SpEL expressions to check course ownership.
 * Example: @PreAuthorize("@courseSecurity.isOwner(#courseId, authentication)")
 */
@Component("courseSecurity")
@RequiredArgsConstructor
public class CourseSecurity {

    private final CourseRepository courseRepository;

    public boolean isOwner(Long courseId, Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            return false;
        }

        Long userId;
        try {
            userId = Long.parseLong(authentication.getPrincipal().toString());
        } catch (NumberFormatException e) {
            return false;
        }

        return courseRepository.findById(courseId)
                .map(course -> course.getInstructor().getId().equals(userId))
                .orElse(false);
    }
}
