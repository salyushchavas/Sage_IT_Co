"use client";

import { Mail } from "lucide-react";

import type {
  ParticipantDashboard as DashboardData,
  ParticipantTeam,
} from "@/lib/api";

interface Props {
  team: ParticipantTeam | null;
  data: DashboardData;
}

function subtitleForRole(role: string, skillset?: string): string {
  switch (role) {
    case "Career Coach":
      return "General career guidance, job-market navigation.";
    case "Resume Specialist":
      return "Resume edits, profile / LinkedIn optimisation.";
    case "Technical Advisor":
      return `Technical mentor — ${skillset ?? "matched to your skillset"}.`;
    case "Interview Coach":
      return "Mock interviews, communication coaching.";
    default:
      return "";
  }
}

export default function TeamTab({ team, data }: Props) {
  const coaches = Object.entries(team?.coaches ?? {});
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold text-gray-900">Your support team</h1>
      <p className="text-sm text-gray-500">
        Reach out to anyone on your team via the email below. Your ERM is your
        primary communication owner.
      </p>
      <div className="space-y-3">
        <BigTeamCard
          role="Employee Relationship Manager (ERM)"
          name={team?.erm?.name ?? null}
          email={team?.erm?.email ?? null}
          subtitle="Primary communication owner — reviews your weekly reports and guides program execution."
        />
        {coaches.map(([label, name]) => (
          <BigTeamCard
            key={label}
            role={label}
            name={name && name !== "Awaiting assignment" ? name : null}
            subtitle={subtitleForRole(label, data.program?.skillset)}
          />
        ))}
      </div>
    </div>
  );
}

function BigTeamCard({
  role,
  name,
  email,
  subtitle,
}: {
  role: string;
  name: string | null;
  email?: string | null;
  subtitle: string;
}) {
  return (
    <div
      className={
        "rounded-2xl border bg-white p-4 sm:p-5 flex items-start gap-3 " +
        (name ? "border-gray-200" : "border-dashed border-gray-200")
      }
    >
      <div
        className={
          "shrink-0 w-10 h-10 rounded-full flex items-center justify-center font-bold text-sm " +
          (name ? "bg-sage-navy text-white" : "bg-gray-100 text-gray-400")
        }
      >
        {name
          ? name
              .split(" ")
              .map((w) => w[0])
              .slice(0, 2)
              .join("")
              .toUpperCase()
          : "?"}
      </div>
      <div className="flex-1 min-w-0">
        <p
          className={
            "text-base font-bold " +
            (name ? "text-gray-900" : "text-gray-400 italic")
          }
        >
          {name ?? "Awaiting assignment"}
        </p>
        <p className="text-[11px] uppercase tracking-wider font-semibold text-gray-500 mt-0.5">
          {role}
        </p>
        <p className="text-xs text-gray-600 mt-1">{subtitle}</p>
        {email && (
          <a
            href={`mailto:${email}`}
            className="mt-1 inline-flex items-center gap-1 text-xs text-sage-navy hover:underline"
          >
            <Mail size={11} /> {email}
          </a>
        )}
      </div>
    </div>
  );
}
