package com.spire.backend.repository;

import com.spire.backend.entity.EmailLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EmailLogRepository extends JpaRepository<EmailLog, Long> {
    List<EmailLog> findByUserIdOrderBySentAtDesc(Long userId);
    List<EmailLog> findByEmailTypeAndUserId(String emailType, Long userId);
}
