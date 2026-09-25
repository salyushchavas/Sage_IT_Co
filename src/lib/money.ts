/**
 * Checklist 5.3: every amount is in US dollars, formatted the US way:
 * "$1,234.50".
 */
export const CURRENCY = "USD";

export function formatMoney(v: string | number | null | undefined, empty = "—"): string {
  if (v == null || v === "") return empty;
  const n = typeof v === "number" ? v : Number(v);
  if (Number.isNaN(n)) return String(v);
  return n.toLocaleString("en-US", { style: "currency", currency: CURRENCY });
}
