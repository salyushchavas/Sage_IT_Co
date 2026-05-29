"use client";

import { ArrowRight, CheckCircle2, Mail } from "lucide-react";

import type {
  ParticipantDashboard as DashboardData,
  ParticipantTeam,
} from "@/lib/api";

interface Props {
  data: DashboardData;
  team: ParticipantTeam | null;
  userEmail: string | null;
  onJumpTo: (id: string) => void;
}

function greeting(): string {
  const h = new Date().getHours();
  if (h < 12) return "Good morning";
  if (h < 17) return "Good afternoon";
  return "Good evening";
}

export default function HomeTab({ data, team, userEmail, onJumpTo }: Props) {
  const firstName = (data.fullName ?? userEmail ?? "there").split(" ")[0];
  const progressPct =
    data.roadmapTotal > 0
      ? Math.round((data.roadmapStep / data.roadmapTotal) * 100)
      : 0;
  const coaches = team?.coaches ?? {};
  const coachEntries = Object.entries(coaches);

  return (
    <div className="space-y-5">
      {/* Header */}
      <div>
        <h1 className="text-2xl font-bold text-gray-900">
          {greeting()}, {firstName}!
        </h1>
        <p className="mt-0.5 text-sm text-gray-500">
          Participant ID:{" "}
          <span className="font-mono text-gray-700">
            {data.participantId ?? "—"}
          </span>
        </p>
      </div>

      {/* Roadmap */}
      <div className="bg-white rounded-2xl border border-gray-100 shadow-sm p-5">
        <div className="flex items-center justify-between mb-3">
          <p className="text-[11px] uppercase tracking-wider font-semibold text-gray-500">
            Your roadmap
          </p>
          <p className="text-xs text-gray-500">
            Step{" "}
            <span className="font-bold text-sage-navy">{data.roadmapStep}</span>{" "}
            of {data.roadmapTotal}:{" "}
            <span className="font-semibold text-gray-700">
              {data.roadmapLabels[Math.max(0, data.roadmapStep - 1)]}
            </span>
          </p>
        </div>
        <div className="h-2 w-full rounded-full bg-gray-100 overflow-hidden">
          <div
            className="h-full bg-sage-navy transition-all"
            style={{ width: `${progressPct}%` }}
          />
        </div>
        <div className="mt-3 flex flex-wrap gap-1.5">
          {data.roadmapLabels.map((label, idx) => {
            const stepNum = idx + 1;
            const isDone = stepNum < data.roadmapStep;
            const isActive = stepNum === data.roadmapStep;
            return (
              <span
                key={label}
                title={`${stepNum}. ${label}`}
                className={
                  "inline-flex items-center justify-center w-5 h-5 rounded-full text-[9px] font-bold " +
                  (isDone
                    ? "bg-emerald-600 text-white"
                    : isActive
                      ? "bg-sage-navy text-white ring-2 ring-sage-navy/30 animate-pulse"
                      : "bg-white border border-gray-200 text-gray-400")
                }
              >
                {isDone ? "✓" : stepNum}
              </span>
            );
          })}
        </div>

        {data.nextAction && (
          <div className="mt-4 rounded-xl border border-sage-navy/20 bg-sage-navy/5 p-3 flex items-center justify-between gap-3">
            <div>
              <p className="text-[11px] uppercase tracking-wider font-semibold text-sage-navy">
                Next action
              </p>
              <p className="text-sm font-bold text-gray-900 mt-0.5">
                {data.nextAction.label}
              </p>
            </div>
            <button
              type="button"
              onClick={() => {
                if (data.nextAction.href.startsWith("#")) {
                  onJumpTo(data.nextAction.href.slice(1));
                } else {
                  window.location.href = data.nextAction.href;
                }
              }}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep cursor-pointer"
            >
              Go <ArrowRight size={12} />
            </button>
          </div>
        )}
      </div>

      {/* Team summary */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-2.5">
        <SmallTeamCard
          role="ERM"
          name={team?.erm?.name ?? null}
          email={team?.erm?.email ?? null}
        />
        {coachEntries.slice(0, 3).map(([label, name]) => (
          <SmallTeamCard
            key={label}
            role={label}
            name={name && name !== "Awaiting assignment" ? name : null}
          />
        ))}
      </div>

      {/* Stats + program */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-3">
        <div className="bg-white rounded-2xl border border-gray-100 shadow-sm p-4">
          <p className="text-[11px] uppercase tracking-wider font-semibold text-gray-500">
            Weeks enrolled
          </p>
          <p className="mt-1 text-3xl font-bold text-gray-900">
            {data.stats?.weeksEnrolled ?? 0}
          </p>
        </div>
        <div className="bg-white rounded-2xl border border-gray-100 shadow-sm p-4">
          <p className="text-[11px] uppercase tracking-wider font-semibold text-gray-500">
            Reports submitted
          </p>
          <p className="mt-1 text-3xl font-bold text-gray-900">
            {data.stats?.reportsSubmitted ?? 0}
          </p>
        </div>
        <div className="bg-white rounded-2xl border border-gray-100 shadow-sm p-4">
          <p className="text-[11px] uppercase tracking-wider font-semibold text-gray-500">
            Program
          </p>
          <p className="mt-1 text-sm font-bold text-gray-900 leading-tight">
            {data.program?.program ?? "—"}
          </p>
          <p className="text-[11px] text-gray-500 mt-0.5">
            {data.program?.skillset} · {data.program?.targetJobTitle}
          </p>
        </div>
      </div>

      <div className="bg-white rounded-2xl border border-gray-100 shadow-sm p-5">
        <p className="text-[11px] uppercase tracking-wider font-semibold text-gray-500 mb-2">
          Recent activity
        </p>
        {data.recentActivity && data.recentActivity.length > 0 ? (
          <ul className="space-y-2">
            {data.recentActivity.map((a, idx) => (
              <li key={idx} className="flex items-start gap-2 text-sm">
                <CheckCircle2
                  size={14}
                  className="text-emerald-600 mt-0.5 shrink-0"
                />
                <div className="flex-1 min-w-0">
                  <p className="text-gray-800">{a.title}</p>
                  <p className="text-[11px] text-gray-400">
                    {new Date(a.createdAt).toLocaleString("en-IN", {
                      timeZone: "Asia/Kolkata",
                      dateStyle: "medium",
                      timeStyle: "short",
                    })}
                  </p>
                </div>
              </li>
            ))}
          </ul>
        ) : (
          <p className="text-xs text-gray-400 italic">No activity yet.</p>
        )}
      </div>
    </div>
  );
}

function SmallTeamCard({
  role,
  name,
  email,
}: {
  role: string;
  name: string | null;
  email?: string | null;
}) {
  return (
    <div
      className={
        "rounded-xl border p-3 " +
        (name
          ? "border-emerald-200 bg-emerald-50/40"
          : "border-dashed border-gray-200 bg-gray-50/60")
      }
    >
      <p className="text-[10px] uppercase tracking-wider font-bold text-gray-500">
        {role}
      </p>
      <p
        className={
          "mt-1 text-sm font-semibold " +
          (name ? "text-gray-900" : "text-gray-400 italic")
        }
      >
        {name ?? "Awaiting…"}
      </p>
      {email && (
        <p className="mt-0.5 text-[11px] text-gray-500 inline-flex items-center gap-1 truncate">
          <Mail size={10} /> {email}
        </p>
      )}
    </div>
  );
}
