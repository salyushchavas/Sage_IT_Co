"use client";

import { useState } from "react";
import {
  AlertCircle,
  Ban,
  CheckCircle2,
  Download,
  FileText,
  Loader2,
  Mail,
  Pencil,
} from "lucide-react";

import {
  cancelConsultantApplication,
  resendConsultantInvite,
  updateConsultantApplication,
  type ConsultantApplicationDetailEnvelope,
} from "@/lib/api";
import AgreementStatusPill from "./AgreementStatusPill";
import AgreementEventTimeline from "./AgreementEventTimeline";
import ConsultantForm, { type ConsultantFormValues } from "./ConsultantForm";

interface Props {
  detail: ConsultantApplicationDetailEnvelope;
  onRefresh: () => Promise<void>;
}

export default function ConsultantDetailView({ detail, onRefresh }: Props) {
  const { application: app, events, revisions } = detail;
  const [editing, setEditing] = useState(false);
  const [busy, setBusy] = useState<"resend" | "cancel" | null>(null);
  const [error, setError] = useState("");
  const [feedback, setFeedback] = useState("");

  const isLocked = ["SIGNED", "COMPLETED", "CANCELLED", "EXPIRED"].includes(app.status);

  const lastConsultantView = events
    .filter((e) => e.eventType === "ACCESSED")
    .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())[0];

  const handleResend = async () => {
    setBusy("resend");
    setError("");
    setFeedback("");
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
    setFeedback("");
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

  const handleEditSubmit = async (values: ConsultantFormValues) => {
    let payload: unknown = undefined;
    if (values.payloadJson) {
      try {
        payload = JSON.parse(values.payloadJson);
      } catch {
        throw new Error("Payload is not valid JSON.");
      }
    }
    await updateConsultantApplication(app.applicationId, {
      consultantEmail: values.consultantEmail,
      consultantName: values.consultantName,
      consultantPhone: values.consultantPhone,
      payload,
    });
    setEditing(false);
    await onRefresh();
  };

  let payloadPretty = "";
  if (app.payload) {
    try {
      payloadPretty = JSON.stringify(JSON.parse(app.payload), null, 2);
    } catch {
      payloadPretty = app.payload;
    }
  }

  return (
    <div className="space-y-5">
      <div className="flex items-start justify-between flex-wrap gap-3">
        <div>
          <div className="flex items-center gap-2 flex-wrap">
            <h2 className="text-2xl font-bold text-gray-900">
              {app.consultantName || app.consultantEmail}
            </h2>
            <AgreementStatusPill status={app.status} />
          </div>
          <p className="text-xs font-mono text-gray-500 mt-0.5">{app.applicationId}</p>
          <p className="text-xs text-gray-500 mt-0.5">{app.consultantEmail}</p>
        </div>
        <div className="flex items-center gap-2">
          {!isLocked && !editing && (
            <button
              type="button"
              onClick={() => setEditing(true)}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-bold border border-gray-200 bg-white hover:bg-gray-50 text-gray-700 cursor-pointer"
            >
              <Pencil size={12} /> Edit
            </button>
          )}
          {!isLocked && (
            <button
              type="button"
              onClick={handleResend}
              disabled={busy !== null}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-bold border border-gray-200 bg-white hover:bg-gray-50 text-gray-700 disabled:opacity-50 cursor-pointer"
            >
              {busy === "resend" ? (
                <Loader2 size={12} className="animate-spin" />
              ) : (
                <Mail size={12} />
              )}
              Resend invite
            </button>
          )}
          {!isLocked && (
            <button
              type="button"
              onClick={handleCancel}
              disabled={busy !== null}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-bold text-red-700 border border-red-100 bg-red-50 hover:bg-red-100 disabled:opacity-50 cursor-pointer"
            >
              {busy === "cancel" ? (
                <Loader2 size={12} className="animate-spin" />
              ) : (
                <Ban size={12} />
              )}
              Cancel
            </button>
          )}
        </div>
      </div>

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

      {app.signedPdfUrl && (
        <a
          href={app.signedPdfUrl}
          target="_blank"
          rel="noreferrer"
          className="inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-xs font-bold bg-emerald-600 text-white hover:bg-emerald-700 cursor-pointer w-fit"
        >
          <Download size={12} /> Download signed PDF
        </a>
      )}

      <div className="grid grid-cols-2 sm:grid-cols-4 gap-2 text-xs">
        <SmallStat label="Created" value={formatDateTime(app.createdAt)} />
        <SmallStat label="Updated" value={formatDateTime(app.updatedAt)} />
        <SmallStat label="Expires" value={formatDateTime(app.expiresAt)} />
        <SmallStat
          label="Signed"
          value={app.signedAt ? formatDateTime(app.signedAt) : "—"}
        />
        <SmallStat label="Phone" value={app.consultantPhone || "—"} />
        <SmallStat label="Legal name" value={app.signedLegalName || "—"} />
        <SmallStat label="Signed IP" value={app.signedIp || "—"} />
        <SmallStat label="Revisions" value={String(revisions.length)} />
      </div>

      {lastConsultantView && (
        <div className="rounded-xl border border-sage-navy/20 bg-sage-navy/5 p-3 text-xs text-sage-navy flex items-center gap-2 flex-wrap">
          <span className="font-bold">Last consultant access:</span>
          <span>{formatDateTime(lastConsultantView.createdAt)}</span>
          {lastConsultantView.ipAddress && (
            <span className="font-mono">from {lastConsultantView.ipAddress}</span>
          )}
        </div>
      )}

      {editing ? (
        <Section title="Edit details">
          <ConsultantForm
            initial={{
              consultantEmail: app.consultantEmail,
              consultantName: app.consultantName ?? "",
              consultantPhone: app.consultantPhone ?? "",
              payloadJson: payloadPretty || "{}",
            }}
            submitLabel="Save changes"
            onSubmit={handleEditSubmit}
            onCancel={() => setEditing(false)}
          />
        </Section>
      ) : (
        <Section title="Agreement payload">
          {payloadPretty ? (
            <pre className="text-xs font-mono whitespace-pre-wrap text-gray-700 bg-gray-50 border border-gray-100 rounded-md p-3 max-h-72 overflow-y-auto">
              {payloadPretty}
            </pre>
          ) : (
            <p className="text-xs text-gray-400 italic">No payload set.</p>
          )}
        </Section>
      )}

      {app.revisionNotes && (
        <Section title="Latest revision request">
          <p className="text-sm text-gray-700 whitespace-pre-wrap">
            {app.revisionNotes}
          </p>
        </Section>
      )}

      <Section title={`Activity (${events.length})`}>
        <AgreementEventTimeline events={events} />
      </Section>

      <Section title={`Revisions (${revisions.length})`}>
        {revisions.length === 0 ? (
          <p className="text-xs text-gray-400 italic">No prior versions yet.</p>
        ) : (
          <ul className="text-xs space-y-2">
            {revisions
              .slice()
              .sort((a, b) => b.versionNumber - a.versionNumber)
              .map((r) => (
                <li
                  key={r.id}
                  className="rounded-md border border-gray-100 bg-gray-50/60 p-2"
                >
                  <div className="flex items-center gap-2 mb-1">
                    <FileText size={11} className="text-gray-400" />
                    <span className="font-semibold">v{r.versionNumber}</span>
                    <span className="text-[10px] text-gray-500">
                      by {r.createdByRole.toLowerCase()} ·{" "}
                      {formatDateTime(r.createdAt)}
                    </span>
                  </div>
                  {r.payloadSnapshot && (
                    <details>
                      <summary className="text-[11px] text-sage-navy cursor-pointer hover:underline">
                        View snapshot
                      </summary>
                      <pre className="mt-1 text-[10px] font-mono whitespace-pre-wrap bg-white border border-gray-100 rounded p-2 max-h-48 overflow-y-auto">
                        {prettifyJson(r.payloadSnapshot)}
                      </pre>
                    </details>
                  )}
                </li>
              ))}
          </ul>
        )}
      </Section>
    </div>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div>
      <p className="text-[11px] uppercase tracking-wider font-semibold text-gray-500 mb-1.5">
        {title}
      </p>
      <div className="rounded-xl border border-gray-100 bg-white p-3">{children}</div>
    </div>
  );
}

function SmallStat({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-lg bg-gray-50 border border-gray-100 px-2 py-1.5">
      <p className="text-[10px] uppercase tracking-wider font-semibold text-gray-500">
        {label}
      </p>
      <p className="font-medium text-gray-800 truncate">{value}</p>
    </div>
  );
}

function formatDateTime(iso: string | null | undefined) {
  if (!iso) return "—";
  return new Date(iso).toLocaleString("en-IN", {
    timeZone: "Asia/Kolkata",
    dateStyle: "medium",
    timeStyle: "short",
  });
}

function prettifyJson(raw: string) {
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
}
