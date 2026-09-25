/**
 * Date/time formatters used everywhere the UI surfaces a timestamp.
 * Checklist 5.3: Sage is a US company, so times are shown in US Central
 * time (the business time zone, the same one the server uses for
 * deadlines) with US formatting. NEXT_PUBLIC_BUSINESS_TIMEZONE can change
 * the zone.
 *
 * IMPORTANT — naive-ISO handling:
 * Spring's `LocalDateTime` fields serialise to JSON as
 * `"2026-05-07T18:08:00"` with no `Z` and no offset. JavaScript's
 * `new Date()` parses such strings as **browser-local time**, not
 * UTC, which means a Railway-served (UTC-clocked) timestamp would
 * render at the user's wall-clock instead of being rebased.
 * `parseTimestamp` sniffs for that pattern and appends `Z` so the
 * value is interpreted as UTC, then `Intl.DateTimeFormat` rebases it
 * to the business zone.
 *
 * A plain calendar date ("2026-10-01": a due date, a start date) is not
 * a moment in time: {@link formatDay} shows it as that same day for
 * everyone.
 *
 * All helpers tolerate null/undefined/invalid input and return an
 * em-dash so callers don't need a defensive ternary at every site.
 */

export const BUSINESS_TIME_ZONE = process.env.NEXT_PUBLIC_BUSINESS_TIMEZONE || "America/Chicago";
const ZONE_LABEL = BUSINESS_TIME_ZONE === "America/Chicago" ? "CT" : BUSINESS_TIME_ZONE;
const LOCALE = "en-US";
const FALLBACK = "—";

type DateLike = string | number | Date | null | undefined;

const NAIVE_ISO_RE = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?(\.\d+)?$/;
const DATE_ONLY_RE = /^(\d{4})-(\d{2})-(\d{2})$/;

export function parseTimestamp(input: DateLike): Date | null {
  if (input == null) return null;
  if (input instanceof Date) {
    return isNaN(input.getTime()) ? null : input;
  }
  let value = input;
  if (typeof value === "string" && NAIVE_ISO_RE.test(value)) {
    value = value + "Z";
  }
  const d = new Date(value);
  return isNaN(d.getTime()) ? null : d;
}

const safeDate = parseTimestamp;

/** "Sep 25, 2026, 9:12 AM" (business time). */
export function formatDateTime(input: DateLike): string {
  const d = safeDate(input);
  if (!d) return FALLBACK;
  return d.toLocaleString(LOCALE, {
    timeZone: BUSINESS_TIME_ZONE,
    day: "numeric",
    month: "short",
    year: "numeric",
    hour: "numeric",
    minute: "2-digit",
    hour12: true,
  });
}

/** "Sep 25, 9:12 AM" (business time). */
export function formatDateTimeShort(input: DateLike): string {
  const d = safeDate(input);
  if (!d) return FALLBACK;
  return d.toLocaleString(LOCALE, {
    timeZone: BUSINESS_TIME_ZONE,
    day: "numeric",
    month: "short",
    hour: "numeric",
    minute: "2-digit",
    hour12: true,
  });
}

/** "September 25, 2026" (business time). */
export function formatDateLong(input: DateLike): string {
  const d = safeDate(input);
  if (!d) return FALLBACK;
  return d.toLocaleDateString(LOCALE, {
    timeZone: BUSINESS_TIME_ZONE,
    day: "numeric",
    month: "long",
    year: "numeric",
  });
}

/** "Sep 25, 2026" for a moment in time (business time). */
export function formatDateMedium(input: DateLike): string {
  const d = safeDate(input);
  if (!d) return FALLBACK;
  return d.toLocaleDateString(LOCALE, {
    timeZone: BUSINESS_TIME_ZONE,
    day: "numeric",
    month: "short",
    year: "numeric",
  });
}

/** "Sep 25" (business time). */
export function formatDateShort(input: DateLike): string {
  const d = safeDate(input);
  if (!d) return FALLBACK;
  return d.toLocaleDateString(LOCALE, {
    timeZone: BUSINESS_TIME_ZONE,
    day: "numeric",
    month: "short",
  });
}

/** "9:12 AM" (business time). */
export function formatTime(input: DateLike): string {
  const d = safeDate(input);
  if (!d) return FALLBACK;
  return d.toLocaleTimeString(LOCALE, {
    timeZone: BUSINESS_TIME_ZONE,
    hour: "numeric",
    minute: "2-digit",
    hour12: true,
  });
}

export function formatTimeWithZone(input: DateLike): string {
  const t = formatTime(input);
  return t === FALLBACK ? FALLBACK : `${t} ${ZONE_LABEL}`;
}

export function formatDateTimeWithZone(input: DateLike): string {
  const t = formatDateTime(input);
  return t === FALLBACK ? FALLBACK : `${t} ${ZONE_LABEL}`;
}

export function timeAgo(input: DateLike): string {
  const d = safeDate(input);
  if (!d) return FALLBACK;
  const now = Date.now();
  const diffMs = now - d.getTime();
  if (diffMs < 0) {
    return formatDateTimeShort(d);
  }
  const diffMin = Math.floor(diffMs / 60000);
  if (diffMin < 1) return "just now";
  if (diffMin < 60) return `${diffMin} min ago`;
  const diffHrs = Math.floor(diffMs / 3600000);
  if (diffHrs < 24) return `${diffHrs}h ago`;
  const diffDays = Math.floor(diffMs / 86400000);
  if (diffDays < 7) return `${diffDays}d ago`;
  return formatDateTimeShort(d);
}

/** The time for today's messages, otherwise the date and time. */
export function formatContextual(input: DateLike): string {
  const d = safeDate(input);
  if (!d) return FALLBACK;
  const today = new Date().toLocaleDateString(LOCALE, { timeZone: BUSINESS_TIME_ZONE });
  const dateDay = d.toLocaleDateString(LOCALE, { timeZone: BUSINESS_TIME_ZONE });
  return dateDay === today ? formatTime(d) : formatDateTimeShort(d);
}

/** "2026-09-25": the business day a moment falls on (for grouping). */
export function dayKey(input: DateLike): string {
  const d = safeDate(input);
  if (!d) return "";
  return d.toLocaleDateString("en-CA", { timeZone: BUSINESS_TIME_ZONE });
}

/**
 * A calendar date ("2026-10-01") as "Oct 1, 2026": the same day for
 * everyone, whatever their time zone. Full timestamps are shown as their
 * business-time day.
 */
export function formatDay(input: string | null | undefined): string {
  if (!input) return FALLBACK;
  const m = DATE_ONLY_RE.exec(input);
  if (!m) return formatDateMedium(input);
  const d = new Date(Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3]), 12));
  return d.toLocaleDateString(LOCALE, { timeZone: "UTC", day: "numeric", month: "short", year: "numeric" });
}

/** Today's business date as "YYYY-MM-DD" (for date inputs). */
export function businessToday(): string {
  return new Date().toLocaleDateString("en-CA", { timeZone: BUSINESS_TIME_ZONE });
}
