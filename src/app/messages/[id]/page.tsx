"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { Loader2, ChevronLeft } from "lucide-react";
import { getSalesInquiry, type SalesInquiry } from "@/lib/api";
import { ConversationThread } from "@/components/sales/ConversationThread";
import { useAuth } from "@/lib/auth-context";

export default function MessageThreadPage({ params }: { params: { id: string } }) {
  const { id } = params;
  const { user, isLoading: authLoading } = useAuth();
  const [inquiry, setInquiry] = useState<SalesInquiry | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  const refresh = async () => {
    try {
      const data = await getSalesInquiry(Number(id));
      setInquiry(data);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to load");
    }
  };

  useEffect(() => {
    if (!user) return;
    setLoading(true);
    refresh().finally(() => setLoading(false));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [user, id]);

  if (authLoading || loading) {
    return (
      <section className="mx-auto max-w-3xl px-6 pt-32 pb-20">
        <div className="flex items-center justify-center py-20">
          <Loader2 size={28} className="animate-spin text-sage-navy" />
        </div>
      </section>
    );
  }

  if (!user) {
    return (
      <section className="mx-auto max-w-3xl px-6 pt-32 pb-20 text-center">
        <p className="text-gray-500 mb-4">Please log in to view this conversation.</p>
        <Link href="/login" className="text-sage-navy underline">Sign in</Link>
      </section>
    );
  }

  if (error || !inquiry) {
    return (
      <section className="mx-auto max-w-3xl px-6 pt-32 pb-20 text-center">
        <p className="text-red-700 mb-4">{error || "Conversation not found"}</p>
        <Link href="/messages" className="text-sage-navy underline">Back to messages</Link>
      </section>
    );
  }

  return (
    <section className="mx-auto max-w-3xl px-6 pt-28 pb-20 min-h-screen">
      <Link
        href="/messages"
        className="inline-flex items-center gap-1 text-sm text-gray-500 hover:text-sage-navy mb-4"
      >
        <ChevronLeft size={16} /> Back to messages
      </Link>
      <ConversationThread
        inquiry={inquiry}
        currentUserId={user.id}
        onUpdated={refresh}
      />
    </section>
  );
}
