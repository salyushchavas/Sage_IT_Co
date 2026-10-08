"use client";

import { CheckCircle2, X } from "lucide-react";

import { CopyButton } from "@/components/web-agreement/ui/primitives";

/**
 * The one-time temporary password of a new or reset Manager / Accounts
 * account. The server hashes it on write and never returns it again, so the
 * System Admin copies it now and shares it themselves: no email is sent for
 * these two roles.
 */
export interface RevealedCredential {
  email: string;
  password: string;
  kind: "created" | "reset";
}

/**
 * Shown after "Add staff member" or "Reset password" for a Manager or
 * Accounts account, until dismissed. A copy of the console's CredentialBanner
 * (UserModals.tsx:71-152) without its "Email to user" button: emails are off
 * on the website side.
 */
export function CredentialBanner({
  credential,
  onDismiss,
}: {
  credential: RevealedCredential;
  onDismiss: () => void;
}) {
  return (
    <div
      role="status"
      aria-live="polite"
      className="rounded-xl border border-sage-copper/40 bg-sage-copper/5 px-4 py-3"
    >
      <div className="flex items-start gap-3">
        <CheckCircle2 size={18} className="text-sage-copper mt-0.5 shrink-0" />
        <div className="flex-1 min-w-0">
          <p className="text-sm font-bold text-sage-navy">
            {credential.kind === "created" ? "User created" : "Password reset"} —
            share these credentials now
          </p>
          <p className="text-[11px] text-gray-500 mb-2">
            This won&apos;t be shown again. Copy it.
          </p>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
            <CredentialField label="Email" value={credential.email} />
            <CredentialField label="Temporary password" value={credential.password} mono />
          </div>
        </div>
        <button
          type="button"
          onClick={onDismiss}
          className="text-gray-400 hover:text-gray-700 cursor-pointer"
          aria-label="Dismiss"
        >
          <X size={16} />
        </button>
      </div>
    </div>
  );
}

/**
 * The console's CopyField, but the value wraps instead of being cut off: on
 * a phone a truncated password could not be read, and it is shown only once.
 */
function CredentialField({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="rounded-lg border border-gray-200 bg-white px-3 py-2 min-w-0">
      <p className="text-[10px] uppercase tracking-wider font-semibold text-gray-400">{label}</p>
      <div className="flex items-center justify-between gap-2">
        <span className={"text-sm text-gray-900 break-all min-w-0 " + (mono ? "font-mono" : "")}>{value}</span>
        <CopyButton text={value} />
      </div>
    </div>
  );
}

export default CredentialBanner;
