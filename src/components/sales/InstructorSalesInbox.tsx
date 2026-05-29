"use client";

import { useEffect, useState } from "react";
import { Loader2, MessageSquare, IndianRupee, Inbox } from "lucide-react";
import { getInstructorSalesInquiries, getSalesInquiry, type SalesInquiry } from "@/lib/api";
import { ConversationThread } from "@/components/sales/ConversationThread";
import { SendQuoteModal } from "@/components/sales/SendQuoteModal";
import { cn } from "@/lib/utils";
import { timeAgoIST } from "@/lib/datetime";

interface Props {
  currentUserId: number;
}

const STATUS_STYLES: Record<string, string> = {
  NEW: "bg-amber-100 text-amber-700",
  IN_PROGRESS: "bg-blue-100 text-blue-700",
  QUOTED: "bg-sage-copper/20 text-sage-copper",
  CONVERTED: "bg-emerald-100 text-emerald-700",
  CLOSED: "bg-gray-100 text-gray-600",
  LOST: "bg-gray-100 text-gray-500",
};

function relative(ts: string | null | undefined) {
  if (!ts) return "";
  return timeAgoIST(ts);
}

export function InstructorSalesInbox({ currentUserId }: Props) {
  const [list, setList] = useState<SalesInquiry[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  const [activeId, setActiveId] = useState<number | null>(null);
  const [activeInquiry, setActiveInquiry] = useState<SalesInquiry | null>(null);
  const [activeLoading, setActiveLoading] = useState(false);

  const [quoteFor, setQuoteFor] = useState<SalesInquiry | null>(null);

  const refreshList = () => {
    setLoading(true);
    getInstructorSalesInquiries()
      .then((rows) => setList(rows ?? []))
      .catch((err) => setError(err instanceof Error ? err.message : "Failed to load"))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    refreshList();
  }, []);

  const openInquiry = async (id: number) => {
    setActiveId(id);
    setActiveLoading(true);
    try {
      const data = await getSalesInquiry(id);
      setActiveInquiry(data);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to load");
    } finally {
      setActiveLoading(false);
    }
  };

  const refreshActive = async () => {
    if (!activeId) return;
    try {
      const data = await getSalesInquiry(activeId);
      setActiveInquiry(data);
    } catch {
      // surface in UI on next user action
    }
    refreshList();
  };

  const newCount = list.filter((i) => i.status === "NEW").length;

  return (
    <div className="bg-white rounded-2xl border border-gray-100 shadow-sm overflow-hidden">
      <div className="px-5 py-4 border-b border-gray-100 flex items-center gap-2">
        <Inbox size={16} className="text-sage-navy" />
        <h2 className="text-lg font-bold text-gray-900">Sales Inquiries</h2>
        {newCount > 0 && (
          <span className="ml-1 inline-flex items-center justify-center text-[10px] font-bold bg-amber-500 text-white rounded-full px-2 py-0.5">
            {newCount} NEW
          </span>
        )}
      </div>

      {loading ? (
        <div className="flex items-center justify-center py-12">
          <Loader2 size={24} className="animate-spin text-sage-navy" />
        </div>
      ) : error ? (
        <p className="text-sm text-red-700 px-5 py-6">{error}</p>
      ) : list.length === 0 ? (
        <div className="text-center py-12 px-5">
          <MessageSquare size={32} className="mx-auto text-gray-300 mb-3" />
          <p className="text-sm text-gray-500">No inquiries yet.</p>
        </div>
      ) : (
        <div className="divide-y divide-gray-100">
          {list.map((i) => (
            <div key={i.id} className="px-5 py-4">
              <div className="flex items-start justify-between gap-3">
                <button
                  onClick={() => openInquiry(i.id)}
                  className="flex-1 min-w-0 text-left cursor-pointer"
                >
                  <div className="flex items-center gap-2 mb-1 flex-wrap">
                    <span className={cn(
                      "text-[10px] font-semibold uppercase tracking-wide px-2 py-0.5 rounded-full",
                      STATUS_STYLES[i.status] ?? "bg-gray-100 text-gray-600"
                    )}>
                      {i.status}
                    </span>
                    <span className="text-sm font-semibold text-gray-900">{i.studentName ?? "Student"}</span>
                    <span className="text-xs text-gray-400">→ {i.courseTitle}</span>
                  </div>
                  {i.lastMessagePreview && (
                    <p className="text-sm text-gray-600 line-clamp-1">
                      &ldquo;{i.lastMessagePreview}&rdquo;
                    </p>
                  )}
                  <p className="text-xs text-gray-400 mt-1">
                    {i.budgetRange && <>Budget: {i.budgetRange} · </>}
                    {relative(i.lastMessageAt)}
                  </p>
                </button>
                <div className="flex items-center gap-2 shrink-0">
                  <button
                    onClick={() => openInquiry(i.id)}
                    className="px-3 py-1.5 rounded-lg text-xs font-medium text-gray-700 bg-gray-100 hover:bg-gray-200 transition cursor-pointer"
                  >
                    Reply
                  </button>
                  {i.status !== "CONVERTED" && i.status !== "CLOSED" && i.status !== "LOST" && (
                    <button
                      onClick={() => setQuoteFor(i)}
                      className="inline-flex items-center gap-1 px-3 py-1.5 rounded-lg text-xs font-semibold bg-sage-navy text-white hover:bg-sage-navy-deep transition cursor-pointer"
                    >
                      <IndianRupee size={11} /> Send Quote
                    </button>
                  )}
                </div>
              </div>
            </div>
          ))}
        </div>
      )}

      {activeId !== null && (
        <div className="border-t border-gray-100 px-5 py-5 bg-gray-50">
          {activeLoading ? (
            <div className="flex items-center justify-center py-12">
              <Loader2 size={24} className="animate-spin text-sage-navy" />
            </div>
          ) : activeInquiry ? (
            <ConversationThread
              inquiry={activeInquiry}
              currentUserId={currentUserId}
              onUpdated={refreshActive}
            />
          ) : null}
          <button
            onClick={() => { setActiveId(null); setActiveInquiry(null); }}
            className="mt-3 text-xs text-gray-500 hover:text-gray-700 cursor-pointer"
          >
            Close conversation
          </button>
        </div>
      )}

      {quoteFor && (
        <SendQuoteModal
          isOpen={!!quoteFor}
          onClose={() => setQuoteFor(null)}
          inquiryId={quoteFor.id}
          courseTitle={quoteFor.courseTitle ?? ""}
          onQuoteSent={() => {
            refreshList();
            if (activeId === quoteFor.id) refreshActive();
          }}
        />
      )}
    </div>
  );
}
