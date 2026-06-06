"use client";

import { useEffect, useMemo, useState, type ReactNode } from "react";
import {
  AlertCircle,
  Ban,
  CheckCircle2,
  Clock,
  Download,
  Eye,
  EyeOff,
  ExternalLink,
  Loader2,
  Mail,
  MessageSquare,
  PenLine,
  ShieldAlert,
  X,
} from "lucide-react";

import SignaturePad from "@/components/common/SignaturePad";
import {
  cancelConsultantApplication,
  ermApproveAndSign,
  ermRequestRevision,
  ermSendPdfToEmail,
  resendConsultantInvite,
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
      { key: "bgDriverLicense", label: "Driver's license", pii: true },
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

type ModalKind = null | "revision" | "approve" | "send";

export default function ConsultantDetailView({ detail, onRefresh }: Props) {
  const { application: app, events } = detail;
  const [modal, setModal] = useState<ModalKind>(null);
  const [busy, setBusy] = useState<"resend" | "cancel" | null>(null);
  const [feedback, setFeedback] = useState("");
  const [error, setError] = useState("");

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
      setFeedback("Invite resent.");
      await onRefresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't resend invite");
    } finally {
      setBusy(null);
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

      <ErmFilledCard app={app} />

      <ConsultantSections app={app} />

      <SignaturesPreview app={app} />

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
    const pdf = app.finalPdfUrl || app.signedPdfUrl;
    return (
      <BarShell badge="Completed" tone="emerald">
        <p className="text-xs text-gray-600 max-w-md">
          Final PDF generated and emailed to both parties.
        </p>
        <div className="flex items-center gap-2 flex-wrap">
          {pdf ? (
            <>
              <a
                href={pdf}
                download
                rel="noreferrer"
                className="inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-xs font-bold bg-emerald-600 text-white hover:bg-emerald-700 cursor-pointer shadow-sm"
              >
                <Download size={12} /> Download PDF
              </a>
              <a
                href={pdf}
                target="_blank"
                rel="noreferrer"
                className="inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-xs font-bold border border-gray-200 bg-white hover:bg-gray-50 text-gray-700 cursor-pointer"
              >
                <ExternalLink size={12} /> View inline
              </a>
            </>
          ) : (
            <span className="text-[11px] text-gray-400 italic">PDF pending…</span>
          )}
          <button
            type="button"
            onClick={onSendEmail}
            className="inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-xs font-bold border border-sage-navy/40 text-sage-navy hover:bg-sage-navy/5 cursor-pointer"
          >
            <Mail size={12} /> Send to email…
          </button>
        </div>
      </BarShell>
    );
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
    </div>
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
  onClose,
  onDone,
}: {
  appId: string;
  onClose: () => void;
  onDone: () => Promise<void>;
}) {
  const [ermName, setErmName] = useState("");
  const [ermTitle, setErmTitle] = useState("");
  const [signature, setSignature] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

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

