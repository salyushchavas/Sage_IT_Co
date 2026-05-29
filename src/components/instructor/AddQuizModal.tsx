"use client";

import { useState } from "react";
import { motion, AnimatePresence } from "framer-motion";
import { Loader2, X, Save } from "lucide-react";
import { createInstructorQuiz } from "@/lib/api";
import { useToast } from "@/components/ui/Toast";

interface ModuleSummary { id: number; title: string }
interface LessonSummary { id: number; title: string }

interface Props {
  isOpen: boolean;
  onClose: () => void;
  courseId: number;
  modules: ModuleSummary[];
  lessons: LessonSummary[];
  onCreated: () => void;
}

type Attachment = "course" | "module" | "lesson";

export function AddQuizModal({ isOpen, onClose, courseId, modules, lessons, onCreated }: Props) {
  const { toast } = useToast();
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [attachment, setAttachment] = useState<Attachment>("course");
  const [moduleId, setModuleId] = useState<string>("");
  const [lessonId, setLessonId] = useState<string>("");
  const [passThreshold, setPassThreshold] = useState("60");
  const [maxAttempts, setMaxAttempts] = useState("3");
  const [timeLimit, setTimeLimit] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const reset = () => {
    setTitle(""); setDescription(""); setAttachment("course");
    setModuleId(""); setLessonId(""); setPassThreshold("60");
    setMaxAttempts("3"); setTimeLimit(""); setError("");
  };

  const submit = async () => {
    if (!title.trim()) { setError("Title is required."); return; }
    if (attachment === "module" && !moduleId) { setError("Pick a module."); return; }
    if (attachment === "lesson" && !lessonId) { setError("Pick a lesson."); return; }
    setBusy(true);
    try {
      await createInstructorQuiz({
        courseId,
        moduleId: attachment === "module" ? Number(moduleId) : null,
        lessonId: attachment === "lesson" ? Number(lessonId) : null,
        title: title.trim(),
        description: description.trim() || undefined,
        passThreshold: passThreshold ? parseInt(passThreshold, 10) : undefined,
        maxAttempts: maxAttempts ? parseInt(maxAttempts, 10) : null,
        timeLimitMinutes: timeLimit ? parseInt(timeLimit, 10) : null,
      });
      toast("success", "Quiz created — now add some questions.");
      onCreated();
      onClose();
      reset();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to create quiz");
    } finally {
      setBusy(false);
    }
  };

  return (
    <AnimatePresence>
      {isOpen && (
        <motion.div
          initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
          className="fixed inset-0 z-50 flex items-center justify-center px-4 bg-black/40 backdrop-blur-sm"
          onClick={onClose}
        >
          <motion.div
            initial={{ scale: 0.95, opacity: 0 }}
            animate={{ scale: 1, opacity: 1 }}
            exit={{ scale: 0.95, opacity: 0 }}
            transition={{ duration: 0.18 }}
            onClick={(e) => e.stopPropagation()}
            className="bg-white rounded-2xl shadow-xl w-full max-w-lg max-h-[90vh] overflow-y-auto p-6"
          >
            <div className="flex items-start justify-between mb-4">
              <h2 className="text-lg font-bold text-gray-900">Create Quiz</h2>
              <button onClick={onClose} className="text-gray-400 hover:text-gray-600 cursor-pointer">
                <X size={18} />
              </button>
            </div>

            <div className="space-y-4">
              <div>
                <label className="block text-xs font-medium text-gray-700 mb-1">
                  Title <span className="text-red-500">*</span>
                </label>
                <input
                  type="text"
                  value={title}
                  onChange={(e) => setTitle(e.target.value)}
                  placeholder="e.g. Module 1 Assessment"
                  className="w-full px-3 py-2 rounded-lg border border-gray-300 text-sm focus:outline-none focus:ring-2 focus:ring-sage-navy/30"
                />
              </div>

              <div>
                <label className="block text-xs font-medium text-gray-700 mb-1">Description</label>
                <input
                  type="text"
                  value={description}
                  onChange={(e) => setDescription(e.target.value)}
                  placeholder="Optional one-line summary"
                  className="w-full px-3 py-2 rounded-lg border border-gray-300 text-sm focus:outline-none focus:ring-2 focus:ring-sage-navy/30"
                />
              </div>

              <div>
                <label className="block text-xs font-medium text-gray-700 mb-1">Attach to</label>
                <div className="space-y-2">
                  <label className="flex items-center gap-2 text-sm cursor-pointer">
                    <input
                      type="radio"
                      checked={attachment === "course"}
                      onChange={() => setAttachment("course")}
                      className="text-sage-navy"
                    />
                    Course final assessment
                  </label>
                  <label className="flex items-center gap-2 text-sm cursor-pointer">
                    <input
                      type="radio"
                      checked={attachment === "module"}
                      onChange={() => setAttachment("module")}
                      disabled={modules.length === 0}
                      className="text-sage-navy"
                    />
                    End of module
                    {attachment === "module" && (
                      <select
                        value={moduleId}
                        onChange={(e) => setModuleId(e.target.value)}
                        className="ml-2 flex-1 px-2 py-1 rounded-lg border border-gray-300 text-sm"
                      >
                        <option value="">Choose module…</option>
                        {modules.map((m) => (
                          <option key={m.id} value={m.id}>{m.title}</option>
                        ))}
                      </select>
                    )}
                  </label>
                  <label className="flex items-center gap-2 text-sm cursor-pointer">
                    <input
                      type="radio"
                      checked={attachment === "lesson"}
                      onChange={() => setAttachment("lesson")}
                      disabled={lessons.length === 0}
                      className="text-sage-navy"
                    />
                    Specific lesson
                    {attachment === "lesson" && (
                      <select
                        value={lessonId}
                        onChange={(e) => setLessonId(e.target.value)}
                        className="ml-2 flex-1 px-2 py-1 rounded-lg border border-gray-300 text-sm"
                      >
                        <option value="">Choose lesson…</option>
                        {lessons.map((l) => (
                          <option key={l.id} value={l.id}>{l.title}</option>
                        ))}
                      </select>
                    )}
                  </label>
                </div>
              </div>

              <div className="grid grid-cols-3 gap-3">
                <div>
                  <label className="block text-xs font-medium text-gray-700 mb-1">Pass %</label>
                  <input
                    type="number"
                    min="0" max="100"
                    value={passThreshold}
                    onChange={(e) => setPassThreshold(e.target.value)}
                    className="w-full px-3 py-2 rounded-lg border border-gray-300 text-sm focus:outline-none focus:ring-2 focus:ring-sage-navy/30"
                  />
                </div>
                <div>
                  <label className="block text-xs font-medium text-gray-700 mb-1">Max attempts</label>
                  <input
                    type="number"
                    min="1"
                    value={maxAttempts}
                    onChange={(e) => setMaxAttempts(e.target.value)}
                    placeholder="∞"
                    className="w-full px-3 py-2 rounded-lg border border-gray-300 text-sm focus:outline-none focus:ring-2 focus:ring-sage-navy/30"
                  />
                </div>
                <div>
                  <label className="block text-xs font-medium text-gray-700 mb-1">Time (min)</label>
                  <input
                    type="number"
                    min="1"
                    value={timeLimit}
                    onChange={(e) => setTimeLimit(e.target.value)}
                    placeholder="—"
                    className="w-full px-3 py-2 rounded-lg border border-gray-300 text-sm focus:outline-none focus:ring-2 focus:ring-sage-navy/30"
                  />
                </div>
              </div>

              {error && <p className="text-xs text-red-700">{error}</p>}

              <div className="flex items-center justify-end gap-2 pt-2">
                <button
                  onClick={() => { onClose(); reset(); }}
                  disabled={busy}
                  className="px-4 py-2 rounded-lg text-sm font-medium text-gray-600 border border-gray-300 hover:bg-gray-50 disabled:opacity-50 transition cursor-pointer"
                >
                  Cancel
                </button>
                <button
                  onClick={submit}
                  disabled={busy}
                  className="inline-flex items-center gap-1.5 px-4 py-2 rounded-lg text-sm font-semibold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-50 transition cursor-pointer"
                >
                  {busy ? <Loader2 size={14} className="animate-spin" /> : <Save size={14} />}
                  Create Quiz
                </button>
              </div>
            </div>
          </motion.div>
        </motion.div>
      )}
    </AnimatePresence>
  );
}
