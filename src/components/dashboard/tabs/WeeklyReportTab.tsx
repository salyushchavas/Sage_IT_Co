"use client";

import { useEffect, useMemo, useState } from "react";
import {
  AlertCircle,
  CheckCircle2,
  Loader2,
  Plus,
  Save,
  Send,
  Trash2,
} from "lucide-react";

import {
  listWeeklyReports,
  saveWeeklyReportDraft,
  submitWeeklyReport,
  type ParticipantDashboard as DashboardData,
  type WeeklyReportDTO,
  type WeeklyReportJobSubmission,
  type WeeklyReportRequest,
} from "@/lib/api";

/**
 * Weekly job-search submission report. Four sections:
 *
 *   1. Job submissions -- dynamic row array, each with company,
 *      client, title, technology, portal, link, submission date,
 *      status, follow-up date. Add / remove buttons.
 *
 *   2. Resume / profile activities -- single-row form covering
 *      resume version, profile updates, portal updates, LinkedIn.
 *
 *   3. Interview training -- mock date, topic, coach, feedback,
 *      improvements, next practice.
 *
 *   4. Communication / acknowledgment -- messages acknowledged,
 *      questions for ERM, escalation checkbox (+ detail when on).
 *
 * Two write paths: Save draft (idempotent, doesn't flip status)
 * and Submit (locks for ERM review).
 *
 * On mount: lists past reports, pre-fills the form from the
 * current-week draft if one exists. dashboardData supplies
 * currentWeekStart / currentWeekEnd which gate the request shape.
 */
interface WeeklyFormState {
  jobs: WeeklyReportJobSubmission[];
  resumeVersion: string;
  profileUpdates: string;
  portalUpdates: string;
  linkedinUpdates: string;
  mockDate: string;
  interviewTopic: string;
  coach: string;
  feedback: string;
  improvements: string;
  nextPractice: string;
  messagesAck: string;
  questions: string;
  escalation: boolean;
  escalationDetail: string;
}

const blankJob = (): WeeklyReportJobSubmission => ({
  company: "",
  client: "",
  jobTitle: "",
  technology: "",
  portal: "",
  applicationLink: "",
  submissionDate: "",
  status: "Applied",
  followUpDate: "",
});

const blankForm = (): WeeklyFormState => ({
  jobs: [blankJob()],
  resumeVersion: "",
  profileUpdates: "",
  portalUpdates: "",
  linkedinUpdates: "",
  mockDate: "",
  interviewTopic: "",
  coach: "",
  feedback: "",
  improvements: "",
  nextPractice: "",
  messagesAck: "",
  questions: "",
  escalation: false,
  escalationDetail: "",
});

interface Props {
  dashboardData: DashboardData;
}

export default function WeeklyReportTab({ dashboardData }: Props) {
  const [reports, setReports] = useState<WeeklyReportDTO[]>([]);
  const [form, setForm] = useState<WeeklyFormState>(blankForm());
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [feedback, setFeedback] = useState("");
  const [error, setError] = useState("");

  // Load reports + pre-fill form from the current-week draft (if any).
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const list = await listWeeklyReports();
        if (cancelled) return;
        setReports(list);
        const weekStart = dashboardData.currentWeekStart;
        if (weekStart) {
          const current = list.find((r) => r.weekStart === weekStart);
          if (current && current.reportData) {
            try {
              const parsed = JSON.parse(current.reportData);
              setForm({
                jobs:
                  Array.isArray(parsed.jobSubmissions) &&
                  parsed.jobSubmissions.length > 0
                    ? parsed.jobSubmissions
                    : [blankJob()],
                resumeVersion: parsed.resumeActivities?.resumeVersion ?? "",
                profileUpdates: parsed.resumeActivities?.profileUpdates ?? "",
                portalUpdates: parsed.resumeActivities?.portalUpdates ?? "",
                linkedinUpdates: parsed.resumeActivities?.linkedinUpdates ?? "",
                mockDate: parsed.interviewTraining?.mockDate ?? "",
                interviewTopic: parsed.interviewTraining?.topic ?? "",
                coach: parsed.interviewTraining?.coach ?? "",
                feedback: parsed.interviewTraining?.feedback ?? "",
                improvements: parsed.interviewTraining?.improvements ?? "",
                nextPractice: parsed.interviewTraining?.nextPracticeDate ?? "",
                messagesAck:
                  parsed.communications?.messagesAcknowledged ?? "",
                questions: parsed.communications?.questions ?? "",
                escalation: parsed.communications?.escalation === "true",
                escalationDetail:
                  parsed.communications?.escalationDetail ?? "",
              });
            } catch {
              /* corrupt draft -- keep blank form */
            }
          }
        }
      } catch (err) {
        if (!cancelled)
          setError(err instanceof Error ? err.message : "Couldn't load reports");
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [dashboardData.currentWeekStart]);

  const metrics = useMemo(() => {
    const subs = form.jobs.filter((j) => (j.company ?? "").trim());
    const followups = subs.filter((j) =>
      (j.followUpDate ?? "").trim(),
    ).length;
    const interviews = subs.filter((j) => j.status === "Interview").length;
    return { submissions: subs.length, followups, interviews };
  }, [form]);

  const toRequest = (): WeeklyReportRequest => ({
    weekStart: dashboardData.currentWeekStart,
    weekEnd: dashboardData.currentWeekEnd,
    jobSubmissions: form.jobs.filter((j) => (j.company ?? "").trim()),
    resumeActivities: {
      resumeVersion: form.resumeVersion,
      profileUpdates: form.profileUpdates,
      portalUpdates: form.portalUpdates,
      linkedinUpdates: form.linkedinUpdates,
    },
    interviewTraining: {
      mockDate: form.mockDate,
      topic: form.interviewTopic,
      coach: form.coach,
      feedback: form.feedback,
      improvements: form.improvements,
      nextPracticeDate: form.nextPractice,
    },
    communications: {
      messagesAcknowledged: form.messagesAck,
      questions: form.questions,
      escalation: form.escalation ? "true" : "false",
      escalationDetail: form.escalation ? form.escalationDetail : "",
    },
  });

  const handleSaveDraft = async () => {
    setSaving(true);
    setError("");
    setFeedback("");
    try {
      await saveWeeklyReportDraft(toRequest());
      setFeedback("Draft saved.");
      setReports(await listWeeklyReports());
    } catch (err) {
      setError(err instanceof Error ? err.message : "Couldn't save draft");
    } finally {
      setSaving(false);
    }
  };

  const handleSubmit = async () => {
    setSubmitting(true);
    setError("");
    setFeedback("");
    try {
      await submitWeeklyReport(toRequest());
      setFeedback("Report submitted. Your ERM will review and respond.");
      setReports(await listWeeklyReports());
    } catch (err) {
      setError(err instanceof Error ? err.message : "Couldn't submit report");
    } finally {
      setSubmitting(false);
    }
  };

  function updateJob(
    idx: number,
    key: keyof WeeklyReportJobSubmission,
    value: string,
  ) {
    setForm((p) => {
      const jobs = [...p.jobs];
      jobs[idx] = { ...jobs[idx], [key]: value };
      return { ...p, jobs };
    });
  }

  if (loading) {
    return (
      <div className="text-center py-10">
        <Loader2 size={20} className="animate-spin text-sage-navy inline" />
      </div>
    );
  }

  return (
    <div className="space-y-5">
      <div className="flex items-center justify-between flex-wrap gap-2">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">
            Weekly submission report
          </h1>
          <p className="text-xs text-gray-500 mt-0.5">
            Week of{" "}
            <span className="font-mono">
              {dashboardData.currentWeekStart}
            </span>{" "}
            –{" "}
            <span className="font-mono">{dashboardData.currentWeekEnd}</span>
            {dashboardData.currentWeekEnd && (
              <>
                {" · "}due{" "}
                <span className="font-mono">
                  {addOneDay(dashboardData.currentWeekEnd)}
                </span>
              </>
            )}
          </p>
        </div>
      </div>

      {/* Metrics */}
      <div className="grid grid-cols-3 gap-2.5">
        <Stat label="Submissions" value={metrics.submissions} />
        <Stat label="Follow-ups" value={metrics.followups} />
        <Stat label="Interviews" value={metrics.interviews} />
      </div>

      {/* Job submissions */}
      <Section title="Job submissions">
        <div className="space-y-3">
          {form.jobs.map((job, idx) => (
            <div
              key={idx}
              className="rounded-xl border border-gray-200 bg-white p-3 grid grid-cols-1 sm:grid-cols-2 gap-2"
            >
              <Input
                label="Company *"
                value={job.company ?? ""}
                onChange={(v) => updateJob(idx, "company", v)}
              />
              <Input
                label="Client / Vendor"
                value={job.client ?? ""}
                onChange={(v) => updateJob(idx, "client", v)}
              />
              <Input
                label="Job title *"
                value={job.jobTitle ?? ""}
                onChange={(v) => updateJob(idx, "jobTitle", v)}
              />
              <Input
                label="Technology *"
                value={job.technology ?? ""}
                onChange={(v) => updateJob(idx, "technology", v)}
              />
              <Input
                label="Portal / source *"
                value={job.portal ?? ""}
                onChange={(v) => updateJob(idx, "portal", v)}
              />
              <Input
                label="Application link"
                value={job.applicationLink ?? ""}
                onChange={(v) => updateJob(idx, "applicationLink", v)}
              />
              <Input
                label="Submission date *"
                type="date"
                value={job.submissionDate ?? ""}
                onChange={(v) => updateJob(idx, "submissionDate", v)}
              />
              <Select
                label="Status *"
                value={job.status ?? "Applied"}
                onChange={(v) => updateJob(idx, "status", v)}
                options={[
                  "Applied",
                  "Screening",
                  "Interview",
                  "Offer",
                  "Rejected",
                  "Withdrawn",
                ]}
              />
              <Input
                label="Follow-up date"
                type="date"
                value={job.followUpDate ?? ""}
                onChange={(v) => updateJob(idx, "followUpDate", v)}
              />
              {form.jobs.length > 1 && (
                <button
                  type="button"
                  onClick={() =>
                    setForm((p) => ({
                      ...p,
                      jobs: p.jobs.filter((_, i) => i !== idx),
                    }))
                  }
                  className="sm:col-span-2 inline-flex items-center gap-1.5 self-end text-xs font-semibold text-gray-500 hover:text-red-700 cursor-pointer"
                >
                  <Trash2 size={11} /> Remove this submission
                </button>
              )}
            </div>
          ))}
          <button
            type="button"
            onClick={() =>
              setForm((p) => ({ ...p, jobs: [...p.jobs, blankJob()] }))
            }
            className="inline-flex items-center gap-1.5 text-xs font-semibold text-sage-navy hover:text-sage-navy-deep cursor-pointer"
          >
            <Plus size={12} /> Add another submission
          </button>
        </div>
      </Section>

      <Section title="Resume / profile activities">
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
          <Input
            label="Resume version used"
            value={form.resumeVersion}
            onChange={(v) => setForm((p) => ({ ...p, resumeVersion: v }))}
          />
          <Input
            label="Profile updates"
            value={form.profileUpdates}
            onChange={(v) => setForm((p) => ({ ...p, profileUpdates: v }))}
          />
          <Input
            label="Portal profile updates"
            value={form.portalUpdates}
            onChange={(v) => setForm((p) => ({ ...p, portalUpdates: v }))}
          />
          <Input
            label="LinkedIn profile updates"
            value={form.linkedinUpdates}
            onChange={(v) => setForm((p) => ({ ...p, linkedinUpdates: v }))}
          />
        </div>
      </Section>

      <Section title="Interview training">
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
          <Input
            label="Mock interview date"
            type="date"
            value={form.mockDate}
            onChange={(v) => setForm((p) => ({ ...p, mockDate: v }))}
          />
          <Input
            label="Interview topic"
            value={form.interviewTopic}
            onChange={(v) => setForm((p) => ({ ...p, interviewTopic: v }))}
          />
          <Input
            label="Coach"
            value={form.coach}
            onChange={(v) => setForm((p) => ({ ...p, coach: v }))}
          />
          <Input
            label="Feedback received"
            value={form.feedback}
            onChange={(v) => setForm((p) => ({ ...p, feedback: v }))}
          />
          <Input
            label="Improvement items"
            value={form.improvements}
            onChange={(v) => setForm((p) => ({ ...p, improvements: v }))}
          />
          <Input
            label="Next practice date"
            type="date"
            value={form.nextPractice}
            onChange={(v) => setForm((p) => ({ ...p, nextPractice: v }))}
          />
        </div>
      </Section>

      <Section title="Communication / acknowledgment">
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
          <Input
            label="Important messages acknowledged"
            value={form.messagesAck}
            onChange={(v) => setForm((p) => ({ ...p, messagesAck: v }))}
          />
          <Input
            label="Questions for ERM"
            value={form.questions}
            onChange={(v) => setForm((p) => ({ ...p, questions: v }))}
          />
        </div>
        <label className="mt-3 flex items-start gap-2 text-sm cursor-pointer">
          <input
            type="checkbox"
            checked={form.escalation}
            onChange={(e) =>
              setForm((p) => ({ ...p, escalation: e.target.checked }))
            }
            className="mt-0.5 w-4 h-4 rounded border-gray-300 text-sage-navy focus:ring-sage-copper"
          />
          <span className="text-gray-700">
            Escalate this week to my ERM — I need help with a blocker.
          </span>
        </label>
        {form.escalation && (
          <div className="mt-2">
            <Input
              label="Briefly describe the blocker"
              value={form.escalationDetail}
              onChange={(v) =>
                setForm((p) => ({ ...p, escalationDetail: v }))
              }
            />
          </div>
        )}
      </Section>

      {error && (
        <p className="inline-flex items-center gap-1.5 text-sm text-red-700">
          <AlertCircle size={14} /> {error}
        </p>
      )}
      {feedback && (
        <p className="inline-flex items-center gap-1.5 text-sm text-emerald-700">
          <CheckCircle2 size={14} /> {feedback}
        </p>
      )}

      <div className="flex flex-col sm:flex-row gap-2">
        <button
          type="button"
          onClick={handleSaveDraft}
          disabled={saving || submitting}
          className="inline-flex items-center justify-center gap-2 px-4 py-2.5 rounded-lg text-sm font-semibold bg-white border border-gray-200 text-gray-700 hover:border-sage-navy hover:text-sage-navy disabled:opacity-60 cursor-pointer transition"
        >
          {saving ? (
            <Loader2 size={14} className="animate-spin" />
          ) : (
            <Save size={14} />
          )}
          {saving ? "Saving…" : "Save draft"}
        </button>
        <button
          type="button"
          onClick={handleSubmit}
          disabled={submitting || saving}
          className="flex-1 inline-flex items-center justify-center gap-2 px-4 py-2.5 rounded-lg text-sm font-bold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-60 cursor-pointer transition"
        >
          {submitting ? (
            <Loader2 size={14} className="animate-spin" />
          ) : (
            <Send size={14} />
          )}
          {submitting ? "Submitting…" : "Submit report"}
        </button>
      </div>

      {/* History */}
      {reports.length > 0 && (
        <div className="mt-8">
          <p className="text-[11px] uppercase tracking-wider font-semibold text-gray-500 mb-2">
            Submitted reports
          </p>
          <div className="rounded-xl border border-gray-200 bg-white overflow-hidden divide-y divide-gray-100">
            {reports.map((r) => (
              <div
                key={r.id}
                className="px-4 py-3 flex items-center gap-3 text-sm"
              >
                <span className="font-mono text-xs text-gray-700 w-44 shrink-0">
                  {r.weekStart} – {r.weekEnd}
                </span>
                <span
                  className={
                    "px-2 py-0.5 rounded-full text-[10px] font-bold " +
                    (r.status === "SUBMITTED"
                      ? "bg-emerald-50 text-emerald-700"
                      : r.status === "REVIEWED"
                        ? "bg-blue-50 text-blue-700"
                        : r.status === "OVERDUE"
                          ? "bg-red-50 text-red-700"
                          : "bg-gray-100 text-gray-600")
                  }
                >
                  {r.status}
                </span>
                {r.submittedAt && (
                  <span className="text-xs text-gray-500">
                    Submitted{" "}
                    {new Date(r.submittedAt).toLocaleDateString("en-IN", {
                      timeZone: "Asia/Kolkata",
                    })}
                  </span>
                )}
                {r.ermNotes && (
                  <span className="text-xs text-gray-500 truncate ml-auto">
                    ERM: {r.ermNotes}
                  </span>
                )}
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

function Section({
  title,
  children,
}: {
  title: string;
  children: React.ReactNode;
}) {
  return (
    <div>
      <p className="text-[11px] uppercase tracking-wider font-semibold text-gray-500 mb-1.5">
        {title}
      </p>
      <div className="rounded-2xl border border-gray-100 bg-white p-3 sm:p-4 shadow-sm">
        {children}
      </div>
    </div>
  );
}

function Stat({
  label,
  value,
}: {
  label: string;
  value: number | string;
}) {
  return (
    <div className="bg-white rounded-xl border border-gray-100 shadow-sm p-3 text-center">
      <p className="text-[10px] uppercase tracking-wider font-semibold text-gray-500">
        {label}
      </p>
      <p className="mt-0.5 text-xl font-bold text-gray-900">{value}</p>
    </div>
  );
}

function Input({
  label,
  value,
  onChange,
  type = "text",
}: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  type?: string;
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

function Select({
  label,
  value,
  onChange,
  options,
}: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  options: ReadonlyArray<string>;
}) {
  return (
    <div>
      <label className="block text-[11px] font-medium text-gray-600 mb-0.5">
        {label}
      </label>
      <select
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="w-full px-3 py-1.5 text-sm rounded-md border border-gray-200 bg-white focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy"
      >
        {options.map((o) => (
          <option key={o} value={o}>
            {o}
          </option>
        ))}
      </select>
    </div>
  );
}

/** YYYY-MM-DD + 1 day -- used for the weekly report's due date hint. */
function addOneDay(isoDate: string | null | undefined): string {
  if (!isoDate) return "";
  const d = new Date(isoDate + "T00:00:00");
  if (Number.isNaN(d.getTime())) return "";
  d.setDate(d.getDate() + 1);
  return d.toISOString().slice(0, 10);
}
