"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { AlertCircle, BookOpen, CheckCircle2, Loader2 } from "lucide-react";

import {
  enroll as enrollInCourse,
  getCourses,
  getEnrollments,
} from "@/lib/api";

/**
 * Participant-facing My Courses surface. Two modes:
 *
 *   enrolled  -- the participant's enrolled course cards with
 *                per-course progress + "Continue Learning" CTA
 *   browse    -- catalog browser (search + 1-click enroll) so the
 *                user can pick up more courses without leaving
 *                the dashboard.
 *
 * Profile-complete gating is handled by the dashboard router
 * (GATED_TABS + LockedTabView). This component assumes a 100%
 * profile and skips its own locked branch.
 */

interface EnrollmentRow {
  id?: number | string;
  courseId?: number;
  courseTitle?: string;
  title?: string;
  thumbnailUrl?: string | null;
  progress?: number;
  progressPercent?: number;
  enrolledAt?: string | null;
  completed?: boolean;
}

interface CourseRow {
  id: number;
  title?: string;
  shortDescription?: string | null;
  thumbnailUrl?: string | null;
  level?: string | null;
  category?: string | null;
  price?: number | null;
  isFree?: boolean;
  enrolledCount?: number;
  rating?: number;
}

export default function MyCoursesTab() {
  const [mode, setMode] = useState<"enrolled" | "browse">("enrolled");
  const [enrolledRows, setEnrolledRows] = useState<EnrollmentRow[]>([]);
  const [enrolledLoading, setEnrolledLoading] = useState(true);
  // Note: the backend's legacy AGREEMENT_REQUIRED gate can also block
  // /api/enrollments for grandfathered users. Translate those codes
  // to a friendly message rather than leaking them into the UI.
  const [error, setError] = useState("");

  const refreshEnrolled = async () => {
    setEnrolledLoading(true);
    try {
      const r = await getEnrollments();
      setEnrolledRows((r ?? []) as EnrollmentRow[]);
    } catch (e) {
      const raw = e instanceof Error ? e.message : "";
      if (raw === "AGREEMENT_REQUIRED" || raw === "PROFILE_INCOMPLETE") {
        // The dashboard's gate handles the messaging upstream; don't
        // double up with a red banner here.
        setError("");
      } else {
        setError("We couldn't load your courses. Please try again in a moment.");
      }
    } finally {
      setEnrolledLoading(false);
    }
  };

  useEffect(() => {
    refreshEnrolled();
  }, []);

  if (mode === "browse") {
    return (
      <BrowseCoursesView
        enrolledIds={
          new Set(
            enrolledRows
              .map((r) => r.courseId ?? (typeof r.id === "number" ? r.id : undefined))
              .filter((id): id is number => typeof id === "number"),
          )
        }
        onBack={() => setMode("enrolled")}
        onEnrolled={refreshEnrolled}
      />
    );
  }

  if (enrolledLoading) {
    return (
      <div className="text-center py-10">
        <Loader2 size={20} className="animate-spin text-sage-navy inline" />
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold text-gray-900">My courses</h1>
      <p className="text-sm text-gray-500">
        Self-paced technical development modules. These complement your
        Phase-1 weekly coaching plan.
      </p>
      {error && (
        <p className="inline-flex items-center gap-1.5 text-sm text-red-700">
          <AlertCircle size={14} /> {error}
        </p>
      )}

      {enrolledRows.length === 0 ? (
        <div className="rounded-2xl border border-dashed border-gray-200 bg-gray-50/60 p-8 text-center">
          <BookOpen size={28} className="text-gray-400 inline-block mb-2" />
          <p className="text-sm text-gray-600">
            You haven&apos;t enrolled in any courses yet.
          </p>
          <p className="text-xs text-gray-500 mt-1">
            Browse our catalog to get started.
          </p>
          <button
            type="button"
            onClick={() => setMode("browse")}
            className="mt-4 inline-flex items-center gap-1.5 px-4 py-2 rounded-md text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep cursor-pointer"
          >
            Browse Courses →
          </button>
        </div>
      ) : (
        <>
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
            {enrolledRows.map((r) => {
              const cid =
                r.courseId ?? (typeof r.id === "number" ? r.id : 0);
              const pct =
                typeof r.progressPercent === "number"
                  ? r.progressPercent
                  : typeof r.progress === "number"
                    ? r.progress
                    : 0;
              return (
                <div
                  key={String(r.id ?? cid)}
                  className="flex flex-col rounded-2xl border border-gray-100 bg-white p-4 hover:shadow-md transition"
                >
                  <div className="aspect-video rounded-lg bg-gray-100 mb-3 overflow-hidden flex items-center justify-center">
                    {r.thumbnailUrl ? (
                      // eslint-disable-next-line @next/next/no-img-element
                      <img
                        src={r.thumbnailUrl}
                        alt={r.courseTitle ?? r.title ?? ""}
                        className="w-full h-full object-cover"
                      />
                    ) : (
                      <BookOpen size={24} className="text-gray-400" />
                    )}
                  </div>
                  <p className="text-sm font-semibold text-gray-900 line-clamp-2 mb-2">
                    {r.courseTitle ?? r.title ?? "Untitled course"}
                  </p>
                  <div className="mt-auto">
                    <div className="h-1.5 w-full rounded-full bg-gray-100 overflow-hidden">
                      <div
                        className="h-full bg-sage-navy"
                        style={{
                          width: `${Math.max(0, Math.min(100, pct))}%`,
                        }}
                      />
                    </div>
                    <p className="text-[10px] text-gray-500 mt-1 mb-2">
                      {pct}% complete{r.completed ? " · ✅" : ""}
                    </p>
                    <Link
                      href={`/courses/${cid}`}
                      target="_blank"
                      rel="noopener"
                      className="inline-flex items-center justify-center gap-1.5 w-full px-3 py-1.5 rounded-md text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep cursor-pointer"
                    >
                      {pct > 0 ? "Continue Learning" : "Start Course"} →
                    </Link>
                  </div>
                </div>
              );
            })}
          </div>
          <div className="pt-2">
            <button
              type="button"
              onClick={() => setMode("browse")}
              className="inline-flex items-center gap-1.5 px-4 py-2 rounded-lg text-xs font-bold bg-white border border-gray-200 text-sage-navy hover:border-sage-navy cursor-pointer"
            >
              Browse More Courses →
            </button>
          </div>
        </>
      )}
    </div>
  );
}

function BrowseCoursesView({
  enrolledIds,
  onBack,
  onEnrolled,
}: {
  enrolledIds: Set<number>;
  onBack: () => void;
  onEnrolled: () => Promise<void>;
}) {
  const [courses, setCourses] = useState<CourseRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [search, setSearch] = useState("");
  const [enrolling, setEnrolling] = useState<number | null>(null);

  const load = async (q?: string) => {
    setLoading(true);
    setError("");
    try {
      const r = await getCourses(q ? { search: q } : undefined);
      setCourses((r ?? []) as CourseRow[]);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't load courses");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, []);

  const handleEnroll = async (id: number) => {
    setEnrolling(id);
    setError("");
    try {
      await enrollInCourse(id);
      await onEnrolled();
      // Stay in browse mode so the user can keep enrolling; the button
      // for this course flips to "Enrolled ✓" on the next render.
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't enroll");
    } finally {
      setEnrolling(null);
    }
  };

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between flex-wrap gap-2">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Browse courses</h1>
          <p className="text-sm text-gray-500 mt-0.5">
            Enroll in self-paced modules without leaving your dashboard.
          </p>
        </div>
        <button
          type="button"
          onClick={onBack}
          className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-bold bg-white border border-gray-200 text-gray-700 hover:border-sage-navy hover:text-sage-navy cursor-pointer"
        >
          ← Back to My Courses
        </button>
      </div>

      <div className="flex items-center gap-2 flex-wrap">
        <input
          type="search"
          placeholder="Search courses…"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter") load(search.trim() || undefined);
          }}
          className="flex-1 min-w-[180px] px-3 py-2 text-sm rounded-lg border border-gray-200 focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy"
        />
        <button
          type="button"
          onClick={() => load(search.trim() || undefined)}
          className="px-3 py-2 rounded-lg text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep cursor-pointer"
        >
          Search
        </button>
      </div>

      {error && (
        <p className="inline-flex items-center gap-1.5 text-sm text-red-700">
          <AlertCircle size={14} /> {error}
        </p>
      )}

      {loading ? (
        <div className="text-center py-10">
          <Loader2 size={20} className="animate-spin text-sage-navy inline" />
        </div>
      ) : courses.length === 0 ? (
        <div className="rounded-2xl border border-dashed border-gray-200 bg-gray-50/60 p-8 text-center">
          <p className="text-sm text-gray-600">No courses match your search.</p>
        </div>
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
          {courses.map((c) => {
            const enrolled = enrolledIds.has(c.id);
            return (
              <div
                key={c.id}
                className="flex flex-col rounded-2xl border border-gray-100 bg-white p-4 hover:shadow-md transition"
              >
                <div className="aspect-video rounded-lg bg-gray-100 mb-3 overflow-hidden flex items-center justify-center">
                  {c.thumbnailUrl ? (
                    // eslint-disable-next-line @next/next/no-img-element
                    <img
                      src={c.thumbnailUrl}
                      alt={c.title ?? ""}
                      className="w-full h-full object-cover"
                    />
                  ) : (
                    <BookOpen size={24} className="text-gray-400" />
                  )}
                </div>
                <p className="text-sm font-semibold text-gray-900 line-clamp-2">
                  {c.title ?? "Untitled course"}
                </p>
                {c.shortDescription && (
                  <p className="text-xs text-gray-500 mt-1 line-clamp-2">
                    {c.shortDescription}
                  </p>
                )}
                <div className="mt-auto pt-3 flex items-center gap-2">
                  {c.level && (
                    <span className="px-2 py-0.5 rounded-full text-[10px] font-bold bg-gray-100 text-gray-700">
                      {c.level}
                    </span>
                  )}
                  {c.isFree ? (
                    <span className="px-2 py-0.5 rounded-full text-[10px] font-bold bg-emerald-50 text-emerald-700">
                      Free
                    </span>
                  ) : (
                    c.price != null && (
                      <span className="text-xs font-semibold text-gray-700">
                        ₹{Number(c.price).toLocaleString()}
                      </span>
                    )
                  )}
                </div>
                <div className="mt-2">
                  {enrolled ? (
                    <button
                      type="button"
                      disabled
                      className="inline-flex items-center justify-center gap-1.5 w-full px-3 py-1.5 rounded-md text-xs font-bold bg-emerald-50 text-emerald-700 cursor-default"
                    >
                      <CheckCircle2 size={12} /> Enrolled
                    </button>
                  ) : (
                    <button
                      type="button"
                      onClick={() => handleEnroll(c.id)}
                      disabled={enrolling === c.id}
                      className="inline-flex items-center justify-center gap-1.5 w-full px-3 py-1.5 rounded-md text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-60 cursor-pointer"
                    >
                      {enrolling === c.id ? (
                        <Loader2 size={12} className="animate-spin" />
                      ) : null}
                      {enrolling === c.id ? "Enrolling…" : "Enroll →"}
                    </button>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
