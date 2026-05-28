package com.spire.backend.repository;

import com.spire.backend.entity.InstructorRequest;
import com.spire.backend.entity.RequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InstructorRequestRepository extends JpaRepository<InstructorRequest, Long> {

    List<InstructorRequest> findByStatus(RequestStatus status);

    Optional<InstructorRequest> findByUserIdAndStatus(Long userId, RequestStatus status);

    boolean existsByUserIdAndStatus(Long userId, RequestStatus status);
}
