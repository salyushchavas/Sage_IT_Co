package com.spire.backend.repository;

import com.spire.backend.entity.UserRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Records are append-only. JpaRepository's mutating helpers are
 * overridden to throw UnsupportedOperationException so a future
 * caller can't accidentally erase audit history.
 */
@Repository
public interface UserRecordRepository extends JpaRepository<UserRecord, Long> {

    // PostgreSQL can't infer the type of a `:param IS NULL` JPQL
     // parameter when it's actually null, so callers always pass
    // sentinel values: empty string for category, far-past / far-future
    // timestamps for the date range. Hibernate then binds proper types
    // and the (:category = '' OR ...) form acts as the "no filter" path.
    @Query("SELECT r FROM UserRecord r WHERE r.userId = :userId " +
           "AND (:category = '' OR r.category = :category) " +
           "AND r.createdAt >= :from " +
           "AND r.createdAt <= :to " +
           "ORDER BY r.createdAt DESC")
    Page<UserRecord> findForUser(@Param("userId") Long userId,
                                  @Param("category") String category,
                                  @Param("from") LocalDateTime from,
                                  @Param("to") LocalDateTime to,
                                  Pageable pageable);

    List<UserRecord> findByUserIdOrderByCreatedAtDesc(Long userId);

    @Query("SELECT r.category, COUNT(r) FROM UserRecord r " +
           "WHERE r.userId = :userId GROUP BY r.category")
    List<Object[]> countByCategoryForUser(@Param("userId") Long userId);

    long countByUserId(Long userId);

    // Cross-user search for the admin investigation tool. Same
    // sentinel-value pattern as findForUser.
    @Query("SELECT r FROM UserRecord r WHERE " +
           "(LOWER(r.title) LIKE LOWER(CONCAT('%', :q, '%')) " +
           " OR LOWER(r.description) LIKE LOWER(CONCAT('%', :q, '%')) " +
           " OR LOWER(r.recordType) LIKE LOWER(CONCAT('%', :q, '%'))) " +
           "AND r.createdAt >= :from " +
           "AND r.createdAt <= :to " +
           "ORDER BY r.createdAt DESC")
    Page<UserRecord> searchAll(@Param("q") String query,
                                @Param("from") LocalDateTime from,
                                @Param("to") LocalDateTime to,
                                Pageable pageable);

    // ─── Mutation guards ─────────────────────────────────────────
    // Records are immutable evidence. Overriding the inherited
    // JpaRepository mutators makes accidental deletes impossible.

    @Override
    default void delete(UserRecord entity) {
        throw new UnsupportedOperationException("user_records is append-only");
    }

    @Override
    default void deleteAll() {
        throw new UnsupportedOperationException("user_records is append-only");
    }

    @Override
    default void deleteAll(Iterable<? extends UserRecord> entities) {
        throw new UnsupportedOperationException("user_records is append-only");
    }

    @Override
    default void deleteAllInBatch() {
        throw new UnsupportedOperationException("user_records is append-only");
    }

    @Override
    default void deleteAllInBatch(Iterable<UserRecord> entities) {
        throw new UnsupportedOperationException("user_records is append-only");
    }

    @Override
    default void deleteAllById(Iterable<? extends Long> ids) {
        throw new UnsupportedOperationException("user_records is append-only");
    }

    @Override
    default void deleteAllByIdInBatch(Iterable<Long> ids) {
        throw new UnsupportedOperationException("user_records is append-only");
    }

    @Override
    default void deleteById(Long id) {
        throw new UnsupportedOperationException("user_records is append-only");
    }
}
