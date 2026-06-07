import type { Metadata } from "next";
import type { ReactNode } from "react";

export const metadata: Metadata = {
  title: "Sage Agreements",
  description: "Hidden internal consultant agreement console.",
  robots: { index: false, follow: false, nocache: true },
};

export default function AgreementsLayout({ children }: { children: ReactNode }) {
  return <>{children}</>;
}
