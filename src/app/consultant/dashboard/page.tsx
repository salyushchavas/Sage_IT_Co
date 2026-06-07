"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import {
  AlertCircle,
  CheckCircle2,
  Clock,
  Download,
  Eye,
  FileText,
  Loader2,
  LogOut,
  PenLine,
  RefreshCw,
} from "lucide-react";

import {
  clearConsultantToken,
  fetchConsultantAgreements,
  fetchConsultantPdfBlob,
  getConsultantToken,
  type ConsultantAgreementSummary,
  type ConsultantPortalAction,
} from "@/lib/api";

/**
 * Consultant portal dashboard. Lists every actionable / in-flight /
 * completed agreement addressed to the verified email -- across every
 * ERM. Each row carries a status label + a single primary action
 * (Sign / Review & Sign / View / Download) derived server-side.
 */
export default function ConsultantPortalDashboardPage() {
  const router = useRouter();
  const [loading, setLoading] = useState(true);
  const [items, setItems] = useState<ConsultantAgreementSummary[]>([]);
  const [error, setError] = useState("");
  const [downloadingId, setDownloadingId] = useState<string | null>(null);

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

  const handleAction = async (item: ConsultantAgreementSummary) => {
    if (item.action === "DOWNLOAD") {
      setDownloadingId(item.appId);
      try {
        const res = await fetchConsultantPdfBlob(item.appId, "attachment");
        if (!res.ok) {
          throw new Error(`Download failed (${res.status})`);
        }
        const blob = await res.blob();
        const url = URL.createObjectURL(blob);
        const a = document.createElement("a");
        a.href = url;
        const slug =
          (item.technologyTrack || "").replace(/[^A-Za-z0-9]+/g, "-") ||
          item.appId.slice(0, 8);
        a.download = `SageITCO-Agreement_${slug}.pdf`;
        document.body.appendChild(a);
        a.click();
        a.remove();
        window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
      } catch (e) {
        setError(e instanceof Error ? e.message : "Couldn't download.");
      } finally {
        setDownloadingId(null);
      }
      return;
    }
    // SIGN / REVIEW_AND_SIGN / VIEW all lead to the same per-app fill
    // surface; the fill page decides between read-only and editable
    // based on the application's status.
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
                onAction={() => void handleAction(item)}
                downloading={downloadingId === item.appId}
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
  downloading,
}: {
  item: ConsultantAgreementSummary;
  onAction: () => void;
  downloading: boolean;
}) {
  const tone = actionTone(item.action);
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
      <button
        type="button"
        onClick={onAction}
        disabled={downloading}
        className={
          "inline-flex items-center justify-center gap-1.5 px-4 py-2.5 rounded-md text-xs font-bold transition-colors shadow-sm shrink-0 disabled:opacity-50 disabled:cursor-not-allowed " +
          tone.button
        }
      >
        {downloading ? (
          <Loader2 size={12} className="animate-spin" />
        ) : (
          tone.icon
        )}
        {tone.cta}
      </button>
    </li>
  );
}

function actionTone(action: ConsultantPortalAction) {
  switch (action) {
    case "SIGN":
      return {
        badge: "bg-amber-100 text-amber-800",
        icon: <PenLine size={11} />,
        cta: "Sign agreement",
        button: "bg-sage-navy text-white hover:bg-sage-navy-deep cursor-pointer",
      };
    case "REVIEW_AND_SIGN":
      return {
        badge: "bg-orange-100 text-sage-copper-deep",
        icon: <PenLine size={11} />,
        cta: "Review & re-sign",
        button:
          "bg-sage-copper text-white hover:bg-sage-copper-deep cursor-pointer",
      };
    case "VIEW":
      return {
        badge: "bg-sage-navy/10 text-sage-navy",
        icon: <Eye size={11} />,
        cta: "View",
        button:
          "border border-gray-200 bg-white text-gray-700 hover:bg-gray-50 cursor-pointer",
      };
    case "DOWNLOAD":
      return {
        badge: "bg-emerald-100 text-emerald-800",
        icon: <CheckCircle2 size={11} />,
        cta: "Download signed copy",
        button:
          "bg-emerald-600 text-white hover:bg-emerald-700 cursor-pointer",
      };
  }
}
