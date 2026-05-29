"use client";

import { ReactNode } from "react";
import { Loader2 } from "lucide-react";

// Shared finance UI primitives. All currency is rendered in Indian
// rupees with the en-IN locale; finance views never display dollars.

export function Pill({ children }: { children: ReactNode }) {
  return (
    <span className="px-2 py-0.5 rounded-full text-[10px] font-bold bg-gray-100 text-gray-700">
      {children}
    </span>
  );
}

export function Spinner() {
  return (
    <div className="text-center py-10">
      <Loader2 size={20} className="animate-spin text-sage-navy inline" />
    </div>
  );
}

export function Field({
  label,
  type = "text",
  value,
  onChange,
}: {
  label: string;
  type?: "text" | "date" | "number";
  value: string;
  onChange: (v: string) => void;
}) {
  return (
    <div>
      <label className="block text-[11px] font-medium text-gray-600 mb-0.5">
        {label}
      </label>
      <input
        type={type}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="w-full px-3 py-1.5 text-sm rounded-md border border-gray-200 focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy"
      />
    </div>
  );
}

export function Stat({
  label,
  value,
  accent,
}: {
  label: string;
  value: number | string;
  accent?: "amber" | "emerald" | "red";
}) {
  const accentClass =
    accent === "amber"
      ? "text-amber-700"
      : accent === "emerald"
        ? "text-emerald-700"
        : accent === "red"
          ? "text-red-700"
          : "text-gray-900";
  return (
    <div className="rounded-2xl border border-gray-100 bg-white shadow-sm p-4">
      <p className="text-[11px] uppercase tracking-wider font-semibold text-gray-500">
        {label}
      </p>
      <p className={"mt-1 text-2xl font-bold " + accentClass}>{value}</p>
    </div>
  );
}

export function moneyFmt(v: string | number | null | undefined): string {
  if (v == null || v === "") return "--";
  const n = typeof v === "number" ? v : Number(v);
  if (Number.isNaN(n)) return String(v);
  return n.toLocaleString("en-IN", { style: "currency", currency: "INR" });
}
