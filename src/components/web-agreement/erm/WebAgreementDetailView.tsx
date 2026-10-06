"use client";

import { useEffect, useMemo, useState, type ReactNode } from "react";
import {
  AlertCircle,
  Ban,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  Clock,
  Eye,
  EyeOff,
  FileText,
  Globe,
  Loader2,
  Mail,
  MessageSquare,
  PenLine,
  Pencil,
  ShieldAlert,
  ShieldCheck,
  Undo2,
  Upload,
  X,
} from "lucide-react";

import {
  cancelWebAgreement,
  fetchWebAgreementDocBlob,
  fetchWebAgreementPreviewPdfBlob,
  parseChequeList,
  parsePortalEntries,
  resendWebAgreement,
  updateWebAgreementContact,
  verifyWebAgreement,
  webAgreementRequestDocumentRevision,
  webAgreementRequestRevision,
  webAgreementRequestSignatureRevision,
  webAgreementRevokeRevision,
  WebAgreementApiError,
  type ChequeEntry,
  type WebAgreement,
  type WebAgreementDetail,
} from "@/lib/api";
import { formatUsDate, formatUsDateTime } from "@/lib/dates";
import { AGREEMENT_SECTIONS } from "@/lib/web-agreement-sections";
import { describeStatus, statusLabel, type StatusContext } from "@/lib/web-agreement-status";
import AgreementStatusPill from "./AgreementStatusPill";
import AgreementEventTimeline from "./AgreementEventTimeline";

/**
 * The website agreement's copy of the console's ERM detail view
 * (src/components/agreement-erm/ConsultantDetailView.tsx), shown inside the
 * ERM dashboard's Agreements tab. It keeps the actions the website flow has
 * today: resend, cancel, edit contact, request revision (with the ERM's
 * corrections), signature re-sign, document re-upload, take back a request
 * and Verify, plus the PDF preview, the documents, the signature record and
 * the activity log. Internal approval, the countersignature, Phase 2 and the
 * final PDF come later.
 */

/**
 * The header pill and the action-bar badge describe the same row on the same
 * screen, so both resolve their wording from this one context object.
 */
function statusContextFor(app: WebAgreement): StatusContext {
  return {
    consultantCopyReleased: app.consultantCopyReleased,
    phase: app.phase,
  };
}

// Sections the ERM can pick in the revision picker (the final sign step is
// always included for the participant and is not pickable).
const REVISABLE_SECTIONS = AGREEMENT_SECTIONS.filter((s) => s.id !== "review").map(
  (s) => ({ id: s.id, title: s.title }),
);

// Derive the displayed work-authorization (custom text when the ERM chose
// "Others") and assemble the US-format billing address from the structured
// columns.
function displayWorkAuth(app: WebAgreement): string | null {
  const cat = (app.workAuthorizationCategory ?? "").trim();
  if (cat.toLowerCase() === "others") {
    const other = (app.workAuthorizationOther ?? "").trim();
    return other || cat;
  }
  return cat || null;
}

function joinUsAddress(parts: Array<string | null | undefined>): string | null {
  const [line1, line2, city, state, zip] = parts.map((p) => (p ?? "").trim());
  if (!(line1 || line2 || city || state || zip)) return null;
  let csz = city;
  if (state) csz = csz ? `${csz}, ${state}` : state;
  if (zip) csz = csz ? `${csz} ${zip}` : zip;
  return [line1, line2, csz].filter((p) => p.length > 0).join(", ");
}

function displayAddress(app: WebAgreement): string | null {
  return joinUsAddress([
    app.addressLine1, app.addressLine2, app.addressCity, app.addressState, app.addressZip,
  ]);
}

// The background-check address as the backend assembled it, or built from
// its structured columns when the assembled copy is empty.
function displayBgAddress(app: WebAgreement): string | null {
  const assembled = (app.bgCurrentAddress ?? "").trim();
  if (assembled) return assembled;
  return joinUsAddress([
    app.bgCurrentAddressLine1, app.bgCurrentAddressLine2, app.bgCurrentAddressCity,
    app.bgCurrentAddressState, app.bgCurrentAddressZip,
  ]);
}

// Appendix 4's repeatable platform + username entries (the website copy has
// no single platform / username columns).
function displayPortalEntries(app: WebAgreement): string | null {
  const entries = parsePortalEntries(app.portalEntries);
  if (entries.length === 0) return null;
  return entries
    .map((e) => [e.platform, e.username].filter((v) => v.length > 0).join(" — "))
    .join("; ");
}

// Resolve a field's displayed value: a compute() override, a MM-DD-YYYY
// date, or the raw column. Returns null when empty.
function resolveFieldValue(
  field: FieldDef,
  app: WebAgreement,
): string | null {
  let raw: string | null;
  if (field.compute) {
    raw = field.compute(app);
  } else {
    const v = app[field.key];
    raw = typeof v === "string" ? v : v == null ? null : String(v);
  }
  if (raw == null || raw.length === 0) return null;
  return field.isDate ? formatUsDate(raw) || raw : raw;
}

interface Props {
  detail: WebAgreementDetail;
  onRefresh: () => Promise<void>;
}

type FieldKey = keyof WebAgreement;

interface FieldDef {
  key: FieldKey;
  label: string;
  /** Sensitive PII (SSN, DL) -- masked + reveal toggle. */
  pii?: boolean;
  /** Render this field full-width on md+. */
  wide?: boolean;
  /** Render the value as a MM-DD-YYYY date. */
  isDate?: boolean;
  /** Derive the displayed value from the whole agreement (e.g. assembled
   *  address, effective work-auth) instead of one column. */
  compute?: (app: WebAgreement) => string | null;
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
      { key: "effectiveDate", label: "Effective date", isDate: true },
      { key: "primaryPhone", label: "Primary phone" },
      {
        key: "workAuthorizationCategory",
        label: "Work authorization",
        compute: displayWorkAuth,
      },
      {
        key: "addressLine1",
        label: "Residence address",
        wide: true,
        compute: displayAddress,
      },
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
      { key: "verifiedStartDate", label: "Verified start date", isDate: true },
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
      { key: "bgCurrentAddress", label: "Current address", wide: true, compute: displayBgAddress },
      { key: "bgDateOfBirth", label: "Date of birth", isDate: true },
      { key: "bgFullSsn", label: "Full SSN", pii: true },
      { key: "bgDriverLicense", label: "Driver's License number", pii: true },
      { key: "bgStateId", label: "State ID number", pii: true },
    ],
  },
  {
    id: "portal",
    title: "Portal access",
    optional: true,
    fields: [
      { key: "portalEntries", label: "Platform / username", wide: true, compute: displayPortalEntries },
      { key: "portalAuthorizedActions", label: "Authorized actions", wide: true },
      { key: "portalEffectiveDate", label: "Effective date", isDate: true },
      { key: "portalRevocationContact", label: "Revocation contact" },
    ],
  },
  {
    id: "security",
    title: "Security cheque",
    optional: true,
    fields: [
      { key: "securityCheckCount", label: "Cheque count" },
      { key: "securityCheckBank", label: "Bank" },
      { key: "securityCheckHolderName", label: "Holder name" },
      { key: "securityCheckAmount", label: "Amount" },
    ],
  },
];

type ModalKind = null | "revision" | "signatureRevision" | "editContact";

export default function WebAgreementDetailView({ detail, onRefresh }: Props) {
  const { application: app, events } = detail;
  const [modal, setModal] = useState<ModalKind>(null);
  // The per-document "Request re-upload" target (doc key + label), set from a
  // document card; null hides the modal.
  const [docRevisionTarget, setDocRevisionTarget] = useState<{
    key: string;
    label: string;
  } | null>(null);
  const [busy, setBusy] = useState<
    "resend" | "cancel" | "verify" | "revokeRevision" | null
  >(null);
  const [feedback, setFeedback] = useState("");
  const [error, setError] = useState("");

  const status = app.status;
  const isLocked = ["COMPLETED", "CANCELLED"].includes(status);
  // The states from which the ERM may request a document re-upload (mirrors
  // the backend guard). Outside these, the per-card button is hidden.
  const canRequestReupload = [
    "VERIFIED",
    "AWAITING_APPROVALS",
    "APPROVAL_REVISION_REQUESTED",
    "READY_TO_SIGN",
  ].includes(status);

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
      await resendWebAgreement(app.applicationId);
      setFeedback(`Invitation re-sent to ${app.consultantEmail}.`);
      await onRefresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't resend invite");
    } finally {
      setBusy(null);
    }
  };

  const handleCancel = async () => {
    if (!confirm("Cancel this agreement? The participant won't be able to sign.")) {
      return;
    }
    setBusy("cancel");
    setError("");
    try {
      await cancelWebAgreement(app.applicationId);
      setFeedback("Agreement cancelled.");
      await onRefresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't cancel");
    } finally {
      setBusy(null);
    }
  };

  /**
   * The ERM checked the participant-signed agreement. Status stays VERIFIED;
   * consultantCopyReleased turns it into "Verified" and the participant is
   * emailed. Revisions stay possible afterwards (a resubmit clears it).
   */
  const handleVerify = async () => {
    if (!confirm(
      "Verify this agreement?\n\nThe participant will be emailed that their "
      + "agreement is verified. You can still send it back for changes afterwards.",
    )) {
      return;
    }
    setBusy("verify");
    setError("");
    try {
      await verifyWebAgreement(app.applicationId);
      setFeedback("Agreement verified. The participant has been emailed.");
      await onRefresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't verify the agreement");
    } finally {
      setBusy(null);
    }
  };

  /**
   * Take back a change request that went out by mistake. The agreement
   * returns to the desk it was revised from and everything the request
   * cleared (affirmations, signatures, documents) is restored.
   *
   * The confirm has to name two things the operator can't see from the bar:
   * the desk it lands back on, and any correction of their OWN that went out
   * with the request — a fixed ACH schedule or rate card is rolled back too,
   * because leaving it would put changed figures under the affirmation and
   * signature this restores.
   */
  const handleRevokeRevision = async () => {
    const landsOn = statusLabel(app.revisionPrevStatus, statusContextFor(app));
    const reverts = app.revisionRevokeReverts ?? [];
    const alsoReverts = reverts.length
      ? `\n\nThis also rolls back the correction you sent with it — `
        + `${reverts.join(", ")} — to the value the participant signed.`
      : "";
    if (
      !confirm(
        `Take back this change request?\n\nThe agreement returns to `
        + `“${landsOn}” with the participant's submission restored, and they'll `
        + `be emailed that the request was withdrawn.${alsoReverts}`,
      )
    ) {
      return;
    }
    setBusy("revokeRevision");
    setError("");
    try {
      await webAgreementRevokeRevision(app.applicationId);
      setFeedback(
        `Change request withdrawn. The agreement is back at “${landsOn}”`
        + (reverts.length ? `, ${reverts.join(", ")} reverted,` : "")
        + " and the participant has been notified.",
      );
      await onRefresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't take back the request");
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
        onRequestSignatureRevision={() => setModal("signatureRevision")}
        onVerify={handleVerify}
        onResendInvite={handleResend}
        onCancel={handleCancel}
        onRevokeRevision={handleRevokeRevision}
        resendBusy={busy === "resend"}
        cancelBusy={busy === "cancel"}
        verifyBusy={busy === "verify"}
        revokeRevisionBusy={busy === "revokeRevision"}
        isLocked={isLocked}
      />

      <ContactActionsBar
        status={status}
        onEditContact={() => setModal("editContact")}
        onResend={handleResend}
        resendBusy={busy === "resend"}
      />

      <ErmFilledCard app={app} />

      {(status === "VERIFIED"
        || status === "AWAITING_APPROVALS"
        || status === "APPROVAL_REVISION_REQUESTED"
        || status === "READY_TO_SIGN") && (
        <ErmPdfPreview appId={app.applicationId} />
      )}

      <ParticipantSections
        app={app}
        onRequestReupload={
          canRequestReupload
            ? (key, label) => setDocRevisionTarget({ key, label })
            : undefined
        }
      />

      <SignaturesPreview app={app} />

      <AccessRecord app={app} />

      <CollapsibleSection title={`Activity (${events.length})`} defaultOpen={false}>
        <AgreementEventTimeline events={events} />
      </CollapsibleSection>

      {modal === "revision" && (
        <RequestRevisionModal
          appId={app.applicationId}
          achDebitDates={app.achDebitDates}
          achDebitAmounts={app.achDebitAmounts}
          ratePeriod1={app.ratePeriod1}
          rateAmount1={app.rateAmount1}
          ratePeriod2={app.ratePeriod2}
          rateAmount2={app.rateAmount2}
          phase2DeliverablePeriod={app.phase2DeliverablePeriod}
          onClose={() => setModal(null)}
          onDone={async () => {
            setModal(null);
            setFeedback("Revision requested. Participant notified.");
            await onRefresh();
          }}
        />
      )}
      {modal === "signatureRevision" && (
        <SignatureRevisionModal
          appId={app.applicationId}
          onClose={() => setModal(null)}
          onDone={async () => {
            setModal(null);
            setFeedback("Signature re-sign requested. Participant notified.");
            await onRefresh();
          }}
        />
      )}
      {docRevisionTarget && (
        <DocRevisionModal
          appId={app.applicationId}
          docKey={docRevisionTarget.key}
          docLabel={docRevisionTarget.label}
          onClose={() => setDocRevisionTarget(null)}
          onDone={async () => {
            setDocRevisionTarget(null);
            setFeedback("Document re-upload requested. Participant notified.");
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

// ── Contact actions ────────────────────────────────────────────
//
// Edit participant contact / resend the "ready to fill" email. The SUBMITTED
// resend also lives in StateActionBar; this bar adds resend for
// REVISION_REQUESTED and the edit escape hatch. Renders nothing when none
// apply (CANCELLED). There is no "copy link": the participant opens the
// agreement from their dashboard.

function ContactActionsBar({
  status,
  onEditContact,
  onResend,
  resendBusy,
}: {
  status: WebAgreement["status"];
  onEditContact: () => void;
  onResend: () => void;
  resendBusy: boolean;
}) {
  const canEdit = ["SUBMITTED", "VERIFIED", "REVISION_REQUESTED", "COMPLETED"].includes(status);
  // SUBMITTED resend is already in StateActionBar; surface it here for
  // REVISION_REQUESTED so the action exists in both states.
  const canResend = status === "REVISION_REQUESTED";

  if (!canEdit && !canResend) return null;

  return (
    <div className="flex items-center gap-2 flex-wrap">
      {canEdit && (
        <button
          type="button"
          onClick={onEditContact}
          className="inline-flex items-center gap-1.5 px-2.5 py-1.5 rounded-md text-[11px] font-semibold border border-gray-200 bg-white hover:bg-gray-50 text-gray-700 cursor-pointer"
        >
          <Pencil size={12} /> Edit participant contact
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
    </div>
  );
}

// ── Edit participant contact modal ─────────────────────────────

function EditContactModal({
  appId,
  status,
  defaultEmail,
  defaultName,
  onClose,
  onDone,
}: {
  appId: string;
  status: WebAgreement["status"];
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
      await updateWebAgreementContact(appId, {
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
        await resendWebAgreement(appId);
        await onDone(`Contact updated and invitation re-sent to ${email.trim()}.`);
      } catch (e) {
        const msg = e instanceof Error ? e.message : "resend failed";
        await onDone(`Contact updated, but the invitation couldn't be re-sent (${msg}).`);
      }
    } else {
      await onDone("Participant contact updated.");
    }
  };

  return (
    <ModalShell
      title="Edit participant contact"
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
        Fix a wrong email or name on the agreement. This updates where future
        agreement emails go and the agreement PDF&apos;s contact field; it
        doesn&apos;t change the participant&apos;s website sign-in.
      </p>
      <div className="space-y-3">
        <div>
          <label className="block text-[11px] font-semibold text-gray-600 mb-1">
            Participant email <span className="text-red-500">*</span>
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
            Participant name
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

// ── Access record (participant IP/time) ────────────────────────

function AccessRecord({ app }: { app: WebAgreement }) {
  // Nothing to show until the participant has at least given the e-sign consent.
  if (!app.consentIp && !app.consentGivenAt && !app.signingIp && !app.signingAt) {
    return null;
  }
  const fmt = (iso: string | null | undefined) =>
    iso ? formatUsDateTime(iso) : "—";
  return (
    <div className="rounded-xl border border-gray-100 bg-gray-50/60 px-4 py-3">
      <p className="text-[11px] font-bold uppercase tracking-wider text-sage-navy inline-flex items-center gap-1.5">
        <Globe size={12} /> Access record
      </p>
      <p className="text-xs text-gray-600 mt-1.5">
        E-sign consent from{" "}
        <span className="font-mono text-gray-900">{app.consentIp || "—"}</span>{" "}
        on {fmt(app.consentGivenAt)}
        {(app.signingIp || app.signingAt) && (
          <>
            {" · "}signed from{" "}
            <span className="font-mono text-gray-900">{app.signingIp || "—"}</span>{" "}
            on {fmt(app.signingAt)}
          </>
        )}
      </p>
      <p className="text-[10px] text-gray-400 mt-1">
        Client IP captured at the e-sign consent and at signing.
      </p>
    </div>
  );
}

// ── Header ─────────────────────────────────────────────────────

function HeaderRow({ app }: { app: WebAgreement }) {
  const phase = app.phase ?? 1;
  return (
    <div>
      <div className="flex items-center gap-2 flex-wrap">
        <h1 className="font-serif text-2xl text-gray-900">
          {app.consultantName || app.consultantEmail}
        </h1>
        <AgreementStatusPill status={app.status} context={statusContextFor(app)} />
        <span
          className={
            "inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-bold uppercase tracking-wider "
            + (phase === 2
              ? "bg-sage-copper/15 text-sage-copper-deep"
              : "bg-sage-navy/10 text-sage-navy")
          }
        >
          Phase {phase}
        </span>
      </div>
      <p className="text-xs font-mono text-gray-500 mt-0.5">{app.applicationId}</p>
      <p className="text-xs text-gray-500 mt-0.5">{app.consultantEmail}</p>
      <div className="mt-2 flex items-center gap-3 text-[11px] text-gray-500 flex-wrap">
        <span className="inline-flex items-center gap-1">
          <Clock size={11} /> Created {fmtDateTime(app.createdAt)}
        </span>
      </div>
    </div>
  );
}

// ── State-aware action bar ─────────────────────────────────────

/**
 * The way out of a change request sent by mistake. Undoes the whole round:
 * the agreement returns to the desk it was revised from, the participant's
 * submission (affirmations, signatures, documents) is restored, and they're
 * emailed that the request was withdrawn.
 *
 * The server decides whether it is still possible, and says no when the
 * participant has already entered something this round (their answers would
 * be left standing under the restored signature) or when nothing was
 * recorded to restore. Either way, say why — hiding the button leaves the ERM
 * guessing at a moment they are already trying to fix a mistake.
 */
function RevokeRevisionAction({
  app,
  onRevoke,
  busy,
}: {
  app: WebAgreement;
  onRevoke: () => void;
  busy: boolean;
}) {
  // Null on a list-sourced row (the flag is resolved on the detail read only);
  // treat that as "not yet known" and render nothing rather than a dead button.
  if (app.revisionRevocable == null) return null;

  if (!app.revisionRevocable) {
    return (
      <p className="text-[11px] text-gray-500 inline-flex items-start gap-1.5 max-w-md">
        <ShieldAlert size={12} className="mt-0.5 shrink-0" />
        <span>
          {app.revisionRevokeBlockedReason
            ?? "This change request can no longer be taken back."}
        </span>
      </p>
    );
  }

  return (
    <div className="space-y-1.5">
      <button
        type="button"
        onClick={onRevoke}
        disabled={busy}
        title="Undo this change request — the agreement goes back exactly as the participant submitted it"
        className="inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-xs font-bold border border-sage-navy/30 bg-white text-sage-navy hover:bg-sage-navy/5 disabled:opacity-60 disabled:cursor-not-allowed cursor-pointer"
      >
        {busy ? <Loader2 size={12} className="animate-spin" /> : <Undo2 size={12} />}
        {busy ? "Taking back…" : "Take back this request"}
      </button>
      {(app.revisionRevokeReverts?.length ?? 0) > 0 && (
        <p className="text-[11px] text-sage-copper-deep inline-flex items-start gap-1.5 max-w-md">
          <AlertCircle size={12} className="mt-0.5 shrink-0" />
          <span>
            Also rolls back the correction you sent with this request (
            {app.revisionRevokeReverts?.join(", ")}) to the value the participant
            signed.
          </span>
        </p>
      )}
    </div>
  );
}

// Asks the participant to re-sign with a proper signature (content
// unchanged). Shown alongside "Request revision" in the revisable states.
function SignatureReSignButton({ onClick }: { onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      title="Ask the participant to re-sign with a proper signature — their details stay unchanged"
      className="inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-xs font-bold border border-sage-navy/30 text-sage-navy hover:bg-sage-navy/5 cursor-pointer"
    >
      <PenLine size={12} /> Signature re-sign
    </button>
  );
}

function StateActionBar({
  status,
  app,
  onRequestRevision,
  onRequestSignatureRevision,
  onVerify,
  onResendInvite,
  onCancel,
  onRevokeRevision,
  resendBusy,
  cancelBusy,
  verifyBusy,
  revokeRevisionBusy,
  isLocked,
}: {
  status: WebAgreement["status"];
  app: WebAgreement;
  onRequestRevision: () => void;
  onRequestSignatureRevision: () => void;
  onVerify: () => void;
  onResendInvite: () => void;
  onCancel: () => void;
  onRevokeRevision: () => void;
  resendBusy: boolean;
  cancelBusy: boolean;
  verifyBusy: boolean;
  revokeRevisionBusy: boolean;
  isLocked: boolean;
}) {
  // One name per state, shared with the header pill. Only the badge comes from
  // here — the body copy and CTAs below are this bar's own operational
  // instructions.
  const meta = describeStatus(status, statusContextFor(app));
  const badge = meta.label;

  if (status === "SUBMITTED") {
    return (
      <BarShell badge={badge} tone="amber">
        <p className="text-xs text-gray-600 max-w-md">
          The participant has the invite and fills the agreement from their
          dashboard. We&apos;ll surface actions here once they submit a signed
          draft.
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
        // Round number kept — it is real information the pill has no room for.
        badge={`${badge} · #${app.revisionCount ?? 1}`}
        tone="copper"
      >
        {app.currentRevisionRemarks && (
          <blockquote className="text-sm text-gray-700 italic border-l-4 border-sage-copper-deep pl-3 mt-2">
            {app.currentRevisionRemarks}
          </blockquote>
        )}
        <p className="text-[11px] text-gray-500">
          The participant has been emailed your remarks and can re-submit.
        </p>
        <RevokeRevisionAction
          app={app}
          onRevoke={onRevokeRevision}
          busy={revokeRevisionBusy}
        />
      </BarShell>
    );
  }

  if (status === "VERIFIED") {
    // consultantCopyReleased = the ERM verified it. Revisions stay open
    // either way; a resubmit clears the flag so it needs verifying again.
    const verified = Boolean(app.consultantCopyReleased);
    return (
      <BarShell badge={badge} tone={verified ? "emerald" : "navy"}>
        <p className="text-xs text-gray-600 max-w-md">
          {verified
            ? `You verified this agreement${
                app.consultantCopyReleasedAt
                  ? ` on ${fmtDateTime(app.consultantCopyReleasedAt)}`
                  : ""
              }. Internal approval comes next. You can still send it back to the participant with revision remarks.`
            : "The participant signed. Review the agreement below, then verify it, or send it back to the participant with revision remarks."}
        </p>
        <div className="flex items-center gap-2 flex-wrap">
          <button
            type="button"
            onClick={onRequestRevision}
            className="inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-xs font-bold border border-sage-copper-deep/40 text-sage-copper-deep hover:bg-sage-copper/5 cursor-pointer"
          >
            <MessageSquare size={12} /> Request revision
          </button>
          <SignatureReSignButton onClick={onRequestSignatureRevision} />
          {!verified && (
            <button
              type="button"
              onClick={onVerify}
              disabled={verifyBusy}
              className="inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer shadow-sm"
            >
              {verifyBusy ? <Loader2 size={12} className="animate-spin" /> : <ShieldCheck size={12} />}
              {verifyBusy ? "Verifying…" : "Verify"}
            </button>
          )}
        </div>
      </BarShell>
    );
  }

  if (status === "CANCELLED") {
    return (
      <BarShell badge={badge} tone="zinc">
        <p className="text-xs text-gray-600 max-w-md">
          This agreement was cancelled and is locked.
        </p>
      </BarShell>
    );
  }

  // Internal approval, the countersignature and the final PDF come later, so
  // nothing produces these states yet; say what the state means and stop.
  if (
    status === "AWAITING_APPROVALS"
    || status === "APPROVAL_REVISION_REQUESTED"
    || status === "READY_TO_SIGN"
    || status === "COMPLETED"
  ) {
    return (
      <BarShell badge={badge} tone={status === "COMPLETED" ? "emerald" : "navy"}>
        <p className="text-xs text-gray-600 max-w-md">{meta.meaning}</p>
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

function ErmFilledCard({ app }: { app: WebAgreement }) {
  return (
    <section className="bg-stone-100 rounded-xl border border-stone-200 p-4 sm:p-5">
      <h2 className="font-serif text-lg text-sage-navy">Set by the ERM</h2>
      <p className="text-[11px] text-gray-500 mt-0.5">
        Seeded at create time. To change the rates or the deliverables period,
        send them with a revision request.
      </p>
      <dl className="mt-4 grid grid-cols-1 md:grid-cols-2 gap-x-4 gap-y-3 text-sm">
        <ReadOnlyRow label="Participant name" value={app.consultantName} />
        <ReadOnlyRow label="Email" value={app.consultantEmail} />
        <ReadOnlyRow label="Rate period 1" value={app.ratePeriod1} />
        <ReadOnlyRow label="Amount 1" value={app.rateAmount1} />
        <ReadOnlyRow label="Rate period 2" value={app.ratePeriod2} />
        <ReadOnlyRow label="Amount 2" value={app.rateAmount2} />
        <ReadOnlyRow
          label="Phase 2 deliverables period"
          value={app.phase2DeliverablePeriod}
        />
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

// ── Authenticated document open ───────────────────────────────────
//
// The document endpoints need the website sign-in (a bearer token in
// localStorage), so a bare new-tab link never carries it. Fetch the bytes
// WITH the bearer, then open a blob URL.
function DocViewButton({
  applicationId,
  docPath,
  label = "View document",
}: {
  applicationId: string;
  docPath: string;
  label?: string;
}) {
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState("");
  const open = async () => {
    setBusy(true);
    setErr("");
    try {
      const blob = await fetchWebAgreementDocBlob(applicationId, docPath, "inline");
      openUploadedBlob(blob, "document");
    } catch (e) {
      setErr(e instanceof Error ? e.message : "Couldn't open the document.");
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className="flex flex-col items-end gap-1">
      <button
        type="button"
        onClick={open}
        disabled={busy}
        className="inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-xs font-semibold border border-stone-300 bg-white text-sage-navy hover:bg-stone-50 disabled:opacity-50 cursor-pointer"
      >
        <FileText size={12} /> {busy ? "Opening…" : label}
      </button>
      {err && <span className="text-[11px] text-sage-copper-deep">{err}</span>}
    </div>
  );
}

// ── Build W — Appendix 1 work-authorization document (read-only) ──

function WorkAuthDocCard({
  app,
  onRequestReupload,
}: {
  app: WebAgreement;
  onRequestReupload?: (docKey: string, docLabel: string) => void;
}) {
  const has = Boolean(app.workAuthDocS3Key);
  if (!has) return null;
  return (
    <section className="bg-white rounded-2xl border border-stone-200 shadow-sm overflow-hidden">
      <header className="px-5 sm:px-6 pt-5 pb-3 border-b border-stone-100">
        <h3 className="font-serif text-lg text-gray-900">
          Work-authorization document
        </h3>
      </header>
      <div className="px-5 sm:px-6 py-4 flex items-center justify-between gap-3 flex-wrap">
        <p className="text-xs text-gray-500 inline-flex items-center gap-1.5">
          <CheckCircle2 size={13} className="text-emerald-600" />
          Uploaded
          {app.workAuthDocUploadedAt
            ? ` ${formatUsDate(app.workAuthDocUploadedAt)}`
            : ""}
        </p>
        <div className="flex items-center gap-2 flex-wrap justify-end">
          <DocViewButton applicationId={app.applicationId} docPath="/workauth" />
          <RequestReuploadButton
            docKey="doc:workauth"
            docLabel="Work-authorization document"
            onRequestReupload={onRequestReupload}
          />
        </div>
      </div>
    </section>
  );
}

// ── Build I — Phase 2 Employment offer letter (read-only) ─────────

function OfferLetterCard({
  app,
  onRequestReupload,
}: {
  app: WebAgreement;
  onRequestReupload?: (docKey: string, docLabel: string) => void;
}) {
  if (!app.offerLetterS3Key) return null;
  return (
    <section className="bg-white rounded-2xl border border-stone-200 shadow-sm overflow-hidden">
      <header className="px-5 sm:px-6 pt-5 pb-3 border-b border-stone-100">
        <h3 className="font-serif text-lg text-gray-900">Offer letter</h3>
      </header>
      <div className="px-5 sm:px-6 py-4 flex items-center justify-between gap-3 flex-wrap">
        <p className="text-xs text-gray-500 inline-flex items-center gap-1.5">
          <CheckCircle2 size={13} className="text-emerald-600" />
          Uploaded
          {app.offerLetterUploadedAt
            ? ` ${formatUsDate(app.offerLetterUploadedAt)}`
            : ""}
        </p>
        <div className="flex items-center gap-2 flex-wrap justify-end">
          <DocViewButton applicationId={app.applicationId} docPath="/offer-letter" />
          <RequestReuploadButton
            docKey="doc:offer-letter"
            docLabel="Offer letter"
            onRequestReupload={onRequestReupload}
          />
        </div>
      </div>
    </section>
  );
}

// ── Build J — Background Check document uploads (read-only) ───────

function UploadedDocCard({
  title,
  uploaded,
  uploadedAt,
  applicationId,
  docPath,
  docKey,
  onRequestReupload,
}: {
  title: string;
  uploaded: boolean;
  uploadedAt: string | null;
  applicationId: string;
  docPath: string;
  docKey?: string;
  onRequestReupload?: (docKey: string, docLabel: string) => void;
}) {
  if (!uploaded) return null;
  return (
    <section className="bg-white rounded-2xl border border-stone-200 shadow-sm overflow-hidden">
      <header className="px-5 sm:px-6 pt-5 pb-3 border-b border-stone-100">
        <h3 className="font-serif text-lg text-gray-900">{title}</h3>
      </header>
      <div className="px-5 sm:px-6 py-4 flex items-center justify-between gap-3 flex-wrap">
        <p className="text-xs text-gray-500 inline-flex items-center gap-1.5">
          <CheckCircle2 size={13} className="text-emerald-600" />
          Uploaded{uploadedAt ? ` ${formatUsDate(uploadedAt)}` : ""}
        </p>
        <div className="flex items-center gap-2 flex-wrap justify-end">
          <DocViewButton applicationId={applicationId} docPath={docPath} />
          {docKey && (
            <RequestReuploadButton
              docKey={docKey}
              docLabel={title}
              onRequestReupload={onRequestReupload}
            />
          )}
        </div>
      </div>
    </section>
  );
}

// ── Participant-filled sections (read-only) ───────────────────

function ParticipantSections({
  app,
  onRequestReupload,
}: {
  app: WebAgreement;
  // Build AK — present only in a revisable state; each document card renders a
  // "Request re-upload" button that opens the scoped re-upload modal.
  onRequestReupload?: (docKey: string, docLabel: string) => void;
}) {
  return (
    <div className="space-y-4">
      {SECTIONS.map((section) => (
        <ParticipantSectionCard key={section.id} section={section} app={app} />
      ))}
      <WorkAuthDocCard app={app} onRequestReupload={onRequestReupload} />
      <OfferLetterCard app={app} onRequestReupload={onRequestReupload} />
      <UploadedDocCard
        title="Driver's License document"
        uploaded={Boolean(app.dlDocS3Key)}
        uploadedAt={app.dlDocUploadedAt}
        applicationId={app.applicationId}
        docPath="/dl-doc"
        docKey="doc:dl-doc"
        onRequestReupload={onRequestReupload}
      />
      <UploadedDocCard
        title="State ID document"
        uploaded={Boolean(app.stateIdDocS3Key)}
        uploadedAt={app.stateIdDocUploadedAt}
        applicationId={app.applicationId}
        docPath="/state-id-doc"
        docKey="doc:state-id"
        onRequestReupload={onRequestReupload}
      />
      <UploadedDocCard
        title="SSN document"
        uploaded={Boolean(app.ssnDocS3Key)}
        uploadedAt={app.ssnDocUploadedAt}
        applicationId={app.applicationId}
        docPath="/ssn-doc"
        docKey="doc:ssn-doc"
        onRequestReupload={onRequestReupload}
      />
      <SecurityChequeCard app={app} onRequestReupload={onRequestReupload} />
    </div>
  );
}

/**
 * Build AK — per-document "Request re-upload" button (revisable states only).
 * Opens the scoped re-upload modal for {@code docKey}; clearing + bouncing is
 * handled by the modal → {@link webAgreementRequestDocumentRevision}.
 */
function RequestReuploadButton({
  docKey,
  docLabel,
  onRequestReupload,
}: {
  docKey: string;
  docLabel: string;
  onRequestReupload?: (docKey: string, docLabel: string) => void;
}) {
  if (!onRequestReupload) return null;
  return (
    <button
      type="button"
      onClick={() => onRequestReupload(docKey, docLabel)}
      title="Ask the participant to replace this document"
      className="inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-xs font-semibold border border-sage-copper/40 bg-white text-sage-copper-deep hover:bg-[#FBF1E8] cursor-pointer"
    >
      <Upload size={12} /> Request re-upload
    </button>
  );
}

/**
 * Inline preview of the participant-signed agreement before the ERM
 * verifies it. The server renders the PDF from current agreement data
 * (participant signatures embedded, ERM blank, uploaded documents
 * appended); bytes stream through {@link fetchWebAgreementPreviewPdfBlob}
 * → blob URL → iframe. Nothing is stored.
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
        const blob = await fetchWebAgreementPreviewPdfBlob(appId);
        if (cancelled) return;
        createdUrl = URL.createObjectURL(blob);
        setBlobUrl(createdUrl);
      } catch (e) {
        if (cancelled) return;
        if (e instanceof WebAgreementApiError && e.status >= 500) {
          // The backend puts the render's actual failure (exception class +
          // message) on X-Preview-Error. Surfacing it in the browser console
          // keeps it diagnosable without putting server internals on screen
          // for the ERM (the renderer needs LibreOffice on the server).
          if (e.previewError) {
            console.error(`Preview render failed on the server — ${e.previewError}`);
          }
          setError(
            "The PDF preview isn't available right now. The details and documents below are up to date.",
          );
        } else {
          setError(e instanceof Error ? e.message : "Couldn't render the preview.");
        }
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
          Participant-signed PDF preview
        </h3>
        <p className="text-[11px] text-gray-500 mt-0.5">
          Read this before you verify. Streamed in-memory; not stored.
        </p>
      </header>
      <div
        className={
          "bg-stone-100 p-4 flex items-stretch justify-stretch "
          + (error ? "min-h-[96px]" : "min-h-[480px]")
        }
      >
        {loading && !blobUrl && (
          <div className="flex-1 flex items-center justify-center text-xs text-gray-500">
            <Loader2 size={16} className="animate-spin mr-2" />
            Generating preview…
          </div>
        )}
        {error && (
          <div className="flex-1 flex items-center justify-center text-center text-xs text-red-700">
            <AlertCircle size={14} className="mr-1.5 shrink-0" /> {error}
          </div>
        )}
        {blobUrl && !error && (
          <iframe
            src={blobUrl}
            title="Participant-signed agreement preview"
            className="w-full h-[640px] rounded-md border border-stone-300 bg-white"
          />
        )}
      </div>
    </section>
  );
}

/**
 * Build AQ — file extension matching what the backend actually served.
 * This used to be a flat ".img" for anything that wasn't a PDF, so a
 * downloaded cheque landed as a file Windows had no handler for. The
 * backend transcodes HEIC to JPEG on read, so the served type is now
 * always something a browser can open — name it accordingly.
 */
function extensionForBlobType(mime: string | undefined | null): string {
  const type = (mime ?? "").toLowerCase().split(";")[0].trim();
  switch (type) {
    case "application/pdf": return ".pdf";
    case "image/jpeg":
    case "image/jpg": return ".jpg";
    case "image/png": return ".png";
    case "image/gif": return ".gif";
    case "image/webp": return ".webp";
    case "image/tiff": return ".tiff";
    case "image/heic":
    case "image/heif": return ".heic";
    default: return type.startsWith("image/") ? ".img" : "";
  }
}

/**
 * Types a participant's upload may be shown as in a new tab. A blob: URL opens
 * on this site's origin, so an SVG or HTML file shown there could run script
 * with the signed-in staff member's session. Anything else is downloaded
 * (as application/octet-stream) instead of rendered.
 */
const VIEWABLE_UPLOAD_TYPES = new Set([
  "application/pdf", "image/jpeg", "image/jpg", "image/png", "image/gif", "image/webp",
]);

function openUploadedBlob(blob: Blob, name: string) {
  const type = (blob.type ?? "").toLowerCase().split(";")[0].trim();
  const viewable = VIEWABLE_UPLOAD_TYPES.has(type);
  const url = URL.createObjectURL(viewable ? blob : new Blob([blob], { type: "application/octet-stream" }));
  if (viewable) {
    window.open(url, "_blank", "noopener,noreferrer");
  } else {
    const a = document.createElement("a");
    a.href = url;
    a.download = name + extensionForBlobType(type);
    document.body.appendChild(a);
    a.click();
    a.remove();
  }
  window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
}

/**
 * Inline view + download of the participant's Appendix 5 cheques. Bytes
 * are streamed through the backend and wrapped in a blob URL for the
 * open/download action -- the storage path never reaches the DOM.
 */
function SecurityChequeCard({
  app,
  onRequestReupload,
}: {
  app: WebAgreement;
  onRequestReupload?: (docKey: string, docLabel: string) => void;
}) {
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState("");
  // Pull the cheque list from the JSON column, falling back to the
  // index-0 mirror columns (the backend does the same).
  // The participant's DECLARED cheque count (0 = not set).
  const chequeCount = useMemo(() => {
    const n = parseInt(String(app.securityCheckCount ?? "").trim(), 10);
    return Number.isFinite(n) && n > 0 ? Math.min(n, 50) : 0;
  }, [app.securityCheckCount]);

  const entries = useMemo<ChequeEntry[]>(() => {
    const parsed = parseChequeList(app.cheques ?? null);
    // Cap to the declared count so stale over-clicked entries the
    // participant left behind (after reducing the count) don't render here.
    const capped = chequeCount > 0 ? parsed.filter((e) => e.index < chequeCount) : parsed;
    if (capped.length > 0) return capped;
    if (app.chequeS3Key) {
      return [{
        index: 0,
        number: "",
        date: "",
        // Website cheques have no Cloudinary id; s3Key holds the stored path.
        publicId: "",
        s3Key: app.chequeS3Key ?? "",
        contentType: app.chequeContentType ?? "",
        uploadedAt: app.chequeUploadedAt ?? "",
      }];
    }
    return [];
  }, [app.cheques, chequeCount, app.chequeS3Key, app.chequeContentType, app.chequeUploadedAt]);

  const uploadedCount = entries.filter((e) => e.publicId || e.s3Key).length;

  const handleAction = async (entry: ChequeEntry, mode: "view" | "download") => {
    const key = `${entry.index}-${mode}`;
    setBusy(key);
    setError("");
    try {
      // Index-aware staff endpoint, bearer-authenticated (the website
      // token isn't a cookie, so a plain link would be refused).
      const blob = await fetchWebAgreementDocBlob(
        app.applicationId,
        `/cheques/${entry.index}`,
        mode === "download" ? "attachment" : "inline",
      );
      const name = `SageITCO-Cheque-${entry.index + 1}_${app.applicationId}`;
      if (mode === "view") {
        openUploadedBlob(blob, name);
        return;
      }
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = name + extensionForBlobType(blob.type);
      document.body.appendChild(a);
      a.click();
      a.remove();
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
          <h3 className="font-serif text-lg text-gray-900">Security cheques</h3>
          <p className="text-[11px] text-gray-500 mt-0.5">
            Per-cheque uploads the participant provided for Appendix 5.
          </p>
        </div>
        <div className="flex items-center gap-2 flex-wrap justify-end">
          <span
            className={
              "inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-bold uppercase tracking-wider " +
              (uploadedCount > 0
                ? "bg-emerald-100 text-emerald-800"
                : "bg-stone-200 text-gray-700")
            }
          >
            {uploadedCount > 0 ? `${uploadedCount} uploaded` : "Not uploaded"}
          </span>
          {uploadedCount > 0 && (
            <RequestReuploadButton
              docKey="doc:cheque"
              docLabel="Security cheque(s)"
              onRequestReupload={onRequestReupload}
            />
          )}
        </div>
      </header>
      <div className="px-5 sm:px-6 py-4 space-y-3">
        {entries.length === 0 ? (
          <p className="text-xs text-gray-600">
            The participant has not uploaded any cheques yet.
          </p>
        ) : (
          entries.map((entry) => (
            <div
              key={entry.index}
              className="border border-stone-100 rounded-lg p-3 flex flex-col gap-2"
            >
              <div className="flex items-center justify-between gap-2 flex-wrap">
                <p className="text-xs font-semibold text-sage-navy">
                  Cheque {entry.index + 1}
                  {entry.number && (
                    <span className="ml-2 text-[11px] font-normal text-gray-600">
                      № <span className="font-mono">{entry.number}</span>
                    </span>
                  )}
                  {entry.date && (
                    <span className="ml-2 text-[11px] font-normal text-gray-600">
                      dated {entry.date}
                    </span>
                  )}
                </p>
                <div className="flex items-center gap-2">
                  {(entry.publicId || entry.s3Key) ? (
                    <>
                      <button
                        type="button"
                        onClick={() => handleAction(entry, "view")}
                        disabled={busy !== null}
                        className="inline-flex items-center gap-1.5 px-2.5 py-1.5 rounded-md text-[11px] font-semibold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-50 cursor-pointer"
                      >
                        View
                      </button>
                      <button
                        type="button"
                        onClick={() => handleAction(entry, "download")}
                        disabled={busy !== null}
                        className="inline-flex items-center gap-1.5 px-2.5 py-1.5 rounded-md text-[11px] font-semibold bg-white text-sage-navy border border-stone-300 hover:bg-stone-50 disabled:opacity-50 cursor-pointer"
                      >
                        Download
                      </button>
                    </>
                  ) : (
                    <span className="text-[11px] text-sage-copper-deep">
                      Not uploaded
                    </span>
                  )}
                </div>
              </div>
              {entry.uploadedAt && (entry.publicId || entry.s3Key) && (
                <p className="text-[10px] text-gray-500">
                  Uploaded {formatUsDateTime(entry.uploadedAt)}
                </p>
              )}
            </div>
          ))
        )}
        {error && <p className="text-[11px] text-red-600">{error}</p>}
      </div>
    </section>
  );
}

function ParticipantSectionCard({
  section,
  app,
}: {
  section: SectionDef;
  app: WebAgreement;
}) {
  const populatedFields = section.fields.filter(
    (f) => resolveFieldValue(f, app) !== null,
  );
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
                value={resolveFieldValue(field, app)}
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

// ── Signatures record ─────────────────────────────────────────
//
// The website copy keeps the participant's signature images in document
// storage and does not send them with the agreement, so this shows the
// signing record (who, when, which signature) rather than the images; the
// images themselves are on the PDF preview. The ERM countersignature comes
// later.

function SignaturesPreview({ app }: { app: WebAgreement }) {
  const hasPrimary = Boolean(app.signatureS3Key);
  const hasFinal = Boolean(app.finalSignatureS3Key);
  if (!hasPrimary && !hasFinal) return null;

  const signer = app.signedLegalName || app.consultantName || "—";
  return (
    <Section title="Signatures">
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        {hasPrimary && (
          <SignatureCard
            label="Participant · main agreement"
            metaPrimary={signer}
            metaSecondary={
              app.signedAt
                ? `Signed ${fmtDateTime(app.signedAt)}`
                : "Signed"
            }
          />
        )}
        {hasFinal && (
          <SignatureCard
            label="Participant · execution"
            metaPrimary={signer}
            metaSecondary={
              app.finalSignedAt
                ? `Signed ${fmtDateTime(app.finalSignedAt)}`
                : "Signed"
            }
          />
        )}
      </div>
      <p className="mt-3 text-[11px] text-gray-500">
        The signature images appear on the PDF preview.
      </p>
    </Section>
  );
}

function SignatureCard({
  label,
  metaPrimary,
  metaSecondary,
}: {
  label: string;
  metaPrimary: string;
  metaSecondary: string;
}) {
  return (
    <div className="rounded-xl border border-stone-200 p-3 bg-white">
      <p className="text-[11px] uppercase tracking-wider font-semibold text-gray-500">
        {label}
      </p>
      <p className="mt-2 inline-flex items-center gap-1.5 text-xs font-semibold text-emerald-700">
        <CheckCircle2 size={13} /> Captured
      </p>
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

/**
 * Build U — title bar collapses/expands a section on click. Used for
 * the Activity log so it doesn't dominate the detail page; the ERM
 * opens it on demand.
 */
function CollapsibleSection({
  title,
  defaultOpen = false,
  children,
}: {
  title: string;
  defaultOpen?: boolean;
  children: ReactNode;
}) {
  const [open, setOpen] = useState(defaultOpen);
  return (
    <div>
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        className="w-full text-left inline-flex items-center gap-1.5 text-[11px] uppercase tracking-wider font-semibold text-gray-500 hover:text-sage-navy mb-1.5 cursor-pointer"
      >
        {open ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
        {title}
      </button>
      {open && (
        <div className="rounded-xl border border-gray-100 bg-white p-4">{children}</div>
      )}
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

// Signature-only revision. Clears the participant's stored signatures
// and sends the agreement back so they re-sign with a proper signature; all
// content stays as-is. Optional note for the participant.
function SignatureRevisionModal({
  appId,
  onClose,
  onDone,
}: {
  appId: string;
  onClose: () => void;
  onDone: () => Promise<void>;
}) {
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const submit = async () => {
    if (busy) return;
    setBusy(true);
    setError("");
    try {
      await webAgreementRequestSignatureRevision(appId, note.trim() || undefined);
      await onDone();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't request the signature re-sign.");
      setBusy(false);
    }
  };

  return (
    <ModalShell
      title="Request signature re-sign"
      subtitle="Send the agreement back for a fresh signature. The participant's details stay unchanged — only their signature is cleared and must be re-drawn."
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
            disabled={busy}
            className="inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-60 cursor-pointer"
          >
            {busy ? <Loader2 size={12} className="animate-spin" /> : <PenLine size={12} />}
            Send signature re-sign
          </button>
        </>
      }
    >
      <div className="space-y-3">
        <div className="rounded-lg border border-sage-navy/15 bg-sage-navy/5 px-3 py-2.5 text-[12px] text-gray-700">
          The participant re-draws <strong>both signatures</strong> (main
          agreement + execution) on their next visit and re-submits. Everything
          else they filled stays exactly as-is.
        </div>
        <div>
          <label className="block text-[11px] font-semibold uppercase tracking-wider text-sage-navy mb-1">
            Note to participant{" "}
            <span className="text-gray-400 normal-case font-normal">(optional)</span>
          </label>
          <textarea
            value={note}
            onChange={(e) => setNote(e.target.value)}
            rows={3}
            placeholder="e.g. Your signature was unclear — please sign your full name legibly."
            className="w-full px-3 py-2 text-[13px] rounded-md border border-stone-300 focus:outline-none focus:ring-2 focus:ring-sage-copper/40 resize-none"
          />
        </div>
        {error && (
          <p className="text-[12px] text-red-600 inline-flex items-center gap-1">
            <AlertCircle size={12} /> {error}
          </p>
        )}
      </div>
    </ModalShell>
  );
}

/**
 * ERM "Request re-upload" for ONE document. Clears that document
 * server-side (so a fresh upload is forced) and bounces the agreement back to
 * the participant, scoped to just that document. Optional note.
 */
function DocRevisionModal({
  appId,
  docKey,
  docLabel,
  onClose,
  onDone,
}: {
  appId: string;
  docKey: string;
  docLabel: string;
  onClose: () => void;
  onDone: () => Promise<void>;
}) {
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const submit = async () => {
    if (busy) return;
    setBusy(true);
    setError("");
    try {
      await webAgreementRequestDocumentRevision(appId, [docKey], note.trim() || undefined);
      await onDone();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't request the re-upload.");
      setBusy(false);
    }
  };

  return (
    <ModalShell
      title="Request document re-upload"
      subtitle="Send the agreement back so the participant uploads a fresh file. Their other details stay unchanged."
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
            disabled={busy}
            className="inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-60 cursor-pointer"
          >
            {busy ? <Loader2 size={12} className="animate-spin" /> : <Upload size={12} />}
            Send re-upload request
          </button>
        </>
      }
    >
      <div className="space-y-3">
        <div className="rounded-lg border border-sage-copper/25 bg-[#FBF1E8] px-3 py-2.5 text-[12px] text-gray-700">
          The current <strong>{docLabel}</strong> will be removed and the
          participant must upload a new one before they can resubmit. Everything
          else they filled stays exactly as-is.
        </div>
        <div>
          <label className="block text-[11px] font-semibold uppercase tracking-wider text-sage-navy mb-1">
            Note to participant{" "}
            <span className="text-gray-400 normal-case font-normal">(optional)</span>
          </label>
          <textarea
            value={note}
            onChange={(e) => setNote(e.target.value)}
            rows={3}
            placeholder="e.g. The document was blurry — please upload a clearer copy."
            className="w-full px-3 py-2 text-[13px] rounded-md border border-stone-300 focus:outline-none focus:ring-2 focus:ring-sage-copper/40 resize-none"
          />
        </div>
        {error && (
          <p className="text-[12px] text-red-600 inline-flex items-center gap-1">
            <AlertCircle size={12} /> {error}
          </p>
        )}
      </div>
    </ModalShell>
  );
}

function RequestRevisionModal({
  appId,
  achDebitDates,
  achDebitAmounts,
  ratePeriod1,
  rateAmount1,
  ratePeriod2,
  rateAmount2,
  phase2DeliverablePeriod,
  onClose,
  onDone,
}: {
  appId: string;
  achDebitDates?: string | null;
  achDebitAmounts?: string | null;
  ratePeriod1?: string | null;
  rateAmount1?: string | null;
  ratePeriod2?: string | null;
  rateAmount2?: string | null;
  phase2DeliverablePeriod?: string | null;
  onClose: () => void;
  onDone: () => Promise<void>;
}) {
  // Build Y — SECTION PICKER. The ERM ticks the section(s) to revise;
  // a per-section note is optional (never required). The participant is
  // then restricted to ONLY the ticked section(s) + the sign step.
  const [picked, setPicked] = useState<Record<string, boolean>>({});
  const [notes, setNotes] = useState<Record<string, string>>({});
  // Build U — editable ACH debit schedule, pre-filled with the current values.
  const [achDates, setAchDates] = useState(achDebitDates ?? "");
  const [achAmounts, setAchAmounts] = useState(achDebitAmounts ?? "");
  // Build W — editable Phase-2 Rate Schedule, pre-filled with current values.
  const [ratePer1, setRatePer1] = useState(ratePeriod1 ?? "");
  const [rateAmt1, setRateAmt1] = useState(rateAmount1 ?? "");
  const [ratePer2, setRatePer2] = useState(ratePeriod2 ?? "");
  const [rateAmt2, setRateAmt2] = useState(rateAmount2 ?? "");
  // Appendix 1 Schedule 1 — editable Phase-2 deliverables period, pre-filled.
  const [delivPeriod, setDelivPeriod] = useState(phase2DeliverablePeriod ?? "");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const selectedIds = REVISABLE_SECTIONS.filter((s) => picked[s.id]).map((s) => s.id);
  // Build U — ACH changed vs the stored values (trim-compared, mirrors the
  // backend). A revision is valid with a ticked section OR an ACH correction;
  // when ACH changes the backend auto-scopes appendix2 for the participant.
  const achChanged = achDates.trim() !== (achDebitDates ?? "").trim()
    || achAmounts.trim() !== (achDebitAmounts ?? "").trim();
  // Build W — rate schedule changed vs stored (trim-compared, mirrors backend).
  // When it changes the backend auto-scopes the main agreement (Section 11
  // carries the rate card) for the participant to re-review + re-sign.
  const rateChanged = ratePer1.trim() !== (ratePeriod1 ?? "").trim()
    || rateAmt1.trim() !== (rateAmount1 ?? "").trim()
    || ratePer2.trim() !== (ratePeriod2 ?? "").trim()
    || rateAmt2.trim() !== (rateAmount2 ?? "").trim();
  // Appendix 1 deliverables period changed vs stored. When it changes the
  // backend auto-scopes Appendix 1 for the participant to re-review + re-sign.
  const delivChanged =
    delivPeriod.trim() !== (phase2DeliverablePeriod ?? "").trim();
  const canSubmit =
    (selectedIds.length > 0 || achChanged || rateChanged || delivChanged) && !busy;

  const toggle = (id: string) =>
    setPicked((p) => ({ ...p, [id]: !p[id] }));
  const setNote = (id: string, v: string) =>
    setNotes((n) => ({ ...n, [id]: v }));

  const submit = async () => {
    if (!canSubmit) return;
    setBusy(true);
    setError("");
    try {
      const sections = selectedIds.map((id) => {
        const note = (notes[id] ?? "").trim();
        return note ? { key: id, note } : { key: id };
      });
      await webAgreementRequestRevision(
        appId,
        sections,
        {
          achDebitDates: achDates,
          achDebitAmounts: achAmounts,
        },
        {
          ratePeriod1: ratePer1,
          rateAmount1: rateAmt1,
          ratePeriod2: ratePer2,
          rateAmount2: rateAmt2,
        },
        { phase2DeliverablePeriod: delivPeriod },
      );
      await onDone();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't send revision request.");
      setBusy(false);
    }
  };

  return (
    <ModalShell
      title="Request revision"
      subtitle="Pick the section(s) to send back. The participant will be restricted to only these."
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
      <p className="text-[11px] text-gray-500 mb-2">
        Tick the missing/wrong section(s). A note is optional — you can send
        a revision with no typed text at all.
      </p>
      <div className="space-y-2 max-h-[46vh] overflow-y-auto pr-1">
        {REVISABLE_SECTIONS.map((s) => {
          const on = Boolean(picked[s.id]);
          return (
            <div
              key={s.id}
              className={
                "rounded-md border px-3 py-2 " +
                (on ? "border-sage-navy bg-sage-navy/5" : "border-gray-200 bg-white")
              }
            >
              <label className="flex items-start gap-2 cursor-pointer">
                <input
                  type="checkbox"
                  checked={on}
                  onChange={() => toggle(s.id)}
                  disabled={busy}
                  className="mt-0.5 h-4 w-4 accent-sage-navy"
                />
                <span className="text-sm font-medium text-gray-800">{s.title}</span>
              </label>
              {on && (
                <input
                  type="text"
                  value={notes[s.id] ?? ""}
                  onChange={(e) => setNote(s.id, e.target.value.slice(0, 500))}
                  disabled={busy}
                  placeholder="Optional note for this section…"
                  className="mt-2 w-full px-2.5 py-1.5 text-[13px] rounded-md border border-gray-200 focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy"
                />
              )}
            </div>
          );
        })}
      </div>
      {/* Build U — correct the ERM-set ACH debit schedule within the revision.
          Pre-filled, free-text; editing sends the corrected values, which the
          participant re-reviews (read-only) in Appendix 2 and re-signs over. */}
      <div className="mt-4 rounded-md border border-gray-200 bg-white px-3 py-2.5">
        <p className="text-sm font-medium text-gray-800">ACH debit schedule</p>
        <p className="text-[11px] text-gray-500 mb-2">
          Correct the debit date(s) / amount(s) if they changed. The participant
          re-reviews Appendix 2 (read-only) and re-signs over the corrected schedule.
        </p>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
          <label className="block">
            <span className="text-[11px] text-gray-600">Debit date(s)</span>
            <input
              type="text"
              value={achDates}
              onChange={(e) => setAchDates(e.target.value.slice(0, 200))}
              disabled={busy}
              placeholder="e.g. 1st and 15th"
              className="mt-1 w-full px-2.5 py-1.5 text-[13px] rounded-md border border-gray-200 focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy"
            />
          </label>
          <label className="block">
            <span className="text-[11px] text-gray-600">Debit amount(s)</span>
            <input
              type="text"
              value={achAmounts}
              onChange={(e) => setAchAmounts(e.target.value.slice(0, 200))}
              disabled={busy}
              placeholder="e.g. $2,500"
              className="mt-1 w-full px-2.5 py-1.5 text-[13px] rounded-md border border-gray-200 focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy"
            />
          </label>
        </div>
        {achChanged && (
          <p className="mt-2 text-[11px] text-sage-copper-deep">
            Appendix 2 will be added to the revision so the participant re-reviews
            the corrected schedule.
          </p>
        )}
      </div>
      {/* Build W — correct the ERM-set Phase-2 Rate Schedule within the revision.
          Pre-filled, free-text; editing sends the corrected values, which the
          participant re-reviews (read-only) in the main agreement (Section 11)
          and re-signs over. */}
      <div className="mt-3 rounded-md border border-gray-200 bg-white px-3 py-2.5">
        <p className="text-sm font-medium text-gray-800">Rate schedule</p>
        <p className="text-[11px] text-gray-500 mb-2">
          Correct the rate period(s) / amount(s) if they changed. The participant
          re-reviews the main agreement (read-only) and re-signs over the
          corrected rate.
        </p>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
          <label className="block">
            <span className="text-[11px] text-gray-600">Rate period 1</span>
            <input
              type="text"
              value={ratePer1}
              onChange={(e) => setRatePer1(e.target.value.slice(0, 200))}
              disabled={busy}
              placeholder="e.g. Months 1–3"
              className="mt-1 w-full px-2.5 py-1.5 text-[13px] rounded-md border border-gray-200 focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy"
            />
          </label>
          <label className="block">
            <span className="text-[11px] text-gray-600">Amount 1</span>
            <input
              type="text"
              value={rateAmt1}
              onChange={(e) => setRateAmt1(e.target.value.slice(0, 200))}
              disabled={busy}
              placeholder="e.g. $40/hr"
              className="mt-1 w-full px-2.5 py-1.5 text-[13px] rounded-md border border-gray-200 focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy"
            />
          </label>
          <label className="block">
            <span className="text-[11px] text-gray-600">Rate period 2</span>
            <input
              type="text"
              value={ratePer2}
              onChange={(e) => setRatePer2(e.target.value.slice(0, 200))}
              disabled={busy}
              placeholder="e.g. Months 4+"
              className="mt-1 w-full px-2.5 py-1.5 text-[13px] rounded-md border border-gray-200 focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy"
            />
          </label>
          <label className="block">
            <span className="text-[11px] text-gray-600">Amount 2</span>
            <input
              type="text"
              value={rateAmt2}
              onChange={(e) => setRateAmt2(e.target.value.slice(0, 200))}
              disabled={busy}
              placeholder="e.g. $55/hr"
              className="mt-1 w-full px-2.5 py-1.5 text-[13px] rounded-md border border-gray-200 focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy"
            />
          </label>
        </div>
        {rateChanged && (
          <p className="mt-2 text-[11px] text-sage-copper-deep">
            The main agreement will be added to the revision so the participant
            re-reviews the corrected rate and re-signs.
          </p>
        )}
      </div>
      {/* Appendix 1 Schedule 1 — correct the Phase-2 deliverables period within
          the revision. Pre-filled, free-text; the participant re-reviews
          Appendix 1 (read-only) and re-signs over the corrected schedule. */}
      <div className="mt-3 rounded-md border border-gray-200 bg-white px-3 py-2.5">
        <p className="text-sm font-medium text-gray-800">
          Phase 2 deliverables period
        </p>
        <p className="text-[11px] text-gray-500 mb-2">
          The single Month / Period value in Appendix 1&apos;s deliverables
          table. The participant re-reviews Appendix 1 (read-only) and re-signs.
        </p>
        <label className="block">
          <span className="text-[11px] text-gray-600">Month / Period</span>
          <input
            type="text"
            value={delivPeriod}
            onChange={(e) => setDelivPeriod(e.target.value.slice(0, 200))}
            disabled={busy}
            placeholder="e.g. Months 1-12"
            className="mt-1 w-full px-2.5 py-1.5 text-[13px] rounded-md border border-gray-200 focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy"
          />
        </label>
        {delivChanged && (
          <p className="mt-2 text-[11px] text-sage-copper-deep">
            Appendix 1 will be added to the revision so the participant re-reviews
            the corrected deliverables period and re-signs.
          </p>
        )}
      </div>
      <p className="mt-2 text-[11px] text-gray-500">
        {selectedIds.length} section{selectedIds.length === 1 ? "" : "s"} selected
        {achChanged ? " · ACH schedule edited" : ""}
        {rateChanged ? " · Rate schedule edited" : ""}
        {delivChanged ? " · Deliverables period edited" : ""}.
      </p>
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
  // Build W — MM-DD-YYYY (+ HH:mm) for consistency across the agreement.
  return formatUsDateTime(iso) || "—";
}
