"use client";

import { Suspense, useEffect, useMemo, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import Link from "next/link";
import { motion } from "framer-motion";
import {
  AlertCircle, ArrowRight, CheckCircle2, Loader2, Save,
} from "lucide-react";

import OnboardingLayout from "@/components/layouts/OnboardingLayout";
import { useAuth } from "@/lib/auth-context";
import {
  getProgramSelection,
  saveProgramSelectionDraft, submitProgramSelection,
  type ProgramSelectionDTO,
} from "@/lib/api";

/**
 * Step 6 — Program selection.
 *
 * The participant reviews a versioned service summary (SVC-v1.0)
 * and picks their program, phase, skillset, target job title, and
 * availability. Skillset + availability pre-fill from the
 * enrollment record but stay editable here.
 *
 * Two write paths:
 *   - "Save and continue later"  →  POST /program-selection/draft
 *     (partial, no validation, no workflow transition)
 *   - "Continue to agreement"    →  POST /program-selection
 *     (validates, transitions to PROGRAM_SELECTED, emails confirmation)
 */

const SUMMARY_VERSION = "SVC-v1.0";

const PROGRAM_OPTIONS = [
  "Career Development Program (Phase 1 + Phase 2)",
  "Career Coaching Only (Phase 1)",
  "Technical Development Only",
  "Interview Preparation Package",
  "Resume & Profile Administration",
  "Custom Service Package (contact for details)",
] as const;

const PHASE_OPTIONS: ReadonlyArray<{ value: string; label: string }> = [
  { value: "PHASE_1_ONLY", label: "Phase 1 Only" },
  { value: "PHASE_1_AND_2", label: "Phase 1 + Phase 2" },
  { value: "PHASE_2_ONLY", label: "Phase 2 Only (returning participant)" },
];

const TECHNOLOGY_OPTIONS = [
  "Java Full Stack",
  "Python Full Stack",
  ".NET Full Stack",
  "Data Engineering",
  "Cloud & DevOps",
  "React / Angular Frontend",
  "QA / Testing",
  "Data Science & AI",
  "Salesforce",
  "ServiceNow",
  "Cybersecurity",
  "Other",
] as const;

const COACHING_OPTIONS: ReadonlyArray<{ value: string; label: string }> = [
  { value: "NO_PREFERENCE", label: "No preference" },
  { value: "MORNING", label: "Morning sessions preferred" },
  { value: "EVENING", label: "Evening sessions preferred" },
  { value: "WEEKEND", label: "Weekend sessions preferred" },
];

const AVAILABILITY_OPTIONS = [
  "Full-time",
  "Part-time",
  "Weekends only",
  "Flexible",
] as const;

const INPUT_CLASS =
  "w-full px-3.5 py-2.5 text-sm rounded-lg border border-gray-200 bg-white " +
  "text-gray-900 placeholder-gray-400 transition focus:outline-none " +
  "focus:border-[#1B2A5C] focus:ring-1 focus:ring-[#1B2A5C] " +
  "disabled:bg-gray-50 disabled:cursor-not-allowed";
const LABEL_CLASS = "block text-[13px] font-medium text-gray-700 mb-1";

function ProgramSelectionPageInner() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const fromProfile = searchParams.get("from") === "profile";
  const { user, isAuthenticated, isLoading: authLoading, refreshUser } = useAuth();

  const [gateChecked, setGateChecked] = useState(false);
  const [gateError, setGateError] = useState("");

  // ── Form state ────────────────────────────────────────────────
  const [reviewed, setReviewed] = useState(false);
  const [program, setProgram] = useState("");
  const [phase, setPhase] = useState("");
  const [skillset, setSkillset] = useState("");
  const [targetJobTitle, setTargetJobTitle] = useState("");
  const [coachingPreference, setCoachingPreference] = useState("NO_PREFERENCE");
  const [availability, setAvailability] = useState("");
  const [notes, setNotes] = useState("");

  const [savingDraft, setSavingDraft] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");
  const [draftSavedAt, setDraftSavedAt] = useState<number | null>(null);

  // ── Gate + pre-fill ───────────────────────────────────────────
  // Phase 1C — gate on programSelectionComplete + participantId.
  useEffect(() => {
    if (authLoading) return;
    if (!isAuthenticated) {
      router.replace("/login");
      return;
    }
    if (!user) return;
    if (user.programSelectionComplete) {
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
        // Pre-fill: prefer the saved program-selection row, then
        // fall back to enrollment values for skillset + availability.
        const existing = await getProgramSelection().catch(() => null);
        if (cancelled) return;
        applyPrefill(existing, {
          selectedTechnology: user.selectedTechnology,
          availability: user.availability,
        });
        setGateChecked(true);
      } catch (err) {
        if (cancelled) return;
        setGateError(err instanceof Error ? err.message : "Couldn't load your selection");
        setGateChecked(true);
      }
    })();
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [authLoading, isAuthenticated, user, router]);

  const applyPrefill = (existing: ProgramSelectionDTO | null,
                       profile: { selectedTechnology?: string | null; availability?: string | null }) => {
    if (existing) {
      if (existing.program) setProgram(existing.program);
      if (existing.phase) setPhase(existing.phase);
      if (existing.skillset) setSkillset(existing.skillset);
      if (existing.targetJobTitle) setTargetJobTitle(existing.targetJobTitle);
      if (existing.coachingPreference) setCoachingPreference(existing.coachingPreference);
      if (existing.availability) setAvailability(existing.availability);
      if (existing.notes) setNotes(existing.notes);
      if (existing.serviceSummaryVersion === SUMMARY_VERSION) {
        // Hold them to re-reviewing only when the version has rolled
        // forward — same-version returning visits keep the check.
        setReviewed(true);
      }
    }
    // Enrollment fallbacks — only fill when the program-selection
    // row didn't already.
    if (!existing?.skillset && profile.selectedTechnology) {
      setSkillset(profile.selectedTechnology);
    }
    if (!existing?.availability && profile.availability) {
      setAvailability(profile.availability);
    }
  };

  // ── Validation ────────────────────────────────────────────────
  const canContinue = useMemo(() => (
    reviewed
    && !!program.trim()
    && !!phase
    && !!skillset
    && targetJobTitle.trim().length >= 2
    && !!availability
    && notes.length <= 500
    && !submitting
  ), [reviewed, program, phase, skillset, targetJobTitle, availability, notes, submitting]);

  // ── Actions ───────────────────────────────────────────────────
  const buildRequest = () => ({
    program: program || undefined,
    phase: phase || undefined,
    skillset: skillset || undefined,
    targetJobTitle: targetJobTitle || undefined,
    coachingPreference: coachingPreference || undefined,
    availability: availability || undefined,
    serviceSummaryVersion: reviewed ? SUMMARY_VERSION : undefined,
    notes: notes || undefined,
  });

  const handleSaveDraft = async () => {
    setSavingDraft(true);
    setError("");
    try {
      await saveProgramSelectionDraft(buildRequest());
      setDraftSavedAt(Date.now());
    } catch (err) {
      setError(err instanceof Error ? err.message : "Couldn't save draft");
    } finally {
      setSavingDraft(false);
    }
  };

  const handleSaveAndExit = async () => {
    await handleSaveDraft();
    router.push("/dashboard");
  };

  const handleSubmit = async () => {
    if (!canContinue) return;
    setSubmitting(true);
    setError("");
    try {
      const result = await submitProgramSelection({
        program,
        phase,
        skillset,
        targetJobTitle: targetJobTitle.trim(),
        coachingPreference,
        availability,
        serviceSummaryVersion: SUMMARY_VERSION,
        notes: notes.trim() || undefined,
      });
      void result;
      await refreshUser();
      router.replace(
        fromProfile
          ? "/dashboard?tab=complete-profile&step=AGREEMENT"
          : "/dashboard?tab=complete-profile",
      );
    } catch (err) {
      setError(err instanceof Error ? err.message : "Couldn't submit program selection");
    } finally {
      setSubmitting(false);
    }
  };

  // ── Render ────────────────────────────────────────────────────
  if (authLoading || !gateChecked) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-[#F8FAFC]">
        <Loader2 size={28} className="animate-spin text-[#1B2A5C]" />
      </div>
    );
  }

  if (gateError) {
    return (
      <OnboardingLayout currentStep={6} contentMaxWidth="xl">
        <div className="bg-white rounded-2xl shadow-lg border border-gray-100 p-6 text-center">
          <AlertCircle size={20} className="text-red-600 inline-block mb-2" />
          <p className="text-sm text-red-700">{gateError}</p>
          <Link href="/document-upload" className="text-xs text-[#1B2A5C] font-semibold hover:underline mt-3 inline-block">
            ← Back to document upload
          </Link>
        </div>
      </OnboardingLayout>
    );
  }

  return (
    <OnboardingLayout currentStep={6} contentMaxWidth="3xl">
      <motion.section
        initial={{ opacity: 0, y: 12 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.3 }}
        className="bg-white rounded-2xl shadow-lg border border-gray-100 px-5 py-5 sm:px-7 sm:py-6"
      >
        <h1 className="font-serif text-xl sm:text-2xl font-bold text-gray-900">
          Select your program
        </h1>
        <p className="text-gray-500 mt-1 text-sm">
          Review the service summary and choose the path that fits your goals.
        </p>

        {/* Versioned service summary */}
        <div className="mt-4 rounded-xl border border-gray-200 bg-gray-50/60 p-4 sm:p-5">
          <div className="flex items-center justify-between mb-2">
            <p className="text-[11px] uppercase tracking-wider font-semibold text-gray-500">
              Service summary
            </p>
            <span className="font-mono text-[11px] text-gray-500">{SUMMARY_VERSION}</span>
          </div>
          <p className="text-sm text-gray-700 leading-relaxed">
            Sage IT Co offers a two-phase career development program:
          </p>
          <div className="mt-3 grid grid-cols-1 sm:grid-cols-2 gap-4 text-sm">
            <div>
              <p className="font-semibold text-gray-900 mb-1">Phase 1: Pre-employment Readiness</p>
              <ul className="space-y-1 text-gray-700 list-disc list-inside marker:text-[#1B2A5C]">
                <li>Career coaching and professional guidance</li>
                <li>Resume and profile administration</li>
                <li>Interview preparation and mock interviews</li>
                <li>Technical development modules</li>
                <li>Job-market orientation and navigation</li>
                <li>Communication coaching</li>
                <li>Weekly submission reports and checkpoints</li>
              </ul>
            </div>
            <div>
              <p className="font-semibold text-gray-900 mb-1">Phase 2: Post-offer / Transition Support</p>
              <ul className="space-y-1 text-gray-700 list-disc list-inside marker:text-[#1B2A5C]">
                <li>Post-offer technical enhancement</li>
                <li>Documentation and transition support</li>
                <li>Role-aligned coaching</li>
                <li>Onboarding support and monthly resources</li>
              </ul>
            </div>
          </div>
        </div>

        <label className="mt-4 flex items-start gap-2.5 text-sm text-gray-700 cursor-pointer">
          <input
            type="checkbox"
            checked={reviewed}
            onChange={(e) => setReviewed(e.target.checked)}
            className="mt-0.5 w-4 h-4 rounded border-gray-300 text-[#1B2A5C] focus:ring-[#C87D5C]"
          />
          <span>
            I have reviewed the service summary{" "}
            <span className="font-mono text-[12px] text-gray-500">({SUMMARY_VERSION})</span>.{" "}
            <span className="text-red-500">*</span>
          </span>
        </label>

        <hr className="my-5 border-gray-100" />

        {/* Form grid */}
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-x-4 gap-y-3">
          <div className="sm:col-span-2">
            <label className={LABEL_CLASS}>
              Program <span className="text-red-500">*</span>
            </label>
            <select
              value={program}
              onChange={(e) => setProgram(e.target.value)}
              disabled={!reviewed}
              className={INPUT_CLASS}
            >
              <option value="" disabled>Select program…</option>
              {PROGRAM_OPTIONS.map((o) => <option key={o} value={o}>{o}</option>)}
            </select>
          </div>

          <div>
            <label className={LABEL_CLASS}>
              Phase applicability <span className="text-red-500">*</span>
            </label>
            <select
              value={phase}
              onChange={(e) => setPhase(e.target.value)}
              disabled={!reviewed}
              className={INPUT_CLASS}
            >
              <option value="" disabled>Select phase…</option>
              {PHASE_OPTIONS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
            </select>
          </div>

          <div>
            <label className={LABEL_CLASS}>
              Technology / Skillset <span className="text-red-500">*</span>
            </label>
            <select
              value={skillset}
              onChange={(e) => setSkillset(e.target.value)}
              disabled={!reviewed}
              className={INPUT_CLASS}
            >
              <option value="" disabled>Select a track…</option>
              {TECHNOLOGY_OPTIONS.map((o) => <option key={o} value={o}>{o}</option>)}
            </select>
          </div>

          <div className="sm:col-span-2">
            <label className={LABEL_CLASS}>
              Target job title <span className="text-red-500">*</span>
            </label>
            <input
              type="text"
              value={targetJobTitle}
              onChange={(e) => setTargetJobTitle(e.target.value)}
              disabled={!reviewed}
              placeholder='e.g. "Java Full Stack Developer", "Data Engineer"'
              className={INPUT_CLASS}
            />
          </div>

          <div>
            <label className={LABEL_CLASS}>Coaching preference</label>
            <select
              value={coachingPreference}
              onChange={(e) => setCoachingPreference(e.target.value)}
              disabled={!reviewed}
              className={INPUT_CLASS}
            >
              {COACHING_OPTIONS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
            </select>
          </div>

          <div>
            <label className={LABEL_CLASS}>
              Availability <span className="text-red-500">*</span>
            </label>
            <select
              value={availability}
              onChange={(e) => setAvailability(e.target.value)}
              disabled={!reviewed}
              className={INPUT_CLASS}
            >
              <option value="" disabled>Select availability…</option>
              {AVAILABILITY_OPTIONS.map((o) => <option key={o} value={o}>{o}</option>)}
            </select>
          </div>

          <div className="sm:col-span-2">
            <label className={LABEL_CLASS}>
              Additional notes <span className="text-gray-400 text-[11px]">(optional, max 500 characters)</span>
            </label>
            <textarea
              value={notes}
              onChange={(e) => setNotes(e.target.value.slice(0, 500))}
              disabled={!reviewed}
              rows={3}
              placeholder="Anything we should know? Goals, blockers, scheduling notes…"
              className={INPUT_CLASS + " resize-none"}
            />
            <p className="mt-0.5 text-[11px] text-gray-400 text-right">
              {notes.length} / 500
            </p>
          </div>
        </div>

        {error && (
          <p className="mt-3 inline-flex items-center gap-1.5 text-sm text-red-600">
            <AlertCircle size={14} /> {error}
          </p>
        )}

        {draftSavedAt && (
          <p className="mt-3 inline-flex items-center gap-1.5 text-xs text-emerald-700">
            <CheckCircle2 size={12} /> Draft saved. You can come back any time to finish.
          </p>
        )}

        <div className="mt-5 flex flex-col sm:flex-row gap-2">
          <button
            type="button"
            onClick={handleSaveAndExit}
            disabled={savingDraft || submitting}
            className="inline-flex items-center justify-center gap-2 px-4 py-2.5 rounded-lg text-sm font-semibold bg-white border border-gray-200 text-gray-700 hover:border-[#1B2A5C] hover:text-[#1B2A5C] disabled:opacity-60 transition cursor-pointer"
          >
            {savingDraft ? <Loader2 size={14} className="animate-spin" /> : <Save size={14} />}
            Save and continue later
          </button>
          <button
            type="button"
            onClick={handleSubmit}
            disabled={!canContinue}
            className={
              "flex-1 inline-flex items-center justify-center gap-2 px-4 py-2.5 rounded-lg text-sm font-bold transition "
              + (canContinue
                  ? "bg-[#1B2A5C] text-white hover:bg-[#2D4480] shadow-md hover:shadow-lg cursor-pointer"
                  : "bg-gray-200 text-gray-500 cursor-not-allowed")
            }
          >
            {submitting && <Loader2 size={14} className="animate-spin" />}
            {submitting ? "Submitting…" : <>Continue to agreement <ArrowRight size={14} /></>}
          </button>
        </div>
      </motion.section>
    </OnboardingLayout>
  );
}

export default function ProgramSelectionPage() {
  return (
    <Suspense fallback={
      <div className="min-h-screen flex items-center justify-center bg-[#F8FAFC]">
        <Loader2 size={28} className="animate-spin text-[#1B2A5C]" />
      </div>
    }>
      <ProgramSelectionPageInner />
    </Suspense>
  );
}
