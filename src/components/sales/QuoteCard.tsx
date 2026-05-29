"use client";

import { useState } from "react";
import { Loader2, CheckCircle2, X } from "lucide-react";
import { acceptSalesQuote, declineSalesQuote, parseQuoteItems, type SalesMessage } from "@/lib/api";
import { cn } from "@/lib/utils";

interface Props {
  inquiryId: number;
  message: SalesMessage;
  isStudent: boolean;
  onUpdated: () => void;
}

export function QuoteCard({ inquiryId, message, isStudent, onUpdated }: Props) {
  const [busy, setBusy] = useState<"accept" | "decline" | null>(null);
  const [error, setError] = useState("");

  const items = parseQuoteItems(message.quotedItems);
  const total = Number(message.quotedPrice ?? 0);
  const status = message.quoteStatus;

  const accept = async () => {
    setBusy("accept");
    setError("");
    try {
      await acceptSalesQuote(inquiryId, message.id);
      onUpdated();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Couldn't accept quote");
    } finally {
      setBusy(null);
    }
  };
  const decline = async () => {
    setBusy("decline");
    setError("");
    try {
      await declineSalesQuote(inquiryId, message.id);
      onUpdated();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Couldn't decline quote");
    } finally {
      setBusy(null);
    }
  };

  const accepted = status === "ACCEPTED";
  const declined = status === "DECLINED";

  return (
    <div className={cn(
      "rounded-xl border-2 p-4 transition",
      accepted ? "bg-emerald-50 border-emerald-300" :
      declined ? "bg-gray-50 border-gray-300" :
      "bg-sage-navy/5 border-sage-navy/40"
    )}>
      <div className="flex items-center gap-2 mb-3">
        <span className={cn(
          "text-[10px] font-semibold uppercase tracking-wide px-2 py-0.5 rounded-full",
          accepted ? "bg-emerald-200 text-emerald-800" :
          declined ? "bg-gray-200 text-gray-700" :
          "bg-sage-navy text-white"
        )}>
          {accepted ? "Quote Accepted" : declined ? "Quote Declined" : "Price Quote"}
        </span>
      </div>

      {items.length > 0 && (
        <div className="space-y-1.5 mb-3 text-sm">
          {items.map((it, idx) => (
            <div key={idx} className="flex items-center justify-between">
              <span className="text-gray-700">{it.item}</span>
              <span className={cn(
                "tabular-nums font-medium",
                it.price < 0 ? "text-emerald-700" : "text-gray-900"
              )}>
                {it.price < 0 ? "−" : ""}₹{Math.abs(it.price).toLocaleString("en-IN")}
              </span>
            </div>
          ))}
        </div>
      )}

      <div className="flex items-center justify-between pt-2 border-t border-current/10">
        <span className="font-semibold text-gray-700">Total</span>
        <span className="text-lg font-bold text-sage-navy tabular-nums">
          ₹{total.toLocaleString("en-IN")}
        </span>
      </div>

      {error && <p className="text-xs text-red-700 mt-3">{error}</p>}

      {isStudent && !accepted && !declined && (
        <div className="flex items-center gap-2 mt-4">
          <button
            onClick={accept}
            disabled={busy !== null}
            className="flex-1 inline-flex items-center justify-center gap-1.5 px-3 py-2 rounded-lg text-xs font-semibold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-50 transition cursor-pointer"
          >
            {busy === "accept" ? <Loader2 size={12} className="animate-spin" /> : <CheckCircle2 size={12} />}
            Accept Quote
          </button>
          <button
            onClick={decline}
            disabled={busy !== null}
            className="flex-1 inline-flex items-center justify-center gap-1.5 px-3 py-2 rounded-lg text-xs font-semibold border border-sage-navy text-sage-navy hover:bg-sage-navy/5 disabled:opacity-50 transition cursor-pointer"
          >
            {busy === "decline" ? <Loader2 size={12} className="animate-spin" /> : <X size={12} />}
            Decline
          </button>
        </div>
      )}
      {accepted && (
        <p className="text-xs text-emerald-700 mt-3 inline-flex items-center gap-1">
          <CheckCircle2 size={12} /> You accepted this quote and were enrolled.
        </p>
      )}
    </div>
  );
}
