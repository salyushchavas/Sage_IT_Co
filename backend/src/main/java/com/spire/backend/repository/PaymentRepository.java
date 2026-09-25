package com.spire.backend.repository;

import com.spire.backend.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;


@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    List<Payment> findByUserId(Long userId);

    Optional<Payment> findByRazorpayOrderId(String razorpayOrderId);

    List<Payment> findAllByOrderByCreatedAtDesc();

    /** Checklist 5.4: the payment behind a Stripe Checkout session. */
    Optional<Payment> findByStripeSessionId(String stripeSessionId);

    /**
     * Checklist 5.4: marks a pending payment completed exactly once. The
     * webhook and the return page can both report the same payment; only
     * the one that gets 1 back enrolls the participant.
     */
    default int markCompletedIfPending(Long id, String intent, java.time.LocalDateTime now) {
        return completeIf(id, intent, now, Payment.Status.PENDING, Payment.Status.COMPLETED);
    }

    default int markFailedIfPending(Long id) {
        return changeStatusIf(id, Payment.Status.PENDING, Payment.Status.FAILED);
    }

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("UPDATE Payment p SET p.status = :to, p.completedAt = :now, "
            + "p.stripePaymentIntentId = :intent WHERE p.id = :id AND p.status = :from")
    int completeIf(@org.springframework.data.repository.query.Param("id") Long id,
                   @org.springframework.data.repository.query.Param("intent") String intent,
                   @org.springframework.data.repository.query.Param("now") java.time.LocalDateTime now,
                   @org.springframework.data.repository.query.Param("from") Payment.Status from,
                   @org.springframework.data.repository.query.Param("to") Payment.Status to);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("UPDATE Payment p SET p.status = :to WHERE p.id = :id AND p.status = :from")
    int changeStatusIf(@org.springframework.data.repository.query.Param("id") Long id,
                       @org.springframework.data.repository.query.Param("from") Payment.Status from,
                       @org.springframework.data.repository.query.Param("to") Payment.Status to);
}
