"use client";

import { useEffect, useState } from "react";
import { FileText } from "lucide-react";

import {
  WebAgreementApiError,
  webFetchAgreementVersionPdfBlob,
  webFetchAgreementVersions,
  type WebAgreement,
  type WebAgreementVersion,
} from "@/lib/api";
import { formatUsDateTimeCt } from "@/lib/datetime";

/**
 * The verified versions (V1, V2, …): one immutable PDF with its Certificate
 * of Completion each time the ERM clicked Verify. View-only. The website's
 * copy of the console's VersionHistory (ConsultantDetailView.tsx); the title
 * and subtitle are the console's text, kept as they are.
 *
 * The PDF is fetched with the website sign-in and opened as a blob URL in a
 * new tab (revoked after a minute); the storage path never reaches the
 * browser. The version routed to the current approval round is tagged.
 * Renders nothing until at least one version exists.
 *
 * Like the console, the list loads when the agreement is opened: a version
 * made by a Verify on this screen shows the next time it is opened.
 */
export default function VersionHistory({ app }: { app: WebAgreement }) {
  const [versions, setVersions] = useState<WebAgreementVersion[] | null>(null);
  const [err, setErr] = useState("");
  const [openingVersion, setOpeningVersion] = useState<number | null>(null);

  useEffect(() => {
    let alive = true;
    webFetchAgreementVersions(app.applicationId)
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
      const blob = await webFetchAgreementVersionPdfBlob(app.applicationId, versionNumber);
      const url = URL.createObjectURL(blob);
      window.open(url, "_blank", "noopener,noreferrer");
      window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
    } catch (e) {
      setErr(
        e instanceof WebAgreementApiError
          ? `Couldn't open version V${versionNumber} (${e.status})`
          : e instanceof Error
            ? e.message
            : "Couldn't open the version.",
      );
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
                  {v.approvedAt ? formatUsDateTimeCt(v.approvedAt) : "—"}
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
