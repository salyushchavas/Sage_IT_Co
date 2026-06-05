"use client";

import { useCallback, useEffect, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import Link from "next/link";
import { AlertCircle, ArrowLeft, Loader2 } from "lucide-react";

import { useAuth } from "@/lib/auth-context";
import {
  getConsultantApplication,
  type ConsultantApplicationDetailEnvelope,
} from "@/lib/api";
import ConsultantDetailView from "@/components/dashboard/erm/consultants/ConsultantDetailView";

export default function ConsultantApplicationDetailPage() {
  const params = useParams<{ appId: string }>();
  const appId = params?.appId ?? "";
  const router = useRouter();
  const { user, isLoading } = useAuth();

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
    if (isLoading) return;
    if (!user) {
      router.replace("/login");
      return;
    }
    const role = (user.role ?? "").toUpperCase();
    if (role !== "ERM" && role !== "SYSTEM_ADMIN") {
      router.replace("/dashboard");
      return;
    }
    if (!appId) return;
    setLoading(true);
    refresh().finally(() => setLoading(false));
  }, [user, isLoading, router, refresh, appId]);

  if (isLoading || loading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-50">
        <Loader2 size={28} className="animate-spin text-sage-navy" />
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-gray-50 px-4 py-8 sm:py-10">
      <div className="max-w-4xl mx-auto">
        <Link
          href="/erm-dashboard?tab=consultants"
          className="inline-flex items-center gap-1 text-xs font-semibold text-sage-navy hover:text-sage-navy-deep mb-4"
        >
          <ArrowLeft size={12} /> Back to consultants
        </Link>
        <div className="bg-white rounded-2xl border border-gray-100 shadow-sm p-6">
          {error && !detail ? (
            <p className="inline-flex items-center gap-1.5 text-sm text-red-700">
              <AlertCircle size={14} /> {error}
            </p>
          ) : detail ? (
            <ConsultantDetailView detail={detail} onRefresh={refresh} />
          ) : null}
        </div>
      </div>
    </div>
  );
}
