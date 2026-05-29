"use client";

import ComingSoonTab from "./ComingSoonTab";

/**
 * Matches Spire's actual implementation -- the Interview tab ships
 * as a PlaceholderCard. Mock interview scheduling + coach feedback
 * + practice tasks are scheduled for a later phase.
 */
export default function InterviewTab() {
  return (
    <ComingSoonTab
      title="Interview training"
      copy="Mock interview schedule, coach feedback, and practice tasks land here in a follow-up phase."
      hint="Talk to your interview coach via the My Team tab to schedule a mock."
    />
  );
}
