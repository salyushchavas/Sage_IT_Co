package com.spire.backend.repository;

import com.spire.backend.entity.CheckDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CheckDocumentRepository extends JpaRepository<CheckDocument, Long> {
    List<CheckDocument> findByUserIdOrderByUploadedAtDesc(Long userId);
}
