"use client";

import { useRef, useState } from "react";
import Image from "next/image";
import { Loader2, Upload, X } from "lucide-react";
import { uploadCourseThumbnail } from "@/lib/api";

export interface CourseFormValues {
  title: string;
  shortDescription: string;
  description: string;
  category: string;
  level: "BEGINNER" | "INTERMEDIATE" | "ADVANCED";
  price: string;
  tags: string;
  thumbnailUrl: string;
}

const CATEGORY_OPTIONS = [
  "Web Development",
  "Data Science",
  "UI/UX Design",
  "Cloud & DevOps",
  "Mobile Development",
  "Career Services",
  "Other",
];

const LEVELS: CourseFormValues["level"][] = ["BEGINNER", "INTERMEDIATE", "ADVANCED"];

const MIN_PRICE = 999;

interface Props {
  initial: CourseFormValues;
  courseId?: number;
  submitLabel: string;
  cancelHref?: string;
  onSubmit: (values: CourseFormValues) => Promise<void>;
}

export function CourseForm({ initial, courseId, submitLabel, cancelHref, onSubmit }: Props) {
  const [values, setValues] = useState<CourseFormValues>(initial);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");

  const [uploading, setUploading] = useState(false);
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  const set = <K extends keyof CourseFormValues>(key: K, v: CourseFormValues[K]) =>
    setValues((prev) => ({ ...prev, [key]: v }));

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError("");

    if (!values.title.trim()) { setError("Title is required."); return; }
    if (!values.description.trim()) { setError("Description is required."); return; }
    if (!values.category.trim()) { setError("Category is required."); return; }
    const priceNum = Number(values.price);
    if (!values.price || Number.isNaN(priceNum) || priceNum < MIN_PRICE) {
      setError(`Price must be at least ₹${MIN_PRICE}.`);
      return;
    }

    setSubmitting(true);
    try {
      await onSubmit(values);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to save");
    } finally {
      setSubmitting(false);
    }
  };

  const handleFileChange = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file || !courseId) return;
    setUploading(true);
    setError("");
    try {
      const { thumbnailUrl } = await uploadCourseThumbnail(courseId, file);
      set("thumbnailUrl", thumbnailUrl);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Thumbnail upload failed");
    } finally {
      setUploading(false);
      if (fileInputRef.current) fileInputRef.current.value = "";
    }
  };

  return (
    <form onSubmit={handleSubmit} className="bg-white rounded-2xl shadow-sm border border-gray-100 p-6 sm:p-8 space-y-5">
      <div>
        <label className="block text-sm font-medium text-gray-700 mb-1.5">
          Course title <span className="text-red-500">*</span>
        </label>
        <input
          type="text"
          value={values.title}
          onChange={(e) => set("title", e.target.value)}
          placeholder="e.g. React Mastery"
          className="w-full px-4 py-2.5 rounded-lg border border-gray-300 text-sm focus:outline-none focus:ring-2 focus:ring-sage-navy/30"
        />
      </div>

      <div>
        <label className="block text-sm font-medium text-gray-700 mb-1.5">
          Short description <span className="text-gray-400 font-normal">(shown on course card)</span>
        </label>
        <input
          type="text"
          value={values.shortDescription}
          onChange={(e) => set("shortDescription", e.target.value.slice(0, 200))}
          maxLength={200}
          placeholder="e.g. Master React from basics to advanced patterns"
          className="w-full px-4 py-2.5 rounded-lg border border-gray-300 text-sm focus:outline-none focus:ring-2 focus:ring-sage-navy/30"
        />
        <p className="mt-1 text-[11px] text-gray-400">
          {values.shortDescription.length}/200 characters
        </p>
      </div>

      <div>
        <label className="block text-sm font-medium text-gray-700 mb-1.5">
          Full description <span className="text-red-500">*</span>
        </label>
        <textarea
          value={values.description}
          onChange={(e) => set("description", e.target.value)}
          rows={5}
          placeholder="What will students learn? What outcomes can they expect?"
          className="w-full px-4 py-2.5 rounded-lg border border-gray-300 text-sm focus:outline-none focus:ring-2 focus:ring-sage-navy/30 resize-y"
        />
      </div>

      <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
        <div>
          <label className="block text-sm font-medium text-gray-700 mb-1.5">
            Category <span className="text-red-500">*</span>
          </label>
          <select
            value={values.category}
            onChange={(e) => set("category", e.target.value)}
            className="w-full px-4 py-2.5 rounded-lg border border-gray-300 bg-white text-sm focus:outline-none focus:ring-2 focus:ring-sage-navy/30"
          >
            <option value="">— Choose —</option>
            {CATEGORY_OPTIONS.map((c) => <option key={c} value={c}>{c}</option>)}
          </select>
        </div>
        <div>
          <label className="block text-sm font-medium text-gray-700 mb-1.5">
            Level <span className="text-red-500">*</span>
          </label>
          <select
            value={values.level}
            onChange={(e) => set("level", e.target.value as CourseFormValues["level"])}
            className="w-full px-4 py-2.5 rounded-lg border border-gray-300 bg-white text-sm focus:outline-none focus:ring-2 focus:ring-sage-navy/30"
          >
            {LEVELS.map((l) => (
              <option key={l} value={l}>{l.charAt(0) + l.slice(1).toLowerCase()}</option>
            ))}
          </select>
        </div>
      </div>

      <div>
        <label className="block text-sm font-medium text-gray-700 mb-1.5">
          Price (₹) <span className="text-red-500">*</span>
        </label>
        <input
          type="number"
          min={MIN_PRICE}
          step="1"
          value={values.price}
          onChange={(e) => set("price", e.target.value)}
          placeholder="3499"
          className="w-full px-4 py-2.5 rounded-lg border border-gray-300 text-sm focus:outline-none focus:ring-2 focus:ring-sage-navy/30"
        />
        <p className="mt-1 text-[11px] text-gray-400">Minimum: ₹{MIN_PRICE}</p>
      </div>

      <div>
        <label className="block text-sm font-medium text-gray-700 mb-1.5">Thumbnail image</label>
        {values.thumbnailUrl ? (
          <div className="flex items-start gap-3">
            <div className="relative w-40 h-24 rounded-lg border border-gray-200 overflow-hidden bg-gray-50">
              <Image
                src={values.thumbnailUrl}
                alt="Thumbnail preview"
                fill
                className="object-cover"
                unoptimized
              />
            </div>
            <div className="flex-1">
              <p className="text-xs text-gray-500 mb-2 break-all">{values.thumbnailUrl}</p>
              <div className="flex items-center gap-2">
                {courseId && (
                  <button
                    type="button"
                    onClick={() => fileInputRef.current?.click()}
                    disabled={uploading}
                    className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium text-gray-700 bg-gray-100 hover:bg-gray-200 disabled:opacity-50 transition cursor-pointer"
                  >
                    {uploading ? <Loader2 size={12} className="animate-spin" /> : <Upload size={12} />}
                    Replace
                  </button>
                )}
                <button
                  type="button"
                  onClick={() => set("thumbnailUrl", "")}
                  className="inline-flex items-center gap-1 px-3 py-1.5 rounded-lg text-xs font-medium text-red-700 bg-red-50 hover:bg-red-100 transition cursor-pointer"
                >
                  <X size={12} /> Remove
                </button>
              </div>
            </div>
          </div>
        ) : courseId ? (
          <button
            type="button"
            onClick={() => fileInputRef.current?.click()}
            disabled={uploading}
            className="w-full flex flex-col items-center justify-center gap-1.5 px-4 py-6 rounded-lg border-2 border-dashed border-gray-300 hover:border-sage-navy/50 hover:bg-sage-navy/5 disabled:opacity-50 transition cursor-pointer"
          >
            {uploading ? <Loader2 size={20} className="animate-spin text-sage-navy" /> : <Upload size={20} className="text-gray-400" />}
            <span className="text-sm font-medium text-gray-700">
              {uploading ? "Uploading…" : "Upload image"}
            </span>
            <span className="text-[11px] text-gray-400">Recommended 1280×720, JPG or PNG, max 5MB</span>
          </button>
        ) : (
          <input
            type="url"
            value={values.thumbnailUrl}
            onChange={(e) => set("thumbnailUrl", e.target.value)}
            placeholder="https://… (or upload after creating the course)"
            className="w-full px-4 py-2.5 rounded-lg border border-gray-300 text-sm focus:outline-none focus:ring-2 focus:ring-sage-navy/30"
          />
        )}
        <input
          ref={fileInputRef}
          type="file"
          accept="image/png,image/jpeg,image/webp"
          onChange={handleFileChange}
          className="hidden"
        />
      </div>

      <div>
        <label className="block text-sm font-medium text-gray-700 mb-1.5">
          Tags <span className="text-gray-400 font-normal">(comma separated)</span>
        </label>
        <input
          type="text"
          value={values.tags}
          onChange={(e) => set("tags", e.target.value)}
          placeholder="react, javascript, frontend"
          className="w-full px-4 py-2.5 rounded-lg border border-gray-300 text-sm focus:outline-none focus:ring-2 focus:ring-sage-navy/30"
        />
      </div>

      {error && (
        <div className="bg-red-50 text-red-700 px-3 py-2 rounded-lg text-sm">{error}</div>
      )}

      <div className="flex items-center justify-end gap-2 pt-2">
        {cancelHref && (
          <a
            href={cancelHref}
            className="px-4 py-2.5 rounded-lg text-sm font-medium text-gray-600 border border-gray-300 hover:bg-gray-50 transition"
          >
            Cancel
          </a>
        )}
        <button
          type="submit"
          disabled={submitting}
          className="inline-flex items-center gap-1.5 px-5 py-2.5 rounded-lg text-sm font-semibold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-50 transition cursor-pointer"
        >
          {submitting && <Loader2 size={14} className="animate-spin" />}
          {submitLabel}
        </button>
      </div>
    </form>
  );
}
