"use client";

import { useEffect, useMemo, useState } from "react";
import { motion } from "framer-motion";
import {
  Loader2, Download, Search, Calendar, AlertCircle, ShieldCheck,
  KeyRound, GraduationCap, ClipboardList, Users, CreditCard,
  Award, Lock, Globe, Smartphone, Monitor,
} from "lucide-react";
import {
  getUserRecords, getUserRecordsSummary, downloadUserRecordsCsv,
  type UserRecord,
} from "@/lib/api";
import { useToast } from "@/components/ui/Toast";
import { cn } from "@/lib/utils";
import { formatISTDate, formatISTTimeWithZone, istDayKey } from "@/lib/datetime";

const CATEGORIES = ["ALL", "ACCOUNT", "LEARNING", "ASSESSMENT", "MENTORSHIP", "PAYMENT", "CERTIFICATE", "SECURITY"] as const;
type Category = typeof CATEGORIES[number];

const CATEGORY_STYLE: Record<string, { bg: string; text: string; Icon: typeof KeyRound }> = {
  ACCOUNT: { bg: "bg-blue-100", text: "text-blue-700", Icon: KeyRound },
  LEARNING: { bg: "bg-teal-100", text: "text-teal-700", Icon: GraduationCap },
  ASSESSMENT: { bg: "bg-sage-copper/20", text: "text-sage-copper", Icon: ClipboardList },
  MENTORSHIP: { bg: "bg-emerald-100", text: "text-emerald-700", Icon: Users },
  PAYMENT: { bg: "bg-amber-100", text: "text-amber-700", Icon: CreditCard },
  CERTIFICATE: { bg: "bg-orange-100", text: "text-orange-700", Icon: Award },
  SECURITY: { bg: "bg-red-100", text: "text-red-700", Icon: Lock },
};

// Date headers + day-bucket key both come from datetime.ts so the
// "naive ISO is UTC" parsing is applied consistently — without it,
// a record at 23:30 IST (= 18:00 UTC) would bucket into the wrong
// day. Times carry an explicit "IST" suffix because admin staff
// reading the audit log might not be in India.
function formatDateHeader(iso: string): string {
  return formatISTDate(iso);
}

function formatTime(iso: string): string {
  return formatISTTimeWithZone(iso);
}

function dayKey(iso: string): string {
  return istDayKey(iso);
}

interface Props {
  userId: number | string;
  /** Used in the CSV filename — falls back to "user-{id}" if blank. */
  fileBaseName?: string;
}

export function UserRecordsPanel({ userId, fileBaseName }: Props) {
  const { toast } = useToast();
  const [category, setCategory] = useState<Category>("ALL");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [search, setSearch] = useState("");

  const [records, setRecords] = useState<UserRecord[]>([]);
  const [summary, setSummary] = useState<Record<string, number>>({});
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState("");
  const [page, setPage] = useState(0);
  const [hasNext, setHasNext] = useState(false);
  const [downloading, setDownloading] = useState(false);

  // Initial load — summary only fires once. Records reload whenever
  // category / date filters change.
  useEffect(() => {
    let cancelled = false;
    getUserRecordsSummary(userId)
      .then((s) => {
        if (cancelled) return;
        setSummary(s.byCategory ?? {});
        setTotal(s.total ?? 0);
      })
      .catch(() => { /* silent — counts are decorative */ });
    return () => { cancelled = true; };
  }, [userId]);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError("");
    getUserRecords(userId, {
      category: category === "ALL" ? undefined : category,
      from: from || undefined,
      to: to || undefined,
      page: 0,
      size: 50,
    })
      .then((res) => {
        if (cancelled) return;
        setRecords(res.records ?? []);
        setPage(res.page ?? 0);
        setHasNext(Boolean(res.hasNext));
      })
      .catch((err) => {
        if (cancelled) return;
        setError(err instanceof Error ? err.message : "Failed to load records");
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [userId, category, from, to]);

  const loadMore = async () => {
    if (!hasNext || loadingMore) return;
    setLoadingMore(true);
    try {
      const res = await getUserRecords(userId, {
        category: category === "ALL" ? undefined : category,
        from: from || undefined,
        to: to || undefined,
        page: page + 1,
        size: 50,
      });
      setRecords((prev) => [...prev, ...(res.records ?? [])]);
      setPage(res.page ?? page + 1);
      setHasNext(Boolean(res.hasNext));
    } catch (err) {
      toast("error", err instanceof Error ? err.message : "Failed to load more");
    } finally {
      setLoadingMore(false);
    }
  };

  const handleDownload = async () => {
    setDownloading(true);
    toast("info", `Downloading ${total} records…`);
    try {
      await downloadUserRecordsCsv(userId, fileBaseName || `user-${userId}`);
      toast("success", "Records downloaded.");
    } catch (err) {
      toast("error", err instanceof Error ? err.message : "Download failed");
    } finally {
      setDownloading(false);
    }
  };

  // Free-text filter applied client-side over the loaded page so the
  // server load stays unchanged. For deeper search the admin can use
  // the cross-user investigation endpoint (separate flow).
  const filtered = useMemo(() => {
    if (!search.trim()) return records;
    const q = search.trim().toLowerCase();
    return records.filter((r) =>
      r.title.toLowerCase().includes(q)
      || (r.description || "").toLowerCase().includes(q)
      || r.recordType.toLowerCase().includes(q)
    );
  }, [records, search]);

  // Bucket records by date so we render "May 6, 2026" headers
  // mirroring the spec.
  const grouped = useMemo(() => {
    const map = new Map<string, UserRecord[]>();
    for (const r of filtered) {
      const key = dayKey(r.createdAt);
      const arr = map.get(key) ?? [];
      arr.push(r);
      map.set(key, arr);
    }
    return Array.from(map.entries()).map(([day, items]) => ({ day, items }));
  }, [filtered]);

  return (
    <div>
      {/* Header */}
      <div className="flex items-center justify-between gap-3 mb-4 flex-wrap">
        <div className="flex items-center gap-1.5 text-sm font-semibold text-gray-900">
          <ShieldCheck size={14} className="text-amber-500" />
          Records
          <span className="text-xs font-normal text-gray-400">
            (immutable, append-only audit log)
          </span>
        </div>
        <button
          onClick={handleDownload}
          disabled={downloading || total === 0}
          className="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg text-xs font-semibold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-50 transition cursor-pointer"
        >
          {downloading ? <Loader2 size={12} className="animate-spin" /> : <Download size={12} />}
          Download Complete Records
        </button>
      </div>

      {/* Category chips */}
      <div className="flex flex-wrap gap-2 mb-4">
        {CATEGORIES.map((c) => {
          const count = c === "ALL" ? total : (summary[c] ?? 0);
          return (
            <button
              key={c}
              onClick={() => setCategory(c)}
              className={cn(
                "inline-flex items-center gap-1.5 px-3 py-1.5 rounded-full text-xs font-medium transition cursor-pointer",
                category === c
                  ? "bg-sage-navy text-white"
                  : "bg-white border border-gray-200 text-gray-700 hover:bg-gray-50"
              )}
            >
              {c === "ALL" ? "All" : c[0] + c.slice(1).toLowerCase()}
              <span className={cn(
                "tabular-nums px-1.5 py-0.5 rounded-full text-[10px]",
                category === c ? "bg-white/20" : "bg-gray-100 text-gray-500"
              )}>{count}</span>
            </button>
          );
        })}
      </div>

      {/* Filters row */}
      <div className="flex flex-wrap gap-2 mb-4">
        <div className="relative flex-1 min-w-[180px]">
          <Search size={12} className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" />
          <input
            type="text"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Filter loaded records..."
            className="w-full pl-8 pr-3 py-2 rounded-lg border border-gray-300 bg-white text-xs focus:outline-none focus:ring-2 focus:ring-sage-navy/30"
          />
        </div>
        <div className="flex items-center gap-1.5">
          <Calendar size={12} className="text-gray-400" />
          <input
            type="date"
            value={from}
            onChange={(e) => setFrom(e.target.value)}
            className="px-2 py-2 rounded-lg border border-gray-300 bg-white text-xs"
          />
          <span className="text-xs text-gray-400">to</span>
          <input
            type="date"
            value={to}
            onChange={(e) => setTo(e.target.value)}
            className="px-2 py-2 rounded-lg border border-gray-300 bg-white text-xs"
          />
        </div>
      </div>

      {/* Body */}
      {error && (
        <div className="mb-4 flex items-center gap-2 rounded-xl bg-red-50 border border-red-200 px-4 py-3 text-sm text-red-700">
          <AlertCircle size={14} /> {error}
        </div>
      )}

      {loading ? (
        <div className="flex items-center justify-center py-16">
          <Loader2 size={24} className="animate-spin text-sage-navy" />
        </div>
      ) : grouped.length === 0 ? (
        <div className="text-center py-16 text-sm text-gray-400">
          {category === "ALL" && !search && !from && !to
            ? "No records yet. Records are written automatically as the user takes actions on the platform."
            : "No records match the current filters."}
        </div>
      ) : (
        <div className="space-y-6">
          {grouped.map(({ day, items }) => (
            <div key={day}>
              <div className="flex items-center gap-2 mb-2">
                <div className="h-px flex-1 bg-gray-100" />
                <span className="text-xs font-semibold text-gray-500 px-2">
                  {formatDateHeader(day + "T00:00:00")}
                </span>
                <div className="h-px flex-1 bg-gray-100" />
              </div>
              <ul className="space-y-2">
                {items.map((r) => <RecordItem key={r.id} record={r} />)}
              </ul>
            </div>
          ))}

          {hasNext && (
            <div className="text-center pt-4">
              <button
                onClick={loadMore}
                disabled={loadingMore}
                className="inline-flex items-center gap-1.5 px-4 py-2 rounded-lg text-xs font-medium text-sage-navy bg-sage-navy/10 hover:bg-sage-navy/15 disabled:opacity-50 transition cursor-pointer"
              >
                {loadingMore ? <Loader2 size={12} className="animate-spin" /> : null}
                Load more
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

function RecordItem({ record }: { record: UserRecord }) {
  const style = CATEGORY_STYLE[record.category] ?? {
    bg: "bg-gray-100", text: "text-gray-700", Icon: ShieldCheck,
  };
  const Icon = style.Icon;

  const DeviceIcon = record.deviceType === "Mobile"
    ? Smartphone
    : record.deviceType === "Tablet" ? Smartphone : Monitor;

  return (
    <motion.li
      initial={{ opacity: 0, y: 4 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.15 }}
      className="rounded-xl border border-gray-100 bg-white p-3.5 hover:border-sage-navy/20 transition"
    >
      <div className="flex items-start gap-3">
        <div className={cn(
          "w-8 h-8 rounded-lg flex items-center justify-center shrink-0",
          style.bg, style.text
        )}>
          <Icon size={14} />
        </div>
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2 flex-wrap mb-0.5">
            <span className="text-[10px] font-semibold uppercase tracking-wide text-gray-400">
              {formatTime(record.createdAt)} · {record.category}
            </span>
            <span className="text-[10px] font-mono text-gray-300">
              {record.recordType}
            </span>
          </div>
          <p className="text-sm font-medium text-gray-900">{record.title}</p>
          {record.description && (
            <p className="text-xs text-gray-600 mt-0.5">{record.description}</p>
          )}
          <div className="flex items-center gap-3 mt-1.5 text-[10px] text-gray-400 flex-wrap">
            {(record.browser || record.os) && (
              <span className="inline-flex items-center gap-1">
                <DeviceIcon size={10} />
                {[record.browser, record.os].filter(Boolean).join(" · ")}
              </span>
            )}
            {record.city && (
              <span className="inline-flex items-center gap-1">
                <Globe size={10} /> {record.city}
              </span>
            )}
            {record.ipAddress && (
              <span className="font-mono">IP: {record.ipAddress}</span>
            )}
          </div>
        </div>
      </div>
    </motion.li>
  );
}
