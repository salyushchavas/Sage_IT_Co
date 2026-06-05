package com.spire.backend.repository;

import com.spire.backend.entity.ConsultantApplicationRevision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConsultantApplicationRevisionRepository
        extends JpaRepository<ConsultantApplicationRevision, Long> {

    List<ConsultantApplicationRevision> findByApplicationIdOrderByVersionNumberDesc(Long applicationId);

    Optional<ConsultantApplicationRevision> findFirstByApplicationIdOrderByVersionNumberDesc(Long applicationId);
}
