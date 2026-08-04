"use client";

import { useEffect, useMemo, useState, type ReactNode } from "react";
import {
  AlertCircle,
  ArrowUpRight,
  Ban,
  CheckCircle2,
  ClipboardCheck,
  ChevronDown,
  ChevronRight,
  Clock,
  Copy,
  Download,
  Eye,
  EyeOff,
  ExternalLink,
  FileText,
  Globe,
  Link2,
  Loader2,
  Mail,
  MessageSquare,
  PenLine,
  Pencil,
  ShieldAlert,
  Undo2,
  Upload,
  X,
} from "lucide-react";

import SignaturePad from "@/components/common/SignaturePad";
import {
  cancelConsultantApplication,
  ermAdvanceToPhase2,
  ermApproveAndSign,
  ermApproveConsultantVersion,
  ermRequestRevision,
  ermRequestSignatureRevision,
  ermRequestDocumentRevision,
  ermRevokeRevision,
  ermSendForApproval,
  ermFetchEligibleApprovers,
  type EligibleApprovers,
  ermFetchAgreementVersions,
  fetchAgreementVersionPdfBlob,
  type AgreementVersion,
  ermSendPdfToEmail,
  fetchAgreementDocBlob,
  fetchAgreementPdfBlob,
  fetchErmPreviewPdfBlob,
  fetchMe,
  parseChequeList,
  resendConsultantInvite,
  updateConsultantContact,
  type AgreementApproval,
  type ApproverRole,
  type ChequeEntry,
  type ConsultantApplication,
  type ConsultantApplicationDetailEnvelope,
  type Phase2PromotionPayload,
} from "@/lib/api";
import { formatUsDate, formatUsDateTime } from "@/lib/dates";
import { AGREEMENT_SECTIONS } from "@/lib/agreement-sections";
import { describeStatus, statusLabel, type StatusContext } from "@/lib/agreement-status";
import AgreementStatusPill from "./AgreementStatusPill";

/**
 * The header pill and the action-bar badge describe the same row on the same
 * screen, so both resolve their wording from this one context object — they
 * used to disagree outright (pill "Signed by consultant" over a bar badged
 * "Ready for your review").
 *
 * linkExpired is only populated on list + consultant reads (the ERM detail
 * read leaves it null, see ConsultantApplicationService#getForConsultant), so
 * it is a no-op here today; it is passed anyway so this stays correct if the
 * detail read ever starts deriving it.
 */
function statusContextFor(app: ConsultantApplication): StatusContext {
  return {
    consultantCopyReleased: app.consultantCopyReleased,
    phase: app.phase,
    linkExpired: app.linkExpired,
  };
}

// Build Y — sections the ERM can pick in the revision picker (the final
// sign step is always included for the consultant and is not pickable).
const REVISABLE_SECTIONS = AGREEMENT_SECTIONS.filter((s) => s.id !== "review").map(
  (s) => ({ id: s.id, title: s.title }),
);
import AgreementEventTimeline from "./AgreementEventTimeline";

// Build W — derive the displayed work-authorization (custom text when
// the ERM chose "Others") and assemble the US-format billing address
// from the structured columns (fallback to the legacy single block).
function displayWorkAuth(app: ConsultantApplication): string | null {
  const cat = (app.workAuthorizationCategory ?? "").trim();
  if (cat.toLowerCase() === "others") {
    const other = (app.workAuthorizationOther ?? "").trim();
    return other || cat;
  }
  return cat || null;
}

function displayAddress(app: ConsultantApplication): string | null {
  const line1 = (app.addressLine1 ?? "").trim();
  const line2 = (app.addressLine2 ?? "").trim();
  const city = (app.addressCity ?? "").trim();
  const state = (app.addressState ?? "").trim();
  const zip = (app.addressZip ?? "").trim();
  const any = line1 || line2 || city || state || zip;
  if (!any) return (app.residenceAddress ?? "").trim() || null;
  let csz = city;
  if (state) csz = csz ? `${csz}, ${state}` : state;
  if (zip) csz = csz ? `${csz} ${zip}` : zip;
  return [line1, line2, csz].filter((p) => p.length > 0).join(", ");
}

// Build W — resolve a field's displayed value: a compute() override, a
// MM-DD-YYYY date, or the raw column. Returns null when empty.
function resolveFieldValue(
  field: FieldDef,
  app: ConsultantApplication,
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
  /** Build W — render the value as a MM-DD-YYYY date. */
  isDate?: boolean;
  /** Build W — derive the displayed value from the whole app (e.g.
   *  assembled address, effective work-auth) instead of one column. */
  compute?: (app: ConsultantApplication) => string | null;
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
        key: "residenceAddress",
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
      { key: "bgCurrentAddress", label: "Current address", wide: true },
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
      { key: "portalPlatform", label: "Platform" },
      { key: "portalUsername", label: "Username" },
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

type ModalKind = null | "revision" | "signatureRevision" | "approve" | "send" | "editContact" | "advancePhase2" | "sendApproval";

export default function ConsultantDetailView({ detail, onRefresh }: Props) {
  const { application: app, events } = detail;
  const [modal, setModal] = useState<ModalKind>(null);
  // Build AK — the per-document "Request re-upload" target (doc key + label),
  // set from a document card; null hides the modal.
  const [docRevisionTarget, setDocRevisionTarget] = useState<{
    key: string;
    label: string;
  } | null>(null);
  const [busy, setBusy] = useState<
    "resend" | "cancel" | "releaseConsultant" | "sendApproval" | "revokeRevision" | null
  >(null);
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
  // Build AK — the states from which the ERM may request a document re-upload
  // (mirrors the backend guard). Outside these, the per-card button is hidden.
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

  /**
   * Build T — release the consultant-version PDF (consultant
   * signatures only, with appended Certificate of Completion).
   * Does NOT countersign; status stays VERIFIED.
   */
  const handleApproveConsultantVersion = async () => {
    if (!confirm(
      "Release the consultant's current content as a consultant-version PDF "
      + "(with Certificate of Completion)? This creates the next numbered "
      + "version (V1, V2, …) for audit/records and re-notifies the consultant; "
      + "it does not countersign the agreement.",
    )) {
      return;
    }
    setBusy("releaseConsultant");
    setError("");
    try {
      await ermApproveConsultantVersion(app.applicationId);
      setFeedback("Consultant version released. The consultant now sees their agreement as Verified and will be notified of any revision.");
      await onRefresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't release consultant version");
    } finally {
      setBusy(null);
    }
  };

  /**
   * Build AQ — take back a change request that went out by mistake. The
   * agreement returns to the desk it was revised from and everything the
   * request cleared (affirmations, signatures, documents) is restored.
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
        + `${reverts.join(", ")} — to the value the consultant signed.`
      : "";
    if (
      !confirm(
        `Take back this change request?\n\nThe agreement returns to `
        + `“${landsOn}” with the consultant's submission restored, and they'll `
        + `be emailed that the request was withdrawn.${alsoReverts}`,
      )
    ) {
      return;
    }
    setBusy("revokeRevision");
    setError("");
    try {
      await ermRevokeRevision(app.applicationId);
      setFeedback(
        `Change request withdrawn. The agreement is back at “${landsOn}”`
        + (reverts.length ? `, ${reverts.join(", ")} reverted,` : "")
        + " and the consultant has been notified.",
      );
      await onRefresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't take back the request");
    } finally {
      setBusy(null);
    }
  };

  /**
   * 3B — route the consultant-signed agreement to the phase's required
   * approvers (Phase 1 = Manager; Phase 2 = Manager + Accounts). Also the
   * re-send action from APPROVAL_REVISION_REQUESTED (resets the gates).
   */
  const handleSendForApproval = async (routing: {
    managerUserId?: string;
    accountsUserId?: string;
    versionNumber?: number;
  }) => {
    setBusy("sendApproval");
    setError("");
    try {
      await ermSendForApproval(app.applicationId, routing);
      setModal(null);
      setFeedback("Sent for approval.");
      await onRefresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't send for approval");
      throw e;
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
        approvals={detail.approvals ?? []}
        onRequestRevision={() => setModal("revision")}
        onRequestSignatureRevision={() => setModal("signatureRevision")}
        onApproveAndSign={() => setModal("approve")}
        onApproveConsultantVersion={handleApproveConsultantVersion}
        onSendForApproval={() => setModal("sendApproval")}
        onSendEmail={() => setModal("send")}
        onResendInvite={handleResend}
        onCancel={handleCancel}
        onRevokeRevision={handleRevokeRevision}
        resendBusy={busy === "resend"}
        cancelBusy={busy === "cancel"}
        releaseConsultantBusy={busy === "releaseConsultant"}
        sendApprovalBusy={busy === "sendApproval"}
        revokeRevisionBusy={busy === "revokeRevision"}
        isLocked={isLocked}
        onAdvanceToPhase2={() => setModal("advancePhase2")}
      />

      <ContactActionsBar
        status={status}
        onEditContact={() => setModal("editContact")}
        onResend={handleResend}
        onCopyLink={handleCopyLink}
        resendBusy={busy === "resend"}
      />

      <ErmFilledCard app={app} />

      {(status === "VERIFIED"
        || status === "AWAITING_APPROVALS"
        || status === "APPROVAL_REVISION_REQUESTED"
        || status === "READY_TO_SIGN") && (
        <ErmPdfPreview appId={app.applicationId} />
      )}

      <ConsultantSections
        app={app}
        onRequestReupload={
          canRequestReupload
            ? (key, label) => setDocRevisionTarget({ key, label })
            : undefined
        }
      />

      <SignaturesPreview app={app} />

      <VersionHistory app={app} />

      <AccessRecord app={app} />

      <ConsultantDownloadStat app={app} />

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
            setFeedback("Revision requested. Consultant notified.");
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
            setFeedback("Signature re-sign requested. Consultant notified.");
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
            setFeedback("Document re-upload requested. Consultant notified.");
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
      {modal === "advancePhase2" && (
        <AdvanceToPhase2Modal
          app={app}
          onClose={() => setModal(null)}
          onDone={async () => {
            setModal(null);
            setFeedback(
              "Advanced to Phase 2. The consultant has been notified.",
            );
            await onRefresh();
          }}
        />
      )}

      {modal === "sendApproval" && (
        <SendForApprovalModal
          app={app}
          busy={busy === "sendApproval"}
          onClose={() => setModal(null)}
          onSend={handleSendForApproval}
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
    iso ? formatUsDateTime(iso) : "—";
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
 * 3B — per-approver gate badges for the CURRENT round (Manager / Accounts),
 * shown in the ERM action bar so each gate's status is visible separately.
 */
function ApproverBadges({ approvals }: { approvals: AgreementApproval[] }) {
  if (!approvals || approvals.length === 0) return null;
  const maxRound = approvals.reduce((m, a) => Math.max(m, a.round), 0);
  const current = approvals
    .filter((a) => a.round === maxRound)
    .slice()
    .sort((a, b) => {
      const order: ApproverRole[] = ["MANAGER", "ACCOUNTS"];
      return order.indexOf(a.role) - order.indexOf(b.role);
    });
  if (current.length === 0) return null;
  return (
    <div className="flex flex-wrap gap-2">
      {current.map((a) => {
        const label = a.role === "ACCOUNTS" ? "Accounts" : "Manager";
        const tone =
          a.status === "APPROVED"
            ? "bg-emerald-50 border-emerald-200 text-emerald-700"
            : a.status === "REVISION_REQUESTED"
              ? "bg-orange-50 border-orange-200 text-orange-700"
              : "bg-gray-50 border-gray-200 text-gray-600";
        const word =
          a.status === "APPROVED"
            ? "Approved"
            : a.status === "REVISION_REQUESTED"
              ? "Revision requested"
              : "Pending";
        return (
          <span
            key={a.id}
            className={
              "inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-[11px] font-semibold "
              + tone
            }
          >
            {label}: {word}
            {a.decidedByName && (
              <span className="font-normal text-gray-500">· {a.decidedByName}</span>
            )}
          </span>
        );
      })}
    </div>
  );
}

/**
 * Build AQ — the way out of a change request sent by mistake. Undoes the whole
 * round: the agreement returns to the desk it was revised from, the
 * consultant's submission (affirmations, signatures, documents) is restored,
 * and they're emailed that the request was withdrawn.
 *
 * The server decides whether it is still possible, and it says no in three
 * cases: the consultant has already entered something this round (their
 * answers would be left standing under the restored signature), the round
 * predates this feature so nothing was recorded, or a document request
 * destroyed a legacy Cloudinary file that can't be put back. In every one of
 * them, say why — hiding the button leaves the ERM guessing at a moment they
 * are already trying to fix a mistake.
 */
function RevokeRevisionAction({
  app,
  onRevoke,
  busy,
}: {
  app: ConsultantApplication;
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
        title="Undo this change request — the agreement goes back exactly as the consultant submitted it"
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
            {app.revisionRevokeReverts?.join(", ")}) to the value the consultant
            signed.
          </span>
        </p>
      )}
    </div>
  );
}

// Build AJ — asks the consultant to re-sign with a proper signature (content
// unchanged). Shown alongside "Request revision" in the revisable states.
function SignatureReSignButton({ onClick }: { onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      title="Ask the consultant to re-sign with a proper signature — their details stay unchanged"
      className="inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-xs font-bold border border-sage-navy/30 text-sage-navy hover:bg-sage-navy/5 cursor-pointer"
    >
      <PenLine size={12} /> Signature re-sign
    </button>
  );
}

function StateActionBar({
  status,
  app,
  approvals,
  onRequestRevision,
  onRequestSignatureRevision,
  onApproveAndSign,
  onApproveConsultantVersion,
  onSendForApproval,
  onSendEmail,
  onResendInvite,
  onCancel,
  onRevokeRevision,
  resendBusy,
  cancelBusy,
  releaseConsultantBusy,
  sendApprovalBusy,
  revokeRevisionBusy,
  isLocked,
  onAdvanceToPhase2,
}: {
  status: ConsultantApplication["status"];
  app: ConsultantApplication;
  approvals: AgreementApproval[];
  onRequestRevision: () => void;
  onRequestSignatureRevision: () => void;
  onApproveAndSign: () => void;
  onApproveConsultantVersion: () => void;
  onSendForApproval: () => void;
  onSendEmail: () => void;
  onResendInvite: () => void;
  onCancel: () => void;
  onRevokeRevision: () => void;
  resendBusy: boolean;
  cancelBusy: boolean;
  releaseConsultantBusy: boolean;
  sendApprovalBusy: boolean;
  revokeRevisionBusy: boolean;
  isLocked: boolean;
  onAdvanceToPhase2: () => void;
}) {
  // One name per state, shared with the header pill. Only the badge comes from
  // here — the body copy and CTAs below are this bar's own operational
  // instructions and stay untouched.
  const badge = describeStatus(status, statusContextFor(app)).label;

  if (status === "SUBMITTED") {
    return (
      <BarShell badge={badge} tone="amber">
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
          The consultant has been emailed your remarks and can re-submit.
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
    const released = Boolean(app.consultantCopyReleased);
    return (
      <BarShell badge={badge} tone="navy">
        <p className="text-xs text-gray-600 max-w-md">
          The consultant signed. Release a consultant-version copy, then send
          the agreement for approval — Phase {app.phase ?? 1} requires{" "}
          {(app.phase ?? 1) >= 2 ? "Manager + Accounts" : "Manager"} sign-off
          before you can countersign. You can also send it back to the
          consultant with revision remarks.
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
          <button
            type="button"
            onClick={onApproveConsultantVersion}
            disabled={releaseConsultantBusy}
            className="inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-xs font-bold border cursor-pointer border-sage-navy/30 text-sage-navy hover:bg-sage-navy/5 disabled:opacity-60 disabled:cursor-not-allowed"
            title={
              released
                ? "Release the consultant's current content as a new version (V2, V3, …)"
                : undefined
            }
          >
            <CheckCircle2 size={12} />
            {releaseConsultantBusy
              ? "Releasing…"
              : released
                ? "Re-approve consultant version"
                : "Approve consultant version"}
          </button>
          <button
            type="button"
            onClick={onSendForApproval}
            disabled={!released || sendApprovalBusy}
            className="inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer shadow-sm"
            title={released ? undefined : "Release the consultant version first"}
          >
            <ClipboardCheck size={12} />
            {sendApprovalBusy ? "Sending…" : "Send for approval"}
          </button>
        </div>
        {released && (
          <p className="text-[11px] text-gray-500">
            A consultant version is already released. If the consultant revised
            after that, <strong>re-approve</strong> to release the updated
            content as a new version, then send the latest for approval.
          </p>
        )}
      </BarShell>
    );
  }

  if (status === "AWAITING_APPROVALS") {
    return (
      <BarShell badge={badge} tone="navy">
        <p className="text-xs text-gray-600 max-w-md">
          Sent to the Phase {app.phase ?? 1} approvers. You can countersign
          once every required approver has approved.
        </p>
        <ApproverBadges approvals={approvals} />
        <div className="flex items-center gap-2 flex-wrap">
          <button
            type="button"
            onClick={onRequestRevision}
            className="inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-xs font-bold border border-sage-copper-deep/40 text-sage-copper-deep hover:bg-sage-copper/5 cursor-pointer"
          >
            <MessageSquare size={12} /> Send to consultant for revision
          </button>
          <SignatureReSignButton onClick={onRequestSignatureRevision} />
        </div>
      </BarShell>
    );
  }

  if (status === "APPROVAL_REVISION_REQUESTED") {
    return (
      <BarShell badge={badge} tone="copper">
        {app.currentRevisionRemarks && (
          <blockquote className="text-sm text-gray-700 italic border-l-4 border-sage-copper-deep pl-3 mt-1">
            {app.currentRevisionRemarks}
          </blockquote>
        )}
        <ApproverBadges approvals={approvals} />
        <p className="text-[11px] text-gray-500 max-w-md">
          Address the note (edit ERM-side details, or send to the consultant),
          then re-send for approval — this resets every required approver to
          pending for a fresh review.
        </p>
        <div className="flex items-center gap-2 flex-wrap">
          <button
            type="button"
            onClick={onSendForApproval}
            disabled={sendApprovalBusy}
            className="inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-50 cursor-pointer shadow-sm"
          >
            <ClipboardCheck size={12} />
            {sendApprovalBusy ? "Re-sending…" : "Re-send for approval"}
          </button>
          <button
            type="button"
            onClick={onRequestRevision}
            className="inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-xs font-bold border border-sage-copper-deep/40 text-sage-copper-deep hover:bg-sage-copper/5 cursor-pointer"
          >
            <MessageSquare size={12} /> Send to consultant
          </button>
          <SignatureReSignButton onClick={onRequestSignatureRevision} />
        </div>
      </BarShell>
    );
  }

  if (status === "READY_TO_SIGN") {
    return (
      <BarShell badge={badge} tone="navy">
        <p className="text-xs text-gray-600 max-w-md">
          All required approvers have approved. Apply your signature to
          generate the final PDF and complete the agreement.
        </p>
        <ApproverBadges approvals={approvals} />
        <div className="flex items-center gap-2 flex-wrap">
          <button
            type="button"
            onClick={onRequestRevision}
            className="inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-xs font-bold border border-sage-copper-deep/40 text-sage-copper-deep hover:bg-sage-copper/5 cursor-pointer"
          >
            <MessageSquare size={12} /> Request revision
          </button>
          <SignatureReSignButton onClick={onRequestSignatureRevision} />
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
    return (
      <CompletedActions
        app={app}
        onSendEmail={onSendEmail}
        onAdvanceToPhase2={onAdvanceToPhase2}
      />
    );
  }

  if (status === "CANCELLED" || status === "EXPIRED") {
    return (
      <BarShell badge={badge} tone="zinc">
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
  onAdvanceToPhase2,
}: {
  app: ConsultantApplication;
  onSendEmail: () => void;
  onAdvanceToPhase2: () => void;
}) {
  const [busy, setBusy] = useState<"view" | "download" | null>(null);
  const [error, setError] = useState("");
  const currentPhase = app.phase ?? 1;

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
    // This is StateActionBar's COMPLETED branch, just extracted — so its badge
    // comes from the same place as every other one. Phase qualifier kept.
    <BarShell
      badge={`${describeStatus(app.status, statusContextFor(app)).label}${
        currentPhase === 2 ? " (Phase 2)" : ""
      }`}
      tone="emerald"
    >
      <p className="text-xs text-gray-600 max-w-md">
        {currentPhase === 2
          ? "Phase 2 PDF generated and emailed."
          : "Final PDF generated and emailed to both parties."}
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
        {currentPhase === 1 && (
          <button
            type="button"
            onClick={onAdvanceToPhase2}
            disabled={busy !== null}
            className="inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-xs font-bold border border-sage-copper-deep/40 text-sage-copper-deep hover:bg-sage-copper/5 disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer"
          >
            <ArrowUpRight size={12} /> Advance to Phase 2
          </button>
        )}
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

// ── Build R — authenticated document open (fixes the 403) ─────────
//
// The erm*ViewUrl endpoints are JWT/role-gated and the ERM bearer lives in
// sessionStorage, so a bare new-tab link (or credentials:"include") never
// carries it. Fetch the bytes WITH the bearer, then open a blob URL. Works
// for S3 + legacy Cloudinary records (the backend dual-reads + streams).
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
      const res = await fetchAgreementDocBlob(applicationId, docPath, "inline");
      if (!res.ok) throw new Error(`Couldn't open the document (${res.status})`);
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      window.open(url, "_blank", "noopener,noreferrer");
      window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
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
  app: ConsultantApplication;
  onRequestReupload?: (docKey: string, docLabel: string) => void;
}) {
  const has = Boolean(app.workAuthDocPublicId || app.workAuthDocS3Key);
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
  app: ConsultantApplication;
  onRequestReupload?: (docKey: string, docLabel: string) => void;
}) {
  if (!app.offerLetterPublicId && !app.offerLetterS3Key) return null;
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

// ── Build V — approved consultant-version history (immutable snapshots) ──
//
// Each row is a numbered snapshot (V1, V2, …) created when the ERM approved a
// consultant version. View-only: the PDF streams through an authenticated
// bearer fetch into a session-local blob URL (never presigned to the client),
// mirroring DocViewButton. The version currently routed for approval is
// flagged. Renders nothing until at least one version exists.
function VersionHistory({ app }: { app: ConsultantApplication }) {
  const [versions, setVersions] = useState<AgreementVersion[] | null>(null);
  const [err, setErr] = useState("");
  const [openingVersion, setOpeningVersion] = useState<number | null>(null);

  useEffect(() => {
    let alive = true;
    ermFetchAgreementVersions(app.applicationId)
      .then((rows) => {
        if (alive) setVersions(rows);
      })
      .catch((e) => {
        if (alive) {
          setErr(e instanceof Error ? e.message : "Couldn't load versions.");
        }
      });
    return () => {
      alive = false;
    };
  }, [app.applicationId]);

  const openVersion = async (versionNumber: number) => {
    setOpeningVersion(versionNumber);
    setErr("");
    try {
      const res = await fetchAgreementVersionPdfBlob(
        app.applicationId,
        versionNumber,
      );
      if (!res.ok) {
        throw new Error(`Couldn't open version V${versionNumber} (${res.status})`);
      }
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      window.open(url, "_blank", "noopener,noreferrer");
      window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
    } catch (e) {
      setErr(e instanceof Error ? e.message : "Couldn't open the version.");
    } finally {
      setOpeningVersion(null);
    }
  };

  if (!versions || versions.length === 0) return null;

  return (
    <section className="bg-white rounded-2xl border border-stone-200 shadow-sm overflow-hidden">
      <header className="px-5 sm:px-6 pt-5 pb-3 border-b border-stone-100">
        <h3 className="font-serif text-lg text-gray-900">
          Approved consultant versions
        </h3>
        <p className="text-xs text-gray-500 mt-0.5">
          Immutable snapshots created each time you approved a consultant
          version. View-only.
        </p>
      </header>
      <ul className="divide-y divide-stone-100">
        {versions.map((v) => {
          const isRouted = app.approvalVersionNumber === v.versionNumber;
          return (
            <li
              key={v.versionNumber}
              className="px-5 sm:px-6 py-3 flex items-center justify-between gap-3 flex-wrap"
            >
              <div className="min-w-0">
                <p className="text-sm font-semibold text-sage-navy inline-flex items-center gap-2 flex-wrap">
                  Version V{v.versionNumber}
                  {typeof v.phase === "number" && (
                    <span className="text-[11px] font-medium text-gray-500">
                      Phase {v.phase}
                    </span>
                  )}
                  {isRouted && (
                    <span className="text-[10px] font-semibold uppercase tracking-wide text-emerald-700 bg-emerald-50 border border-emerald-200 rounded px-1.5 py-0.5">
                      Sent for approval
                    </span>
                  )}
                </p>
                <p className="text-[11px] text-gray-500 mt-0.5">
                  Approved{" "}
                  {v.approvedAt ? formatUsDateTime(v.approvedAt) : "—"}
                </p>
              </div>
              <button
                type="button"
                onClick={() => openVersion(v.versionNumber)}
                disabled={openingVersion === v.versionNumber}
                className="inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-xs font-semibold border border-stone-300 bg-white text-sage-navy hover:bg-stone-50 disabled:opacity-50 cursor-pointer"
              >
                <FileText size={12} />{" "}
                {openingVersion === v.versionNumber ? "Opening…" : "View version"}
              </button>
            </li>
          );
        })}
      </ul>
      {err && (
        <p className="px-5 sm:px-6 pb-3 text-[11px] text-sage-copper-deep">{err}</p>
      )}
    </section>
  );
}

// ── Consultant-filled sections (read-only) ────────────────────

function ConsultantSections({
  app,
  onRequestReupload,
}: {
  app: ConsultantApplication;
  // Build AK — present only in a revisable state; each document card renders a
  // "Request re-upload" button that opens the scoped re-upload modal.
  onRequestReupload?: (docKey: string, docLabel: string) => void;
}) {
  return (
    <div className="space-y-4">
      {SECTIONS.map((section) => (
        <ConsultantSectionCard key={section.id} section={section} app={app} />
      ))}
      <WorkAuthDocCard app={app} onRequestReupload={onRequestReupload} />
      <OfferLetterCard app={app} onRequestReupload={onRequestReupload} />
      <UploadedDocCard
        title="Driver's License document"
        uploaded={Boolean(app.dlDocPublicId || app.dlDocS3Key)}
        uploadedAt={app.dlDocUploadedAt}
        applicationId={app.applicationId}
        docPath="/dl-doc"
        docKey="doc:dl-doc"
        onRequestReupload={onRequestReupload}
      />
      <UploadedDocCard
        title="State ID document"
        uploaded={Boolean(app.stateIdDocPublicId || app.stateIdDocS3Key)}
        uploadedAt={app.stateIdDocUploadedAt}
        applicationId={app.applicationId}
        docPath="/state-id-doc"
        docKey="doc:state-id"
        onRequestReupload={onRequestReupload}
      />
      <UploadedDocCard
        title="SSN document"
        uploaded={Boolean(app.ssnDocPublicId || app.ssnDocS3Key)}
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
 * handled by the modal → {@link ermRequestDocumentRevision}.
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
      title="Ask the consultant to replace this document"
      className="inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-xs font-semibold border border-sage-copper/40 bg-white text-sage-copper-deep hover:bg-[#FBF1E8] cursor-pointer"
    >
      <Upload size={12} /> Request re-upload
    </button>
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
          // The backend puts the render's actual failure (exception class +
          // message) on X-Preview-Error. Surfacing it in the console turns a
          // bare "(500)" into something diagnosable from the browser, without
          // putting server internals on screen for the ERM.
          const detail = res.headers.get("X-Preview-Error");
          if (detail) {
            console.error(`Preview render failed on the server — ${detail}`);
          }
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
 * Build G — inline view + download of the consultant's Appendix 5
 * cheque. Bytes are streamed through the backend (re-signs the
 * Cloudinary URL each call), wrapped in a blob URL for the open/
 * download action -- the raw Cloudinary URL never reaches the DOM.
 */
function SecurityChequeCard({
  app,
  onRequestReupload,
}: {
  app: ConsultantApplication;
  onRequestReupload?: (docKey: string, docLabel: string) => void;
}) {
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState("");
  // Build U — pull the cheque list from the JSON column, falling back
  // to the legacy single cheque as index 0 (handled by parseChequeList
  // on the backend before serialization; mirror it here).
  // Build AO — the consultant's DECLARED cheque count (0 = not set).
  const chequeCount = useMemo(() => {
    const n = parseInt(String(app.securityCheckCount ?? "").trim(), 10);
    return Number.isFinite(n) && n > 0 ? Math.min(n, 50) : 0;
  }, [app.securityCheckCount]);

  const entries = useMemo<ChequeEntry[]>(() => {
    const parsed = parseChequeList(app.cheques ?? null);
    // Build AO — cap to the declared count so stale over-clicked entries the
    // consultant left behind (after reducing the count) don't render here.
    const capped = chequeCount > 0 ? parsed.filter((e) => e.index < chequeCount) : parsed;
    if (capped.length > 0) return capped;
    if (app.chequePublicId || app.chequeS3Key) {
      return [{
        index: 0,
        number: "",
        date: "",
        publicId: app.chequePublicId ?? "",
        s3Key: app.chequeS3Key ?? "",
        contentType: app.chequeContentType ?? "",
        uploadedAt: app.chequeUploadedAt ?? "",
      }];
    }
    return [];
  }, [app.cheques, chequeCount, app.chequePublicId, app.chequeS3Key, app.chequeContentType, app.chequeUploadedAt]);

  const uploadedCount = entries.filter((e) => e.publicId || e.s3Key).length;

  const handleAction = async (entry: ChequeEntry, mode: "view" | "download") => {
    const key = `${entry.index}-${mode}`;
    setBusy(key);
    setError("");
    try {
      // Index-aware ERM endpoint — works for the new per-cheque storage
      // and falls back to the legacy single-cheque path at index 0 via
      // the backend's parseCheques. Build R — bearer-authenticated (the
      // sessionStorage token isn't a cookie, so credentials:"include" 403'd).
      const res = await fetchAgreementDocBlob(
        app.applicationId,
        `/cheques/${entry.index}`,
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
        a.download =
          `SageITCO-Cheque-${entry.index + 1}_${app.applicationId}`
          + extensionForBlobType(blob.type);
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
          <h3 className="font-serif text-lg text-gray-900">Security cheques</h3>
          <p className="text-[11px] text-gray-500 mt-0.5">
            Per-cheque uploads the consultant provided for Appendix 5.
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
            The consultant has not uploaded any cheques yet.
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

function ConsultantSectionCard({
  section,
  app,
}: {
  section: SectionDef;
  app: ConsultantApplication;
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
              app.ermSignatureDate
                ? `Counter-signed ${fmtDateTime(app.ermSignatureDate)}`
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

/**
 * Build U — quick stat surfacing how many times the consultant has
 * downloaded their consultant-version PDF. Lives OUTSIDE the
 * collapsed Activity section so the ERM sees it at a glance; the
 * full per-download event rows (with IP/time) live inside the
 * timeline when the ERM expands it.
 */
function ConsultantDownloadStat({ app }: { app: ConsultantApplication }) {
  const count = app.consultantDownloadCount ?? 0;
  if (!app.consultantCopyReleased && count === 0) return null;
  const fmt = (iso: string | null | undefined) =>
    iso ? formatUsDateTime(iso) : null;
  const last = fmt(app.consultantLastDownloadedAt);
  return (
    <div className="rounded-xl border border-gray-100 bg-white px-4 py-3 inline-flex items-start gap-2.5">
      <Download size={14} className="text-sage-navy mt-0.5 shrink-0" />
      <div>
        <p className="text-xs font-semibold text-sage-navy">
          Consultant downloads:{" "}
          <span className="font-mono tabular-nums">{count}</span>
        </p>
        <p className="text-[11px] text-gray-500 mt-0.5">
          {count === 0
            ? "Consultant has not downloaded their copy yet."
            : last
              ? `Last download ${last}.`
              : "Counted on every OTP-verified download."}
        </p>
      </div>
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

/**
 * Build M — ERM advances a Phase-1 COMPLETED agreement to Phase 2.
 * Lists the five appendices + the SSN flag as checkboxes; each
 * checkbox is pre-checked when the section is currently optional
 * (those are the typical candidates for promotion). Confirm calls
 * the backend endpoint, which flips the chosen require_* flags,
 * preserves filled data, clears signatures + affirmations, and
 * transitions COMPLETED → SUBMITTED.
 */
// Build K — directed approval routing. The ERM picks one Manager (Phase 1
// + 2) and one Accounts (Phase 2) from their assigned set: a dropdown when
// several are assigned, a pre-selected read-only row when exactly one, and a
// blocking message when none. A selection is required to send.
function SendForApprovalModal({
  app,
  busy,
  onClose,
  onSend,
}: {
  app: ConsultantApplication;
  busy: boolean;
  onClose: () => void;
  onSend: (routing: {
    managerUserId?: string;
    accountsUserId?: string;
    versionNumber?: number;
  }) => Promise<void>;
}) {
  const [eligible, setEligible] = useState<EligibleApprovers | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");
  const [managerId, setManagerId] = useState("");
  const [accountsId, setAccountsId] = useState("");
  const [sendError, setSendError] = useState("");
  // Build V — the approved consultant versions to choose from. The selected
  // version is the frozen snapshot the approver(s) review. Defaults to the
  // latest; null/empty means no version exists (approver sees the current
  // consultant-signed state — the legacy live-render fallback).
  const [versions, setVersions] = useState<AgreementVersion[]>([]);
  const [selectedVersion, setSelectedVersion] = useState<number | "">("");

  useEffect(() => {
    let cancelled = false;
    ermFetchEligibleApprovers(app.applicationId)
      .then((e) => {
        if (cancelled) return;
        setEligible(e);
        if (e.managers.length === 1) setManagerId(e.managers[0].id);
        if (e.accounts.length === 1) setAccountsId(e.accounts[0].id);
      })
      .catch((err) =>
        setLoadError(err instanceof Error ? err.message : "Couldn't load approvers."),
      )
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, [app.applicationId]);

  useEffect(() => {
    let cancelled = false;
    ermFetchAgreementVersions(app.applicationId)
      .then((rows) => {
        if (cancelled) return;
        setVersions(rows);
        if (rows.length > 0) {
          setSelectedVersion(rows[rows.length - 1].versionNumber); // latest
        }
      })
      .catch(() => {
        /* non-fatal — fall back to no explicit version (live render) */
      });
    return () => {
      cancelled = true;
    };
  }, [app.applicationId]);

  const phase2 = (eligible?.phase ?? 1) >= 2;
  const managerBlocked = !!eligible && eligible.managers.length === 0;
  const accountsBlocked = phase2 && !!eligible && eligible.accounts.length === 0;
  const canSend =
    !loading
    && !loadError
    && !managerBlocked
    && !accountsBlocked
    && managerId.length > 0
    && (!phase2 || accountsId.length > 0);

  const renderPicker = (
    label: string,
    options: { id: string; name: string; email: string }[],
    value: string,
    setter: (v: string) => void,
    roleWord: string,
  ) => {
    if (options.length === 0) {
      return (
        <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2.5 text-[12px] text-red-700 inline-flex items-start gap-1.5">
          <AlertCircle size={13} className="mt-0.5 shrink-0" />
          <span>No {roleWord} assigned — ask an admin to assign one.</span>
        </div>
      );
    }
    if (options.length === 1) {
      const only = options[0];
      return (
        <div>
          <p className="text-[11px] font-semibold uppercase tracking-wider text-sage-navy mb-1">{label}</p>
          <div className="rounded-md border border-stone-200 bg-stone-50 px-3 py-2.5">
            <p className="text-[13px] font-medium text-gray-900">{only.name}</p>
            <p className="text-[11px] text-gray-500">{only.email}</p>
          </div>
        </div>
      );
    }
    return (
      <div>
        <p className="text-[11px] font-semibold uppercase tracking-wider text-sage-navy mb-1">{label}</p>
        <select
          value={value}
          onChange={(e) => setter(e.target.value)}
          className="w-full px-3 py-2.5 text-[14px] rounded-md border border-stone-300 bg-white focus:outline-none focus:ring-2 focus:ring-sage-copper/40"
        >
          <option value="">Select a {roleWord}…</option>
          {options.map((o) => (
            <option key={o.id} value={o.id}>
              {o.name} ({o.email})
            </option>
          ))}
        </select>
      </div>
    );
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 px-4">
      <div className="w-full max-w-md bg-white rounded-2xl shadow-xl overflow-hidden">
        <div className="px-5 sm:px-6 pt-5 pb-3 border-b border-gray-100 flex items-start justify-between">
          <h3 className="font-serif text-lg text-gray-900">Send for approval</h3>
          <button type="button" onClick={onClose} className="text-gray-400 hover:text-gray-600 cursor-pointer">
            <X size={18} />
          </button>
        </div>
        <div className="px-5 sm:px-6 py-4 space-y-4">
          {loading ? (
            <div className="py-8 flex justify-center text-gray-400">
              <Loader2 size={20} className="animate-spin" />
            </div>
          ) : loadError ? (
            <p className="text-[12px] text-red-600 inline-flex items-center gap-1">
              <AlertCircle size={12} /> {loadError}
            </p>
          ) : (
            <>
              <p className="text-[12px] text-gray-500">
                Route this agreement to {phase2 ? "a Manager and an Accounts approver" : "a Manager"}.
                {phase2 ? " Both must approve." : ""}
              </p>
              {versions.length > 1 ? (
                <div>
                  <p className="text-[11px] font-semibold uppercase tracking-wider text-sage-navy mb-1">
                    Version to review
                  </p>
                  <select
                    value={selectedVersion === "" ? "" : String(selectedVersion)}
                    onChange={(e) =>
                      setSelectedVersion(
                        e.target.value === "" ? "" : Number(e.target.value),
                      )
                    }
                    className="w-full px-3 py-2.5 text-[14px] rounded-md border border-stone-300 bg-white focus:outline-none focus:ring-2 focus:ring-sage-copper/40"
                  >
                    {versions.map((v) => (
                      <option key={v.versionNumber} value={v.versionNumber}>
                        Version V{v.versionNumber}
                        {typeof v.phase === "number" ? ` · Phase ${v.phase}` : ""}
                        {v.approvedAt ? ` · ${formatUsDate(v.approvedAt)}` : ""}
                      </option>
                    ))}
                  </select>
                  <p className="text-[11px] text-gray-400 mt-1">
                    The approver(s) review this frozen version. Defaults to the
                    latest (the version you just released).
                  </p>
                </div>
              ) : versions.length === 1 ? (
                <div>
                  <p className="text-[11px] font-semibold uppercase tracking-wider text-sage-navy mb-1">
                    Version to review
                  </p>
                  <div className="rounded-md border border-stone-200 bg-stone-50 px-3 py-2.5">
                    <p className="text-[13px] font-medium text-gray-900">
                      Version V{versions[0].versionNumber}
                    </p>
                    <p className="text-[11px] text-gray-500">
                      Approved{" "}
                      {versions[0].approvedAt
                        ? formatUsDate(versions[0].approvedAt)
                        : "—"}
                    </p>
                  </div>
                </div>
              ) : null}
              {renderPicker("Manager", eligible?.managers ?? [], managerId, setManagerId, "manager")}
              {phase2 &&
                renderPicker("Accounts", eligible?.accounts ?? [], accountsId, setAccountsId, "accounts")}
            </>
          )}
          {sendError && (
            <p className="text-[12px] text-red-600 inline-flex items-center gap-1">
              <AlertCircle size={12} /> {sendError}
            </p>
          )}
        </div>
        <div className="px-5 sm:px-6 py-3 border-t border-gray-100 flex justify-end gap-2">
          <button
            type="button"
            onClick={onClose}
            className="px-3 py-2 rounded-md text-xs font-semibold border border-gray-200 bg-white hover:bg-gray-50 text-gray-700 cursor-pointer"
          >
            Cancel
          </button>
          <button
            type="button"
            disabled={!canSend || busy}
            onClick={() => {
              setSendError("");
              void onSend({
                managerUserId: managerId,
                accountsUserId: phase2 ? accountsId : undefined,
                versionNumber:
                  selectedVersion === "" ? undefined : selectedVersion,
              }).catch((e) =>
                setSendError(
                  e instanceof Error ? e.message : "Couldn't send for approval.",
                ),
              );
            }}
            className="inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-xs font-semibold bg-sage-navy text-white hover:bg-sage-navy-deep cursor-pointer disabled:opacity-50"
          >
            {busy ? <Loader2 size={12} className="animate-spin" /> : <CheckCircle2 size={12} />}
            Send for approval
          </button>
        </div>
      </div>
    </div>
  );
}

function AdvanceToPhase2Modal({
  app,
  onClose,
  onDone,
}: {
  app: ConsultantApplication;
  onClose: () => void;
  onDone: () => Promise<void>;
}) {
  // Currently-optional sections are the default selection; sections
  // already required in Phase 1 stay required and shouldn't be
  // toggleable to "unrequired" via this dialog.
  const initial = {
    appendix1: !app.requireAppendix1,
    appendix2: !app.requireAppendix2,
    appendix3: !app.requireAppendix3,
    appendix4: !app.requireAppendix4,
    appendix5: !app.requireAppendix5,
    ssn: !app.requireSsn,
  };
  const [promote, setPromote] = useState<Phase2PromotionPayload>(initial);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const rows: Array<{
    key: keyof Phase2PromotionPayload;
    label: string;
    alreadyRequired: boolean;
  }> = [
    { key: "appendix1", label: "Appendix 1 — Employment Confirmation", alreadyRequired: Boolean(app.requireAppendix1) },
    { key: "appendix2", label: "Appendix 2 — ACH Payment Authorization", alreadyRequired: Boolean(app.requireAppendix2) },
    { key: "appendix3", label: "Appendix 3 — Background Check", alreadyRequired: Boolean(app.requireAppendix3) },
    { key: "appendix4", label: "Appendix 4 — Portal & Account Access", alreadyRequired: Boolean(app.requireAppendix4) },
    { key: "appendix5", label: "Appendix 5 — Security Cheque", alreadyRequired: Boolean(app.requireAppendix5) },
    { key: "ssn", label: "Require SSN (within Appendix 3)", alreadyRequired: Boolean(app.requireSsn) },
  ];

  const submit = async () => {
    setBusy(true);
    setError("");
    try {
      await ermAdvanceToPhase2(app.applicationId, promote);
      await onDone();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't advance to Phase 2.");
      setBusy(false);
    }
  };

  return (
    <ModalShell
      title="Advance to Phase 2"
      subtitle="Reopens this agreement for the consultant on the same document. Phase-1 data is preserved."
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
            className="inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-xs font-bold bg-sage-copper-deep text-white hover:opacity-90 disabled:opacity-60 cursor-pointer"
          >
            {busy ? <Loader2 size={12} className="animate-spin" /> : <ArrowUpRight size={12} />}
            {busy ? "Advancing…" : "Advance to Phase 2"}
          </button>
        </>
      }
    >
      <p className="text-xs text-gray-600">
        Tick the sections the consultant must now complete. The consultant&apos;s
        previously filled values stay put; both signatures + every section
        affirmation will clear so they re-sign the now-final document.
      </p>
      <ul className="mt-4 space-y-2">
        {rows.map((row) => (
          <li
            key={row.key}
            className={
              "flex items-start gap-2 rounded-md border p-3 "
              + (row.alreadyRequired
                ? "border-gray-200 bg-gray-50"
                : "border-stone-200 bg-white")
            }
          >
            <input
              id={`phase2-${row.key}`}
              type="checkbox"
              disabled={busy || row.alreadyRequired}
              checked={row.alreadyRequired || Boolean(promote[row.key])}
              onChange={(e) =>
                setPromote((prev) => ({ ...prev, [row.key]: e.target.checked }))
              }
              className="mt-0.5 h-3.5 w-3.5 accent-sage-navy"
            />
            <label
              htmlFor={`phase2-${row.key}`}
              className="text-xs text-gray-800 flex-1 cursor-pointer"
            >
              <span className="font-semibold">{row.label}</span>
              {row.alreadyRequired && (
                <span className="block text-[10px] text-gray-500 mt-0.5">
                  Already required in Phase 1 — stays required.
                </span>
              )}
            </label>
          </li>
        ))}
      </ul>
      <p className="mt-4 text-[11px] text-gray-500">
        On confirm: status flips COMPLETED → SUBMITTED, both signatures clear,
        the 15-day invite window restarts, and the consultant gets a no-PDF
        email asking them to complete Phase 2.
      </p>
      {error && (
        <p className="mt-3 inline-flex items-center gap-1.5 text-sm text-red-600">
          <AlertCircle size={14} /> {error}
        </p>
      )}
    </ModalShell>
  );
}

// Build AJ — signature-only revision. Clears the consultant's stored signatures
// and sends the agreement back so they re-sign with a proper signature; all
// content stays as-is. Optional note for the consultant.
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
      await ermRequestSignatureRevision(appId, note.trim() || undefined);
      await onDone();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't request the signature re-sign.");
      setBusy(false);
    }
  };

  return (
    <ModalShell
      title="Request signature re-sign"
      subtitle="Send the agreement back for a fresh signature. The consultant's details stay unchanged — only their signature is cleared and must be re-drawn."
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
          The consultant re-draws <strong>both signatures</strong> (main
          agreement + execution) on their next visit and re-submits. Everything
          else they filled stays exactly as-is.
        </div>
        <div>
          <label className="block text-[11px] font-semibold uppercase tracking-wider text-sage-navy mb-1">
            Note to consultant{" "}
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
 * Build AK — ERM "Request re-upload" for ONE document. Clears that document
 * server-side (so a fresh upload is forced) and bounces the agreement back to
 * the consultant, scoped to just that document. Optional note.
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
      await ermRequestDocumentRevision(appId, [docKey], note.trim() || undefined);
      await onDone();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't request the re-upload.");
      setBusy(false);
    }
  };

  return (
    <ModalShell
      title="Request document re-upload"
      subtitle="Send the agreement back so the consultant uploads a fresh file. Their other details stay unchanged."
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
          consultant must upload a new one before they can resubmit. Everything
          else they filled stays exactly as-is.
        </div>
        <div>
          <label className="block text-[11px] font-semibold uppercase tracking-wider text-sage-navy mb-1">
            Note to consultant{" "}
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
  // a per-section note is optional (never required). The consultant is
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
  // when ACH changes the backend auto-scopes appendix2 for the consultant.
  const achChanged = achDates.trim() !== (achDebitDates ?? "").trim()
    || achAmounts.trim() !== (achDebitAmounts ?? "").trim();
  // Build W — rate schedule changed vs stored (trim-compared, mirrors backend).
  // When it changes the backend auto-scopes the main agreement (Section 11
  // carries the rate card) for the consultant to re-review + re-sign.
  const rateChanged = ratePer1.trim() !== (ratePeriod1 ?? "").trim()
    || rateAmt1.trim() !== (rateAmount1 ?? "").trim()
    || ratePer2.trim() !== (ratePeriod2 ?? "").trim()
    || rateAmt2.trim() !== (rateAmount2 ?? "").trim();
  // Appendix 1 deliverables period changed vs stored. When it changes the
  // backend auto-scopes Appendix 1 for the consultant to re-review + re-sign.
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
      await ermRequestRevision(
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
      subtitle="Pick the section(s) to send back. The consultant will be restricted to only these."
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
          consultant re-reviews (read-only) in Appendix 2 and re-signs over. */}
      <div className="mt-4 rounded-md border border-gray-200 bg-white px-3 py-2.5">
        <p className="text-sm font-medium text-gray-800">ACH debit schedule</p>
        <p className="text-[11px] text-gray-500 mb-2">
          Correct the debit date(s) / amount(s) if they changed. The consultant
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
            Appendix 2 will be added to the revision so the consultant re-reviews
            the corrected schedule.
          </p>
        )}
      </div>
      {/* Build W — correct the ERM-set Phase-2 Rate Schedule within the revision.
          Pre-filled, free-text; editing sends the corrected values, which the
          consultant re-reviews (read-only) in the main agreement (Section 11)
          and re-signs over. */}
      <div className="mt-3 rounded-md border border-gray-200 bg-white px-3 py-2.5">
        <p className="text-sm font-medium text-gray-800">Rate schedule</p>
        <p className="text-[11px] text-gray-500 mb-2">
          Correct the rate period(s) / amount(s) if they changed. The consultant
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
            The main agreement will be added to the revision so the consultant
            re-reviews the corrected rate and re-signs.
          </p>
        )}
      </div>
      {/* Appendix 1 Schedule 1 — correct the Phase-2 deliverables period within
          the revision. Pre-filled, free-text; the consultant re-reviews
          Appendix 1 (read-only) and re-signs over the corrected schedule. */}
      <div className="mt-3 rounded-md border border-gray-200 bg-white px-3 py-2.5">
        <p className="text-sm font-medium text-gray-800">
          Phase 2 deliverables period
        </p>
        <p className="text-[11px] text-gray-500 mb-2">
          The single Month / Period value in Appendix 1&apos;s deliverables
          table. The consultant re-reviews Appendix 1 (read-only) and re-signs.
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
            Appendix 1 will be added to the revision so the consultant re-reviews
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
          suggestedName={ermName}
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
  // Build W — MM-DD-YYYY (+ HH:mm) for consistency across the agreement.
  return formatUsDateTime(iso) || "—";
}

