package com.spire.backend.repository;

import com.spire.backend.entity.WeeklyReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Repository
public interface WeeklyReportRepository extends JpaRepository<WeeklyReport, Long> {
    List<WeeklyReport> findByUserIdOrderByWeekStartDesc(Long userId);
    List<WeeklyReport> findByStatus(String status);
    List<WeeklyReport> findByUserIdAndWeekStartOrderByIdAsc(Long userId, LocalDate weekStart);

    /**
     * The participant's report for that week. Should two rows ever exist
     * for one week (a submit racing the overdue job), the one furthest
     * along counts, instead of every screen failing on "not unique".
     */
    default Optional<WeeklyReport> findByUserIdAndWeekStart(Long userId, LocalDate weekStart) {
        return findByUserIdAndWeekStartOrderByIdAsc(userId, weekStart).stream()
                .min(Comparator.comparingInt(WeeklyReportRepository::progressRank));
    }

    private static int progressRank(WeeklyReport r) {
        String s = r.getStatus() == null ? "" : r.getStatus();
        return switch (s) {
            case "REVIEWED" -> 0;
            case "SUBMITTED" -> 1;
            case "DRAFT" -> 2;
            default -> 3;
        };
    }
}
