"use client";

import { useEffect, useState } from "react";
import { AlertCircle, CheckCircle2 } from "lucide-react";

import {
  createCoachTask,
  listCoachTasks,
  updateCoachTaskStatus,
  type CoachParticipantRow,
  type CoachingTaskDTO,
} from "@/lib/api";
import { businessToday } from "@/lib/datetime";
import {
  CoachForm,
  Field,
  FormRow,
  TabLoading,
  participantName,
} from "./CoachFormParts";

export function CoachTasksTab({
  participants,
  onSaved,
}: {
  participants: CoachParticipantRow[];
  onSaved?: () => void;
}) {
  const [tasks, setTasks] = useState<CoachingTaskDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [participantId, setParticipantId] = useState<number | "">("");
  const [title, setTitle] = useState("");
  const [desc, setDesc] = useState("");
  const [due, setDue] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [listError, setListError] = useState("");

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
    if (!participantId) return;
    if (!title.trim()) {
      setError("Title is required.");
      return;
    }
    if (due && due < businessToday()) {
      setError("The due date can't be in the past.");
      return;
    }
    setError("");
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
      onSaved?.();
    } catch (e) {
      // Keep what they typed; say why it wasn't saved.
      setError(e instanceof Error ? e.message : "Couldn't save the task");
    } finally {
      setSaving(false);
    }
  };

  const toggleStatus = async (t: CoachingTaskDTO) => {
    if (!t.id) return;
    const next = t.status === "DONE" ? "OPEN" : "DONE";
    setListError("");
    try {
      await updateCoachTaskStatus(t.id, next);
      setTasks(await listCoachTasks());
    } catch (e) {
      setListError(e instanceof Error ? e.message : "Couldn't change the task");
    }
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
        error={error}
      >
        <FormRow>
          <Field label="Title" value={title} onChange={setTitle} />
          <Field
            label="Due date"
            type="date"
            value={due}
            onChange={setDue}
            min={businessToday()}
          />
        </FormRow>
        <Field
          label="Description"
          type="textarea"
          value={desc}
          onChange={setDesc}
          rows={2}
        />
      </CoachForm>

      {listError && (
        <p className="flex items-start gap-1.5 text-sm text-red-700">
          <AlertCircle size={14} className="mt-0.5 shrink-0" /> {listError}
        </p>
      )}
      <div className="rounded-2xl border border-gray-100 bg-white overflow-hidden divide-y divide-gray-100">
        {tasks.length === 0 ? (
          <p className="px-4 py-6 text-center text-sm text-gray-400 italic">
            No tasks yet.
          </p>
        ) : (
          tasks.map((t) => (
            <div
              key={t.id}
              className="px-4 py-2.5 flex flex-wrap items-center gap-x-3 gap-y-1 text-sm"
            >
              <button
                type="button"
                onClick={() => toggleStatus(t)}
                aria-label={t.status === "DONE" ? "Mark as open" : "Mark as done"}
                className={
                  "shrink-0 w-4 h-4 rounded border flex items-center justify-center cursor-pointer " +
                  (t.status === "DONE"
                    ? "bg-emerald-600 border-emerald-600 text-white"
                    : "border-gray-300 bg-white")
                }
              >
                {t.status === "DONE" && <CheckCircle2 size={11} />}
              </button>
              <span className="font-semibold text-gray-900 shrink-0 max-w-[10rem] truncate">
                {participantName(participants, t.participantUserId)}
              </span>
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
