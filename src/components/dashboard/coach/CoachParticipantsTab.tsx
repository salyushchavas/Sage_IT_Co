"use client";

import type { CoachParticipantRow } from "@/lib/api";

export function CoachParticipantsTab({
  participants,
}: {
  participants: CoachParticipantRow[];
}) {
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold text-gray-900">My participants</h1>
      <p className="text-sm text-gray-500">
        Participants currently on your coaching list. Use the other tabs to log
        session notes, assign tasks, and record feedback.
      </p>
      <div className="bg-white rounded-2xl border border-gray-100 shadow-sm overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 text-[11px] uppercase tracking-wider font-semibold text-gray-500">
            <tr>
              <th className="text-left px-4 py-2">Name</th>
              <th className="text-left px-4 py-2">Technology</th>
              <th className="text-left px-4 py-2">Target role</th>
              <th className="text-left px-4 py-2">Program</th>
              <th className="text-left px-4 py-2">Phase</th>
              <th className="text-left px-4 py-2">Coach role</th>
              <th className="text-left px-4 py-2">Sessions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {participants.length === 0 ? (
              <tr>
                <td
                  colSpan={7}
                  className="px-4 py-6 text-center text-sm text-gray-400 italic"
                >
                  No participants assigned yet.
                </td>
              </tr>
            ) : (
              participants.map((p) => (
                <tr key={p.userId}>
                  <td className="px-4 py-2 font-medium text-gray-900">
                    {p.fullName ?? "--"}
                  </td>
                  <td className="px-4 py-2 text-gray-700">
                    {p.technology ?? "--"}
                  </td>
                  <td className="px-4 py-2 text-gray-700">
                    {p.targetJobTitle ?? "--"}
                  </td>
                  <td className="px-4 py-2 text-gray-700">
                    {p.program ?? "--"}
                  </td>
                  <td className="px-4 py-2 text-gray-700">
                    {p.phase ?? "--"}
                  </td>
                  <td className="px-4 py-2 text-gray-700">
                    {p.coachRole ?? "--"}
                  </td>
                  <td className="px-4 py-2 text-gray-700">{p.sessions}</td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
