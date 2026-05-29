"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { motion, AnimatePresence } from "framer-motion";
import {
  ChevronLeft, ChevronRight, Loader2, AlertCircle, Trophy, XCircle, Clock,
  CheckCircle2, X,
} from "lucide-react";
import {
  getQuizForStudent, submitQuiz,
  type Quiz, type QuizSubmitResult,
} from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { cn } from "@/lib/utils";

export default function QuizTakePage({ params }: { params: { id: string } }) {
  const quizId = Number(params.id);
  const router = useRouter();
  const searchParams = useSearchParams();
  const returnTo = searchParams.get("return");
  const { user, isLoading: authLoading } = useAuth();

  const [quiz, setQuiz] = useState<Quiz | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  const [answers, setAnswers] = useState<Record<number, number[]>>({});
  const [current, setCurrent] = useState(0);
  const [submitting, setSubmitting] = useState(false);
  const [result, setResult] = useState<QuizSubmitResult | null>(null);

  const [secondsElapsed, setSecondsElapsed] = useState(0);
  const startedAtRef = useRef<number | null>(null);

  useEffect(() => {
    if (authLoading) return;
    if (!user) { router.push(`/login?redirect=/quiz/${quizId}/take`); return; }
    setLoading(true);
    getQuizForStudent(quizId)
      .then((data) => {
        setQuiz(data);
        const empty: Record<number, number[]> = {};
        for (const q of data.questions ?? []) empty[q.id] = [];
        setAnswers(empty);
        startedAtRef.current = Date.now();
      })
      .catch((err) => setError(err instanceof Error ? err.message : "Failed to load quiz"))
      .finally(() => setLoading(false));
  }, [quizId, user, authLoading, router]);

  useEffect(() => {
    if (!quiz || result) return;
    const id = setInterval(() => {
      if (startedAtRef.current != null) {
        setSecondsElapsed(Math.floor((Date.now() - startedAtRef.current) / 1000));
      }
    }, 1000);
    return () => clearInterval(id);
  }, [quiz, result]);

  const questions = quiz?.questions ?? [];
  const total = questions.length;
  const q = questions[current];

  const timeLimitSeconds = quiz?.timeLimitMinutes != null ? quiz.timeLimitMinutes * 60 : null;
  const timeRemaining = timeLimitSeconds != null ? Math.max(0, timeLimitSeconds - secondsElapsed) : null;
  const lowOnTime = timeRemaining != null && timeRemaining <= 5 * 60;

  const submitRef = useRef<() => void>(() => {});

  const handleSelect = (optionId: number) => {
    if (!q) return;
    setAnswers((prev) => {
      const cur = prev[q.id] ?? [];
      if (q.questionType === "MULTI_SELECT") {
        if (cur.includes(optionId)) {
          return { ...prev, [q.id]: cur.filter((x) => x !== optionId) };
        }
        return { ...prev, [q.id]: [...cur, optionId] };
      }
      return { ...prev, [q.id]: [optionId] };
    });
  };

  const submit = async () => {
    if (!quiz) return;
    setSubmitting(true);
    setError("");
    try {
      const payload = {
        answers: Object.entries(answers).map(([questionId, selectedOptionIds]) => ({
          questionId: Number(questionId),
          selectedOptionIds,
        })),
        timeTakenSeconds: secondsElapsed,
      };
      const r = await submitQuiz(quiz.id, payload);
      setResult(r);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Submission failed");
    } finally {
      setSubmitting(false);
    }
  };
  submitRef.current = submit;

  useEffect(() => {
    if (timeRemaining === 0 && !result && !submitting) {
      submitRef.current();
    }
  }, [timeRemaining, result, submitting]);

  const answeredQuestions = useMemo(() => {
    const set = new Set<number>();
    for (const [qid, ids] of Object.entries(answers)) {
      if (ids.length > 0) set.add(Number(qid));
    }
    return set;
  }, [answers]);

  if (authLoading || loading) {
    return (
      <section className="mx-auto max-w-2xl px-6 pt-32 pb-20 flex items-center justify-center">
        <Loader2 size={28} className="animate-spin text-sage-navy" />
      </section>
    );
  }

  if (error && !result) {
    return (
      <section className="mx-auto max-w-md px-6 pt-32 pb-20 text-center">
        <AlertCircle size={36} className="text-red-400 mx-auto mb-3" />
        <p className="text-gray-700 mb-4">{error}</p>
        <Link
          href={returnTo ?? "/dashboard"}
          className="text-sage-navy underline text-sm"
        >
          Back
        </Link>
      </section>
    );
  }

  if (!quiz || total === 0) {
    return (
      <section className="mx-auto max-w-md px-6 pt-32 pb-20 text-center">
        <p className="text-gray-700 mb-4">This quiz has no questions yet.</p>
        <Link
          href={returnTo ?? "/dashboard"}
          className="text-sage-navy underline text-sm"
        >
          Back
        </Link>
      </section>
    );
  }

  if (result) {
    return <QuizResultsView
      quiz={quiz}
      result={result}
      answers={answers}
      returnTo={returnTo}
      onRetry={() => {
        setAnswers(Object.fromEntries(questions.map((qq) => [qq.id, []])));
        setResult(null);
        setCurrent(0);
        setSecondsElapsed(0);
        startedAtRef.current = Date.now();
      }}
    />;
  }

  const isLast = current === total - 1;
  const isFirst = current === 0;
  const currentSelections = q ? (answers[q.id] ?? []) : [];

  return (
    <section className="mx-auto max-w-2xl px-6 pt-28 pb-20 min-h-screen">
      <div className="flex items-center justify-between mb-6">
        <div className="flex-1 min-w-0">
          <h1 className="text-xl font-bold text-gray-900 truncate">{quiz.title}</h1>
          <p className="text-xs text-gray-500">
            Question {current + 1} of {total}
          </p>
        </div>
        {timeRemaining != null && (
          <div className={cn(
            "inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold tabular-nums",
            lowOnTime ? "bg-amber-50 text-amber-700 border border-amber-200" : "bg-gray-100 text-gray-700"
          )}>
            <Clock size={12} /> {formatMMSS(timeRemaining)}
          </div>
        )}
      </div>

      <div className="mb-6 h-1.5 bg-gray-100 rounded-full overflow-hidden">
        <div
          className="h-full bg-gradient-to-r from-sage-navy to-sage-navy-deep transition-all"
          style={{ width: `${((current + 1) / total) * 100}%` }}
        />
      </div>

      {q && (
        <AnimatePresence mode="wait">
          <motion.div
            key={q.id}
            initial={{ opacity: 0, x: 16 }}
            animate={{ opacity: 1, x: 0 }}
            exit={{ opacity: 0, x: -16 }}
            transition={{ duration: 0.2 }}
            className="bg-white rounded-2xl border border-gray-100 shadow-sm p-6 mb-6"
          >
            <p className="text-base font-medium text-gray-900 mb-4 whitespace-pre-wrap">
              {q.questionText}
            </p>
            {q.questionType === "MULTI_SELECT" && (
              <p className="text-[11px] text-gray-500 mb-3">Select all that apply.</p>
            )}
            <div className="space-y-2">
              {q.options.map((opt) => {
                const selected = currentSelections.includes(opt.id);
                return (
                  <button
                    key={opt.id}
                    onClick={() => handleSelect(opt.id)}
                    className={cn(
                      "w-full text-left px-4 py-3 rounded-xl border text-sm transition flex items-center gap-3 cursor-pointer",
                      selected
                        ? "bg-sage-navy/10 border-sage-navy text-gray-900"
                        : "bg-white border-gray-200 text-gray-700 hover:border-sage-navy/40"
                    )}
                  >
                    <div className={cn(
                      "w-5 h-5 flex-shrink-0 flex items-center justify-center transition",
                      q.questionType === "MULTI_SELECT" ? "rounded" : "rounded-full",
                      selected
                        ? "bg-sage-navy border-2 border-sage-navy text-white"
                        : "border-2 border-gray-300"
                    )}>
                      {selected && <CheckCircle2 size={12} />}
                    </div>
                    <span className="flex-1">{opt.optionText}</span>
                  </button>
                );
              })}
            </div>
          </motion.div>
        </AnimatePresence>
      )}

      {error && (
        <p className="text-xs text-red-700 mb-3">{error}</p>
      )}

      <div className="flex items-center justify-between gap-3">
        <button
          onClick={() => setCurrent((c) => Math.max(0, c - 1))}
          disabled={isFirst}
          className="inline-flex items-center gap-1 px-4 py-2 rounded-lg text-sm font-medium text-gray-700 border border-gray-300 hover:bg-gray-50 disabled:opacity-40 disabled:cursor-not-allowed transition cursor-pointer"
        >
          <ChevronLeft size={14} /> Previous
        </button>
        {isLast ? (
          <button
            onClick={submit}
            disabled={submitting}
            className="inline-flex items-center gap-1.5 px-5 py-2 rounded-lg text-sm font-semibold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-50 transition cursor-pointer"
          >
            {submitting && <Loader2 size={14} className="animate-spin" />}
            Submit Quiz
          </button>
        ) : (
          <button
            onClick={() => setCurrent((c) => Math.min(total - 1, c + 1))}
            className="inline-flex items-center gap-1 px-4 py-2 rounded-lg text-sm font-semibold bg-sage-navy text-white hover:bg-sage-navy-deep transition cursor-pointer"
          >
            Next <ChevronRight size={14} />
          </button>
        )}
      </div>

      <div className="flex items-center justify-center gap-2 mt-8">
        {questions.map((qq, idx) => (
          <button
            key={qq.id}
            onClick={() => setCurrent(idx)}
            className={cn(
              "w-2.5 h-2.5 rounded-full transition cursor-pointer",
              idx === current && "ring-2 ring-sage-navy ring-offset-2",
              answeredQuestions.has(qq.id) ? "bg-sage-navy" : "bg-gray-300"
            )}
            aria-label={`Question ${idx + 1}`}
          />
        ))}
      </div>
    </section>
  );
}

function formatMMSS(seconds: number) {
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return `${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
}

interface ResultsProps {
  quiz: Quiz;
  result: QuizSubmitResult;
  answers: Record<number, number[]>;
  returnTo: string | null;
  onRetry: () => void;
}

function QuizResultsView({ quiz, result, returnTo, onRetry }: ResultsProps) {
  const passed = result.passed;
  const canRetry = result.attemptsRemaining == null || result.attemptsRemaining > 0;
  const optionLookup = new Map<number, string>();
  for (const q of quiz.questions ?? []) {
    for (const opt of q.options) optionLookup.set(opt.id, opt.optionText);
  }

  return (
    <section className="mx-auto max-w-2xl px-6 pt-28 pb-20 min-h-screen">
      <h1 className="text-2xl font-bold text-gray-900 mb-6">Quiz Results</h1>

      <motion.div
        initial={{ opacity: 0, scale: 0.95 }}
        animate={{ opacity: 1, scale: 1 }}
        className={cn(
          "rounded-2xl border-2 p-8 text-center mb-6",
          passed
            ? "bg-emerald-50 border-emerald-300"
            : "bg-amber-50 border-amber-300"
        )}
      >
        {passed ? (
          <Trophy size={48} className="mx-auto text-emerald-600 mb-3" />
        ) : (
          <XCircle size={48} className="mx-auto text-amber-600 mb-3" />
        )}
        <p className={cn(
          "text-2xl font-bold mb-1",
          passed ? "text-emerald-800" : "text-amber-800"
        )}>
          {passed ? "You Passed!" : "Not Passed"}
        </p>
        <p className="text-3xl font-bold text-gray-900 tabular-nums mb-1">
          {Number(result.scorePercent).toFixed(0)}%
        </p>
        <p className="text-sm text-gray-600">
          {result.correctCount} of {result.totalQuestions} correct
          {result.timeTakenSeconds != null && (
            <> · {Math.floor(result.timeTakenSeconds / 60)}m {result.timeTakenSeconds % 60}s</>
          )}
        </p>
        <p className="text-xs text-gray-500 mt-2">
          Pass threshold: {result.passThreshold}%
        </p>
        {!passed && result.attemptsRemaining != null && (
          <p className="text-xs text-amber-700 mt-2">
            {result.attemptsRemaining > 0
              ? `Attempts remaining: ${result.attemptsRemaining}`
              : "No more attempts. Review the material and try again later."}
          </p>
        )}
      </motion.div>

      <h2 className="text-lg font-semibold text-gray-900 mb-3">Review Answers</h2>
      <div className="space-y-3 mb-8">
        {(quiz.questions ?? []).map((q, idx) => {
          const r = result.results.find((x) => x.questionId === q.id);
          const selectedTexts = (r?.selectedOptionIds ?? []).map((id) => optionLookup.get(id) ?? "?");
          const correctTexts = (r?.correctOptionIds ?? []).map((id) => optionLookup.get(id) ?? "?");
          const correct = !!r?.correct;
          return (
            <div
              key={q.id}
              className={cn(
                "rounded-xl border p-4 text-sm",
                correct ? "bg-emerald-50 border-emerald-200" : "bg-red-50 border-red-200"
              )}
            >
              <p className="font-medium text-gray-900 mb-2">
                Q{idx + 1}. {q.questionText}
              </p>
              <p className="text-gray-700">
                <span className="font-semibold">Your answer:</span>{" "}
                {selectedTexts.length > 0 ? selectedTexts.join(", ") : "—"}{" "}
                {correct
                  ? <CheckCircle2 className="inline text-emerald-600" size={14} />
                  : <X className="inline text-red-700" size={14} />}
              </p>
              {!correct && (
                <p className="text-gray-700 mt-1">
                  <span className="font-semibold">Correct:</span> {correctTexts.join(", ")}
                </p>
              )}
              {r?.explanation && (
                <p className="text-xs text-gray-600 italic mt-2">
                  💡 {r.explanation}
                </p>
              )}
            </div>
          );
        })}
      </div>

      <div className="flex flex-wrap items-center gap-3">
        {!passed && canRetry && (
          <button
            onClick={onRetry}
            className="inline-flex items-center gap-1.5 px-5 py-2.5 rounded-lg text-sm font-semibold bg-sage-navy text-white hover:bg-sage-navy-deep transition cursor-pointer"
          >
            Try Again
          </button>
        )}
        <Link
          href={returnTo ?? "/dashboard"}
          className="inline-flex items-center gap-1.5 px-5 py-2.5 rounded-lg text-sm font-medium text-gray-700 border border-gray-300 hover:bg-gray-50 transition"
        >
          {passed ? "Back to course" : "Back"}
        </Link>
      </div>
    </section>
  );
}
