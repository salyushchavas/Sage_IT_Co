"use client";

import { CheckCircle2, Mail } from "lucide-react";

import SplitAuthLayout from "@/components/layout/SplitAuthLayout";

/**
 * Final screen in the consultant flow. The two-stage workflow
 * automatically emails the signed PDF to both parties as soon as the
 * ERM countersigns (see EmailTemplateService.sendCompletedAgreementToParties),
 * so this page is now informational only -- the old "Email me another
 * copy" button is gone. If the consultant somehow doesn't see the
 * email they can ask the operator to forward it from the agreement-erm
 * dashboard's send-email modal.
 */
export default function ConsultantDonePage() {
  return (
    <SplitAuthLayout
      heroTitle={"Signed. We've\nemailed your copy."}
      heroSubtitle="Your signed agreement is on its way to your inbox. The operator has a copy too. You can safely close this tab."
      heroFooter="Done · Thank you"
    >
      <meta name="robots" content="noindex,nofollow" />
      <div className="space-y-5 text-center">
        <div className="mx-auto inline-flex items-center justify-center w-14 h-14 rounded-2xl bg-emerald-100 text-emerald-700">
          <CheckCircle2 size={26} />
        </div>
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Agreement signed</h1>
          <p className="text-sm text-gray-600 mt-2 max-w-md mx-auto">
            Your signed agreement has been emailed to you. Check your inbox
            for the signed PDF — the operator received a copy too.
          </p>
        </div>

        <div className="border-t border-gray-200 pt-4 max-w-md mx-auto">
          <p className="text-xs text-gray-500 inline-flex items-center gap-1.5 justify-center">
            <Mail size={11} /> Email may take a minute or two to arrive.
          </p>
        </div>

        <p className="text-[11px] text-gray-400">You can now close this tab.</p>
      </div>
    </SplitAuthLayout>
  );
}
