"use client";

import { useEffect, useState } from "react";
import { motion, AnimatePresence } from "framer-motion";
import { Info, CheckCircle2, AlertTriangle, X } from "lucide-react";
import { getActiveAnnouncements, type Announcement } from "@/lib/api";
import { cn } from "@/lib/utils";

const STORAGE_KEY = "sage-dismissed-announcements";

function loadDismissed(): Set<number> {
  if (typeof window === "undefined") return new Set();
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return new Set();
    return new Set(JSON.parse(raw) as number[]);
  } catch {
    return new Set();
  }
}

function persistDismissed(set: Set<number>) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(Array.from(set)));
  } catch {
    // localStorage full / unavailable — fall back to in-memory only
  }
}

const TYPE_STYLES: Record<Announcement["type"], { bg: string; border: string; text: string; Icon: typeof Info }> = {
  INFO: { bg: "bg-sage-navy/5", border: "border-sage-navy/30", text: "text-sage-navy", Icon: Info },
  SUCCESS: { bg: "bg-emerald-50", border: "border-emerald-200", text: "text-emerald-800", Icon: CheckCircle2 },
  WARNING: { bg: "bg-amber-50", border: "border-amber-200", text: "text-amber-800", Icon: AlertTriangle },
};

export function AnnouncementsBanner() {
  const [items, setItems] = useState<Announcement[]>([]);
  const [dismissed, setDismissed] = useState<Set<number>>(() => loadDismissed());

  useEffect(() => {
    getActiveAnnouncements()
      .then((data) => setItems(data ?? []))
      .catch(() => setItems([]));
  }, []);

  const dismiss = (id: number) => {
    setDismissed((prev) => {
      const next = new Set(prev);
      next.add(id);
      persistDismissed(next);
      return next;
    });
  };

  const visible = items.filter((a) => !dismissed.has(a.id));
  if (visible.length === 0) return null;

  return (
    <div className="space-y-2 mb-6">
      <AnimatePresence initial={false}>
        {visible.map((a) => {
          const style = TYPE_STYLES[a.type] ?? TYPE_STYLES.INFO;
          const Icon = style.Icon;
          return (
            <motion.div
              key={a.id}
              layout
              initial={{ opacity: 0, y: -8 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, x: 24 }}
              transition={{ duration: 0.25 }}
              className={cn(
                "flex items-start gap-3 rounded-xl border px-4 py-3 text-sm",
                style.bg, style.border, style.text
              )}
            >
              <Icon size={16} className="mt-0.5 shrink-0" />
              <div className="flex-1 min-w-0">
                <p className="font-semibold">{a.title}</p>
                <p className="opacity-90 mt-0.5">{a.message}</p>
              </div>
              <button
                onClick={() => dismiss(a.id)}
                className="opacity-50 hover:opacity-100 transition cursor-pointer shrink-0"
                aria-label="Dismiss"
              >
                <X size={14} />
              </button>
            </motion.div>
          );
        })}
      </AnimatePresence>
    </div>
  );
}
