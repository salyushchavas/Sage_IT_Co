package com.spire.backend.service;

import com.spire.backend.dto.WishlistItemDto;
import com.spire.backend.entity.Course;
import com.spire.backend.entity.Wishlist;
import com.spire.backend.repository.CourseRepository;
import com.spire.backend.repository.WishlistRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Wishlist CRUD. Browsing + add/remove is unconditional; the
 * "Enroll all" bulk path is gated on
 * {@link ProfileCompletionService#canEnrollInCourses}.
 *
 * Services aren't a separate entity in this codebase yet, so the
 * service-id column is reserved for forward compat but the public
 * API only accepts courseId today.
 */
@Service
@RequiredArgsConstructor
public class WishlistService {

    private final WishlistRepository wishlistRepository;
    private final CourseRepository courseRepository;

    @Transactional
    public Wishlist addCourse(Long userId, Long courseId) {
        if (courseId == null) {
            throw new IllegalArgumentException("courseId is required");
        }
        if (!courseRepository.existsById(courseId)) {
            throw new IllegalArgumentException("Course not found");
        }
        Optional<Wishlist> existing = wishlistRepository
                .findByUserIdAndCourseId(userId, courseId);
        return existing.orElseGet(() -> wishlistRepository.save(
                Wishlist.builder()
                        .userId(userId)
                        .courseId(courseId)
                        .build()));
    }

    @Transactional
    public void removeCourse(Long userId, Long courseId) {
        wishlistRepository.deleteByUserIdAndCourseId(userId, courseId);
    }

    @Transactional(readOnly = true)
    public List<WishlistItemDto> listForUser(Long userId) {
        return wishlistRepository.findByUserIdOrderByAddedAtDesc(userId).stream()
                .map(this::toDto)
                .filter(d -> d != null)
                .toList();
    }

    @Transactional(readOnly = true)
    public boolean isOnWishlist(Long userId, Long courseId) {
        return wishlistRepository.findByUserIdAndCourseId(userId, courseId).isPresent();
    }

    @Transactional(readOnly = true)
    public List<Long> courseIdsForUser(Long userId) {
        return wishlistRepository.findByUserIdOrderByAddedAtDesc(userId).stream()
                .map(Wishlist::getCourseId)
                .filter(id -> id != null)
                .toList();
    }

    private WishlistItemDto toDto(Wishlist w) {
        if (w.getCourseId() == null) return null;
        Optional<Course> course = courseRepository.findById(w.getCourseId());
        if (course.isEmpty()) return null;
        Course c = course.get();
        return WishlistItemDto.builder()
                .id(w.getId())
                .kind("COURSE")
                .targetId(c.getId())
                .title(c.getTitle())
                .thumbnailUrl(c.getThumbnailUrl())
                .price(c.getPrice())
                .addedAt(w.getAddedAt())
                .build();
    }
}
