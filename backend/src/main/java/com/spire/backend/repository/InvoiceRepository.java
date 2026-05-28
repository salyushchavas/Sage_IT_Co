package com.spire.backend.repository;

import com.spire.backend.entity.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InvoiceRepository extends JpaRepository<Invoice, Long> {
    List<Invoice> findByUserIdOrderByIssueDateDesc(Long userId);
    Optional<Invoice> findByInvoiceNumber(String invoiceNumber);
    List<Invoice> findByPaymentPlanId(Long paymentPlanId);
    List<Invoice> findByStatusOrderByDueDateAsc(String status);
}
