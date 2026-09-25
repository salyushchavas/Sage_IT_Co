package com.spire.backend.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * The company's working clock (Lewisville, TX: America/Chicago by
 * default, app.business-zone). "Today", "this week" and report due dates
 * follow it; the server itself runs in UTC, so its own date turns over at
 * 7 pm Central and weekly boundaries used to shift (checklist 4.2 / 4.3).
 */
@Component
public class BusinessClock {

    private final Clock clock;

    @Autowired
    public BusinessClock(@Value("${app.business-zone:America/Chicago}") String zone) {
        this(Clock.system(ZoneId.of(zone == null || zone.isBlank() ? "America/Chicago" : zone)));
    }

    /** A fixed or custom clock (tests). */
    public BusinessClock(Clock clock) {
        this.clock = clock;
    }

    public ZoneId zone() {
        return clock.getZone();
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /** Monday of the current week, in business time. */
    public LocalDate startOfWeek() {
        return startOfWeek(today());
    }

    public static LocalDate startOfWeek(LocalDate date) {
        return date.with(DayOfWeek.MONDAY);
    }

    /** A stored server time (UTC) as a business-time date. */
    public LocalDate dateOf(LocalDateTime utc) {
        return utc == null ? null : utc.atZone(ZoneOffset.UTC).withZoneSameInstant(zone()).toLocalDate();
    }
}
