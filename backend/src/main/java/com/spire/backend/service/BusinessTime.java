package com.spire.backend.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Checklist 5.3: times shown to people (emails, PDFs, CSV exports) are in
 * the business time zone — US Central unless app.business-zone says
 * otherwise — never India time. Stored times are the server's clock
 * (UTC in production), so they're converted, not relabelled.
 */
public final class BusinessTime {

    public static final String DEFAULT_ZONE = "America/Chicago";
    /** "September 25, 2026, 9:12 AM CDT". */
    public static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("MMMM d, yyyy, h:mm a z", Locale.US);

    private BusinessTime() {}

    public static ZoneId zone(String configured) {
        return ZoneId.of(configured == null || configured.isBlank() ? DEFAULT_ZONE : configured);
    }

    /** A time from the server's clock, in business time. */
    public static ZonedDateTime of(LocalDateTime serverTime, String configured) {
        return serverTime.atZone(ZoneId.systemDefault()).withZoneSameInstant(zone(configured));
    }

    public static String stamp(LocalDateTime serverTime, String configured) {
        return serverTime == null ? "—" : of(serverTime, configured).format(STAMP);
    }

    /** "CT" for US Central, otherwise the zone's name (for CSV headers). */
    public static String label(String configured) {
        String z = zone(configured).getId();
        return DEFAULT_ZONE.equals(z) ? "CT" : z;
    }
}
