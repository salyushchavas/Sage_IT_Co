package com.spire.backend.repository;

import com.spire.backend.entity.EmailLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EmailLogRepository extends JpaRepository<EmailLog, Long> {
    List<EmailLog> findByUserIdOrderBySentAtDesc(Long userId);
    List<EmailLog> findByEmailTypeAndUserId(String emailType, Long userId);
    List<EmailLog> findTop300ByOrderBySentAtDesc();
    List<EmailLog> findTop300ByStatusOrderBySentAtDesc(String status);
    List<EmailLog> findTop300ByUserIdOrderBySentAtDesc(Long userId);
    List<EmailLog> findTop300ByRecipientIgnoreCaseOrderBySentAtDesc(String recipient);
    boolean existsByEmailTypeAndUserIdAndStatus(String emailType, Long userId, String status);
    long countByEmailTypeAndUserIdAndStatus(String emailType, Long userId, String status);
    boolean existsByUserId(Long userId);
}
