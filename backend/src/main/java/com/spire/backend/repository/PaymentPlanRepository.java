package com.spire.backend.repository;

import com.spire.backend.entity.PaymentPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentPlanRepository extends JpaRepository<PaymentPlan, Long> {
    List<PaymentPlan> findByUserIdOrderByIdDesc(Long userId);
    Optional<PaymentPlan> findByPlanId(String planId);

    /** Latest plan a participant has, regardless of status — used by
     *  the participant Payments tab to render whatever is current. */
    default Optional<PaymentPlan> findLatestByUserId(Long userId) {
        return findByUserIdOrderByIdDesc(userId).stream().findFirst();
    }
}
