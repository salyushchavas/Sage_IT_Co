"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { FileSignature, Loader2 } from "lucide-react";

import AgreementErmShell from "@/components/agreement-erm/AgreementErmShell";
import ApprovalStatusBoards from "@/components/agreement-erm/ApprovalStatusBoards";
import ConsultantsListView from "@/components/agreement-erm/ConsultantsListView";
import { fetchMe, getAgreementErmToken } from "@/lib/api";

export default function AgreementsDashboardPage() {
  const router = useRouter();
  const [checked, setChecked] = useState(false);

  useEffect(() => {
    if (!getAgreementErmToken()) {
      router.replace("/agreements/login");
      return;
    }
    // 3B — approvers (Manager / Accounts) belong on the approvals queue,
    // not the ERM management console.
    let cancelled = false;
    fetchMe()
      .then((me) => {
        if (cancelled) return;
        if (me.role === "MANAGER" || me.role === "ACCOUNTS") {
          router.replace("/agreements/approvals");
        } else {
          setChecked(true);
        }
      })
      .catch(() => {
        // Non-fatal: a dead session is handled by the API layer; show
        // the console rather than trapping the operator.
        if (!cancelled) setChecked(true);
      });
    return () => {
      cancelled = true;
    };
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
      subtitle="Every agreement — in flight or completed. Agreements are permanent records and never expire."
      Icon={FileSignature}
    >
      <ApprovalStatusBoards />
      <ConsultantsListView />
    </AgreementErmShell>
  );
}
