"use client";

import { useCallback, useEffect, useState, type ReactNode } from "react";
import { CheckCircle2, Loader2 } from "lucide-react";
import { formatDay } from "@/lib/datetime";

import {
  getMyCoaching,
  listWeeklyReports,
  markCoachingTaskDone,
  type CoachingFeedbackEntry,
  type CoachingSessionEntry,
  type CoachingTaskEntry,
  type MyCoaching,
  type WeeklyReportDTO,
} from "@/lib/api";

/**
 * Checklist 4.4: shared pieces of the Resume and Interviews tabs — the
 * participant's coaching record (sessions, tasks, feedback) and what they
 * logged in their weekly reports.
 */
export function useCoachingData() {
  const [coaching, setCoaching] = useState<MyCoaching>({ sessions: [], tasks: [], feedback: [] });
  const [reports, setReports] = useState<WeeklyReportDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const load = useCallback(async () => {
    const [c, r] = await Promise.allSettled([getMyCoaching(), listWeeklyReports()]);
    if (c.status === "fulfilled") setCoaching(c.value);
    if (r.status === "fulfilled") setReports(r.value.filter((x) => x.status !== "PENDING"));
  }, []);
  useEffect(() => {
    load().finally(() => setLoading(false));
  }, [load]);
  return { coaching, reports, loading, reload: load };
}

/** The parsed JSON of a weekly report (empty when it can't be read). */
export function reportBody(r: WeeklyReportDTO): Record<string, unknown> {
  try {
    return r.reportData ? JSON.parse(r.reportData) : {};
  } catch {
    return {};
  }
}

export function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div>
      <p className="text-[11px] uppercase tracking-wider font-semibold text-gray-500 mb-1.5">{title}</p>
      <div className="rounded-xl border border-gray-200 bg-white divide-y divide-gray-100">{children}</div>
    </div>
  );
}

export function Empty({ text }: { text: string }) {
  return <p className="px-4 py-3 text-sm text-gray-400 italic">{text}</p>;
}

export function Loading() {
  return (
    <div className="text-center py-10">
      <Loader2 size={20} className="animate-spin text-sage-navy inline" />
    </div>
  );
}

/**
 * "Sep 24, 2026". A plain date ("2026-09-24") is read as that calendar day,
 * not as midnight UTC, which would show the day before in the US.
 */
export function fmt(value: string | null | undefined): string {
  if (!value) return "";
  // A plain date stays that day; a server timestamp (UTC) is shown in Central time.
  return formatDay(value);
}

export function SessionList({ sessions, empty }: { sessions: CoachingSessionEntry[]; empty: string }) {
  if (sessions.length === 0) return <Empty text={empty} />;
  return (
    <>
      {sessions.map((s) => (
        <div key={s.id} className="px-4 py-3 text-sm">
          <div className="flex items-center gap-2">
            <span className="font-medium text-gray-900">{s.topic || "Coaching session"}</span>
            <span className="text-xs text-gray-500 ml-auto">{s.date ? fmt(s.date) : ""}</span>
          </div>
          <p className="text-xs text-gray-500 mt-0.5">
            {s.coachName ?? "Your coach"}
            {s.durationMinutes ? ` · ${s.durationMinutes} min` : ""}
          </p>
          {s.nextSteps && <p className="text-xs text-gray-700 mt-1">Next steps: {s.nextSteps}</p>}
        </div>
      ))}
    </>
  );
}

export function FeedbackList({ feedback, empty }: { feedback: CoachingFeedbackEntry[]; empty: string }) {
  if (feedback.length === 0) return <Empty text={empty} />;
  return (
    <>
      {feedback.map((f) => (
        <div key={f.id} className="px-4 py-3 text-sm">
          <div className="flex items-center gap-2">
            <span className="text-xs text-gray-500">{f.coachName ?? "Your coach"}</span>
            {f.rating ? <span className="text-xs text-gray-500">· {f.rating}/5</span> : null}
            <span className="text-xs text-gray-400 ml-auto">{fmt(f.createdAt)}</span>
          </div>
          <p className="text-gray-800 mt-1 whitespace-pre-wrap">{f.content}</p>
        </div>
      ))}
    </>
  );
}

export function TaskList({ tasks, empty, onChanged }: {
  tasks: CoachingTaskEntry[];
  empty: string;
  onChanged: () => Promise<void>;
}) {
  const [busy, setBusy] = useState<number | null>(null);
  const [error, setError] = useState("");
  if (tasks.length === 0) return <Empty text={empty} />;
  const done = async (id: number) => {
    setBusy(id);
    setError("");
    try {
      await markCoachingTaskDone(id);
      await onChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't update the task");
    } finally {
      setBusy(null);
    }
  };
  return (
    <>
      {error && <p className="px-4 py-2 text-xs text-red-700">{error}</p>}
      {tasks.map((t) => (
        <div key={t.id} className="px-4 py-3 text-sm flex items-start gap-3">
          <div className="flex-1 min-w-0">
            <p className={"font-medium " + (t.status === "DONE" ? "text-gray-400 line-through" : "text-gray-900")}>{t.title}</p>
            {t.description && <p className="text-xs text-gray-600 mt-0.5">{t.description}</p>}
            <p className="text-xs text-gray-500 mt-0.5">
              {t.coachName ?? "Your coach"}
              {t.dueDate ? ` · due ${fmt(t.dueDate)}` : ""}
            </p>
          </div>
          {t.status === "DONE" ? (
            <span className="inline-flex items-center gap-1 text-xs font-semibold text-emerald-700 shrink-0">
              <CheckCircle2 size={12} /> Done
            </span>
          ) : (
            <button
              type="button"
              onClick={() => done(t.id)}
              disabled={busy === t.id}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-60 cursor-pointer shrink-0"
            >
              {busy === t.id && <Loader2 size={12} className="animate-spin" />} Mark done
            </button>
          )}
        </div>
      ))}
    </>
  );
}
