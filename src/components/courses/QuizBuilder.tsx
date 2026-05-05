"use client";

import { useState } from "react";
import { motion, AnimatePresence } from "framer-motion";
import { Plus, ChevronDown, ChevronUp, Loader2, CheckCircle2, BrainCircuit } from "lucide-react";
import { createQuiz, addQuizQuestion } from "@/lib/api";

interface QuizBuilderProps {
  lessonId: number;
  lessonTitle: string;
}

interface CreatedQuiz {
  id: number;
  title: string;
}

interface QuestionForm {
  questionText: string;
  optionA: string;
  optionB: string;
  optionC: string;
  optionD: string;
  correctAnswer: string;
}

const emptyQuestion: QuestionForm = {
  questionText: "",
  optionA: "",
  optionB: "",
  optionC: "",
  optionD: "",
  correctAnswer: "A",
};

export default function QuizBuilder({ lessonId, lessonTitle }: QuizBuilderProps) {
  const [open, setOpen] = useState(false);
  const [quizTitle, setQuizTitle] = useState(`Quiz: ${lessonTitle}`);
  const [quiz, setQuiz] = useState<CreatedQuiz | null>(null);
  const [creating, setCreating] = useState(false);
  const [question, setQuestion] = useState<QuestionForm>({ ...emptyQuestion });
  const [adding, setAdding] = useState(false);
  const [questionsAdded, setQuestionsAdded] = useState(0);
  const [error, setError] = useState<string | null>(null);

  const handleCreateQuiz = async () => {
    setCreating(true);
    setError(null);
    try {
      const data = await createQuiz(lessonId, quizTitle);
      setQuiz(data as CreatedQuiz);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to create quiz");
    } finally {
      setCreating(false);
    }
  };

  const handleAddQuestion = async () => {
    if (!quiz) return;
    setAdding(true);
    setError(null);
    try {
      await addQuizQuestion(quiz.id, {
        questionText: question.questionText,
        optionA: question.optionA,
        optionB: question.optionB,
        optionC: question.optionC || undefined,
        optionD: question.optionD || undefined,
        correctAnswer: question.correctAnswer,
      });
      setQuestionsAdded((n) => n + 1);
      setQuestion({ ...emptyQuestion });
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to add question");
    } finally {
      setAdding(false);
    }
  };

  const canAddQuestion =
    question.questionText.trim() &&
    question.optionA.trim() &&
    question.optionB.trim() &&
    question.correctAnswer;

  return (
    <div className="bg-white/60 backdrop-blur-xl border border-zinc-200 rounded-2xl overflow-hidden">
      {/* Toggle header */}
      <button
        onClick={() => setOpen(!open)}
        className="w-full flex items-center justify-between p-5 text-left hover:bg-white/[0.02] transition-colors"
      >
        <div className="flex items-center gap-2">
          <BrainCircuit className="w-5 h-5 text-[#D4A017]" />
          <span className="text-zinc-900 font-semibold">Quiz Builder</span>
          {questionsAdded > 0 && (
            <span className="text-xs bg-[#D4A017]/20 text-[#D4A017] px-2 py-0.5 rounded-full">
              {questionsAdded} question{questionsAdded !== 1 ? "s" : ""}
            </span>
          )}
        </div>
        {open ? (
          <ChevronUp className="w-5 h-5 text-zinc-600" />
        ) : (
          <ChevronDown className="w-5 h-5 text-zinc-600" />
        )}
      </button>

      <AnimatePresence>
        {open && (
          <motion.div
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: "auto", opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={{ duration: 0.25 }}
            className="overflow-hidden"
          >
            <div className="p-5 pt-0 space-y-5 border-t border-zinc-200">
              {!quiz ? (
                /* Step 1: Create quiz */
                <div className="space-y-3">
                  <label className="block text-zinc-600 text-sm">Quiz Title</label>
                  <input
                    value={quizTitle}
                    onChange={(e) => setQuizTitle(e.target.value)}
                    className="w-full px-4 py-2.5 rounded-xl bg-white/60 border border-zinc-200 text-zinc-900 text-sm outline-none focus:border-[#0F5132]/40 transition-colors"
                  />
                  <button
                    onClick={handleCreateQuiz}
                    disabled={!quizTitle.trim() || creating}
                    className="px-5 py-2.5 rounded-xl bg-gradient-to-r from-[#D4A017] to-[#0F5132] text-zinc-900 font-medium text-sm hover:opacity-90 transition-opacity disabled:opacity-40 flex items-center gap-2"
                  >
                    {creating ? (
                      <Loader2 className="w-4 h-4 animate-spin" />
                    ) : (
                      <Plus className="w-4 h-4" />
                    )}
                    Create Quiz
                  </button>
                </div>
              ) : (
                /* Step 2: Add questions */
                <div className="space-y-4">
                  <div className="flex items-center gap-2 text-emerald-400 text-sm">
                    <CheckCircle2 className="w-4 h-4" />
                    Quiz &quot;{quiz.title}&quot; created
                  </div>

                  <div className="space-y-3">
                    <label className="block text-zinc-600 text-sm">Question</label>
                    <textarea
                      value={question.questionText}
                      onChange={(e) =>
                        setQuestion((q) => ({ ...q, questionText: e.target.value }))
                      }
                      rows={2}
                      className="w-full px-4 py-2.5 rounded-xl bg-white/60 border border-zinc-200 text-zinc-900 text-sm outline-none focus:border-[#0F5132]/40 transition-colors resize-none"
                      placeholder="Enter the question..."
                    />

                    <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                      {(["A", "B", "C", "D"] as const).map((opt) => (
                        <input
                          key={opt}
                          value={question[`option${opt}` as keyof QuestionForm]}
                          onChange={(e) =>
                            setQuestion((q) => ({
                              ...q,
                              [`option${opt}`]: e.target.value,
                            }))
                          }
                          placeholder={`Option ${opt}${opt === "C" || opt === "D" ? " (optional)" : ""}`}
                          className="px-4 py-2.5 rounded-xl bg-white/60 border border-zinc-200 text-zinc-900 text-sm outline-none focus:border-[#0F5132]/40 transition-colors"
                        />
                      ))}
                    </div>

                    <div>
                      <label className="block text-zinc-600 text-sm mb-2">
                        Correct Answer
                      </label>
                      <div className="flex gap-2">
                        {["A", "B", "C", "D"].map((opt) => (
                          <button
                            key={opt}
                            onClick={() =>
                              setQuestion((q) => ({ ...q, correctAnswer: opt }))
                            }
                            className={`w-10 h-10 rounded-lg border text-sm font-semibold transition-all ${
                              question.correctAnswer === opt
                                ? "bg-[#0F5132]/20 border-[#0F5132]/50 text-[#0F5132]"
                                : "bg-white/60 border-zinc-200 text-zinc-600 hover:text-zinc-900"
                            }`}
                          >
                            {opt}
                          </button>
                        ))}
                      </div>
                    </div>

                    <button
                      onClick={handleAddQuestion}
                      disabled={!canAddQuestion || adding}
                      className="px-5 py-2.5 rounded-xl bg-[#D4A017]/20 border border-[#D4A017]/30 text-[#D4A017] font-medium text-sm hover:bg-[#D4A017]/30 transition-colors disabled:opacity-40 flex items-center gap-2"
                    >
                      {adding ? (
                        <Loader2 className="w-4 h-4 animate-spin" />
                      ) : (
                        <Plus className="w-4 h-4" />
                      )}
                      Add Question
                    </button>
                  </div>
                </div>
              )}

              {error && <p className="text-red-400 text-sm">{error}</p>}
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
