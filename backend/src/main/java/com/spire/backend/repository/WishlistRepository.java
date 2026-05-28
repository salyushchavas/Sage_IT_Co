package com.spire.backend.repository;

import com.spire.backend.entity.Wishlist;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WishlistRepository extends JpaRepository<Wishlist, Long> {

    List<Wishlist> findByUserIdOrderByAddedAtDesc(Long userId);

    Optional<Wishlist> findByUserIdAndCourseId(Long userId, Long courseId);

    Optional<Wishlist> findByUserIdAndServiceId(Long userId, Long serviceId);

    long countByUserId(Long userId);

    void deleteByUserIdAndCourseId(Long userId, Long courseId);

    void deleteByUserIdAndServiceId(Long userId, Long serviceId);
}
