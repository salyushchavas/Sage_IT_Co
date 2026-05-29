"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { motion } from "framer-motion";
import {
  ChevronLeft, Loader2, Plus, Edit3, Trash2, AlertCircle, CheckCircle2,
} from "lucide-react";
import {
  getInstructorQuiz, deleteQuizQuestion,
  type Quiz, type QuizQuestion,
} from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { QuestionEditorModal } from "@/components/instructor/QuestionEditorModal";
import { cn } from "@/lib/utils";

export default function QuizQuestionsPage({ params }: { params: { id: string } }) {
  const quizId = Number(params.id);
  const router = useRouter();
  const { user, isLoading: authLoading } = useAuth();
  const role = user?.role?.toUpperCase();

  const [quiz, setQuiz] = useState<Quiz | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [editorOpen, setEditorOpen] = useState(false);
  const [editingQuestion, setEditingQuestion] = useState<QuizQuestion | null>(null);
  const [toast, setToast] = useState<{ kind: "success" | "error"; message: string } | null>(null);

  const showToast = (kind: "success" | "error", message: string) => {
    setToast({ kind, message });
    setTimeout(() => setToast(null), 3000);
  };

  const fetchQuiz = async () => {
    setLoading(true);
    try {
      const data = await getInstructorQuiz(quizId);
      setQuiz(data);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to load quiz");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (authLoading) return;
    if (!user) { router.push(`/login?redirect=/instructor/quizzes/${quizId}/questions`); return; }
    if (role !== "INSTRUCTOR" && role !== "ADMIN" && role !== "SYSTEM_ADMIN") {
      router.push("/dashboard");
      return;
    }
    fetchQuiz();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [user, authLoading, role, quizId, router]);

  const openCreate = () => { setEditingQuestion(null); setEditorOpen(true); };
  const openEdit = (q: QuizQuestion) => { setEditingQuestion(q); setEditorOpen(true); };

  const handleDelete = async (id: number) => {
    if (!confirm("Delete this question?")) return;
    setBusy(true);
    try {
      await deleteQuizQuestion(id);
      showToast("success", "Question deleted.");
      fetchQuiz();
    } catch (err) {
      showToast("error", err instanceof Error ? err.message : "Delete failed");
    } finally {
      setBusy(false);
    }
  };

  if (authLoading || loading) {
    return (
      <section className="mx-auto max-w-3xl px-6 pt-32 pb-20 flex items-center justify-center">
        <Loader2 size={28} className="animate-spin text-sage-navy" />
      </section>
    );
  }

  if (error || !quiz) {
    return (
      <section className="mx-auto max-w-md px-6 pt-32 pb-20 text-center">
        <AlertCircle size={36} className="text-red-400 mx-auto mb-3" />
        <p className="text-gray-700 mb-4">{error || "Quiz not found"}</p>
        <Link href="/instructor" className="text-sage-navy underline text-sm">
          Back
        </Link>
      </section>
    );
  }

  const backHref = quiz.courseId
    ? `/instructor/courses/${quiz.courseId}/content`
    : "/instructor";

  return (
    <section className="mx-auto max-w-3xl px-6 pt-28 pb-20 min-h-screen">
      {toast && (
        <div
          className={
            "fixed top-24 right-6 z-50 px-4 py-2.5 rounded-lg text-sm font-semibold shadow-lg " +
            (toast.kind === "success" ? "bg-emerald-600 text-white" : "bg-red-700 text-white")
          }
        >
          {toast.message}
        </div>
      )}

      <Link
        href={backHref}
        className="inline-flex items-center gap-1 text-sm text-gray-500 hover:text-sage-navy mb-4"
      >
        <ChevronLeft size={16} /> Back to content
      </Link>

      <div className="flex items-end justify-between gap-4 mb-2">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">{quiz.title}</h1>
          <p className="text-xs text-gray-500 mt-1">
            {(quiz.questions ?? []).length} question{(quiz.questions ?? []).length === 1 ? "" : "s"}
            {" · "}Pass: {quiz.passThreshold}%
            {quiz.timeLimitMinutes != null && ` · ${quiz.timeLimitMinutes} min limit`}
            {quiz.maxAttempts != null && ` · ${quiz.maxAttempts} attempts`}
          </p>
        </div>
        <button
          onClick={openCreate}
          className="inline-flex items-center gap-1.5 px-4 py-2 rounded-lg text-sm font-semibold bg-sage-navy text-white hover:bg-sage-navy-deep transition cursor-pointer"
        >
          <Plus size={14} /> Add Question
        </button>
      </div>

      {quiz.description && (
        <p className="text-sm text-gray-600 mb-6">{quiz.description}</p>
      )}

      <div className="space-y-4 mt-6">
        {(quiz.questions ?? []).length === 0 ? (
          <div className="text-center py-12 bg-white rounded-2xl border border-gray-100">
            <p className="text-sm text-gray-500 mb-3">No questions yet.</p>
            <button
              onClick={openCreate}
              className="inline-flex items-center gap-1.5 px-4 py-2 rounded-lg text-sm font-semibold bg-sage-navy text-white hover:bg-sage-navy-deep transition cursor-pointer"
            >
              <Plus size={14} /> Add your first question
            </button>
          </div>
        ) : (
          (quiz.questions ?? []).map((q, idx) => (
            <motion.div
              key={q.id}
              initial={{ opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ delay: idx * 0.04 }}
              className="bg-white rounded-2xl border border-gray-100 shadow-sm p-5"
            >
              <div className="flex items-start justify-between gap-3 mb-3">
                <div className="flex-1 min-w-0">
                  <p className="font-semibold text-gray-900 mb-1">
                    Q{idx + 1}. {q.questionText}
                  </p>
                  <p className="text-[10px] uppercase tracking-wide text-gray-500">
                    {prettyType(q.questionType)} · {q.points} point{q.points === 1 ? "" : "s"}
                  </p>
                </div>
                <div className="flex items-center gap-1 shrink-0">
                  <button
                    onClick={() => openEdit(q)}
                    className="p-1.5 text-gray-500 hover:text-gray-900 transition cursor-pointer"
                    aria-label="Edit question"
                  >
                    <Edit3 size={14} />
                  </button>
                  <button
                    onClick={() => handleDelete(q.id)}
                    disabled={busy}
                    className="p-1.5 text-gray-400 hover:text-red-700 disabled:opacity-50 transition cursor-pointer"
                    aria-label="Delete question"
                  >
                    <Trash2 size={14} />
                  </button>
                </div>
              </div>

              <ul className="space-y-1.5 mb-3">
                {q.options.map((opt) => {
                  const correct = !!opt.isCorrect;
                  return (
                    <li
                      key={opt.id}
                      className={cn(
                        "flex items-center gap-2 px-3 py-2 rounded-lg text-sm",
                        correct ? "bg-emerald-50 text-emerald-800" : "text-gray-700"
                      )}
                    >
                      <span className={cn(
                        "w-4 h-4 flex items-center justify-center flex-shrink-0",
                        q.questionType === "MULTI_SELECT" ? "rounded" : "rounded-full",
                        correct
                          ? "bg-emerald-500 text-white"
                          : "border-2 border-gray-300"
                      )}>
                        {correct && <CheckCircle2 size={10} />}
                      </span>
                      <span className="flex-1">{opt.optionText}</span>
                      {correct && (
                        <span className="text-[10px] font-semibold uppercase tracking-wide text-emerald-600">
                          Correct
                        </span>
                      )}
                    </li>
                  );
                })}
              </ul>

              {q.explanation && (
                <p className="text-xs text-gray-500 italic">
                  Explanation: {q.explanation}
                </p>
              )}
            </motion.div>
          ))
        )}
      </div>

      <QuestionEditorModal
        isOpen={editorOpen}
        onClose={() => setEditorOpen(false)}
        quizId={quizId}
        question={editingQuestion}
        onSaved={fetchQuiz}
      />
    </section>
  );
}

function prettyType(t: QuizQuestion["questionType"]) {
  switch (t) {
    case "MULTIPLE_CHOICE": return "Multiple Choice";
    case "TRUE_FALSE": return "True/False";
    case "MULTI_SELECT": return "Multi-Select";
  }
}
