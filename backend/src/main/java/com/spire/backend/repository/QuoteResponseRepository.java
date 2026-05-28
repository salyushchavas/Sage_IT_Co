package com.spire.backend.repository;

import com.spire.backend.entity.QuoteResponse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface QuoteResponseRepository extends JpaRepository<QuoteResponse, Long> {

    Optional<QuoteResponse> findByMessageId(Long messageId);
}
