"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { ArrowLeft, FilePlus2, Loader2 } from "lucide-react";

import AgreementErmShell from "@/components/agreement-erm/AgreementErmShell";
import ConsultantForm, {
  type ConsultantFormValues,
} from "@/components/agreement-erm/ConsultantForm";
import {
  createConsultantApplication,
  getAgreementErmToken,
} from "@/lib/api";

export default function NewConsultantApplicationPage() {
  const router = useRouter();
  const [checked, setChecked] = useState(false);

  useEffect(() => {
    if (!getAgreementErmToken()) {
      router.replace("/agreement-erm/login");
      return;
    }
    setChecked(true);
  }, [router]);

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
    router.replace(`/agreement-erm/${app.applicationId}`);
  };

  if (!checked) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-50">
        <Loader2 size={28} className="animate-spin text-sage-navy" />
      </div>
    );
  }

  return (
    <AgreementErmShell
      title="New consultant agreement"
      subtitle="Create a draft and email the invite to the consultant."
      Icon={FilePlus2}
      toolbar={
        <Link
          href="/agreement-erm"
          className="inline-flex items-center gap-1 text-xs font-semibold text-gray-500 hover:text-sage-navy"
        >
          <ArrowLeft size={12} /> Back
        </Link>
      }
    >
      <div className="bg-white rounded-2xl border border-gray-100 shadow-sm p-6 max-w-3xl">
        <ConsultantForm
          submitLabel="Create + send invite"
          onSubmit={onSubmit}
          onCancel={() => router.push("/agreement-erm")}
        />
      </div>
    </AgreementErmShell>
  );
}
