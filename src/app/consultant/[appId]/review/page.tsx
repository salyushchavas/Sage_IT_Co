"use client";

import { useCallback, useEffect, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import {
  AlertCircle,
  CheckCircle2,
  FileText,
  Loader2,
  MessageSquare,
  PenLine,
} from "lucide-react";

import SplitAuthLayout from "@/components/layout/SplitAuthLayout";
import {
  getConsultantApplicationView,
  requestConsultantRevision,
  verifyConsultantDetails,
  type ConsultantApplication,
} from "@/lib/api";
import {
  clearConsultantSession,
  getConsultantToken,
} from "@/lib/consultant-session";

export default function ConsultantReviewPage() {
  const router = useRouter();
  const params = useParams<{ appId: string }>();
  const appId = params?.appId ?? "";

  const [app, setApp] = useState<ConsultantApplication | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  const [busy, setBusy] = useState<"verify" | "revision" | null>(null);
  const [showRevision, setShowRevision] = useState(false);
  const [revisionReason, setRevisionReason] = useState("");
  const [feedback, setFeedback] = useState("");

  const load = useCallback(async () => {
    if (!appId) return;
    const token = getConsultantToken(appId);
    if (!token) {
      router.replace(`/consultant?app=${encodeURIComponent(appId)}`);
      return;
    }
    try {
      const data = await getConsultantApplicationView(appId, token);
      setApp(data);
      setError("");
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't load agreement.");
      // 401 likely → token expired. Send back to entry.
      if (e instanceof Error && /401|unauthorized|expired/i.test(e.message)) {
        clearConsultantSession(appId);
        setTimeout(
          () =>
            router.replace(
              `/consultant?app=${encodeURIComponent(appId)}`,
            ),
          1200,
        );
      }
    }
  }, [appId, router]);

  useEffect(() => {
    setLoading(true);
    load().finally(() => setLoading(false));
  }, [load]);

  const handleVerifyDetails = async () => {
    if (!app) return;
    const token = getConsultantToken(appId);
    if (!token) return;
    setBusy("verify");
    setError("");
    try {
      await verifyConsultantDetails(appId, token);
      router.push(`/consultant/${encodeURIComponent(appId)}/sign`);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't verify.");
    } finally {
      setBusy(null);
    }
  };

  const handleRequestRevision = async () => {
    if (!app) return;
    if (revisionReason.trim().length < 10) {
      setError("Please explain what should change (at least 10 characters).");
      return;
    }
    const token = getConsultantToken(appId);
    if (!token) return;
    setBusy("revision");
    setError("");
    try {
      await requestConsultantRevision(appId, token, revisionReason.trim());
      setFeedback(
        "Revision request sent. We've emailed your ERM — they'll update the agreement and re-invite you.",
      );
      setShowRevision(false);
      setRevisionReason("");
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't send request.");
    } finally {
      setBusy(null);
    }
  };

  if (loading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-50">
        <Loader2 size={28} className="animate-spin text-sage-navy" />
      </div>
    );
  }

  if (!app) {
    return (
      <SplitAuthLayout heroFooter="Hidden internal flow · Sage IT Co">
        <meta name="robots" content="noindex,nofollow" />
        <div className="space-y-3 text-center">
          <AlertCircle size={20} className="text-red-600 inline" />
          <p className="text-sm text-red-700">
            {error || "We couldn't load this agreement."}
          </p>
        </div>
      </SplitAuthLayout>
    );
  }

  const payload = parsePayload(app.payload);
  const isLocked = ["SIGNED", "COMPLETED", "CANCELLED", "EXPIRED"].includes(
    app.status,
  );
  const canActOn =
    app.status === "SUBMITTED" ||
    app.status === "UPDATED" ||
    app.status === "REVISION_REQUESTED";

  return (
    <SplitAuthLayout
      heroTitle={"Review the\nagreement details."}
      heroSubtitle="Confirm everything looks right. If anything is off, request a revision and your ERM will update it and re-invite you."
      heroFooter="Step 3 of 3 · Review + sign"
    >
      <meta name="robots" content="noindex,nofollow" />
      <div className="space-y-4">
        <div>
          <h1 className="font-serif text-2xl font-bold text-gray-900">
            Hello {app.consultantName || "there"}
          </h1>
          <p className="text-sm text-gray-500 mt-1">
            Review the details below before continuing to sign.
          </p>
        </div>

        <div className="rounded-xl border border-gray-200 bg-gray-50/60 p-3 space-y-2 text-sm">
          <Row label="Name" value={app.consultantName || "—"} />
          <Row label="Email" value={app.consultantEmail} />
          <Row label="Phone" value={app.consultantPhone || "—"} />
          <Row label="Status" value={app.status.replace(/_/g, " ").toLowerCase()} />
        </div>

        {payload && (
          <div>
            <p className="text-[11px] uppercase tracking-wider font-semibold text-gray-500 mb-1.5 inline-flex items-center gap-1">
              <FileText size={11} /> Agreement terms
            </p>
            <div className="rounded-xl border border-gray-200 bg-white p-3 text-sm text-gray-700 max-h-56 overflow-y-auto">
              {Object.entries(payload).length === 0 ? (
                <p className="text-xs text-gray-400 italic">
                  No additional details provided.
                </p>
              ) : (
                <dl className="grid grid-cols-1 sm:grid-cols-2 gap-x-3 gap-y-1.5">
                  {Object.entries(payload).map(([k, v]) => (
                    <div key={k}>
                      <dt className="text-[10px] uppercase tracking-wider font-semibold text-gray-500">
                        {camelToTitle(k)}
                      </dt>
                      <dd className="text-sm">{formatPayloadValue(v)}</dd>
                    </div>
                  ))}
                </dl>
              )}
            </div>
          </div>
        )}

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

        {isLocked ? (
          <div className="rounded-xl border border-gray-200 bg-gray-50 p-3 text-sm text-gray-700">
            This agreement is{" "}
            <span className="font-semibold">{app.status.toLowerCase()}</span>{" "}
            and no longer accepts further action.
          </div>
        ) : !canActOn ? (
          <div className="rounded-xl border border-amber-200 bg-amber-50 p-3 text-sm text-amber-800">
            Your ERM is preparing the next revision. We&apos;ll email you the
            updated invite shortly.
          </div>
        ) : showRevision ? (
          <div className="space-y-2">
            <label className="block text-[11px] font-semibold text-gray-600">
              What should change?
            </label>
            <textarea
              value={revisionReason}
              onChange={(e) => setRevisionReason(e.target.value)}
              rows={4}
              placeholder="e.g. The engagement length should be 6 months, not 3."
              className="w-full px-3 py-2 text-sm rounded-md border border-gray-200 focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy"
            />
            <div className="flex items-center justify-end gap-2">
              <button
                type="button"
                onClick={() => {
                  setShowRevision(false);
                  setRevisionReason("");
                }}
                disabled={busy !== null}
                className="px-3 py-1.5 rounded-md text-xs font-semibold text-gray-600 hover:text-gray-900 cursor-pointer disabled:opacity-50"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={handleRequestRevision}
                disabled={busy !== null}
                className="inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-xs font-bold bg-sage-copper text-white hover:opacity-90 disabled:opacity-60 cursor-pointer"
              >
                {busy === "revision" ? (
                  <Loader2 size={12} className="animate-spin" />
                ) : (
                  <MessageSquare size={12} />
                )}
                {busy === "revision" ? "Sending…" : "Send revision request"}
              </button>
            </div>
          </div>
        ) : (
          <div className="grid grid-cols-1 gap-2">
            <button
              type="button"
              onClick={handleVerifyDetails}
              disabled={busy !== null}
              className="inline-flex items-center justify-center gap-1.5 px-3 py-3 rounded-lg text-sm font-bold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-60 cursor-pointer"
            >
              {busy === "verify" ? (
                <Loader2 size={14} className="animate-spin" />
              ) : (
                <PenLine size={14} />
              )}
              {busy === "verify" ? "Verifying…" : "Looks good — sign now →"}
            </button>
            <button
              type="button"
              onClick={() => setShowRevision(true)}
              disabled={busy !== null}
              className="inline-flex items-center justify-center gap-1.5 px-3 py-2 rounded-lg text-xs font-bold text-sage-copper border border-sage-copper/40 hover:bg-sage-copper/5 disabled:opacity-60 cursor-pointer"
            >
              <MessageSquare size={12} /> Something&apos;s wrong — request a revision
            </button>
          </div>
        )}
      </div>
    </SplitAuthLayout>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="grid grid-cols-3 gap-2 text-sm">
      <dt className="text-[11px] uppercase tracking-wider font-semibold text-gray-500 col-span-1">
        {label}
      </dt>
      <dd className="col-span-2 text-gray-800 capitalize">{value}</dd>
    </div>
  );
}

function parsePayload(raw: string | null): Record<string, unknown> | null {
  if (!raw) return null;
  try {
    const v = JSON.parse(raw);
    return typeof v === "object" && v !== null ? (v as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}

function camelToTitle(key: string) {
  return key
    .replace(/([A-Z])/g, " $1")
    .replace(/^./, (s) => s.toUpperCase())
    .replace(/_/g, " ");
}

function formatPayloadValue(v: unknown) {
  if (v == null) return "—";
  if (typeof v === "string" || typeof v === "number" || typeof v === "boolean") {
    return String(v);
  }
  return JSON.stringify(v);
}
