package com.spire.backend.repository;

import com.spire.backend.entity.SalesMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SalesMessageRepository extends JpaRepository<SalesMessage, Long> {

    List<SalesMessage> findByInquiryIdOrderByCreatedAtAsc(Long inquiryId);
}
