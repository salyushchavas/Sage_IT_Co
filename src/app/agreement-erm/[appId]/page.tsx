"use client";

import { useCallback, useEffect, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import Link from "next/link";
import { AlertCircle, ArrowLeft, FileText, Loader2 } from "lucide-react";

import AgreementErmShell from "@/components/agreement-erm/AgreementErmShell";
import ConsultantDetailView from "@/components/agreement-erm/ConsultantDetailView";
import {
  getAgreementErmToken,
  getConsultantApplication,
  type ConsultantApplicationDetailEnvelope,
} from "@/lib/api";

export default function ConsultantApplicationDetailPage() {
  const router = useRouter();
  const params = useParams<{ appId: string }>();
  const appId = params?.appId ?? "";

  const [detail, setDetail] = useState<ConsultantApplicationDetailEnvelope | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  const refresh = useCallback(async () => {
    try {
      const data = await getConsultantApplication(appId);
      setDetail(data);
      setError("");
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't load application");
    }
  }, [appId]);

  useEffect(() => {
    if (!getAgreementErmToken()) {
      router.replace("/agreement-erm/login");
      return;
    }
    if (!appId) return;
    setLoading(true);
    refresh().finally(() => setLoading(false));
  }, [router, refresh, appId]);

  if (loading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-50">
        <Loader2 size={28} className="animate-spin text-sage-navy" />
      </div>
    );
  }

  return (
    <AgreementErmShell
      title="Agreement detail"
      Icon={FileText}
      toolbar={
        <Link
          href="/agreement-erm"
          className="inline-flex items-center gap-1 text-xs font-semibold text-gray-500 hover:text-sage-navy"
        >
          <ArrowLeft size={12} /> Back to list
        </Link>
      }
    >
      <div className="bg-white rounded-2xl border border-gray-100 shadow-sm p-6">
        {error && !detail ? (
          <p className="inline-flex items-center gap-1.5 text-sm text-red-700">
            <AlertCircle size={14} /> {error}
          </p>
        ) : detail ? (
          <ConsultantDetailView detail={detail} onRefresh={refresh} />
        ) : null}
      </div>
    </AgreementErmShell>
  );
}
