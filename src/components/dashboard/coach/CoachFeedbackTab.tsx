"use client";

import { useEffect, useState } from "react";

import {
  createCoachFeedback,
  listCoachFeedback,
  type CoachParticipantRow,
  type CoachingFeedbackDTO,
} from "@/lib/api";
import {
  CoachForm,
  Field,
  FormRow,
  RecordsList,
  TabLoading,
} from "./CoachFormParts";

const FEEDBACK_TYPES = [
  "GENERAL",
  "SESSION",
  "RESUME",
  "TECHNICAL",
  "INTERVIEW",
] as const;

export function CoachFeedbackTab({
  participants,
}: {
  participants: CoachParticipantRow[];
}) {
  const [items, setItems] = useState<CoachingFeedbackDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [participantId, setParticipantId] = useState<number | "">("");
  const [type, setType] = useState<string>("GENERAL");
  const [content, setContent] = useState("");
  const [rating, setRating] = useState("");
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    let cancelled = false;
    listCoachFeedback()
      .then((f) => {
        if (!cancelled) setItems(f);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const handleSubmit = async () => {
    if (!participantId || !content.trim()) return;
    setSaving(true);
    try {
      await createCoachFeedback({
        participantUserId: Number(participantId),
        feedbackType: type,
        content: content.trim(),
        rating: rating ? Number(rating) : null,
      });
      setItems(await listCoachFeedback());
      setContent("");
      setRating("");
    } finally {
      setSaving(false);
    }
  };

  if (loading) return <TabLoading />;

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold text-gray-900">Feedback</h1>
      <CoachForm
        participantId={participantId}
        onParticipant={setParticipantId}
        participants={participants}
        submitLabel={saving ? "Saving..." : "Add feedback"}
        saving={saving}
        onSubmit={handleSubmit}
      >
        <FormRow>
          <div>
            <label className="block text-[11px] font-medium text-gray-600 mb-0.5">
              Type
            </label>
            <select
              value={type}
              onChange={(e) => setType(e.target.value)}
              className="w-full px-3 py-2 text-sm rounded-md border border-gray-200"
            >
              {FEEDBACK_TYPES.map((t) => (
                <option key={t} value={t}>
                  {t}
                </option>
              ))}
            </select>
          </div>
          <Field
            label="Rating (1-5)"
            type="number"
            value={rating}
            onChange={setRating}
          />
        </FormRow>
        <Field
          label="Feedback"
          type="textarea"
          value={content}
          onChange={setContent}
          rows={3}
        />
      </CoachForm>

      <RecordsList
        items={items}
        empty="No feedback recorded yet."
        renderRow={(f) => (
          <>
            <span className="px-2 py-0.5 rounded-full text-[10px] font-bold bg-gray-100 text-gray-700 w-24 shrink-0 text-center">
              {f.feedbackType}
            </span>
            <span className="font-medium text-gray-900 truncate">
              {f.content}
            </span>
            {f.rating && (
              <span className="text-xs text-amber-600 font-bold ml-auto">
                ★ {f.rating}/5
              </span>
            )}
          </>
        )}
      />
    </div>
  );
}
