/** Tab ids for the super-admin console. Also the `?tab=` URL values. */
export type AdminTabId =
  | "overview"
  | "agreements"
  | "people"
  | "lifecycle"
  | "maintenance";

/**
 * Jump to another tab, optionally pre-filtering it. `filter` is interpreted
 * by the destination tab — the agreements tab reads it as a status or stage
 * key, the people tab as a role.
 */
export type NavigateFn = (tab: AdminTabId, filter?: string) => void;
