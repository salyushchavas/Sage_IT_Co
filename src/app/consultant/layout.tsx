import type { Metadata } from "next";
import type { ReactNode } from "react";

export const metadata: Metadata = {
  title: "Consultant Agreement",
  description: "Hidden consultant agreement signing flow.",
  robots: { index: false, follow: false, nocache: true },
};

export default function ConsultantLayout({ children }: { children: ReactNode }) {
  return <>{children}</>;
}
