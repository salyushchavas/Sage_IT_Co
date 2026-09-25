"use client";

import {
  Empty, FeedbackList, fmt, Loading, reportBody, Section, SessionList, TaskList, useCoachingData,
} from "./CoachingParts";

/**
 * Checklist 4.4: the participant's interview preparation — sessions with
 * their interview coach, interview feedback and practice tasks, the mock
 * interviews they logged, and the interviews from their job search.
 */
export default function InterviewTab() {
  const { coaching, reports, loading, reload } = useCoachingData();
  if (loading) return <Loading />;

  const sessions = coaching.sessions.filter((s) => s.coachRole === "INTERVIEW_COACH");
  const feedback = coaching.feedback.filter((f) => f.type === "INTERVIEW" || f.coachRole === "INTERVIEW_COACH");
  const tasks = coaching.tasks.filter((t) => t.coachRole === "INTERVIEW_COACH");
  const mocks = reports
    .map((r) => ({ week: r.weekStart, t: (reportBody(r).interviewTraining ?? {}) as Record<string, string> }))
    .filter(({ t }) => Object.values(t).some((v) => v && String(v).trim()));
  const interviews: { company?: string; jobTitle?: string; status?: string }[] = reports.flatMap((r) => {
    const jobs = reportBody(r).jobSubmissions;
    return (Array.isArray(jobs) ? (jobs as Record<string, string>[]) : [])
      .filter((j) => j.status === "Interview" || j.status === "Offer");
  });

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold text-gray-900">Interviews</h1>
      <p className="text-sm text-gray-500">
        Your interview coaching, practice tasks, mock interviews and real interviews in one place.
      </p>

      <Section title="Sessions with your interview coach">
        <SessionList sessions={sessions} empty="No interview coaching sessions logged yet." />
      </Section>

      <Section title="Interview feedback">
        <FeedbackList feedback={feedback} empty="No interview feedback yet." />
      </Section>

      <Section title="Practice tasks">
        <TaskList tasks={tasks} empty="No practice tasks right now." onChanged={reload} />
      </Section>

      <Section title="Mock interviews you logged">
        {mocks.length === 0 ? (
          <Empty text="Log mock interviews in your weekly report and they'll appear here." />
        ) : (
          mocks.map(({ week, t }) => (
            <div key={week} className="px-4 py-3 text-sm">
              <div className="flex items-center gap-2">
                <span className="font-medium text-gray-900">{t.topic || "Mock interview"}</span>
                <span className="text-xs text-gray-500 ml-auto">{t.mockDate ? fmt(t.mockDate) : `Week of ${fmt(week)}`}</span>
              </div>
              {t.coach && <p className="text-xs text-gray-500 mt-0.5">With {t.coach}</p>}
              {t.feedback && <p className="text-xs text-gray-700 mt-1">Feedback: {t.feedback}</p>}
              {t.improvements && <p className="text-xs text-gray-700">To improve: {t.improvements}</p>}
              {t.nextPracticeDate && <p className="text-xs text-gray-500">Next practice: {t.nextPracticeDate}</p>}
            </div>
          ))
        )}
      </Section>

      <Section title="Interviews from your job search">
        {interviews.length === 0 ? (
          <Empty text="Job submissions you mark as Interview or Offer in your weekly report appear here." />
        ) : (
          interviews.map((j, i) => (
            <div key={i} className="px-4 py-3 flex items-center gap-3 text-sm">
              <span className="font-medium text-gray-900">{j.company}</span>
              <span className="text-gray-600">{j.jobTitle}</span>
              <span className="ml-auto px-2 py-0.5 rounded-full text-[10px] font-bold bg-emerald-50 text-emerald-700">
                {j.status}
              </span>
            </div>
          ))
        )}
      </Section>
    </div>
  );
}
