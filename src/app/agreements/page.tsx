"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { FileSignature, Loader2 } from "lucide-react";

import AgreementErmShell from "@/components/agreement-erm/AgreementErmShell";
import ConsultantsListView from "@/components/agreement-erm/ConsultantsListView";
import { getAgreementErmToken } from "@/lib/api";

export default function AgreementsDashboardPage() {
  const router = useRouter();
  const [checked, setChecked] = useState(false);

  useEffect(() => {
    if (!getAgreementErmToken()) {
      router.replace("/agreements/login");
      return;
    }
    setChecked(true);
  }, [router]);

  if (!checked) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-50">
        <Loader2 size={28} className="animate-spin text-sage-navy" />
      </div>
    );
  }

  return (
    <AgreementErmShell
      title="Consultant agreements"
      subtitle="All applications in flight, completed, or expired."
      Icon={FileSignature}
    >
      <ConsultantsListView />
    </AgreementErmShell>
  );
}
