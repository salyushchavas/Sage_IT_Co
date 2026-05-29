"use client";

import { useForm } from "react-hook-form";
import { z } from "zod";
import { zodResolver } from "@hookform/resolvers/zod";
import { Loader2 } from "lucide-react";
import { useState } from "react";

import { submitBasicInfo } from "@/lib/api";

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

const AVAILABILITY_OPTIONS = [
  "Full-time",
  "Part-time",
  "Weekends only",
  "Flexible",
] as const;

const EXPERIENCE_OPTIONS = [
  "Entry Level (0-2 years)",
  "Mid Level (3-5 years)",
  "Senior Level (6+ years)",
] as const;

const schema = z.object({
  location: z.string().optional(),
  availability: z.enum(AVAILABILITY_OPTIONS),
  selectedTechnology: z.enum(TECHNOLOGY_OPTIONS),
  targetExperienceLevel: z.enum(EXPERIENCE_OPTIONS),
});

type Values = z.infer<typeof schema>;

const INPUT_CLASS =
  "w-full px-3.5 py-2.5 text-sm rounded-lg border border-gray-200 bg-white " +
  "text-gray-900 placeholder-gray-400 transition focus:outline-none " +
  "focus:border-sage-navy focus:ring-1 focus:ring-sage-navy";
const LABEL_CLASS = "block text-[13px] font-medium text-gray-700 mb-1";

interface Props {
  /** Called after the backend confirms the basic-info save. */
  onComplete?: () => void;
}

/** First profile-completion step -- rendered inline in the dashboard checklist. */
export default function BasicInfoStep({ onComplete }: Props) {
  const [error, setError] = useState("");
  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<Values>({ resolver: zodResolver(schema) });

  const onSubmit = async (data: Values) => {
    setError("");
    try {
      await submitBasicInfo({
        location: data.location?.trim() || undefined,
        availability: data.availability,
        selectedTechnology: data.selectedTechnology,
        targetExperienceLevel: data.targetExperienceLevel,
      });
      onComplete?.();
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : "Couldn't save. Try again.");
    }
  };

  return (
    <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
      {error && (
        <div className="p-2.5 rounded-lg bg-red-50 border border-red-200 text-red-700 text-sm">
          {error}
        </div>
      )}
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-x-4 gap-y-3">
        <div className="sm:col-span-2">
          <label className={LABEL_CLASS}>
            Location <span className="text-gray-400 text-[11px]">(optional)</span>
          </label>
          <input
            type="text"
            autoComplete="address-level2"
            {...register("location")}
            className={INPUT_CLASS}
            placeholder="City, state, country"
          />
        </div>
        <div>
          <label className={LABEL_CLASS}>
            Availability <span className="text-red-500">*</span>
          </label>
          <select
            {...register("availability")}
            defaultValue=""
            className={INPUT_CLASS + (errors.availability ? " !border-red-500" : "")}
          >
            <option value="" disabled>Choose availability…</option>
            {AVAILABILITY_OPTIONS.map((o) => <option key={o} value={o}>{o}</option>)}
          </select>
          {errors.availability && <p className="text-[11px] text-red-500 mt-1">{errors.availability.message}</p>}
        </div>
        <div>
          <label className={LABEL_CLASS}>
            Target Experience Level <span className="text-red-500">*</span>
          </label>
          <select
            {...register("targetExperienceLevel")}
            defaultValue=""
            className={INPUT_CLASS + (errors.targetExperienceLevel ? " !border-red-500" : "")}
          >
            <option value="" disabled>Choose experience level…</option>
            {EXPERIENCE_OPTIONS.map((o) => <option key={o} value={o}>{o}</option>)}
          </select>
          {errors.targetExperienceLevel && <p className="text-[11px] text-red-500 mt-1">{errors.targetExperienceLevel.message}</p>}
        </div>
        <div className="sm:col-span-2">
          <label className={LABEL_CLASS}>
            Technology / Skillset <span className="text-red-500">*</span>
          </label>
          <select
            {...register("selectedTechnology")}
            defaultValue=""
            className={INPUT_CLASS + (errors.selectedTechnology ? " !border-red-500" : "")}
          >
            <option value="" disabled>Choose a track…</option>
            {TECHNOLOGY_OPTIONS.map((o) => <option key={o} value={o}>{o}</option>)}
          </select>
          {errors.selectedTechnology && <p className="text-[11px] text-red-500 mt-1">{errors.selectedTechnology.message}</p>}
        </div>
      </div>

      <button
        type="submit"
        disabled={isSubmitting}
        className="inline-flex items-center justify-center gap-2 bg-sage-navy hover:bg-sage-navy-deep text-white text-sm font-bold px-5 py-2.5 rounded-lg shadow-sm hover:shadow-md transition disabled:opacity-60 disabled:cursor-not-allowed cursor-pointer"
      >
        {isSubmitting && <Loader2 size={14} className="animate-spin" />}
        {isSubmitting ? "Saving…" : "Save and Continue"}
      </button>
    </form>
  );
}
