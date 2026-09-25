"use client";

import { useEffect, useState } from "react";
import { Eye, FileText } from "lucide-react";

import {
  listParticipantDocuments,
  viewParticipantDocument,
  type ParticipantDocument,
} from "@/lib/api";
import {
  Empty, FeedbackList, fmt, Loading, reportBody, Section, TaskList, useCoachingData,
} from "./CoachingParts";

const FILE_STATUS: Record<string, string> = {
  PENDING: "Waiting for review",
  APPROVED: "Approved",
  REJECTED: "Sent back",
};

/**
 * Checklist 4.4: the participant's resume work — their resume files, their
 * resume specialist's feedback and tasks, and the resume / profile /
 * LinkedIn updates they logged in their weekly reports.
 */
export default function ResumeTab() {
  const { coaching, reports, loading, reload } = useCoachingData();
  const [files, setFiles] = useState<ParticipantDocument[]>([]);
  useEffect(() => {
    listParticipantDocuments()
      .then((d) => setFiles(d.filter((x) => x.documentType === "RESUME" && !x.notApplicable)))
      .catch(() => {});
  }, []);
  if (loading) return <Loading />;

  const feedback = coaching.feedback.filter((f) => f.type === "RESUME" || f.coachRole === "RESUME_SPECIALIST");
  const tasks = coaching.tasks.filter((t) => t.coachRole === "RESUME_SPECIALIST");
  const updates = reports
    .map((r) => ({ week: r.weekStart, a: (reportBody(r).resumeActivities ?? {}) as Record<string, string> }))
    .filter(({ a }) => Object.values(a).some((v) => v && String(v).trim()));

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold text-gray-900">Resume</h1>
      <p className="text-sm text-gray-500">
        Your resume files, what your resume specialist said, and the updates you logged each week.
      </p>

      <Section title="Your resume files">
        {files.length === 0 ? (
          <Empty text="No resume uploaded yet." />
        ) : (
          files.map((d) => (
            <div key={d.id} className="px-4 py-3 flex items-center gap-3 text-sm">
              <FileText size={14} className="text-gray-400" />
              <span className="flex-1 font-medium text-gray-800 truncate">{d.fileName}</span>
              <span className="text-xs text-gray-500">{FILE_STATUS[d.reviewStatus] ?? d.reviewStatus}</span>
              <button
                type="button"
                onClick={() => viewParticipantDocument(d.id).catch(() => {})}
                className="text-sage-navy hover:text-sage-navy-deep cursor-pointer"
                aria-label="View"
              >
                <Eye size={14} />
              </button>
            </div>
          ))
        )}
      </Section>

      <Section title="Feedback from your resume specialist">
        <FeedbackList feedback={feedback} empty="No resume feedback yet." />
      </Section>

      <Section title="Tasks from your resume specialist">
        <TaskList tasks={tasks} empty="No resume tasks right now." onChanged={reload} />
      </Section>

      <Section title="Resume, profile and LinkedIn updates (from your weekly reports)">
        {updates.length === 0 ? (
          <Empty text="Log resume and profile updates in your weekly report and they'll appear here." />
        ) : (
          updates.map(({ week, a }) => (
            <div key={week} className="px-4 py-3 text-sm">
              <p className="text-xs text-gray-500">Week of {fmt(week)}</p>
              <dl className="mt-1 grid grid-cols-1 sm:grid-cols-2 gap-x-4 gap-y-1 text-xs">
                {([["Resume version", a.resumeVersion], ["Profile updates", a.profileUpdates],
                  ["Portal updates", a.portalUpdates], ["LinkedIn updates", a.linkedinUpdates]] as const)
                  .filter(([, v]) => v && String(v).trim())
                  .map(([k, v]) => (
                    <div key={k}>
                      <dt className="text-gray-500">{k}</dt>
                      <dd className="text-gray-800 whitespace-pre-wrap">{v}</dd>
                    </div>
                  ))}
              </dl>
            </div>
          ))
        )}
      </Section>
    </div>
  );
}
