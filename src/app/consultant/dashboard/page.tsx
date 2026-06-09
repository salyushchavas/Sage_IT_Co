"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import {
  AlertCircle,
  Ban,
  CheckCircle2,
  Clock,
  Download,
  FileText,
  Hourglass,
  Loader2,
  LogOut,
  PenLine,
  RefreshCw,
} from "lucide-react";

import {
  clearConsultantToken,
  fetchConsultantAgreements,
  getConsultantToken,
  type ConsultantAgreementSummary,
  type ConsultantPortalAction,
} from "@/lib/api";

/**
 * Build K — consultant portal dashboard. Lists every agreement
 * addressed to the verified email. SIGN + REVIEW_AND_SIGN rows route
 * to the wizard; VERIFIED + COMPLETED rows show the status pill with
 * NO action button (post-submit experience is status-only -- the
 * consultant never views or downloads the generated PDF from here).
 */
export default function ConsultantPortalDashboardPage() {
  const router = useRouter();
  const [loading, setLoading] = useState(true);
  const [items, setItems] = useState<ConsultantAgreementSummary[]>([]);
  const [error, setError] = useState("");

  // Token guard + initial fetch ──────────────────────────────────
  useEffect(() => {
    if (!getConsultantToken()) {
      router.replace("/consultant");
      return;
    }
    let cancelled = false;
    setLoading(true);
    fetchConsultantAgreements()
      .then((data) => {
        if (cancelled) return;
        setItems(data);
        setError("");
      })
      .catch((e) => {
        if (cancelled) return;
        setError(
          e instanceof Error ? e.message : "Couldn't load your agreements.",
        );
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [router]);

  const refresh = async () => {
    setError("");
    setLoading(true);
    try {
      setItems(await fetchConsultantAgreements());
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't refresh.");
    } finally {
      setLoading(false);
    }
  };

  const signOut = () => {
    clearConsultantToken();
    router.replace("/consultant");
  };

  const handleAction = (item: ConsultantAgreementSummary) => {
    // Build K — only SIGN / REVIEW_AND_SIGN are clickable.
    // Build T — DOWNLOAD is clickable too: it routes into the per-app
    // status screen, which renders the OTP-gated download for
    // VERIFIED/COMPLETED + released rows.
    if (
      item.action !== "SIGN"
      && item.action !== "REVIEW_AND_SIGN"
      && item.action !== "DOWNLOAD"
    ) {
      return;
    }
    router.push(`/consultant/${encodeURIComponent(item.appId)}/fill`);
  };

  return (
    <main className="min-h-screen bg-stone-50">
      <meta name="robots" content="noindex,nofollow" />

      <header className="bg-sage-navy text-white">
        <div className="max-w-4xl mx-auto px-6 py-10 flex items-start justify-between gap-4">
          <div>
            <p className="text-xs font-bold uppercase tracking-widest text-sage-copper">
              Sage IT Consultant Portal
            </p>
            <h1 className="font-serif text-3xl mt-2">Your agreements</h1>
            <p className="text-sm text-white/80 mt-2 max-w-md">
              Every agreement Sage IT has addressed to your email lives
              here. Items waiting for you appear at the top.
            </p>
          </div>
          <button
            type="button"
            onClick={signOut}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold rounded-md border border-white/30 hover:bg-white/10 transition-colors"
          >
            <LogOut size={12} /> Sign out
          </button>
        </div>
      </header>

      <section className="max-w-4xl mx-auto px-6 py-8">
        <div className="flex items-center justify-between mb-4">
          <p className="text-xs uppercase tracking-wider font-bold text-gray-500">
            {loading ? "Loading…" : `${items.length} agreement${items.length === 1 ? "" : "s"}`}
          </p>
          <button
            type="button"
            onClick={() => void refresh()}
            disabled={loading}
            className="inline-flex items-center gap-1.5 px-2.5 py-1 text-[11px] font-semibold rounded-md border border-gray-200 bg-white hover:bg-gray-50 text-gray-700 disabled:opacity-50"
          >
            <RefreshCw size={11} className={loading ? "animate-spin" : ""} />
            Refresh
          </button>
        </div>

        {error && (
          <div className="mb-4 p-3 rounded-md border border-red-200 bg-red-50 text-sm text-red-700 inline-flex items-start gap-2">
            <AlertCircle size={14} className="mt-0.5 shrink-0" /> {error}
          </div>
        )}

        {loading ? (
          <div className="flex items-center justify-center py-16">
            <Loader2 size={28} className="animate-spin text-sage-navy" />
          </div>
        ) : items.length === 0 ? (
          <div className="text-center py-16 rounded-2xl bg-white border border-stone-200">
            <FileText size={36} className="text-stone-300 mx-auto" />
            <p className="mt-3 text-sm text-gray-600">
              No agreements yet.
            </p>
            <p className="mt-1 text-xs text-gray-400">
              When Sage IT sends you an agreement, it will appear here.
            </p>
          </div>
        ) : (
          <ul className="space-y-3">
            {items.map((item) => (
              <AgreementRow
                key={item.appId}
                item={item}
                onAction={() => handleAction(item)}
              />
            ))}
          </ul>
        )}
      </section>
    </main>
  );
}

function AgreementRow({
  item,
  onAction,
}: {
  item: ConsultantAgreementSummary;
  onAction: () => void;
}) {
  const tone = actionTone(item.action, item.status);
  const clickable =
    item.action === "SIGN"
    || item.action === "REVIEW_AND_SIGN"
    || item.action === "DOWNLOAD";
  return (
    <li className="bg-white rounded-2xl border border-stone-200 p-5 flex flex-col sm:flex-row sm:items-center gap-4 shadow-sm">
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-2 flex-wrap">
          <span
            className={
              "inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-bold uppercase tracking-wider " +
              tone.badge
            }
          >
            {tone.icon}
            {item.statusLabel}
          </span>
          {item.technologyTrack && (
            <span className="text-[11px] font-mono text-gray-500">
              {item.technologyTrack}
            </span>
          )}
          {(item.phase ?? 1) === 2 && (
            <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-bold uppercase tracking-wider bg-sage-copper/15 text-sage-copper-deep">
              Phase 2
            </span>
          )}
        </div>
        <h2 className="font-serif text-base text-gray-900 mt-2 line-clamp-2">
          {item.agreementTitle}
        </h2>
        {item.createdAt && (
          <p className="mt-1 text-[11px] text-gray-500 inline-flex items-center gap-1">
            <Clock size={10} />
            Issued {new Date(item.createdAt).toLocaleDateString()}
          </p>
        )}
      </div>
      {clickable ? (
        <button
          type="button"
          onClick={onAction}
          className={
            "inline-flex items-center justify-center gap-1.5 px-4 py-2.5 rounded-md text-xs font-bold transition-colors shadow-sm shrink-0 " +
            tone.button
          }
        >
          {tone.icon}
          {tone.cta}
        </button>
      ) : (
        <p className="text-[11px] text-gray-500 max-w-[14rem] shrink-0">
          {tone.passiveCopy}
        </p>
      )}
    </li>
  );
}

function actionTone(action: ConsultantPortalAction, status: string) {
  switch (action) {
    case "SIGN":
      return {
        badge: "bg-amber-100 text-amber-800",
        icon: <PenLine size={11} />,
        cta: "Sign agreement",
        button: "bg-sage-navy text-white hover:bg-sage-navy-deep cursor-pointer",
        passiveCopy: "",
      };
    case "REVIEW_AND_SIGN":
      return {
        badge: "bg-orange-100 text-sage-copper-deep",
        icon: <PenLine size={11} />,
        cta: "Review & re-sign",
        button:
          "bg-sage-copper text-white hover:bg-sage-copper-deep cursor-pointer",
        passiveCopy: "",
      };
    case "DOWNLOAD":
      // Build T — Sage IT has released the consultant version. The row
      // is clickable and routes to the per-app screen, which fires the
      // OTP-gated download flow.
      return {
        badge:
          status === "COMPLETED"
            ? "bg-emerald-100 text-emerald-800"
            : "bg-sage-navy/10 text-sage-navy",
        icon: <Download size={11} />,
        cta: "Download my copy",
        button: "bg-sage-navy text-white hover:bg-sage-navy-deep cursor-pointer",
        passiveCopy: "",
      };
    case "NONE":
      if (status === "COMPLETED") {
        return {
          badge: "bg-emerald-100 text-emerald-800",
          icon: <CheckCircle2 size={11} />,
          cta: "",
          button: "",
          passiveCopy:
            "Sage IT has accepted this agreement. No further action is required.",
        };
      }
      if (status === "EXPIRED") {
        return {
          // Build L — stale invite. Quiet, non-actionable.
          badge: "bg-stone-200 text-gray-700",
          icon: <Ban size={11} />,
          cta: "",
          button: "",
          passiveCopy:
            "The 15-day window to complete this agreement has passed. Contact Sage IT and ask them to resend the invitation.",
        };
      }
      return _SENT_FOR_VERIFICATION_TONE;
    default:
      return _SENT_FOR_VERIFICATION_TONE;
  }
}

const _SENT_FOR_VERIFICATION_TONE = {
  badge: "bg-sage-navy/10 text-sage-navy",
  icon: <Hourglass size={11} />,
  cta: "",
  button: "",
  passiveCopy:
    "Sent for verification. We'll update the status here once Sage IT has reviewed it.",
};

