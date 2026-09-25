package com.spire.backend.repository;

import com.spire.backend.entity.Coupon;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CouponRepository extends JpaRepository<Coupon, Long> {

    Optional<Coupon> findByCodeIgnoreCase(String code);

    List<Coupon> findAllByOrderByCreatedAtDesc();

    boolean existsByCodeIgnoreCase(String code);

    /** One more use, counted in the database (two payments at once can't lose one). */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(
            "UPDATE Coupon c SET c.usesCount = COALESCE(c.usesCount, 0) + 1 WHERE c.id = :id")
    int addUse(@org.springframework.data.repository.query.Param("id") Long id);
}
