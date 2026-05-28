package com.spire.backend.repository;

import com.spire.backend.entity.ParticipantDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ParticipantDocumentRepository extends JpaRepository<ParticipantDocument, Long> {
    List<ParticipantDocument> findByUserIdOrderByUploadedAtDesc(Long userId);
    List<ParticipantDocument> findByReviewStatus(String reviewStatus);
    List<ParticipantDocument> findByUserIdAndDocumentType(Long userId, String documentType);
}
