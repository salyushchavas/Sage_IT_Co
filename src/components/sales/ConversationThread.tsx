"use client";

import { useEffect, useRef, useState } from "react";
import { Loader2, Send } from "lucide-react";
import { postSalesMessage, type SalesInquiry } from "@/lib/api";
import { QuoteCard } from "@/components/sales/QuoteCard";
import { cn } from "@/lib/utils";
import { formatISTContextual } from "@/lib/datetime";

interface Props {
  inquiry: SalesInquiry;
  currentUserId: number;
  onUpdated: () => void;
  readOnly?: boolean;
}

const STATUS_STYLES: Record<string, string> = {
  NEW: "bg-amber-100 text-amber-700",
  IN_PROGRESS: "bg-blue-100 text-blue-700",
  QUOTED: "bg-violet-100 text-violet-700",
  CONVERTED: "bg-emerald-100 text-emerald-700",
  CLOSED: "bg-gray-100 text-gray-600",
  LOST: "bg-gray-100 text-gray-500",
};

export function ConversationThread({ inquiry, currentUserId, onUpdated, readOnly }: Props) {
  const [draft, setDraft] = useState("");
  const [sending, setSending] = useState(false);
  const [error, setError] = useState("");
  const scrollRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  }, [inquiry.messages?.length]);

  const send = async () => {
    if (!draft.trim()) return;
    setSending(true);
    setError("");
    try {
      await postSalesMessage(inquiry.id, draft.trim());
      setDraft("");
      onUpdated();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Couldn't send message");
    } finally {
      setSending(false);
    }
  };

  const isStudent = inquiry.userId === currentUserId;
  const messages = inquiry.messages ?? [];
  const closed = inquiry.status === "CONVERTED" || inquiry.status === "CLOSED" || inquiry.status === "LOST";

  return (
    <div className="bg-white rounded-2xl border border-gray-100 shadow-sm flex flex-col h-[70vh]">
      <div className="px-5 py-4 border-b border-gray-100 flex items-center justify-between">
        <div>
          <h2 className="text-lg font-bold text-gray-900">{inquiry.courseTitle}</h2>
          <p className="text-xs text-gray-500">
            {isStudent ? `with ${inquiry.instructorName ?? "instructor"}` : `from ${inquiry.studentName ?? "student"}`}
            {inquiry.budgetRange && ` · Budget: ${inquiry.budgetRange}`}
          </p>
        </div>
        <span className={cn(
          "text-[10px] font-semibold uppercase tracking-wide px-2 py-0.5 rounded-full",
          STATUS_STYLES[inquiry.status] ?? "bg-gray-100 text-gray-600"
        )}>
          {inquiry.status}
        </span>
      </div>

      <div ref={scrollRef} className="flex-1 overflow-y-auto px-5 py-4 space-y-4">
        {messages.length === 0 ? (
          <p className="text-sm text-gray-400 text-center py-8">No messages yet.</p>
        ) : (
          messages.map((m) => {
            const mine = m.senderId === currentUserId;
            return (
              <div key={m.id} className={cn("flex", mine ? "justify-end" : "justify-start")}>
                <div className={cn("max-w-[85%]", mine ? "items-end" : "items-start")}>
                  <p className={cn(
                    "text-[11px] font-semibold mb-1 px-1",
                    mine ? "text-right text-sage-navy" : "text-gray-700"
                  )}>
                    {mine ? "YOU" : (m.senderName ?? "User").toUpperCase()}
                    <span className="ml-2 text-[10px] font-normal text-gray-400">
                      {m.createdAt ? formatISTContextual(m.createdAt) : ""}
                    </span>
                  </p>
                  <div className={cn(
                    "rounded-2xl px-4 py-2.5 text-sm",
                    mine ? "bg-sage-navy text-white" : "bg-gray-100 text-gray-800"
                  )}>
                    <p className="whitespace-pre-wrap">{m.message}</p>
                  </div>
                  {m.isQuote && (
                    <div className="mt-2">
                      <QuoteCard
                        inquiryId={inquiry.id}
                        message={m}
                        isStudent={isStudent}
                        onUpdated={onUpdated}
                      />
                    </div>
                  )}
                </div>
              </div>
            );
          })
        )}
      </div>

      {!readOnly && !closed && (
        <div className="px-5 py-3 border-t border-gray-100">
          {error && <p className="text-xs text-red-700 mb-2">{error}</p>}
          <div className="flex items-end gap-2">
            <textarea
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter" && !e.shiftKey) {
                  e.preventDefault();
                  send();
                }
              }}
              placeholder="Type your reply…"
              rows={2}
              className="flex-1 px-3 py-2 rounded-lg border border-gray-300 bg-white text-sm focus:outline-none focus:ring-2 focus:ring-sage-navy/30 resize-none"
            />
            <button
              onClick={send}
              disabled={sending || !draft.trim()}
              className="inline-flex items-center gap-1.5 px-4 py-2 rounded-lg text-sm font-semibold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-50 transition cursor-pointer"
            >
              {sending ? <Loader2 size={14} className="animate-spin" /> : <Send size={14} />}
              Send
            </button>
          </div>
        </div>
      )}
      {closed && (
        <div className="px-5 py-3 border-t border-gray-100 text-center text-xs text-gray-500">
          {inquiry.status === "CONVERTED"
            ? "This conversation ended in an enrollment."
            : "This conversation is closed."}
        </div>
      )}
    </div>
  );
}
