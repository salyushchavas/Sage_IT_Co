"use client";

import ComingSoonTab from "./ComingSoonTab";

/**
 * Matches Spire's actual implementation -- the Resume tab ships as a
 * PlaceholderCard in ParticipantDashboard.tsx. Detailed resume
 * version tracking + LinkedIn / portal sync are scheduled for a
 * later phase.
 */
export default function ResumeTab() {
  return (
    <ComingSoonTab
      title="Resume / Profile activity"
      copy="Track your resume versions, LinkedIn updates, and portal profiles here. Detailed tracking lands in a follow-up phase."
      hint="In the meantime, log resume updates on your weekly report."
    />
  );
}
