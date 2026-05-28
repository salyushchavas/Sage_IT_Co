package com.spire.backend.repository;

import com.spire.backend.entity.CheckTracking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CheckTrackingRepository extends JpaRepository<CheckTracking, Long> {
    List<CheckTracking> findByPaymentPlanId(Long paymentPlanId);
    List<CheckTracking> findByStatus(String status);
}
