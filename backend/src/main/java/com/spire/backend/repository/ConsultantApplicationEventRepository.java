package com.spire.backend.repository;

import com.spire.backend.entity.ConsultantApplicationEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ConsultantApplicationEventRepository
        extends JpaRepository<ConsultantApplicationEvent, Long> {

    List<ConsultantApplicationEvent> findByApplicationIdOrderByCreatedAtDesc(Long applicationId);
}
