"use client";

import { useState, useEffect, useCallback } from "react";
import { motion, AnimatePresence } from "framer-motion";
import { BrainCircuit, Lock, Loader2, CheckCircle2, XCircle, RotateCcw } from "lucide-react";
import { getLessonQuiz, submitQuiz } from "@/lib/api";

interface Question {
  id: number;
  questionText: string;
  optionA: string;
  optionB: string;
  optionC?: string;
  optionD?: string;
}

interface Quiz {
  id: number;
  title: string;
  questions: Question[];
}

interface QuizResult {
  score: number;
  totalQuestions: number;
  passed: boolean;
}

interface QuizSectionProps {
  lessonId: number;
  isAuthenticated: boolean;
  lessonCompleted: boolean;
}

export default function QuizSection({
  lessonId,
  isAuthenticated,
  lessonCompleted,
}: QuizSectionProps) {
  const [quiz, setQuiz] = useState<Quiz | null>(null);
  const [loading, setLoading] = useState(false);
  const [answers, setAnswers] = useState<Record<number, string>>({});
  const [submitting, setSubmitting] = useState(false);
  const [result, setResult] = useState<QuizResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  const fetchQuiz = useCallback(async () => {
    if (!isAuthenticated || !lessonCompleted) return;
    setLoading(true);
    try {
      const data = await getLessonQuiz(lessonId);
      setQuiz(data as Quiz | null);
    } catch {
      // no quiz for this lesson
    } finally {
      setLoading(false);
    }
  }, [lessonId, isAuthenticated, lessonCompleted]);

  useEffect(() => {
    fetchQuiz();
  }, [fetchQuiz]);

  const handleSelect = (questionId: number, option: string) => {
    if (result) return;
    setAnswers((prev) => ({ ...prev, [questionId]: option }));
  };

  const handleSubmit = async () => {
    if (!quiz) return;
    setSubmitting(true);
    setError(null);
    try {
      const data = await submitQuiz(quiz.id, answers);
      setResult(data as QuizResult);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Submission failed");
    } finally {
      setSubmitting(false);
    }
  };

  const handleRetry = () => {
    setAnswers({});
    setResult(null);
    setError(null);
  };

  if (!lessonCompleted) {
    return (
      <div className="bg-white/60 backdrop-blur-xl border border-zinc-200 rounded-2xl p-6 flex items-center gap-3 text-zinc-500">
        <Lock className="w-5 h-5" />
        <span className="text-sm">Complete the lesson to unlock the quiz</span>
      </div>
    );
  }

  if (!isAuthenticated) return null;
  if (loading) {
    return (
      <div className="flex items-center justify-center py-12">
        <Loader2 className="w-6 h-6 text-[#22C55E] animate-spin" />
      </div>
    );
  }
  if (!quiz) return null;

  const allAnswered = quiz.questions.every((q) => answers[q.id]);
  const options = ["A", "B", "C", "D"] as const;

  return (
    <motion.div
      initial={{ opacity: 0, y: 16 }}
      animate={{ opacity: 1, y: 0 }}
      className="bg-white/60 backdrop-blur-xl border border-zinc-200 rounded-2xl p-6 space-y-6"
    >
      <div className="flex items-center gap-2">
        <BrainCircuit className="w-5 h-5 text-[#E5B62E]" />
        <h3 className="text-zinc-900 font-semibold">{quiz.title}</h3>
      </div>

      {/* Result banner */}
      <AnimatePresence>
        {result && (
          <motion.div
            initial={{ opacity: 0, height: 0 }}
            animate={{ opacity: 1, height: "auto" }}
            exit={{ opacity: 0, height: 0 }}
            className={`rounded-xl p-4 flex items-center justify-between ${
              result.passed
                ? "bg-emerald-500/10 border border-emerald-500/20"
                : "bg-red-500/10 border border-red-500/20"
            }`}
          >
            <div className="flex items-center gap-2">
              {result.passed ? (
                <CheckCircle2 className="w-5 h-5 text-emerald-400" />
              ) : (
                <XCircle className="w-5 h-5 text-red-400" />
              )}
              <span className={result.passed ? "text-emerald-400" : "text-red-400"}>
                Score: {result.score}/{result.totalQuestions}
                {result.passed ? " - Passed!" : " - Try again"}
              </span>
            </div>
            {!result.passed && (
              <button
                onClick={handleRetry}
                className="flex items-center gap-1 text-sm text-zinc-600 hover:text-zinc-900 transition-colors"
              >
                <RotateCcw className="w-4 h-4" />
                Retry
              </button>
            )}
          </motion.div>
        )}
      </AnimatePresence>

      {/* Questions */}
      <div className="space-y-5">
        {quiz.questions.map((q, qi) => (
          <div key={q.id} className="space-y-3">
            <p className="text-zinc-900 text-sm font-medium">
              <span className="text-[#22C55E] mr-2">{qi + 1}.</span>
              {q.questionText}
            </p>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
              {options.map((opt) => {
                const value = q[`option${opt}` as keyof Question] as string | undefined;
                if (!value) return null;
                const selected = answers[q.id] === opt;
                return (
                  <button
                    key={opt}
                    onClick={() => handleSelect(q.id, opt)}
                    disabled={!!result}
                    className={`text-left px-4 py-3 rounded-xl border text-sm transition-all ${
                      selected
                        ? "bg-[#22C55E]/15 border-[#22C55E]/40 text-zinc-900"
                        : "bg-white/60 border-zinc-200 text-zinc-600 hover:border-zinc-300 hover:text-zinc-900"
                    } disabled:cursor-default`}
                  >
                    <span className="font-semibold mr-2 text-[#22C55E]">{opt}.</span>
                    {value}
                  </button>
                );
              })}
            </div>
          </div>
        ))}
      </div>

      {error && <p className="text-red-400 text-sm">{error}</p>}

      {!result && (
        <button
          onClick={handleSubmit}
          disabled={!allAnswered || submitting}
          className="px-6 py-2.5 rounded-xl bg-gradient-to-r from-[#E5B62E] to-[#22C55E] text-zinc-900 font-medium text-sm hover:opacity-90 transition-opacity disabled:opacity-40 disabled:cursor-not-allowed flex items-center gap-2"
        >
          {submitting ? (
            <>
              <Loader2 className="w-4 h-4 animate-spin" />
              Submitting...
            </>
          ) : (
            "Submit Quiz"
          )}
        </button>
      )}
    </motion.div>
  );
}
