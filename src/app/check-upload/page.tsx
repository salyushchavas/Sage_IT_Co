"use client";

import { Suspense, useEffect, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import Link from "next/link";
import { motion } from "framer-motion";
import {
  AlertCircle, CheckCircle2, FileText, Loader2, Lock, Plus, Send,
  Trash2, Upload as UploadIcon,
} from "lucide-react";

import OnboardingLayout from "@/components/layouts/OnboardingLayout";
import { useAuth } from "@/lib/auth-context";
import {
  getProfileCompletion,
  listMyChecks, markCheckNotApplicable, uploadCheckSoftCopy,
  type CheckDocumentDTO,
} from "@/lib/api";

/**
 * Step 7 (continued) — /check-upload.
 *
 * Per PRD Section 4 the check soft-copy upload is its own page,
 * sitting between /agreement and /welcome. Two routes:
 *
 *   • "Not applicable" → POST /checks/mark-na → /welcome
 *   • Upload one or more checks → POST /checks/upload → /welcome
 *
 * Either path flips workflow to CHECK_COPY_UPLOADED.
 */

const ACCEPTED = "application/pdf,image/png,image/jpeg,image/jpg";
const MAX_BYTES = 5 * 1024 * 1024;

interface CheckDraft {
  id: string;
  file: File | null;
  checkNumber: string;
  amount: string;
  checkDate: string;
  notes: string;
  uploading: boolean;
  error: string;
}

const newDraft = (): CheckDraft => ({
  id: Math.random().toString(36).slice(2),
  file: null,
  checkNumber: "",
  amount: "",
  checkDate: new Date().toISOString().slice(0, 10),
  notes: "",
  uploading: false,
  error: "",
});

function CheckUploadPageInner() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const fromProfile = searchParams.get("from") === "profile";
  const { user, isAuthenticated, isLoading, refreshUser } = useAuth();

  const [gateChecked, setGateChecked] = useState(false);
  const [gateError, setGateError] = useState("");
  const [mode, setMode] = useState<"undecided" | "na" | "upload">("undecided");
  const [drafts, setDrafts] = useState<CheckDraft[]>([newDraft()]);
  const [uploaded, setUploaded] = useState<CheckDocumentDTO[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    if (isLoading) return;
    if (!isAuthenticated) {
      router.replace("/login");
      return;
    }
    if (!user) return;
    // Phase 1C — gate on checkUploadComplete + participantId.
    if (user.checkUploadComplete) {
      router.replace("/dashboard?tab=complete-profile");
      return;
    }
    if (!user.participantId) {
      router.replace("/enroll");
      return;
    }
    let cancelled = false;
    (async () => {
      try {
        const existing = await listMyChecks().catch(() => []);
        if (!cancelled) {
          setUploaded(existing);
          if (existing.length > 0) setMode("upload");
        }
        setGateChecked(true);
      } catch (err) {
        if (!cancelled) {
          setGateError(err instanceof Error ? err.message : "Couldn't load check upload");
          setGateChecked(true);
        }
      }
    })();
    return () => { cancelled = true; };
  }, [isAuthenticated, isLoading, user, router]);

  const patch = (id: string, p: Partial<CheckDraft>) =>
    setDrafts((prev) => prev.map((d) => (d.id === id ? { ...d, ...p } : d)));

  const handleUploadCheck = async (draftId: string) => {
    const draft = drafts.find((d) => d.id === draftId);
    if (!draft || !draft.file || !draft.checkNumber.trim()) {
      patch(draftId, { error: "File and check number are required" });
      return;
    }
    if (draft.file.size > MAX_BYTES) {
      patch(draftId, { error: "Max file size is 5 MB" });
      return;
    }
    patch(draftId, { uploading: true, error: "" });
    try {
      const result = await uploadCheckSoftCopy(draft.file, {
        checkNumber: draft.checkNumber.trim(),
        amount: draft.amount ? Number(draft.amount) : undefined,
        checkDate: draft.checkDate || undefined,
        notes: draft.notes.trim() || undefined,
      });
      setUploaded((prev) => [...prev, result]);
      setDrafts((prev) => prev.filter((d) => d.id !== draftId));
      if (drafts.length === 1) setDrafts([newDraft()]);
    } catch (err) {
      patch(draftId, { error: err instanceof Error ? err.message : "Upload failed" });
    } finally {
      patch(draftId, { uploading: false });
    }
  };

  // Shared post-finish nav — check-upload is the last profile step,
  // so a 100% rollup here routes the user to /welcome for the
  // celebration; otherwise back to the checklist tab.
  const finishCheckStep = async () => {
    await refreshUser();
    try {
      const completion = await getProfileCompletion();
      const isComplete = completion.isComplete ?? completion.complete ?? false;
      if (isComplete) {
        router.push("/welcome?celebration=true");
        return;
      }
    } catch {
      /* fall through to the checklist */
    }
    router.push("/dashboard?tab=complete-profile");
  };

  const handleSkipNotApplicable = async () => {
    setSubmitting(true);
    setError("");
    try {
      await markCheckNotApplicable();
      await finishCheckStep();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Couldn't mark N/A");
    } finally {
      setSubmitting(false);
    }
  };

  const handleContinue = async () => {
    await finishCheckStep();
  };

  if (isLoading || !gateChecked) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-[#F8FAFC]">
        <Loader2 size={28} className="animate-spin text-[#1B2A5C]" />
      </div>
    );
  }
  if (gateError) {
    return (
      <OnboardingLayout currentStep={7} contentMaxWidth="xl">
        <div className="bg-white rounded-2xl shadow-lg border border-gray-100 p-6 text-center">
          <AlertCircle size={20} className="text-red-600 inline-block mb-2" />
          <p className="text-sm text-red-700">{gateError}</p>
          <Link
            href="/agreement"
            className="text-xs text-[#1B2A5C] font-semibold hover:underline mt-3 inline-block"
          >
            ← Back to agreement
          </Link>
        </div>
      </OnboardingLayout>
    );
  }

  return (
    <OnboardingLayout currentStep={7} contentMaxWidth="3xl">
      <motion.section
        initial={{ opacity: 0, y: 8 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.25 }}
        className="bg-white rounded-2xl shadow-lg border border-gray-100 px-5 py-5 sm:px-7 sm:py-6"
      >
        <h1 className="font-serif text-xl sm:text-2xl font-bold text-gray-900">
          Upload check soft copies
        </h1>
        <p className="text-gray-500 mt-1 text-sm">
          Upload soft copies / images of your checks through this secure
          portal where required. If checks aren&apos;t part of your payment
          arrangement, mark this step as not applicable.
        </p>

        <div className="mt-5 rounded-xl border border-gray-200 bg-gray-50/60 p-4 space-y-2">
          <label className="inline-flex items-center gap-2 text-sm cursor-pointer">
            <input
              type="radio"
              name="checkMode"
              checked={mode === "na"}
              onChange={() => setMode("na")}
              className="accent-[#1B2A5C]"
            />
            <span className="text-gray-800">Check upload is not applicable to me</span>
          </label>
          <label className="inline-flex items-center gap-2 text-sm cursor-pointer">
            <input
              type="radio"
              name="checkMode"
              checked={mode === "upload"}
              onChange={() => setMode("upload")}
              className="accent-[#1B2A5C]"
            />
            <span className="text-gray-800">I need to upload check copies</span>
          </label>
        </div>

        {mode === "upload" && (
          <>
            <p className="mt-5 inline-flex items-start gap-1.5 text-[11px] text-amber-800 bg-amber-50 border border-amber-200 rounded-md px-3 py-2">
              <Lock size={12} className="mt-0.5 shrink-0" />
              Check images are stored securely. Only authorised finance team
              members can view full check details.
            </p>

            {uploaded.length > 0 && (
              <div className="mt-4 space-y-1.5">
                <p className="text-[11px] uppercase tracking-wider font-semibold text-gray-500">
                  Uploaded
                </p>
                {uploaded.map((c) => (
                  <div
                    key={c.id}
                    className="flex items-center gap-2 text-xs rounded-lg border border-emerald-200 bg-emerald-50/50 px-3 py-2"
                  >
                    <CheckCircle2 size={14} className="text-emerald-600 shrink-0" />
                    <span className="font-medium text-gray-800">
                      Check #{c.checkNumber || c.id}
                    </span>
                    <span className="text-gray-500 ml-auto">{c.reviewStatus}</span>
                  </div>
                ))}
              </div>
            )}

            <div className="mt-4 space-y-3">
              {drafts.map((d) => (
                <DraftCard
                  key={d.id}
                  draft={d}
                  onPatch={(p) => patch(d.id, p)}
                  onUpload={() => handleUploadCheck(d.id)}
                  onRemove={
                    drafts.length > 1
                      ? () =>
                          setDrafts((prev) => prev.filter((x) => x.id !== d.id))
                      : null
                  }
                />
              ))}
              <button
                type="button"
                onClick={() => setDrafts((prev) => [...prev, newDraft()])}
                className="inline-flex items-center gap-1.5 text-xs font-semibold text-[#1B2A5C] hover:text-[#2D4480] cursor-pointer"
              >
                <Plus size={12} /> Add another check
              </button>
            </div>
          </>
        )}

        {error && (
          <p className="mt-4 inline-flex items-center gap-1.5 text-sm text-red-600">
            <AlertCircle size={14} /> {error}
          </p>
        )}

        <div className="mt-6 flex flex-col sm:flex-row gap-2 sm:justify-end">
          {mode === "na" && (
            <button
              type="button"
              onClick={handleSkipNotApplicable}
              disabled={submitting}
              className="inline-flex items-center justify-center gap-2 px-4 py-2.5 rounded-lg text-sm font-bold bg-[#1B2A5C] text-white hover:bg-[#2D4480] disabled:opacity-60 cursor-pointer"
            >
              {submitting ? <Loader2 size={14} className="animate-spin" /> : <Send size={14} />}
              {submitting ? "Saving…" : "Skip — Not applicable"}
            </button>
          )}
          {mode === "upload" && uploaded.length > 0 && (
            <button
              type="button"
              onClick={handleContinue}
              className="inline-flex items-center justify-center gap-2 px-4 py-2.5 rounded-lg text-sm font-bold bg-[#1B2A5C] text-white hover:bg-[#2D4480] cursor-pointer"
            >
              Continue → <Send size={14} />
            </button>
          )}
        </div>
      </motion.section>
    </OnboardingLayout>
  );
}

function DraftCard({
  draft,
  onPatch,
  onUpload,
  onRemove,
}: {
  draft: CheckDraft;
  onPatch: (p: Partial<CheckDraft>) => void;
  onUpload: () => void;
  onRemove: (() => void) | null;
}) {
  const inputRef = useRef<HTMLInputElement | null>(null);
  return (
    <div className="rounded-xl border border-gray-200 bg-white p-3 space-y-2">
      <div>
        <label className="block text-[11px] font-medium text-gray-700 mb-1">
          Check image <span className="text-red-500">*</span>
        </label>
        <input
          ref={inputRef}
          type="file"
          accept={ACCEPTED}
          onChange={(e) =>
            onPatch({ file: e.target.files?.[0] ?? null, error: "" })
          }
          className="block w-full text-xs file:mr-3 file:py-1.5 file:px-3 file:rounded-md file:border-0 file:text-xs file:font-semibold file:bg-[#1B2A5C] file:text-white hover:file:bg-[#2D4480] cursor-pointer"
        />
        {draft.file && (
          <p className="text-[11px] text-gray-500 mt-1 inline-flex items-center gap-1">
            <FileText size={11} /> {draft.file.name} ·{" "}
            {(draft.file.size / 1024).toFixed(0)} KB
          </p>
        )}
      </div>
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
        <Input
          label="Check number *"
          value={draft.checkNumber}
          onChange={(v) => onPatch({ checkNumber: v })}
          placeholder="Enter check number"
        />
        <Input
          label="Amount"
          type="number"
          prefix="$"
          value={draft.amount}
          onChange={(v) => onPatch({ amount: v })}
          placeholder="0.00"
        />
        <Input
          label="Check date"
          type="date"
          value={draft.checkDate}
          onChange={(v) => onPatch({ checkDate: v })}
        />
        <Input
          label="Notes"
          value={draft.notes}
          onChange={(v) => onPatch({ notes: v })}
          placeholder="Any additional notes"
        />
      </div>
      {draft.error && (
        <p className="text-[11px] text-red-600 inline-flex items-center gap-1">
          <AlertCircle size={11} /> {draft.error}
        </p>
      )}
      <div className="flex items-center gap-2">
        <button
          type="button"
          onClick={onUpload}
          disabled={draft.uploading || !draft.file || !draft.checkNumber.trim()}
          className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-bold bg-[#1B2A5C] text-white hover:bg-[#2D4480] disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer"
        >
          {draft.uploading ? (
            <Loader2 size={12} className="animate-spin" />
          ) : (
            <UploadIcon size={12} />
          )}
          {draft.uploading ? "Uploading…" : "Upload check"}
        </button>
        {onRemove && (
          <button
            type="button"
            onClick={onRemove}
            className="text-gray-400 hover:text-red-600 cursor-pointer"
          >
            <Trash2 size={12} />
          </button>
        )}
      </div>
    </div>
  );
}

function Input({
  label,
  value,
  onChange,
  type = "text",
  prefix,
  placeholder,
}: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  type?: string;
  prefix?: string;
  placeholder?: string;
}) {
  return (
    <div>
      <label className="block text-[11px] font-medium text-gray-700 mb-0.5">
        {label}
      </label>
      <div className="relative">
        {prefix && (
          <span className="absolute left-2 top-1/2 -translate-y-1/2 text-xs text-gray-400">
            {prefix}
          </span>
        )}
        <input
          type={type}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          placeholder={placeholder}
          className={
            "w-full py-1.5 text-xs rounded-md border border-gray-200 " +
            (prefix ? "pl-5 pr-2" : "px-2")
          }
        />
      </div>
    </div>
  );
}

export default function CheckUploadPage() {
  return (
    <Suspense fallback={
      <div className="min-h-screen flex items-center justify-center bg-[#F8FAFC]">
        <Loader2 size={28} className="animate-spin text-[#1B2A5C]" />
      </div>
    }>
      <CheckUploadPageInner />
    </Suspense>
  );
}
