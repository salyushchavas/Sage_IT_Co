package com.spire.backend.repository;

import com.spire.backend.entity.Acknowledgment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AcknowledgmentRepository extends JpaRepository<Acknowledgment, Long> {
    List<Acknowledgment> findByUserIdOrderByCreatedAtDesc(Long userId);
    List<Acknowledgment> findByUserIdAndAcknowledgmentType(Long userId, String acknowledgmentType);
}
