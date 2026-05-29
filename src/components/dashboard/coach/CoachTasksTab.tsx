"use client";

import { useEffect, useState } from "react";
import { CheckCircle2 } from "lucide-react";

import {
  createCoachTask,
  listCoachTasks,
  updateCoachTaskStatus,
  type CoachParticipantRow,
  type CoachingTaskDTO,
} from "@/lib/api";
import {
  CoachForm,
  Field,
  FormRow,
  TabLoading,
} from "./CoachFormParts";

export function CoachTasksTab({
  participants,
}: {
  participants: CoachParticipantRow[];
}) {
  const [tasks, setTasks] = useState<CoachingTaskDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [participantId, setParticipantId] = useState<number | "">("");
  const [title, setTitle] = useState("");
  const [desc, setDesc] = useState("");
  const [due, setDue] = useState("");
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    let cancelled = false;
    listCoachTasks()
      .then((t) => {
        if (!cancelled) setTasks(t);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const handleSubmit = async () => {
    if (!participantId || !title.trim()) return;
    setSaving(true);
    try {
      await createCoachTask({
        participantUserId: Number(participantId),
        title: title.trim(),
        description: desc || null,
        dueDate: due || null,
      });
      setTasks(await listCoachTasks());
      setTitle("");
      setDesc("");
      setDue("");
    } finally {
      setSaving(false);
    }
  };

  const toggleStatus = async (t: CoachingTaskDTO) => {
    if (!t.id) return;
    const next = t.status === "DONE" ? "OPEN" : "DONE";
    await updateCoachTaskStatus(t.id, next);
    setTasks(await listCoachTasks());
  };

  if (loading) return <TabLoading />;

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold text-gray-900">Practice tasks</h1>
      <CoachForm
        participantId={participantId}
        onParticipant={setParticipantId}
        participants={participants}
        submitLabel={saving ? "Saving..." : "Assign task"}
        saving={saving}
        onSubmit={handleSubmit}
      >
        <FormRow>
          <Field label="Title" value={title} onChange={setTitle} />
          <Field label="Due date" type="date" value={due} onChange={setDue} />
        </FormRow>
        <Field
          label="Description"
          type="textarea"
          value={desc}
          onChange={setDesc}
          rows={2}
        />
      </CoachForm>

      <div className="rounded-2xl border border-gray-100 bg-white overflow-hidden divide-y divide-gray-100">
        {tasks.length === 0 ? (
          <p className="px-4 py-6 text-center text-sm text-gray-400 italic">
            No tasks yet.
          </p>
        ) : (
          tasks.map((t) => (
            <div
              key={t.id}
              className="px-4 py-2.5 flex items-center gap-3 text-sm"
            >
              <button
                type="button"
                onClick={() => toggleStatus(t)}
                className={
                  "shrink-0 w-4 h-4 rounded border flex items-center justify-center cursor-pointer " +
                  (t.status === "DONE"
                    ? "bg-emerald-600 border-emerald-600 text-white"
                    : "border-gray-300 bg-white")
                }
              >
                {t.status === "DONE" && <CheckCircle2 size={11} />}
              </button>
              <span
                className={
                  t.status === "DONE"
                    ? "line-through text-gray-400"
                    : "font-medium text-gray-900"
                }
              >
                {t.title}
              </span>
              {t.dueDate && (
                <span className="font-mono text-xs text-gray-500">
                  due {t.dueDate}
                </span>
              )}
              <span className="ml-auto px-2 py-0.5 rounded-full text-[10px] font-bold bg-gray-100 text-gray-700">
                {t.status}
              </span>
            </div>
          ))
        )}
      </div>
    </div>
  );
}
