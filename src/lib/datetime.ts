/**
 * IST (Asia/Kolkata, UTC+5:30) date/time formatters used everywhere
 * the UI surfaces a timestamp. Centralised here so a future timezone
 * switch (or a per-user preference) is a single-file change.
 *
 * IMPORTANT — naive-ISO handling:
 * Spring's `LocalDateTime` fields serialise to JSON as
 * `"2026-05-07T18:08:00"` with no `Z` and no offset. JavaScript's
 * `new Date()` parses such strings as **browser-local time**, not
 * UTC, which means a Railway-served (UTC-clocked) timestamp would
 * render at the user's wall-clock instead of being rebased to IST.
 * `safeDate` below sniffs for that pattern and appends `Z` so the
 * value is interpreted as UTC, then `Intl.DateTimeFormat` rebases
 * to Asia/Kolkata correctly. {@link parseTimestamp} exposes the
 * same logic for components that need a raw `Date` (e.g. day-bucket
 * grouping in audit logs).
 *
 * `spring.jackson.time-zone=Asia/Kolkata` only affects timezone-
 * aware types (`Instant`, `ZonedDateTime`) — for tz-naive
 * `LocalDateTime` the frontend has to do the work. Every helper
 * here passes `timeZone: "Asia/Kolkata"` to `Intl.DateTimeFormat`
 * regardless, defensively.
 *
 * All helpers tolerate null/undefined/invalid input and return an
 * em-dash so callers don't need a defensive ternary at every site.
 */

const IST = "Asia/Kolkata";
const FALLBACK = "—";

type DateLike = string | number | Date | null | undefined;

const NAIVE_ISO_RE = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?(\.\d+)?$/;

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

export function formatIST(input: DateLike): string {
  const d = safeDate(input);
  if (!d) return FALLBACK;
  return d.toLocaleString("en-IN", {
    timeZone: IST,
    day: "numeric",
    month: "short",
    year: "numeric",
    hour: "numeric",
    minute: "2-digit",
    hour12: true,
  });
}

export function formatISTShort(input: DateLike): string {
  const d = safeDate(input);
  if (!d) return FALLBACK;
  return d.toLocaleString("en-IN", {
    timeZone: IST,
    day: "numeric",
    month: "short",
    hour: "numeric",
    minute: "2-digit",
    hour12: true,
  });
}

export function formatISTDate(input: DateLike): string {
  const d = safeDate(input);
  if (!d) return FALLBACK;
  return d.toLocaleDateString("en-IN", {
    timeZone: IST,
    day: "numeric",
    month: "long",
    year: "numeric",
  });
}

export function formatISTDateShort(input: DateLike): string {
  const d = safeDate(input);
  if (!d) return FALLBACK;
  return d.toLocaleDateString("en-IN", {
    timeZone: IST,
    day: "numeric",
    month: "short",
  });
}

export function formatISTTime(input: DateLike): string {
  const d = safeDate(input);
  if (!d) return FALLBACK;
  return d.toLocaleTimeString("en-IN", {
    timeZone: IST,
    hour: "numeric",
    minute: "2-digit",
    hour12: true,
  });
}

export function formatISTTimeWithZone(input: DateLike): string {
  const t = formatISTTime(input);
  return t === FALLBACK ? FALLBACK : `${t} IST`;
}

export function formatISTWithZone(input: DateLike): string {
  const t = formatIST(input);
  return t === FALLBACK ? FALLBACK : `${t} IST`;
}

export function timeAgoIST(input: DateLike): string {
  const d = safeDate(input);
  if (!d) return FALLBACK;
  const now = Date.now();
  const diffMs = now - d.getTime();
  if (diffMs < 0) {
    return formatISTShort(d);
  }
  const diffMin = Math.floor(diffMs / 60000);
  if (diffMin < 1) return "just now";
  if (diffMin < 60) return `${diffMin} min ago`;
  const diffHrs = Math.floor(diffMs / 3600000);
  if (diffHrs < 24) return `${diffHrs}h ago`;
  const diffDays = Math.floor(diffMs / 86400000);
  if (diffDays < 7) return `${diffDays}d ago`;
  return formatISTShort(d);
}

export function formatISTContextual(input: DateLike): string {
  const d = safeDate(input);
  if (!d) return FALLBACK;
  const today = new Date().toLocaleDateString("en-IN", { timeZone: IST });
  const dateDay = d.toLocaleDateString("en-IN", { timeZone: IST });
  return dateDay === today ? formatISTTime(d) : formatISTShort(d);
}

export function istDayKey(input: DateLike): string {
  const d = safeDate(input);
  if (!d) return "";
  return d.toLocaleDateString("en-CA", { timeZone: IST });
}
