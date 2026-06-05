"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { ArrowLeft, Loader2 } from "lucide-react";

import { useAuth } from "@/lib/auth-context";
import { createConsultantApplication } from "@/lib/api";
import ConsultantForm, {
  type ConsultantFormValues,
} from "@/components/dashboard/erm/consultants/ConsultantForm";

export default function NewConsultantApplicationPage() {
  const router = useRouter();
  const { user, isLoading } = useAuth();

  useEffect(() => {
    if (isLoading) return;
    if (!user) {
      router.replace("/login");
      return;
    }
    const role = (user.role ?? "").toUpperCase();
    if (role !== "ERM" && role !== "SYSTEM_ADMIN") {
      router.replace("/dashboard");
    }
  }, [user, isLoading, router]);

  if (isLoading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-50">
        <Loader2 size={28} className="animate-spin text-sage-navy" />
      </div>
    );
  }

  const onSubmit = async (values: ConsultantFormValues) => {
    let payload: unknown = undefined;
    if (values.payloadJson) {
      try {
        payload = JSON.parse(values.payloadJson);
      } catch {
        throw new Error("Payload is not valid JSON.");
      }
    }
    const app = await createConsultantApplication({
      consultantEmail: values.consultantEmail,
      consultantName: values.consultantName || undefined,
      consultantPhone: values.consultantPhone || undefined,
      payload,
    });
    router.replace(`/erm-dashboard/consultants/${app.applicationId}`);
  };

  return (
    <div className="min-h-screen bg-gray-50 px-4 py-8 sm:py-10">
      <div className="max-w-3xl mx-auto">
        <Link
          href="/erm-dashboard?tab=consultants"
          className="inline-flex items-center gap-1 text-xs font-semibold text-sage-navy hover:text-sage-navy-deep mb-4"
        >
          <ArrowLeft size={12} /> Back to consultants
        </Link>
        <div className="bg-white rounded-2xl border border-gray-100 shadow-sm p-6">
          <h1 className="text-2xl font-bold text-gray-900">New consultant agreement</h1>
          <p className="text-sm text-gray-500 mt-1 mb-5">
            Create a draft application. An invite email goes to the consultant
            with a one-time code so they can verify and sign on a hidden URL.
          </p>
          <ConsultantForm
            submitLabel="Create + send invite"
            onSubmit={onSubmit}
            onCancel={() => router.push("/erm-dashboard?tab=consultants")}
          />
        </div>
      </div>
    </div>
  );
}
