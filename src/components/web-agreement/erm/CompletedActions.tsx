"use client";

import { useState } from "react";
import { AlertCircle, ArrowUpRight, Download, ExternalLink, Loader2 } from "lucide-react";

import { WebAgreementApiError, webFetchAgreementPdfBlob, type WebAgreement } from "@/lib/api";

/**
 * The executed (COMPLETED) agreement's actions on the ERM detail: Download
 * PDF, View inline, and Advance to Phase 2 (Phase 1 only). The body of the
 * console's CompletedActions (ConsultantDetailView.tsx); the detail view
 * wraps it in its "Fully executed" bar.
 *
 * The PDF is fetched with the website sign-in and handed to the browser as
 * a blob URL, revoked after a minute, so no storage path reaches the page and
 * a copied address stops working. There is no "Send to email…": emails are
 * off, and the console's "…and emailed" wording is dropped.
 */
export default function CompletedActions({
  app,
  onAdvanceToPhase2,
}: {
  app: WebAgreement;
  onAdvanceToPhase2: () => void;
}) {
  const [busy, setBusy] = useState<"view" | "download" | null>(null);
  const [error, setError] = useState("");
  const currentPhase = app.phase ?? 1;

  const handleAction = async (mode: "view" | "download") => {
    setBusy(mode);
    setError("");
    try {
      const blob = await webFetchAgreementPdfBlob(
        app.applicationId,
        mode === "download" ? "attachment" : "inline",
      );
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
      window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
    } catch (e) {
      setError(
        e instanceof WebAgreementApiError
          ? `Couldn't fetch the PDF (${e.status})`
          : e instanceof Error
            ? e.message
            : "Couldn't fetch the PDF.",
      );
    } finally {
      setBusy(null);
    }
  };

  return (
    <>
      <p className="text-xs text-gray-600 max-w-md">
        {currentPhase === 2 ? "Phase 2 PDF generated." : "Final PDF generated."}
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
    </>
  );
}

/**
 * The server's executed-PDF file name (AgreementDocumentService
 * .buildPdfFilename), so the saved file is named as the response would name
 * it: SageITCO-Agreement_{name}_{track}.pdf. Slug rule: whitespace to "-",
 * drop anything but [A-Za-z0-9_-], collapse repeats, trim the ends.
 */
function buildClientPdfFilename(app: WebAgreement): string {
  const rawName =
    app.signedLegalName?.trim()
    || app.consultantName?.trim()
    || app.applicationId;
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
