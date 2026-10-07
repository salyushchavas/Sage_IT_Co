"use client";

import { Suspense, useCallback, useEffect, useState } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { AlertCircle, ArrowLeft, Loader2 } from "lucide-react";

import { getWebAgreement, type WebAgreementDetail } from "@/lib/api";
import ParticipantRequestsPanel from "./ParticipantRequestsPanel";
import WebAgreementsListView from "./WebAgreementsListView";
import WebAgreementCreateForm from "./WebAgreementCreateForm";
import WebAgreementDetailView from "./WebAgreementDetailView";

/**
 * The "Agreements" tab of the ERM dashboard (and of the Operations panel):
 * the website's own copy of the console's agreement screens. A participant
 * who signed their consent and said they're ready appears at the top; "Start
 * agreement" opens the create form, the new agreement then opens in the
 * detail view, and the list below tracks every agreement (an ERM sees their
 * own; Operations / System admins see all).
 *
 * Moving between list, create form and detail is component state, so the
 * dashboard around it stays put. The open agreement is also in the URL
 * (?tab=agreements&agreement=<id>), so a refresh keeps it open, browser Back
 * returns to the list, and the same link opens it directly.
 */
type View =
  | { kind: "list" }
  | { kind: "new"; participantUserId: number }
  | { kind: "detail"; applicationId: string };

export function WebAgreementsPanel() {
  // useSearchParams needs a Suspense boundary in the App Router.
  return (
    <Suspense fallback={<Spinner />}>
      <WebAgreementsPanelInner />
    </Suspense>
  );
}

export default WebAgreementsPanel;

function WebAgreementsPanelInner() {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const linked = searchParams.get("agreement");
  const [view, setView] = useState<View>(() =>
    linked ? { kind: "detail", applicationId: linked } : { kind: "list" },
  );

  // The URL leads: ?agreement=<id> shows that agreement (a link, a refresh,
  // browser Forward); losing it (browser Back) returns to the list.
  useEffect(() => {
    if (linked) {
      setView((v) =>
        v.kind === "detail" && v.applicationId === linked
          ? v
          : { kind: "detail", applicationId: linked },
      );
    } else {
      setView((v) => (v.kind === "detail" ? { kind: "list" } : v));
    }
  }, [linked]);

  const go = (next: View) => {
    setView(next);
    if (typeof window !== "undefined") window.scrollTo({ top: 0 });
  };

  /** This page's URL with the agreement set (and the Agreements tab), or without it. */
  const urlFor = (applicationId: string | null) => {
    const params = new URLSearchParams(searchParams.toString());
    if (applicationId) {
      params.set("tab", "agreements");
      params.set("agreement", applicationId);
    } else {
      params.delete("agreement");
    }
    const qs = params.toString();
    return qs ? `${pathname}?${qs}` : pathname;
  };

  const openDetail = (applicationId: string) => {
    router.push(urlFor(applicationId), { scroll: false });
    go({ kind: "detail", applicationId });
  };

  const backToList = () => {
    // Drop the agreement from the URL so a reload shows the list.
    if (linked) router.push(urlFor(null), { scroll: false });
    go({ kind: "list" });
  };

  if (view.kind === "new") {
    return (
      <WebAgreementCreateForm
        participantUserId={view.participantUserId}
        onCancel={backToList}
        onCreated={openDetail}
      />
    );
  }

  if (view.kind === "detail") {
    return (
      <AgreementDetail
        key={view.applicationId}
        applicationId={view.applicationId}
        onBack={backToList}
      />
    );
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-gray-900">Agreements</h1>
        <p className="text-sm text-gray-500 max-w-2xl">
          Send, track and verify participant agreements. The participant fills and signs theirs from
          their dashboard; you review it here, then verify it or send it back for changes.
        </p>
      </div>
      <ParticipantRequestsPanel
        onStart={(participantUserId) => go({ kind: "new", participantUserId })}
      />
      <WebAgreementsListView
        onOpen={openDetail}
      />
    </div>
  );
}

/** Loads one agreement and shows it; onRefresh reloads after every action. */
function AgreementDetail({
  applicationId,
  onBack,
}: {
  applicationId: string;
  onBack: () => void;
}) {
  const [detail, setDetail] = useState<WebAgreementDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  const refresh = useCallback(async () => {
    try {
      const data = await getWebAgreement(applicationId);
      setDetail(data);
      setError("");
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't load the agreement");
    }
  }, [applicationId]);

  useEffect(() => {
    setLoading(true);
    refresh().finally(() => setLoading(false));
  }, [refresh]);

  return (
    <div className="space-y-4">
      <button
        type="button"
        onClick={onBack}
        className="inline-flex items-center gap-1 text-xs font-semibold text-gray-500 hover:text-sage-navy cursor-pointer"
      >
        <ArrowLeft size={12} /> Back to agreements
      </button>
      {loading ? (
        <Spinner />
      ) : (
        <div className="bg-white rounded-2xl border border-gray-100 shadow-sm p-4 sm:p-6">
          {error && !detail ? (
            <p className="inline-flex items-center gap-1.5 text-sm text-red-700">
              <AlertCircle size={14} /> {error}
            </p>
          ) : detail ? (
            <WebAgreementDetailView detail={detail} onRefresh={refresh} />
          ) : null}
        </div>
      )}
    </div>
  );
}

function Spinner() {
  return (
    <div className="text-center py-10">
      <Loader2 size={20} className="animate-spin text-sage-navy inline" />
    </div>
  );
}
