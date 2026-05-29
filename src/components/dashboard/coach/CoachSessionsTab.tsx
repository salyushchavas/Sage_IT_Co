"use client";

import { useEffect, useState } from "react";

import {
  createCoachSession,
  listCoachSessions,
  type CoachParticipantRow,
  type CoachingSessionDTO,
} from "@/lib/api";
import {
  CoachForm,
  Field,
  FormRow,
  RecordsList,
  TabLoading,
} from "./CoachFormParts";

export function CoachSessionsTab({
  participants,
}: {
  participants: CoachParticipantRow[];
}) {
  const [sessions, setSessions] = useState<CoachingSessionDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [participantId, setParticipantId] = useState<number | "">("");
  const [date, setDate] = useState("");
  const [topic, setTopic] = useState("");
  const [duration, setDuration] = useState("");
  const [notes, setNotes] = useState("");
  const [nextSteps, setNextSteps] = useState("");
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    let cancelled = false;
    listCoachSessions()
      .then((s) => {
        if (!cancelled) setSessions(s);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const handleSubmit = async () => {
    if (!participantId) return;
    setSaving(true);
    try {
      await createCoachSession({
        participantUserId: Number(participantId),
        sessionDate: date || null,
        topic: topic || null,
        notes: notes || null,
        nextSteps: nextSteps || null,
        durationMinutes: duration ? Number(duration) : null,
      });
      setSessions(await listCoachSessions());
      setTopic("");
      setNotes("");
      setNextSteps("");
      setDuration("");
    } finally {
      setSaving(false);
    }
  };

  if (loading) return <TabLoading />;

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold text-gray-900">Session notes</h1>
      <CoachForm
        participantId={participantId}
        onParticipant={setParticipantId}
        participants={participants}
        submitLabel={saving ? "Saving..." : "Log session"}
        saving={saving}
        onSubmit={handleSubmit}
      >
        <FormRow>
          <Field label="Date" type="date" value={date} onChange={setDate} />
          <Field
            label="Duration (min)"
            type="number"
            value={duration}
            onChange={setDuration}
          />
          <Field label="Topic" value={topic} onChange={setTopic} />
        </FormRow>
        <Field
          label="Notes"
          type="textarea"
          value={notes}
          onChange={setNotes}
          rows={3}
        />
        <Field
          label="Next steps"
          type="textarea"
          value={nextSteps}
          onChange={setNextSteps}
          rows={2}
        />
      </CoachForm>

      <RecordsList
        items={sessions}
        empty="No sessions yet."
        renderRow={(s) => (
          <>
            <span className="font-mono text-xs text-gray-700 w-32 shrink-0">
              {s.sessionDate ?? (s.createdAt ? s.createdAt.slice(0, 10) : "--")}
            </span>
            <span className="font-medium text-gray-900 truncate">
              {s.topic || "(no topic)"}
            </span>
            <span className="text-xs text-gray-500 ml-auto truncate">
              {s.notes?.slice(0, 80) ?? ""}
            </span>
          </>
        )}
      />
    </div>
  );
}
