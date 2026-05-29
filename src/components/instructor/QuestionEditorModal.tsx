"use client";

import { useEffect, useState } from "react";
import { motion, AnimatePresence } from "framer-motion";
import { Loader2, Plus, Trash2, X, Save } from "lucide-react";
import {
  addQuizQuestion, updateQuizQuestion,
  type QuizQuestion, type QuizQuestionType,
} from "@/lib/api";
import { cn } from "@/lib/utils";

interface DraftOption {
  id?: number;
  optionText: string;
  isCorrect: boolean;
}

interface Props {
  isOpen: boolean;
  onClose: () => void;
  quizId: number;
  question?: QuizQuestion | null;
  onSaved: () => void;
}

const EMPTY_DRAFT = (): DraftOption[] => [
  { optionText: "", isCorrect: false },
  { optionText: "", isCorrect: false },
];

const TF_DRAFT = (): DraftOption[] => [
  { optionText: "True", isCorrect: false },
  { optionText: "False", isCorrect: false },
];

export function QuestionEditorModal({
  isOpen, onClose, quizId, question, onSaved,
}: Props) {
  const editing = !!question;

  const [questionText, setQuestionText] = useState("");
  const [questionType, setQuestionType] = useState<QuizQuestionType>("MULTIPLE_CHOICE");
  const [points, setPoints] = useState("1");
  const [explanation, setExplanation] = useState("");
  const [options, setOptions] = useState<DraftOption[]>(EMPTY_DRAFT());
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    if (!isOpen) return;
    if (question) {
      setQuestionText(question.questionText);
      setQuestionType(question.questionType);
      setPoints(String(question.points ?? 1));
      setExplanation(question.explanation ?? "");
      setOptions(question.options.map((o) => ({
        id: o.id,
        optionText: o.optionText,
        isCorrect: !!o.isCorrect,
      })));
    } else {
      setQuestionText("");
      setQuestionType("MULTIPLE_CHOICE");
      setPoints("1");
      setExplanation("");
      setOptions(EMPTY_DRAFT());
    }
    setError("");
  }, [isOpen, question]);

  const handleTypeChange = (next: QuizQuestionType) => {
    setQuestionType(next);
    if (next === "TRUE_FALSE") {
      setOptions(TF_DRAFT());
    } else if (next === "MULTIPLE_CHOICE") {
      setOptions((prev) => {
        let foundCorrect = false;
        return prev.map((o) => {
          if (o.isCorrect && !foundCorrect) { foundCorrect = true; return o; }
          return { ...o, isCorrect: false };
        });
      });
    }
  };

  const setOptionText = (idx: number, text: string) =>
    setOptions((prev) => prev.map((o, i) => i === idx ? { ...o, optionText: text } : o));

  const toggleCorrect = (idx: number) => {
    setOptions((prev) => {
      if (questionType === "MULTI_SELECT") {
        return prev.map((o, i) => i === idx ? { ...o, isCorrect: !o.isCorrect } : o);
      }
      return prev.map((o, i) => ({ ...o, isCorrect: i === idx }));
    });
  };

  const addOption = () => {
    if (questionType === "TRUE_FALSE") return;
    if (options.length >= 6) return;
    setOptions((prev) => [...prev, { optionText: "", isCorrect: false }]);
  };

  const removeOption = (idx: number) => {
    if (questionType === "TRUE_FALSE") return;
    if (options.length <= 2) return;
    setOptions((prev) => prev.filter((_, i) => i !== idx));
  };

  const submit = async () => {
    if (!questionText.trim()) { setError("Question text is required."); return; }
    const cleanOptions = options.map((o) => ({
      optionText: o.optionText.trim(),
      isCorrect: o.isCorrect,
    }));
    if (cleanOptions.some((o) => !o.optionText)) {
      setError("Every option needs text.");
      return;
    }
    const correctCount = cleanOptions.filter((o) => o.isCorrect).length;
    if (questionType === "MULTI_SELECT") {
      if (correctCount < 1) { setError("Pick at least one correct option."); return; }
    } else {
      if (correctCount !== 1) { setError("Mark exactly one correct option."); return; }
    }

    setBusy(true);
    setError("");
    try {
      const payload = {
        questionText: questionText.trim(),
        questionType,
        points: Math.max(1, parseInt(points, 10) || 1),
        explanation: explanation.trim() || undefined,
        options: cleanOptions,
      };
      if (editing && question) {
        await updateQuizQuestion(question.id, payload);
      } else {
        await addQuizQuestion(quizId, payload);
      }
      onSaved();
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Save failed");
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
              <h2 className="text-lg font-bold text-gray-900">
                {editing ? "Edit Question" : "Add Question"}
              </h2>
              <button
                onClick={onClose}
                className="text-gray-400 hover:text-gray-600 cursor-pointer"
                aria-label="Close"
              >
                <X size={18} />
              </button>
            </div>

            <div className="space-y-4">
              <div>
                <label className="block text-xs font-medium text-gray-700 mb-1">Type</label>
                <div className="grid grid-cols-3 gap-2 text-xs">
                  {([
                    ["MULTIPLE_CHOICE", "Multiple Choice"],
                    ["TRUE_FALSE", "True/False"],
                    ["MULTI_SELECT", "Multi-Select"],
                  ] as const).map(([val, label]) => (
                    <button
                      key={val}
                      onClick={() => handleTypeChange(val)}
                      className={cn(
                        "px-3 py-2 rounded-lg font-medium transition cursor-pointer",
                        questionType === val
                          ? "bg-sage-navy text-white"
                          : "bg-gray-100 text-gray-700 hover:bg-gray-200"
                      )}
                    >
                      {label}
                    </button>
                  ))}
                </div>
              </div>

              <div>
                <label className="block text-xs font-medium text-gray-700 mb-1">
                  Question <span className="text-red-500">*</span>
                </label>
                <textarea
                  value={questionText}
                  onChange={(e) => setQuestionText(e.target.value)}
                  rows={2}
                  placeholder="What hook is used for state in React?"
                  className="w-full px-3 py-2 rounded-lg border border-gray-300 text-sm focus:outline-none focus:ring-2 focus:ring-sage-navy/30 resize-y"
                />
              </div>

              <div>
                <label className="block text-xs font-medium text-gray-700 mb-1">Points</label>
                <input
                  type="number"
                  min="1"
                  value={points}
                  onChange={(e) => setPoints(e.target.value)}
                  className="w-24 px-3 py-2 rounded-lg border border-gray-300 text-sm focus:outline-none focus:ring-2 focus:ring-sage-navy/30"
                />
              </div>

              <div>
                <label className="block text-xs font-medium text-gray-700 mb-1">
                  Options <span className="text-gray-400 font-normal">
                    ({questionType === "MULTI_SELECT" ? "check all that are correct" : "select the correct answer"})
                  </span>
                </label>
                <div className="space-y-2">
                  {options.map((o, idx) => (
                    <div key={idx} className="flex items-center gap-2">
                      <button
                        onClick={() => toggleCorrect(idx)}
                        className={cn(
                          "w-6 h-6 flex-shrink-0 flex items-center justify-center transition cursor-pointer",
                          questionType === "MULTI_SELECT" ? "rounded" : "rounded-full",
                          o.isCorrect
                            ? "bg-emerald-500 border-2 border-emerald-500 text-white"
                            : "border-2 border-gray-300 hover:border-sage-navy"
                        )}
                        aria-label={o.isCorrect ? "Marked correct" : "Mark correct"}
                      >
                        {o.isCorrect && "✓"}
                      </button>
                      <input
                        type="text"
                        value={o.optionText}
                        onChange={(e) => setOptionText(idx, e.target.value)}
                        disabled={questionType === "TRUE_FALSE"}
                        placeholder="Option text"
                        className="flex-1 px-3 py-2 rounded-lg border border-gray-300 text-sm focus:outline-none focus:ring-2 focus:ring-sage-navy/30 disabled:bg-gray-50"
                      />
                      {questionType !== "TRUE_FALSE" && (
                        <button
                          onClick={() => removeOption(idx)}
                          disabled={options.length <= 2}
                          className="p-2 text-gray-400 hover:text-red-700 disabled:opacity-30 disabled:cursor-not-allowed cursor-pointer"
                          aria-label="Remove option"
                        >
                          <Trash2 size={14} />
                        </button>
                      )}
                    </div>
                  ))}
                </div>
                {questionType !== "TRUE_FALSE" && options.length < 6 && (
                  <button
                    onClick={addOption}
                    className="mt-2 inline-flex items-center gap-1 text-xs font-semibold text-sage-navy hover:text-sage-navy-deep cursor-pointer"
                  >
                    <Plus size={12} /> Add Option
                  </button>
                )}
              </div>

              <div>
                <label className="block text-xs font-medium text-gray-700 mb-1">
                  Explanation <span className="text-gray-400 font-normal">(shown after the student submits)</span>
                </label>
                <textarea
                  value={explanation}
                  onChange={(e) => setExplanation(e.target.value)}
                  rows={2}
                  placeholder="Why is the correct answer correct?"
                  className="w-full px-3 py-2 rounded-lg border border-gray-300 text-sm focus:outline-none focus:ring-2 focus:ring-sage-navy/30 resize-y"
                />
              </div>

              {error && <p className="text-xs text-red-700">{error}</p>}

              <div className="flex items-center justify-end gap-2 pt-2">
                <button
                  onClick={onClose}
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
                  {editing ? "Save Question" : "Add Question"}
                </button>
              </div>
            </div>
          </motion.div>
        </motion.div>
      )}
    </AnimatePresence>
  );
}
