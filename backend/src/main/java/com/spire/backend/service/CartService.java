package com.spire.backend.service;

import com.spire.backend.dto.CourseDTO;
import com.spire.backend.entity.CartItem;
import com.spire.backend.entity.Course;
import com.spire.backend.entity.User;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.repository.CartRepository;
import com.spire.backend.repository.CourseRepository;
import com.spire.backend.repository.EnrollmentRepository;
import com.spire.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CartService {

    private final CartRepository cartRepository;
    private final UserRepository userRepository;
    private final CourseRepository courseRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final CourseCheckoutService courseCheckoutService;

    @Transactional
    public void addToCart(Long userId, Long courseId) {
        if (cartRepository.existsByUserIdAndCourseId(userId, courseId)) {
            throw new IllegalArgumentException("Course is already in your cart");
        }

        if (enrollmentRepository.existsByUserIdAndCourseId(userId, courseId)) {
            throw new IllegalArgumentException("You are already enrolled in this course");
        }

        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new ResourceNotFoundException("Course", "id", courseId));

        if (Boolean.TRUE.equals(course.getIsFree())) {
            throw new IllegalArgumentException("Free courses can be enrolled directly");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        CartItem item = CartItem.builder()
                .user(user)
                .course(course)
                .build();

        cartRepository.save(item);
    }

    public List<CourseDTO> getCart(Long userId) {
        return cartRepository.findByUserId(userId).stream()
                .map(item -> CourseDTO.from(item.getCourse()))
                .collect(Collectors.toList());
    }

    @Transactional
    public void removeFromCart(Long userId, Long courseId) {
        cartRepository.deleteByUserIdAndCourseId(userId, courseId);
    }

    @Transactional
    public void clearCart(Long userId) {
        cartRepository.deleteByUserId(userId);
    }

    /**
     * Checklist 5.4: checkout lives in {@link CourseCheckoutService} — free
     * courses are enrolled, paid ones go through online payment first
     * (checkout used to enroll every course for free).
     */
    @Transactional
    public Map<String, Object> checkout(Long userId, String couponCode) {
        return courseCheckoutService.checkout(userId, couponCode);
    }
}
