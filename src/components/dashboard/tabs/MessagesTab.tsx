"use client";

import { Bell, Mail } from "lucide-react";

import type {
  ParticipantDashboard as DashboardData,
  ParticipantTeam,
} from "@/lib/api";

interface Props {
  data: DashboardData;
  team: ParticipantTeam | null;
}

export default function MessagesTab({ data, team }: Props) {
  const items = data.recentActivity ?? [];
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold text-gray-900">
        Messages &amp; notifications
      </h1>
      <p className="text-sm text-gray-500">
        System notifications, acknowledgments, and lifecycle events from your
        account. Direct messaging is handled by your ERM via email — see My
        Team for contact info.
      </p>

      {team?.erm?.email && (
        <div className="rounded-2xl border border-sage-navy/20 bg-sage-navy/5 p-4 flex items-center justify-between gap-3">
          <div className="min-w-0">
            <p className="text-[11px] uppercase tracking-wider font-semibold text-sage-navy">
              Your ERM
            </p>
            <p className="text-sm font-bold text-gray-900 mt-0.5 truncate">
              {team.erm.name ?? "—"}
            </p>
            <p className="text-xs text-gray-600 truncate">{team.erm.email}</p>
          </div>
          <a
            href={`mailto:${team.erm.email}`}
            className="shrink-0 inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep cursor-pointer"
          >
            <Mail size={12} /> Email ERM
          </a>
        </div>
      )}

      <div className="rounded-2xl border border-gray-200 bg-white divide-y divide-gray-100">
        {items.length === 0 ? (
          <p className="px-4 py-3 text-sm text-gray-400 italic">
            No notifications yet.
          </p>
        ) : (
          items.map((m, idx) => (
            <div
              key={idx}
              className="px-4 py-3 flex items-start gap-3 text-sm"
            >
              <Bell size={14} className="text-sage-navy mt-0.5 shrink-0" />
              <div className="flex-1 min-w-0">
                <p className="text-gray-800">{m.title}</p>
                <p className="text-[11px] text-gray-400">
                  {m.category} ·{" "}
                  {new Date(m.createdAt).toLocaleString("en-IN", {
                    timeZone: "Asia/Kolkata",
                    dateStyle: "medium",
                    timeStyle: "short",
                  })}
                </p>
              </div>
            </div>
          ))
        )}
      </div>
    </div>
  );
}
