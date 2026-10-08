"use client";

import { useEffect, useState } from "react";
import { IdCard, Loader2 } from "lucide-react";

import { fetchStaffDetails, updateStaffDetails, type StaffDetails } from "@/lib/api";
import {
  InlineAlert,
  ModalField,
  Spinner,
  modalInput,
} from "@/components/web-agreement/ui/primitives";

/** The console's check for an edited login email (UserModals.tsx:434-435). */
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/;

/**
 * The user page's "Details" card: full name, login email and the title
 * printed in the agreement's signature block. A copy of the console's Edit
 * details (UserModals.tsx:409-538), as a card instead of a dialog. Shown to a
 * System Admin on ERM, Manager, Accounts, Operations admin and System admin
 * accounts; the server checks both again.
 *
 * Name and title are required; a blank or unchanged email leaves the login
 * email as it is. A System Admin's login email can't be changed (the console
 * locks its super-admin's). Sessions already issued stay valid; a new email
 * is used at their next sign-in.
 */
export function StaffDetailsCard({
  userId,
  targetRole,
  onSaved,
}: {
  userId: number;
  /** The account's role: a System Admin's email is locked. */
  targetRole: string;
  /** After a save, so the page header shows the new name and email. */
  onSaved?: (details: StaffDetails) => void;
}) {
  const [saved, setSaved] = useState<StaffDetails | null>(null);
  const [fullName, setFullName] = useState("");
  const [title, setTitle] = useState("");
  const [email, setEmail] = useState("");
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");
  const [done, setDone] = useState("");

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setLoadError("");
    fetchStaffDetails(userId)
      .then((d) => {
        if (cancelled) return;
        setSaved(d);
        setFullName(d.fullName ?? "");
        setTitle(d.title ?? "");
        setEmail(d.email ?? "");
      })
      .catch((e) => {
        if (!cancelled) setLoadError(e instanceof Error ? e.message : "Couldn't load the details.");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [userId]);

  const emailLocked = targetRole.toUpperCase() === "SYSTEM_ADMIN";

  const trimmedName = fullName.trim();
  const trimmedTitle = title.trim();
  const trimmedEmail = email.trim().toLowerCase();
  const emailChanged =
    !emailLocked && trimmedEmail !== (saved?.email ?? "").trim().toLowerCase();
  const emailValid = !emailChanged || EMAIL_PATTERN.test(trimmedEmail);
  const changed =
    trimmedName !== (saved?.fullName ?? "").trim() ||
    trimmedTitle !== (saved?.title ?? "").trim() ||
    emailChanged;
  const canSubmit =
    !!saved &&
    trimmedName.length > 0 &&
    trimmedTitle.length > 0 &&
    emailValid &&
    changed &&
    !submitting;

  const handleSubmit = async () => {
    setError("");
    setDone("");
    if (trimmedName.length === 0 || trimmedTitle.length === 0) {
      setError("Name and title are both required.");
      return;
    }
    if (!emailValid) {
      setError("Enter a valid email address.");
      return;
    }
    setSubmitting(true);
    try {
      const d = await updateStaffDetails(userId, {
        fullName: trimmedName,
        title: trimmedTitle,
        // Leaving the key out keeps the login email as it is.
        ...(emailChanged ? { email: trimmedEmail } : {}),
      });
      setSaved(d);
      setFullName(d.fullName ?? "");
      setTitle(d.title ?? "");
      setEmail(d.email ?? "");
      setDone("Details updated");
      onSaved?.(d);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't update details.");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="bg-white border border-zinc-200 rounded-2xl p-6 mb-5">
      <h2 className="text-sm font-bold text-zinc-900 flex items-center gap-2 mb-1">
        <IdCard size={16} className="text-sage-navy" />
        Details
      </h2>
      <p className="text-xs text-zinc-500 mb-4">
        Name + title stamp into the agreement signature block. To change the
        role, use the Role card.
      </p>

      {loading ? (
        <Spinner />
      ) : loadError ? (
        <InlineAlert tone="error">{loadError}</InlineAlert>
      ) : (
        <>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            <ModalField label="Full name" required>
              <input
                type="text"
                value={fullName}
                onChange={(e) => setFullName(e.target.value)}
                disabled={submitting}
                placeholder="e.g. Riya Sharma"
                className={modalInput}
              />
            </ModalField>
            <ModalField
              label="Login email"
              required
              hint={
                emailLocked
                  ? "A System Admin's login email can't be changed."
                  : "Changing the email takes effect at their next sign-in — they'll log in with the new address."
              }
            >
              <input
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                disabled={submitting || emailLocked}
                placeholder="firstname.lastname@sageitco.com"
                className={modalInput}
              />
            </ModalField>
            <div className="sm:col-span-2">
              <ModalField label="Title" required>
                <input
                  type="text"
                  value={title}
                  onChange={(e) => setTitle(e.target.value)}
                  disabled={submitting}
                  placeholder="e.g. Program Manager"
                  className={modalInput}
                />
              </ModalField>
            </div>
          </div>

          {(error || done) && (
            <div className="mt-3">
              {error ? (
                <InlineAlert tone="error" onDismiss={() => setError("")}>{error}</InlineAlert>
              ) : (
                <InlineAlert tone="success" onDismiss={() => setDone("")}>{done}</InlineAlert>
              )}
            </div>
          )}

          <div className="mt-4 flex justify-end">
            <button
              type="button"
              onClick={() => void handleSubmit()}
              disabled={!canSubmit}
              className="inline-flex items-center gap-1.5 px-4 py-2 rounded-lg text-sm font-semibold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-60 cursor-pointer disabled:cursor-not-allowed"
            >
              {submitting && <Loader2 size={14} className="animate-spin" />} Save details
            </button>
          </div>
        </>
      )}
    </div>
  );
}

export default StaffDetailsCard;
