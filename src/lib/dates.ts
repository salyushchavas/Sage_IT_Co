/**
 * Build W — single source of truth for US date formatting across the
 * agreement UI. Mirrors the backend's MM-dd-yyyy formatter so every
 * date the user sees (effective date, signing dates, ERM date, dates in
 * exhibits/appendices, list timestamps) reads consistently as
 * MM-DD-YYYY.
 *
 * Accepts an ISO date (yyyy-MM-dd) or an ISO datetime
 * (yyyy-MM-ddTHH:mm[:ss…]); only the date portion is used. Returns ""
 * for null / blank / unparseable input (callers render a placeholder).
 */
export function formatUsDate(iso: string | null | undefined): string {
  if (!iso) return "";
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(String(iso).trim());
  if (!match) return String(iso);
  return `${match[2]}-${match[3]}-${match[1]}`;
}

/**
 * Build W — MM-DD-YYYY plus HH:mm (local) for places that show a
 * timestamp rather than a bare date (e.g. activity logs). Falls back to
 * the raw value when unparseable.
 */
export function formatUsDateTime(iso: string | null | undefined): string {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return formatUsDate(iso);
  const mm = String(d.getMonth() + 1).padStart(2, "0");
  const dd = String(d.getDate()).padStart(2, "0");
  const yyyy = d.getFullYear();
  const hh = String(d.getHours()).padStart(2, "0");
  const min = String(d.getMinutes()).padStart(2, "0");
  return `${mm}-${dd}-${yyyy} ${hh}:${min}`;
}
