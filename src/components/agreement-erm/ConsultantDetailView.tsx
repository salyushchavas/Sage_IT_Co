"use client";

import { useEffect, useMemo, useState, type ReactNode } from "react";
import {
  AlertCircle,
  Ban,
  CheckCircle2,
  Clock,
  Copy,
  Download,
  Eye,
  EyeOff,
  ExternalLink,
  Globe,
  Link2,
  Loader2,
  Mail,
  MessageSquare,
  PenLine,
  Pencil,
  ShieldAlert,
  X,
} from "lucide-react";

import SignaturePad from "@/components/common/SignaturePad";
import {
  cancelConsultantApplication,
  ermApproveAndSign,
  ermRequestRevision,
  ermSendPdfToEmail,
  fetchAgreementPdfBlob,
  fetchAgreementChequeBlob,
  fetchErmPreviewPdfBlob,
  fetchMe,
  resendConsultantInvite,
  updateConsultantContact,
  type ConsultantApplication,
  type ConsultantApplicationDetailEnvelope,
} from "@/lib/api";
import AgreementStatusPill from "./AgreementStatusPill";
import AgreementEventTimeline from "./AgreementEventTimeline";

interface Props {
  detail: ConsultantApplicationDetailEnvelope;
  onRefresh: () => Promise<void>;
}

type FieldKey = keyof ConsultantApplication;

interface FieldDef {
  key: FieldKey;
  label: string;
  /** Sensitive PII (SSN, DL) -- masked + reveal toggle. */
  pii?: boolean;
  /** Render this field full-width on md+. */
  wide?: boolean;
}

interface SectionDef {
  id: "personal" | "service" | "employment" | "ach" | "bg" | "portal" | "security";
  title: string;
  optional?: boolean;
  warning?: string;
  fields: FieldDef[];
}

const SECTIONS: readonly SectionDef[] = [
  {
    id: "personal",
    title: "Personal information",
    fields: [
      { key: "effectiveDate", label: "Effective date" },
      { key: "primaryPhone", label: "Primary phone" },
      { key: "workAuthorizationCategory", label: "Work authorization" },
      { key: "residenceAddress", label: "Residence address", wide: true },
    ],
  },
  {
    id: "service",
    title: "Service track",
    fields: [
      { key: "technologyTrack", label: "Technology / skill track" },
      { key: "customScopeNotes", label: "Custom scope / notes", wide: true },
    ],
  },
  {
    id: "employment",
    title: "Phase 2 employment",
    fields: [
      { key: "employerPayrollEntity", label: "Employer / payroll entity" },
      { key: "implementationPartner", label: "Implementation partner" },
      { key: "endClient", label: "End client" },
      { key: "roleTitle", label: "Role / position" },
      { key: "verifiedStartDate", label: "Verified start date" },
      { key: "payrollCycle", label: "Payroll cycle" },
    ],
  },
  {
    id: "ach",
    title: "ACH authorization",
    optional: true,
    fields: [
      { key: "achAccountType", label: "Account type" },
      { key: "achBankName", label: "Bank" },
      { key: "achAccountHolderName", label: "Account holder" },
      { key: "achRoutingNumber", label: "Routing number" },
      { key: "achAccountNumber", label: "Account number" },
      { key: "achNoticeEmail", label: "Notice email" },
      { key: "achDebitDates", label: "Debit dates" },
      { key: "achDebitAmounts", label: "Debit amounts" },
    ],
  },
  {
    id: "bg",
    title: "Background check",
    optional: true,
    warning: "Sensitive PII. Reveal toggles below; values are hidden by default.",
    fields: [
      { key: "bgFullLegalName", label: "Full legal name" },
      { key: "bgOtherNamesUsed", label: "Other names" },
      { key: "bgCurrentAddress", label: "Current address", wide: true },
      { key: "bgDateOfBirth", label: "Date of birth" },
      { key: "bgFullSsn", label: "Full SSN", pii: true },
      { key: "idType", label: "ID type" },
      { key: "bgDriverLicense", label: "Driver's License / State ID", pii: true },
    ],
  },
  {
    id: "portal",
    title: "Portal access",
    optional: true,
    fields: [
      { key: "portalPlatform", label: "Platform" },
      { key: "portalUsername", label: "Username" },
      { key: "portalAuthorizedActions", label: "Authorized actions", wide: true },
      { key: "portalEffectiveDate", label: "Effective date" },
      { key: "portalRevocationContact", label: "Revocation contact" },
    ],
  },
  {
    id: "security",
    title: "Security check",
    optional: true,
    fields: [
      { key: "securityCheckCount", label: "Check count" },
      { key: "securityCheckNumbers", label: "Check numbers" },
      { key: "securityCheckBank", label: "Bank" },
      { key: "securityCheckHolderName", label: "Holder name" },
      { key: "securityCheckAmount", label: "Amount" },
      { key: "securityCheckDates", label: "Dates" },
    ],
  },
];

type ModalKind = null | "revision" | "approve" | "send" | "editContact";

export default function ConsultantDetailView({ detail, onRefresh }: Props) {
  const { application: app, events } = detail;
  const [modal, setModal] = useState<ModalKind>(null);
  const [busy, setBusy] = useState<"resend" | "cancel" | null>(null);
  const [feedback, setFeedback] = useState("");
  const [error, setError] = useState("");
  // Phase B nicety — pre-fill the Approve & Sign name/title from the
  // signed-in user's profile so they don't retype each time.
  const [me, setMe] = useState<{ fullName: string | null; title: string | null }>({
    fullName: null,
    title: null,
  });

  useEffect(() => {
    let cancelled = false;
    fetchMe()
      .then((identity) => {
        if (!cancelled) setMe({ fullName: identity.fullName, title: identity.title });
      })
      .catch(() => {
        /* non-fatal: fields just start blank */
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const status = app.status;
  const isLocked = ["SIGNED", "COMPLETED", "CANCELLED", "EXPIRED"].includes(status);

  // Clear any banner feedback after refresh fires so it doesn't
  // linger across actions.
  useEffect(() => {
    const t = setTimeout(() => setFeedback(""), 8_000);
    return () => clearTimeout(t);
  }, [feedback]);

  const handleResend = async () => {
    setBusy("resend");
    setError("");
    try {
      await resendConsultantInvite(app.applicationId);
      setFeedback(`Invitation re-sent to ${app.consultantEmail}.`);
      await onRefresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't resend invite");
    } finally {
      setBusy(null);
    }
  };

  // Phase C — manual escape hatch when email delivery fails: copy the
  // consultant's fill link straight to the clipboard. Same-origin app, so
  // window.location.origin is the public site host.
  const handleCopyLink = async () => {
    const origin = typeof window !== "undefined" ? window.location.origin : "";
    const url = `${origin}/consultant/${app.applicationId}/fill`;
    try {
      await navigator.clipboard.writeText(url);
      setFeedback("Consultant link copied to clipboard.");
    } catch {
      setError("Couldn't copy automatically — the link is: " + url);
    }
  };

  const handleCancel = async () => {
    if (!confirm("Cancel this application? The consultant won't be able to sign.")) {
      return;
    }
    setBusy("cancel");
    setError("");
    try {
      await cancelConsultantApplication(app.applicationId);
      setFeedback("Application cancelled.");
      await onRefresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't cancel");
    } finally {
      setBusy(null);
    }
  };

  return (
    <div className="space-y-5">
      <HeaderRow app={app} />

      {feedback && (
        <p className="inline-flex items-center gap-1.5 text-sm text-emerald-700">
          <CheckCircle2 size={14} /> {feedback}
        </p>
      )}
      {error && (
        <p className="inline-flex items-center gap-1.5 text-sm text-red-700">
          <AlertCircle size={14} /> {error}
        </p>
      )}

      <StateActionBar
        status={status}
        app={app}
        onRequestRevision={() => setModal("revision")}
        onApproveAndSign={() => setModal("approve")}
        onSendEmail={() => setModal("send")}
        onResendInvite={handleResend}
        onCancel={handleCancel}
        resendBusy={busy === "resend"}
        cancelBusy={busy === "cancel"}
        isLocked={isLocked}
      />

      <ContactActionsBar
        status={status}
        onEditContact={() => setModal("editContact")}
        onResend={handleResend}
        onCopyLink={handleCopyLink}
        resendBusy={busy === "resend"}
      />

      <ErmFilledCard app={app} />

      {status === "VERIFIED" && <ErmPdfPreview appId={app.applicationId} />}

      <ConsultantSections app={app} />

      <SignaturesPreview app={app} />

      <AccessRecord app={app} />

      <Section title={`Activity (${events.length})`}>
        <AgreementEventTimeline events={events} />
      </Section>

      {modal === "revision" && (
        <RequestRevisionModal
          appId={app.applicationId}
          onClose={() => setModal(null)}
          onDone={async () => {
            setModal(null);
            setFeedback("Revision requested. Consultant notified.");
            await onRefresh();
          }}
        />
      )}
      {modal === "approve" && (
        <ApproveAndSignModal
          appId={app.applicationId}
          defaultName={me.fullName ?? ""}
          defaultTitle={me.title ?? ""}
          onClose={() => setModal(null)}
          onDone={async () => {
            setModal(null);
            setFeedback("Agreement signed and emailed. Now downloadable.");
            await onRefresh();
          }}
        />
      )}
      {modal === "send" && (
        <SendEmailModal
          appId={app.applicationId}
          onClose={() => setModal(null)}
          onDone={async (to) => {
            setModal(null);
            setFeedback("Sent to " + to);
            await onRefresh();
          }}
        />
      )}
      {modal === "editContact" && (
        <EditContactModal
          appId={app.applicationId}
          status={status}
          defaultEmail={app.consultantEmail}
          defaultName={app.consultantName ?? ""}
          onClose={() => setModal(null)}
          onDone={async (msg) => {
            setModal(null);
            setFeedback(msg);
            await onRefresh();
          }}
        />
      )}
    </div>
  );
}

// ── Contact actions (Phase C) ──────────────────────────────────
//
// Edit consultant contact / resend the fill invite / copy the fill link.
// The SUBMITTED resend also lives in StateActionBar; this bar adds resend
// for REVISION_REQUESTED and the always-available edit + copy escape
// hatches. Renders nothing when none apply (CANCELLED/EXPIRED).

function ContactActionsBar({
  status,
  onEditContact,
  onResend,
  onCopyLink,
  resendBusy,
}: {
  status: ConsultantApplication["status"];
  onEditContact: () => void;
  onResend: () => void;
  onCopyLink: () => void;
  resendBusy: boolean;
}) {
  const canEdit = ["SUBMITTED", "VERIFIED", "REVISION_REQUESTED", "COMPLETED"].includes(status);
  // SUBMITTED resend is already in StateActionBar; surface it here for
  // REVISION_REQUESTED so the action exists in both states.
  const canResend = status === "REVISION_REQUESTED";
  const isLocked = ["SIGNED", "COMPLETED", "CANCELLED", "EXPIRED"].includes(status);
  const canCopy = !isLocked;

  if (!canEdit && !canResend && !canCopy) return null;

  return (
    <div className="flex items-center gap-2 flex-wrap">
      {canEdit && (
        <button
          type="button"
          onClick={onEditContact}
          className="inline-flex items-center gap-1.5 px-2.5 py-1.5 rounded-md text-[11px] font-semibold border border-gray-200 bg-white hover:bg-gray-50 text-gray-700 cursor-pointer"
        >
          <Pencil size={12} /> Edit consultant contact
        </button>
      )}
      {canResend && (
        <button
          type="button"
          onClick={onResend}
          disabled={resendBusy}
          className="inline-flex items-center gap-1.5 px-2.5 py-1.5 rounded-md text-[11px] font-semibold border border-gray-200 bg-white hover:bg-gray-50 text-gray-700 cursor-pointer disabled:opacity-50"
        >
          {resendBusy ? <Loader2 size={12} className="animate-spin" /> : <Mail size={12} />}
          Resend invitation
        </button>
      )}
      {canCopy && (
        <button
          type="button"
          onClick={onCopyLink}
          className="inline-flex items-center gap-1.5 px-2.5 py-1.5 rounded-md text-[11px] font-semibold border border-gray-200 bg-white hover:bg-gray-50 text-gray-700 cursor-pointer"
        >
          <Link2 size={12} /> Copy consultant link
        </button>
      )}
    </div>
  );
}

// ── Edit consultant contact modal (Phase C) ────────────────────

function EditContactModal({
  appId,
  status,
  defaultEmail,
  defaultName,
  onClose,
  onDone,
}: {
  appId: string;
  status: ConsultantApplication["status"];
  defaultEmail: string;
  defaultName: string;
  onClose: () => void;
  onDone: (message: string) => Promise<void>;
}) {
  const [email, setEmail] = useState(defaultEmail);
  const [name, setName] = useState(defaultName);
  const [busy, setBusy] = useState<"save" | "saveResend" | null>(null);
  const [error, setError] = useState("");

  const emailValid = /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim());
  const canResend = status === "SUBMITTED" || status === "REVISION_REQUESTED";

  const submit = async (alsoResend: boolean) => {
    if (!emailValid) {
      setError("Enter a valid email address.");
      return;
    }
    setBusy(alsoResend ? "saveResend" : "save");
    setError("");
    try {
      await updateConsultantContact(appId, {
        consultantEmail: email.trim(),
        consultantName: name.trim(),
      });
    } catch (e) {
      // The contact update itself failed — keep the modal open to retry.
      setError(e instanceof Error ? e.message : "Couldn't update contact.");
      setBusy(null);
      return;
    }
    // Contact is saved. A subsequent resend failure must NOT hide that
    // success or trap the user in an error modal — close with a note.
    if (alsoResend) {
      try {
        await resendConsultantInvite(appId);
        await onDone(`Contact updated and invitation re-sent to ${email.trim()}.`);
      } catch (e) {
        const msg = e instanceof Error ? e.message : "resend failed";
        await onDone(`Contact updated, but the invitation couldn't be re-sent (${msg}).`);
      }
    } else {
      await onDone("Consultant contact updated.");
    }
  };

  return (
    <ModalShell
      title="Edit consultant contact"
      onClose={onClose}
      closeable={busy === null}
      footer={
        <>
          <button
            type="button"
            onClick={onClose}
            disabled={busy !== null}
            className="px-3 py-1.5 rounded-md text-xs font-semibold text-gray-600 hover:text-gray-900 cursor-pointer disabled:opacity-50"
          >
            Cancel
          </button>
          {canResend && (
            <button
              type="button"
              onClick={() => submit(true)}
              disabled={busy !== null || !emailValid}
              className="inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-xs font-bold border border-sage-navy/30 text-sage-navy hover:bg-sage-navy/5 cursor-pointer disabled:opacity-60"
            >
              {busy === "saveResend" ? <Loader2 size={12} className="animate-spin" /> : <Mail size={12} />}
              Save &amp; resend invitation
            </button>
          )}
          <button
            type="button"
            onClick={() => submit(false)}
            disabled={busy !== null || !emailValid}
            className="inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep cursor-pointer disabled:opacity-60"
          >
            {busy === "save" ? <Loader2 size={12} className="animate-spin" /> : <CheckCircle2 size={12} />}
            Save
          </button>
        </>
      }
    >
      <p className="text-xs text-gray-500 mb-3">
        Fix a wrong email or name so the consultant can reach their form.
        This updates where future emails and the agreement PDF&apos;s contact
        field point; it doesn&apos;t alter an already-generated signed PDF.
      </p>
      <div className="space-y-3">
        <div>
          <label className="block text-[11px] font-semibold text-gray-600 mb-1">
            Consultant email <span className="text-red-500">*</span>
          </label>
          <input
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            disabled={busy !== null}
            autoComplete="off"
            className="w-full px-3 py-2 text-sm rounded-md border border-gray-200 focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy disabled:bg-gray-50"
          />
        </div>
        <div>
          <label className="block text-[11px] font-semibold text-gray-600 mb-1">
            Consultant name
          </label>
          <input
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            disabled={busy !== null}
            className="w-full px-3 py-2 text-sm rounded-md border border-gray-200 focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy disabled:bg-gray-50"
          />
        </div>
        {error && (
          <p className="text-[11px] text-red-600 inline-flex items-center gap-1">
            <AlertCircle size={11} /> {error}
          </p>
        )}
      </div>
    </ModalShell>
  );
}

// ── Access record (Phase D — consultant IP/time) ───────────────

function AccessRecord({ app }: { app: ConsultantApplication }) {
  // Nothing to show until the consultant has at least passed the gate.
  if (!app.accessIp && !app.accessAt && !app.signingIp && !app.signingAt) {
    return null;
  }
  const fmt = (iso: string | null | undefined) =>
    iso
      ? new Date(iso).toLocaleString("en-IN", {
          timeZone: "Asia/Kolkata",
          dateStyle: "medium",
          timeStyle: "short",
        })
      : "—";
  return (
    <div className="rounded-xl border border-gray-100 bg-gray-50/60 px-4 py-3">
      <p className="text-[11px] font-bold uppercase tracking-wider text-sage-navy inline-flex items-center gap-1.5">
        <Globe size={12} /> Access record
      </p>
      <p className="text-xs text-gray-600 mt-1.5">
        Accessed from{" "}
        <span className="font-mono text-gray-900">{app.accessIp || "—"}</span>{" "}
        on {fmt(app.accessAt)}
        {(app.signingIp || app.signingAt) && (
          <>
            {" · "}signed from{" "}
            <span className="font-mono text-gray-900">{app.signingIp || "—"}</span>{" "}
            on {fmt(app.signingAt)}
          </>
        )}
      </p>
      <p className="text-[10px] text-gray-400 mt-1">
        Client IP captured at email-OTP verification and at signing.
      </p>
    </div>
  );
}

// ── Header ─────────────────────────────────────────────────────

function HeaderRow({ app }: { app: ConsultantApplication }) {
  return (
    <div>
      <div className="flex items-center gap-2 flex-wrap">
        <h1 className="font-serif text-2xl text-gray-900">
          {app.consultantName || app.consultantEmail}
        </h1>
        <AgreementStatusPill status={app.status} />
      </div>
      <p className="text-xs font-mono text-gray-500 mt-0.5">{app.applicationId}</p>
      <p className="text-xs text-gray-500 mt-0.5">{app.consultantEmail}</p>
      <div className="mt-2 flex items-center gap-3 text-[11px] text-gray-500 flex-wrap">
        <span className="inline-flex items-center gap-1">
          <Clock size={11} /> Created {fmtDateTime(app.createdAt)}
        </span>
        <span className="inline-flex items-center gap-1">
          <Clock size={11} /> Expires {fmtDateTime(app.expiresAt)}
        </span>
      </div>
    </div>
  );
}

// ── State-aware action bar ─────────────────────────────────────

function StateActionBar({
  status,
  app,
  onRequestRevision,
  onApproveAndSign,
  onSendEmail,
  onResendInvite,
  onCancel,
  resendBusy,
  cancelBusy,
  isLocked,
}: {
  status: ConsultantApplication["status"];
  app: ConsultantApplication;
  onRequestRevision: () => void;
  onApproveAndSign: () => void;
  onSendEmail: () => void;
  onResendInvite: () => void;
  onCancel: () => void;
  resendBusy: boolean;
  cancelBusy: boolean;
  isLocked: boolean;
}) {
  if (status === "SUBMITTED") {
    return (
      <BarShell badge="Awaiting consultant" tone="amber">
        <p className="text-xs text-gray-600 max-w-md">
          The consultant has the invite. We&apos;ll surface actions here once
          they submit a signed draft.
        </p>
        {!isLocked && (
          <div className="flex items-center gap-2">
            <SubtleButton onClick={onResendInvite} busy={resendBusy} icon={<Mail size={12} />}>
              Resend invite
            </SubtleButton>
            <DangerButton onClick={onCancel} busy={cancelBusy} icon={<Ban size={12} />}>
              Cancel
            </DangerButton>
          </div>
        )}
      </BarShell>
    );
  }

  if (status === "REVISION_REQUESTED") {
    return (
      <BarShell
        badge={`Awaiting consultant revision · #${app.revisionCount ?? 1}`}
        tone="copper"
      >
        {app.currentRevisionRemarks && (
          <blockquote className="text-sm text-gray-700 italic border-l-4 border-sage-copper-deep pl-3 mt-2">
            {app.currentRevisionRemarks}
          </blockquote>
        )}
        <p className="text-[11px] text-gray-500">
          The consultant has been emailed your remarks and can re-submit.
        </p>
      </BarShell>
    );
  }

  if (status === "VERIFIED") {
    return (
      <BarShell badge="Ready for your review" tone="navy">
        <p className="text-xs text-gray-600 max-w-md">
          The consultant signed. Approve to apply your signature and generate
          the final PDF, or send it back with revision remarks.
        </p>
        <div className="flex items-center gap-2 flex-wrap">
          <button
            type="button"
            onClick={onRequestRevision}
            className="inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-xs font-bold border border-sage-copper-deep/40 text-sage-copper-deep hover:bg-sage-copper/5 cursor-pointer"
          >
            <MessageSquare size={12} /> Request revision
          </button>
          <button
            type="button"
            onClick={onApproveAndSign}
            className="inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep cursor-pointer shadow-sm"
          >
            <PenLine size={12} /> Approve &amp; sign
          </button>
        </div>
      </BarShell>
    );
  }

  if (status === "COMPLETED") {
    return <CompletedActions app={app} onSendEmail={onSendEmail} />;
  }

  if (status === "CANCELLED" || status === "EXPIRED") {
    return (
      <BarShell badge={status === "CANCELLED" ? "Cancelled" : "Expired"} tone="zinc">
        <p className="text-xs text-gray-600 max-w-md">
          {status === "CANCELLED"
            ? "This application was cancelled and is locked."
            : "This application expired without a final signature."}
        </p>
      </BarShell>
    );
  }

  return null;
}

function BarShell({
  badge,
  tone,
  children,
}: {
  badge: string;
  tone: "amber" | "copper" | "navy" | "emerald" | "zinc";
  children: ReactNode;
}) {
  const toneClass =
    tone === "amber"
      ? "bg-amber-50 border-amber-100 text-amber-800"
      : tone === "copper"
        ? "bg-orange-50 border-orange-100 text-sage-copper-deep"
        : tone === "navy"
          ? "bg-sage-navy/5 border-sage-navy/15 text-sage-navy"
          : tone === "emerald"
            ? "bg-emerald-50 border-emerald-100 text-emerald-800"
            : "bg-zinc-100 border-zinc-200 text-zinc-700";
  return (
    <section className={`rounded-xl border p-4 space-y-2.5 ${toneClass}`}>
      <span className="inline-block text-[11px] font-bold uppercase tracking-wider">
        {badge}
      </span>
      {children}
    </section>
  );
}

function SubtleButton({
  onClick,
  busy,
  icon,
  children,
}: {
  onClick: () => void;
  busy: boolean;
  icon: ReactNode;
  children: ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={busy}
      className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-bold border border-gray-200 bg-white hover:bg-gray-50 text-gray-700 disabled:opacity-50 cursor-pointer"
    >
      {busy ? <Loader2 size={12} className="animate-spin" /> : icon}
      {children}
    </button>
  );
}

function DangerButton({
  onClick,
  busy,
  icon,
  children,
}: {
  onClick: () => void;
  busy: boolean;
  icon: ReactNode;
  children: ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={busy}
      className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-bold text-red-700 border border-red-100 bg-red-50 hover:bg-red-100 disabled:opacity-50 cursor-pointer"
    >
      {busy ? <Loader2 size={12} className="animate-spin" /> : icon}
      {children}
    </button>
  );
}

// ── COMPLETED action set (View Inline / Download / Send to email) ──
//
// Cloudinary URLs never reach the DOM. Both buttons stream the PDF
// through the backend via fetchAgreementPdfBlob, then mint a
// session-local blob: URL via URL.createObjectURL. Blob URLs aren't
// network resources -- copying one into a different tab / browser /
// account produces a "can't open file" error, which is exactly the
// previously-leaky vector the redesign closed.

function CompletedActions({
  app,
  onSendEmail,
}: {
  app: ConsultantApplication;
  onSendEmail: () => void;
}) {
  const [busy, setBusy] = useState<"view" | "download" | null>(null);
  const [error, setError] = useState("");

  const handleAction = async (mode: "view" | "download") => {
    setBusy(mode);
    setError("");
    try {
      const res = await fetchAgreementPdfBlob(
        app.applicationId,
        mode === "download" ? "attachment" : "inline",
      );
      if (!res.ok) {
        throw new Error(`Couldn't fetch the PDF (${res.status})`);
      }
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      if (mode === "view") {
        window.open(url, "_blank", "noopener,noreferrer");
      } else {
        const a = document.createElement("a");
        a.href = url;
        a.download = buildClientPdfFilename(app);
        document.body.appendChild(a);
        a.click();
        a.remove();
      }
      // Revoke after a minute -- long enough for the opened tab to
      // load the bytes, then the blob URL stops working entirely so
      // someone shoulder-surfing the address bar after the fact gets
      // nothing reusable.
      window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't fetch the PDF.");
    } finally {
      setBusy(null);
    }
  };

  return (
    <BarShell badge="Completed" tone="emerald">
      <p className="text-xs text-gray-600 max-w-md">
        Final PDF generated and emailed to both parties.
      </p>
      <div className="flex items-center gap-2 flex-wrap">
        <button
          type="button"
          onClick={() => handleAction("download")}
          disabled={busy !== null}
          className="inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-xs font-bold bg-emerald-600 text-white hover:bg-emerald-700 disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer shadow-sm"
        >
          {busy === "download" ? (
            <Loader2 size={12} className="animate-spin" />
          ) : (
            <Download size={12} />
          )}
          Download PDF
        </button>
        <button
          type="button"
          onClick={() => handleAction("view")}
          disabled={busy !== null}
          className="inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-xs font-bold border border-gray-200 bg-white hover:bg-gray-50 text-gray-700 disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer"
        >
          {busy === "view" ? (
            <Loader2 size={12} className="animate-spin" />
          ) : (
            <ExternalLink size={12} />
          )}
          View inline
        </button>
        <button
          type="button"
          onClick={onSendEmail}
          disabled={busy !== null}
          className="inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-xs font-bold border border-sage-navy/40 text-sage-navy hover:bg-sage-navy/5 disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer"
        >
          <Mail size={12} /> Send to email…
        </button>
      </div>
      {error && (
        <p className="text-[11px] text-red-600 inline-flex items-center gap-1">
          <AlertCircle size={11} /> {error}
        </p>
      )}
    </BarShell>
  );
}

/**
 * Mirrors AgreementDocumentService.buildPdfFilename so the
 * download anchor's `download` attribute saves the same name the
 * backend's Content-Disposition would have set. Slug rule matches:
 * whitespace -> "-", strip [^A-Za-z0-9_-], collapse repeats, trim.
 */
function buildClientPdfFilename(app: ConsultantApplication): string {
  const rawName =
    app.signedLegalName?.trim() ||
    app.consultantName?.trim() ||
    app.applicationId;
  const nameSlug = slugify(rawName) || slugify(app.applicationId);
  const trackSlug = slugify(app.technologyTrack ?? "");
  const base =
    "SageITCO-Agreement_" + nameSlug + (trackSlug ? "_" + trackSlug : "");
  return base + ".pdf";
}

function slugify(input: string): string {
  if (!input) return "";
  return input
    .trim()
    .replace(/\s+/g, "-")
    .replace(/[^A-Za-z0-9_-]/g, "")
    .replace(/[-_]+/g, "-")
    .replace(/^[-_]+|[-_]+$/g, "");
}

// ── ERM-filled summary card ────────────────────────────────────

function ErmFilledCard({ app }: { app: ConsultantApplication }) {
  return (
    <section className="bg-stone-100 rounded-xl border border-stone-200 p-4 sm:p-5">
      <h2 className="font-serif text-lg text-sage-navy">From your console</h2>
      <p className="text-[11px] text-gray-500 mt-0.5">
        Seeded at create time. Edit via the existing detail edit flow if needed.
      </p>
      <dl className="mt-4 grid grid-cols-1 md:grid-cols-2 gap-x-4 gap-y-3 text-sm">
        <ReadOnlyRow label="Consultant name" value={app.consultantName} />
        <ReadOnlyRow label="Email" value={app.consultantEmail} />
        <ReadOnlyRow label="Rate period 1" value={app.ratePeriod1} />
        <ReadOnlyRow label="Amount 1" value={app.rateAmount1} />
        <ReadOnlyRow label="Rate period 2" value={app.ratePeriod2} />
        <ReadOnlyRow label="Amount 2" value={app.rateAmount2} />
      </dl>
    </section>
  );
}

function ReadOnlyRow({
  label,
  value,
}: {
  label: string;
  value: string | null | undefined;
}) {
  return (
    <div>
      <dt className="text-[10px] uppercase tracking-wider font-semibold text-gray-500">
        {label}
      </dt>
      <dd className="text-sm text-gray-800 font-medium mt-0.5">
        {value && String(value).length > 0 ? String(value) : <span className="text-gray-400">—</span>}
      </dd>
    </div>
  );
}

// ── Consultant-filled 7 sections (read-only) ──────────────────

function ConsultantSections({ app }: { app: ConsultantApplication }) {
  return (
    <div className="space-y-4">
      {SECTIONS.map((section) => (
        <ConsultantSectionCard key={section.id} section={section} app={app} />
      ))}
      <SecurityChequeCard app={app} />
    </div>
  );
}

/**
 * Build G — inline preview of the consultant-signed agreement before
 * the ERM countersigns. Renders the PDF server-side from current
 * entity data (consultant signatures embedded, ERM blank); bytes
 * stream through {@link fetchErmPreviewPdfBlob} → blob URL → iframe.
 * No Cloudinary fetch -- the stored final PDF doesn't exist yet.
 */
function ErmPdfPreview({ appId }: { appId: string }) {
  const [blobUrl, setBlobUrl] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  useEffect(() => {
    let cancelled = false;
    let createdUrl: string | null = null;
    setLoading(true);
    setError("");
    (async () => {
      try {
        const res = await fetchErmPreviewPdfBlob(appId);
        if (!res.ok) {
          throw new Error(`Couldn't render the preview (${res.status})`);
        }
        const blob = await res.blob();
        if (cancelled) return;
        createdUrl = URL.createObjectURL(blob);
        setBlobUrl(createdUrl);
      } catch (e) {
        if (cancelled) return;
        setError(e instanceof Error ? e.message : "Couldn't render the preview.");
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
      if (createdUrl) URL.revokeObjectURL(createdUrl);
    };
  }, [appId]);

  return (
    <section className="bg-white rounded-2xl border border-stone-200 shadow-sm overflow-hidden">
      <header className="px-5 sm:px-6 pt-5 pb-3 border-b border-stone-100">
        <h3 className="font-serif text-lg text-gray-900">
          Consultant-signed PDF preview
        </h3>
        <p className="text-[11px] text-gray-500 mt-0.5">
          Read this before countersigning. Streamed in-memory; not stored.
        </p>
      </header>
      <div className="bg-stone-100 p-4 min-h-[480px] flex items-stretch justify-stretch">
        {loading && !blobUrl && (
          <div className="flex-1 flex items-center justify-center text-xs text-gray-500">
            <Loader2 size={16} className="animate-spin mr-2" />
            Generating preview…
          </div>
        )}
        {error && (
          <div className="flex-1 flex items-center justify-center text-xs text-red-700">
            <AlertCircle size={14} className="mr-1.5" /> {error}
          </div>
        )}
        {blobUrl && !error && (
          <iframe
            src={blobUrl}
            title="Consultant-signed agreement preview"
            className="w-full h-[640px] rounded-md border border-stone-300 bg-white"
          />
        )}
      </div>
    </section>
  );
}

/**
 * Build G — inline view + download of the consultant's Appendix 5
 * cheque. Bytes are streamed through the backend (re-signs the
 * Cloudinary URL each call), wrapped in a blob URL for the open/
 * download action -- the raw Cloudinary URL never reaches the DOM.
 */
function SecurityChequeCard({ app }: { app: ConsultantApplication }) {
  const [busy, setBusy] = useState<"view" | "download" | null>(null);
  const [error, setError] = useState("");
  const uploaded = Boolean(app.chequePublicId);

  const handleAction = async (mode: "view" | "download") => {
    setBusy(mode);
    setError("");
    try {
      const res = await fetchAgreementChequeBlob(
        app.applicationId,
        mode === "download" ? "attachment" : "inline",
      );
      if (!res.ok) {
        throw new Error(`Couldn't fetch the cheque (${res.status})`);
      }
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      if (mode === "view") {
        window.open(url, "_blank", "noopener,noreferrer");
      } else {
        const a = document.createElement("a");
        a.href = url;
        const ext = blob.type === "application/pdf" ? "pdf" : "img";
        a.download = `SageITCO-Cheque_${app.applicationId}.${ext}`;
        document.body.appendChild(a);
        a.click();
        a.remove();
      }
      window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't fetch the cheque.");
    } finally {
      setBusy(null);
    }
  };

  return (
    <section className="bg-white rounded-2xl border border-stone-200 shadow-sm overflow-hidden">
      <header className="px-5 sm:px-6 pt-5 pb-3 border-b border-stone-100 flex items-start justify-between gap-3 flex-wrap">
        <div>
          <h3 className="font-serif text-lg text-gray-900">Security cheque</h3>
          <p className="text-[11px] text-gray-500 mt-0.5">
            Build G — the post-dated cheque the consultant uploaded for Appendix 5.
          </p>
        </div>
        <span
          className={
            "inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-bold uppercase tracking-wider " +
            (uploaded
              ? "bg-emerald-100 text-emerald-800"
              : "bg-stone-200 text-gray-700")
          }
        >
          {uploaded ? "Uploaded" : "Not uploaded"}
        </span>
      </header>
      <div className="px-5 sm:px-6 py-4 space-y-2">
        {!uploaded ? (
          <p className="text-xs text-gray-600">
            The consultant has not uploaded their cheque yet.
          </p>
        ) : (
          <>
            <div className="flex items-center gap-2 flex-wrap">
              <button
                type="button"
                onClick={() => handleAction("view")}
                disabled={busy !== null}
                className="inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-xs font-semibold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer"
              >
                View inline
              </button>
              <button
                type="button"
                onClick={() => handleAction("download")}
                disabled={busy !== null}
                className="inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-xs font-semibold bg-white text-sage-navy border border-stone-300 hover:bg-stone-50 disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer"
              >
                Download
              </button>
              {app.chequeUploadedAt && (
                <span className="text-[10px] text-gray-500">
                  Uploaded {new Date(app.chequeUploadedAt).toLocaleString()}
                </span>
              )}
            </div>
            {error && (
              <p className="text-[11px] text-red-600">{error}</p>
            )}
          </>
        )}
      </div>
    </section>
  );
}

function ConsultantSectionCard({
  section,
  app,
}: {
  section: SectionDef;
  app: ConsultantApplication;
}) {
  const populatedFields = section.fields.filter((f) => {
    const v = app[f.key];
    return typeof v === "string" && v.length > 0;
  });
  const allEmpty = populatedFields.length === 0;

  return (
    <section className="bg-white rounded-2xl border border-stone-200 shadow-sm overflow-hidden">
      <header className="px-5 sm:px-6 pt-5 pb-3 border-b border-stone-100 flex items-start justify-between gap-3">
        <div>
          <h3 className="font-serif text-lg text-gray-900">
            {section.title}
            {section.optional && (
              <span className="text-[11px] font-sans uppercase tracking-wider text-sage-copper-deep ml-2 align-middle">
                Optional
              </span>
            )}
          </h3>
          {section.warning && !allEmpty && (
            <p className="text-[11px] text-sage-copper-deep inline-flex items-center gap-1 mt-1">
              <ShieldAlert size={11} /> {section.warning}
            </p>
          )}
        </div>
      </header>

      <div className="px-5 sm:px-6 py-4">
        {section.optional && allEmpty ? (
          <p className="text-xs text-gray-400 italic">Not provided.</p>
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-2 gap-x-4 gap-y-3">
            {section.fields.map((field) => (
              <ReadOnlyField
                key={field.key}
                field={field}
                value={(app[field.key] as string | null | undefined) ?? null}
              />
            ))}
          </div>
        )}
      </div>
    </section>
  );
}

function ReadOnlyField({
  field,
  value,
}: {
  field: FieldDef;
  value: string | null;
}) {
  const [revealed, setRevealed] = useState(false);
  const wide = field.wide ? "md:col-span-2" : "";
  const empty = !value || value.length === 0;
  const displayValue = field.pii && !revealed && !empty ? maskValue(value!) : value;

  return (
    <div className={wide}>
      <div className="flex items-center justify-between gap-1.5 mb-1">
        <p className="text-[10px] uppercase tracking-wider font-semibold text-gray-500">
          {field.label}
        </p>
        {field.pii && !empty && (
          <button
            type="button"
            onClick={() => {
              if (!revealed) {
                if (!confirm("Reveal sensitive PII? This is logged.")) return;
              }
              setRevealed((r) => !r);
            }}
            aria-label={revealed ? "Hide value" : "Reveal value"}
            className="inline-flex items-center gap-1 text-[10px] font-semibold text-sage-navy hover:text-sage-navy-deep cursor-pointer"
          >
            {revealed ? <EyeOff size={11} /> : <Eye size={11} />}
            {revealed ? "Hide" : "Reveal"}
          </button>
        )}
      </div>
      <p className="text-sm text-gray-800 font-medium break-words">
        {empty ? <span className="text-gray-400">—</span> : displayValue}
      </p>
    </div>
  );
}

function maskValue(value: string) {
  if (value.length <= 4) return "•".repeat(value.length);
  return "•".repeat(Math.max(0, value.length - 4)) + value.slice(-4);
}

// ── Signatures preview ────────────────────────────────────────

function SignaturesPreview({ app }: { app: ConsultantApplication }) {
  const hasConsultant = app.signatureImage && app.signatureImage.length > 0;
  const hasErm = app.ermSignatureUrl && app.ermSignatureUrl.length > 0;
  if (!hasConsultant && !hasErm) return null;

  return (
    <Section title="Signatures">
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        {hasConsultant && (
          <SignatureCard
            label="Consultant"
            url={app.signatureImage!}
            metaPrimary={app.signedLegalName || app.consultantName || "—"}
            metaSecondary={
              app.signedAt
                ? `Signed ${fmtDateTime(app.signedAt)}`
                : "Signed"
            }
          />
        )}
        {hasErm && (
          <SignatureCard
            label="ERM"
            url={app.ermSignatureUrl!}
            metaPrimary={
              app.ermName ? `${app.ermName}${app.ermTitle ? ` · ${app.ermTitle}` : ""}` : "—"
            }
            metaSecondary={
              app.signatureDate
                ? `Counter-signed ${fmtDateTime(app.signatureDate)}`
                : "Counter-signed"
            }
          />
        )}
      </div>
    </Section>
  );
}

function SignatureCard({
  label,
  url,
  metaPrimary,
  metaSecondary,
}: {
  label: string;
  url: string;
  metaPrimary: string;
  metaSecondary: string;
}) {
  return (
    <div className="rounded-xl border border-stone-200 p-3 bg-white">
      <p className="text-[11px] uppercase tracking-wider font-semibold text-gray-500">
        {label}
      </p>
      <div className="mt-2 rounded-md bg-stone-50 border border-stone-100 p-2 inline-block">
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img
          src={url}
          alt={`${label} signature`}
          style={{ maxHeight: 80, maxWidth: "100%" }}
        />
      </div>
      <p className="mt-2 text-sm text-gray-800 font-medium">{metaPrimary}</p>
      <p className="text-[11px] text-gray-500">{metaSecondary}</p>
    </div>
  );
}

// ── Section frame ─────────────────────────────────────────────

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div>
      <p className="text-[11px] uppercase tracking-wider font-semibold text-gray-500 mb-1.5">
        {title}
      </p>
      <div className="rounded-xl border border-gray-100 bg-white p-4">{children}</div>
    </div>
  );
}

// ── Modals ───────────────────────────────────────────────────

function ModalShell({
  title,
  subtitle,
  onClose,
  closeable,
  children,
  footer,
}: {
  title: string;
  subtitle?: string;
  onClose: () => void;
  closeable: boolean;
  children: ReactNode;
  footer: ReactNode;
}) {
  useEffect(() => {
    if (!closeable) return;
    const onEsc = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onEsc);
    return () => window.removeEventListener("keydown", onEsc);
  }, [onClose, closeable]);

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="agreement-modal-title"
      className="fixed inset-0 z-50 flex items-end sm:items-center justify-center bg-black/40 px-3 py-6"
      onClick={() => closeable && onClose()}
    >
      <div
        className="bg-white rounded-2xl shadow-xl w-full max-w-lg max-h-[90vh] overflow-y-auto"
        onClick={(e) => e.stopPropagation()}
      >
        <header className="px-5 sm:px-6 pt-5 pb-3 border-b border-gray-100 flex items-start justify-between gap-3">
          <div>
            <h2 id="agreement-modal-title" className="font-serif text-lg text-gray-900">
              {title}
            </h2>
            {subtitle && (
              <p className="text-xs text-gray-500 mt-0.5">{subtitle}</p>
            )}
          </div>
          {closeable && (
            <button
              type="button"
              onClick={onClose}
              aria-label="Close"
              className="text-gray-400 hover:text-gray-700 cursor-pointer"
            >
              <X size={16} />
            </button>
          )}
        </header>
        <div className="px-5 sm:px-6 py-4">{children}</div>
        <footer className="px-5 sm:px-6 py-4 border-t border-gray-100 flex items-center justify-end gap-2 bg-gray-50/50 rounded-b-2xl">
          {footer}
        </footer>
      </div>
    </div>
  );
}

function RequestRevisionModal({
  appId,
  onClose,
  onDone,
}: {
  appId: string;
  onClose: () => void;
  onDone: () => Promise<void>;
}) {
  const [remarks, setRemarks] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const canSubmit = remarks.trim().length >= 4 && !busy;

  const submit = async () => {
    if (!canSubmit) return;
    setBusy(true);
    setError("");
    try {
      await ermRequestRevision(appId, remarks.trim());
      await onDone();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't send revision request.");
      setBusy(false);
    }
  };

  return (
    <ModalShell
      title="Request revision"
      subtitle="The consultant will receive an email with your remarks."
      onClose={onClose}
      closeable={!busy}
      footer={
        <>
          <button
            type="button"
            onClick={onClose}
            disabled={busy}
            className="px-3 py-1.5 rounded-md text-xs font-semibold text-gray-600 hover:text-gray-900 cursor-pointer disabled:opacity-50"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={submit}
            disabled={!canSubmit}
            className="inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-xs font-bold bg-sage-copper-deep text-white hover:opacity-90 disabled:opacity-60 cursor-pointer"
          >
            {busy ? <Loader2 size={12} className="animate-spin" /> : <MessageSquare size={12} />}
            {busy ? "Sending…" : "Send revision request"}
          </button>
        </>
      }
    >
      <label className="block text-[11px] font-semibold text-gray-600 mb-1">
        Remarks for consultant <span className="text-red-500">*</span>
      </label>
      <textarea
        value={remarks}
        onChange={(e) => setRemarks(e.target.value.slice(0, 2000))}
        rows={5}
        autoFocus
        disabled={busy}
        placeholder="e.g. Please correct the phone number — it appears to be missing a digit."
        className="w-full px-3 py-2 text-sm rounded-md border border-gray-200 focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy"
      />
      <p className="mt-1 text-[11px] text-gray-500">
        Be specific. The consultant sees your remarks verbatim. {remarks.length}/2000
      </p>
      {error && (
        <p className="mt-3 inline-flex items-center gap-1.5 text-sm text-red-600">
          <AlertCircle size={14} /> {error}
        </p>
      )}
    </ModalShell>
  );
}

function ApproveAndSignModal({
  appId,
  defaultName = "",
  defaultTitle = "",
  onClose,
  onDone,
}: {
  appId: string;
  defaultName?: string;
  defaultTitle?: string;
  onClose: () => void;
  onDone: () => Promise<void>;
}) {
  // Pre-filled from /me (Phase B); both stay fully editable.
  const [ermName, setErmName] = useState(defaultName);
  const [ermTitle, setErmTitle] = useState(defaultTitle);
  const [signature, setSignature] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  // /me may resolve AFTER this modal has opened (useState only takes the
  // initial value at mount). Backfill the prefill into any field the
  // signer hasn't already typed into — never clobber their input.
  useEffect(() => {
    if (defaultName) setErmName((v) => v || defaultName);
    if (defaultTitle) setErmTitle((v) => v || defaultTitle);
  }, [defaultName, defaultTitle]);

  const canSubmit =
    ermName.trim().length > 0 &&
    ermTitle.trim().length > 0 &&
    !!signature &&
    !busy;

  const submit = async () => {
    if (!canSubmit || !signature) return;
    setBusy(true);
    setError("");
    try {
      await ermApproveAndSign(appId, {
        ermName: ermName.trim(),
        ermTitle: ermTitle.trim(),
        ermSignatureBase64: signature,
      });
      await onDone();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't approve + sign.");
      setBusy(false);
    }
  };

  return (
    <ModalShell
      title="Approve and sign agreement"
      onClose={onClose}
      closeable={!busy}
      footer={
        <>
          <button
            type="button"
            onClick={onClose}
            disabled={busy}
            className="px-3 py-1.5 rounded-md text-xs font-semibold text-gray-600 hover:text-gray-900 cursor-pointer disabled:opacity-50"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={submit}
            disabled={!canSubmit}
            className="inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-60 cursor-pointer"
          >
            {busy ? <Loader2 size={12} className="animate-spin" /> : <PenLine size={12} />}
            {busy ? "Generating final PDF…" : "Confirm & generate PDF"}
          </button>
        </>
      }
    >
      <div className="rounded-md border border-sage-navy/15 bg-sage-navy/5 p-3 text-xs text-sage-navy">
        Signing will apply your signature to all 8 signature blocks, generate
        the final PDF, and email it to both parties.
      </div>

      <div className="mt-4 grid grid-cols-1 md:grid-cols-2 gap-3">
        <div>
          <label className="block text-[11px] font-semibold text-gray-600 mb-1">
            Your name <span className="text-red-500">*</span>
          </label>
          <input
            type="text"
            value={ermName}
            onChange={(e) => setErmName(e.target.value)}
            disabled={busy}
            placeholder="Sarah Johnson"
            className="w-full px-3 py-2 text-sm rounded-md border border-gray-200 focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy disabled:bg-gray-50"
          />
        </div>
        <div>
          <label className="block text-[11px] font-semibold text-gray-600 mb-1">
            Your title <span className="text-red-500">*</span>
          </label>
          <input
            type="text"
            value={ermTitle}
            onChange={(e) => setErmTitle(e.target.value)}
            disabled={busy}
            placeholder="Director of Operations"
            className="w-full px-3 py-2 text-sm rounded-md border border-gray-200 focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy disabled:bg-gray-50"
          />
        </div>
      </div>

      <div className="mt-4">
        <SignaturePad
          onChange={setSignature}
          disabled={busy}
          fileInputId="erm-sig-upload"
        />
      </div>

      {busy && (
        <div className="mt-4 rounded-md border border-sage-navy/15 bg-white p-3 text-xs text-sage-navy inline-flex items-start gap-2">
          <Loader2 size={14} className="animate-spin shrink-0 mt-0.5" />
          <div>
            <p className="font-semibold">Generating final PDF…</p>
            <p className="text-[11px] text-gray-500 mt-0.5">
              This usually takes 10–30 seconds. Please don&apos;t close this
              window.
            </p>
          </div>
        </div>
      )}

      {error && (
        <p className="mt-3 inline-flex items-center gap-1.5 text-sm text-red-600">
          <AlertCircle size={14} /> {error}
        </p>
      )}
    </ModalShell>
  );
}

function SendEmailModal({
  appId,
  onClose,
  onDone,
}: {
  appId: string;
  onClose: () => void;
  onDone: (recipientEmail: string) => Promise<void>;
}) {
  const [recipient, setRecipient] = useState("");
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const emailLooksValid = useMemo(
    () => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(recipient.trim()),
    [recipient],
  );
  const canSubmit = emailLooksValid && !busy;

  const submit = async () => {
    if (!canSubmit) return;
    setBusy(true);
    setError("");
    try {
      await ermSendPdfToEmail(appId, recipient.trim(), note.trim() || undefined);
      await onDone(recipient.trim());
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't send.");
      setBusy(false);
    }
  };

  return (
    <ModalShell
      title="Send agreement to email"
      subtitle="The signed PDF will be sent as an attachment."
      onClose={onClose}
      closeable={!busy}
      footer={
        <>
          <button
            type="button"
            onClick={onClose}
            disabled={busy}
            className="px-3 py-1.5 rounded-md text-xs font-semibold text-gray-600 hover:text-gray-900 cursor-pointer disabled:opacity-50"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={submit}
            disabled={!canSubmit}
            className="inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-60 cursor-pointer"
          >
            {busy ? <Loader2 size={12} className="animate-spin" /> : <Mail size={12} />}
            {busy ? "Sending…" : "Send"}
          </button>
        </>
      }
    >
      <div className="space-y-3">
        <div>
          <label className="block text-[11px] font-semibold text-gray-600 mb-1">
            Recipient email <span className="text-red-500">*</span>
          </label>
          <input
            type="email"
            value={recipient}
            onChange={(e) => setRecipient(e.target.value)}
            disabled={busy}
            autoFocus
            placeholder="legal@example.com"
            className="w-full px-3 py-2 text-sm rounded-md border border-gray-200 focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy disabled:bg-gray-50"
          />
        </div>
        <div>
          <label className="block text-[11px] font-semibold text-gray-600 mb-1">
            Optional note
          </label>
          <textarea
            value={note}
            onChange={(e) => setNote(e.target.value.slice(0, 1000))}
            rows={3}
            disabled={busy}
            placeholder="Leave blank for a default message."
            className="w-full px-3 py-2 text-sm rounded-md border border-gray-200 focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy disabled:bg-gray-50"
          />
          <p className="mt-1 text-[11px] text-gray-500">{note.length}/1000</p>
        </div>
      </div>
      {error && (
        <p className="mt-3 inline-flex items-center gap-1.5 text-sm text-red-600">
          <AlertCircle size={14} /> {error}
        </p>
      )}
    </ModalShell>
  );
}

// ── Helpers ───────────────────────────────────────────────────

function fmtDateTime(iso: string | null | undefined) {
  if (!iso) return "—";
  return new Date(iso).toLocaleString("en-IN", {
    timeZone: "Asia/Kolkata",
    dateStyle: "medium",
    timeStyle: "short",
  });
}

