"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { motion, AnimatePresence } from "framer-motion";
import {
  ChevronLeft, ChevronDown, Loader2, Plus, Edit3, Trash2, AlertCircle,
  Globe, GlobeLock, CheckCircle2, AlertTriangle, Upload, Play, X, Save,
  ArrowUp, ArrowDown, Brain, FolderUp,
} from "lucide-react";
import {
  getCourse, getCourseModules, getCourseLessons,
  createModule, updateModule, deleteModule,
  createLesson, updateLesson, deleteLesson, reorderLessons,
  publishCourse, unpublishCourse, getPublishReadiness,
  listInstructorQuizzes, deleteInstructorQuiz, type Quiz as QuizType,
} from "@/lib/api";
import { LessonVideoUploader } from "@/components/instructor/LessonVideoUploader";
import { AddQuizModal } from "@/components/instructor/AddQuizModal";
import { BulkUploadModal } from "@/components/instructor/BulkUploadModal";
import { useAuth } from "@/lib/auth-context";
import { useToast } from "@/components/ui/Toast";
import { cn } from "@/lib/utils";
import type { Module as ModuleType } from "@/lib/types";

interface CourseDetail {
  id: number;
  title: string;
  isPublished: boolean;
  category: string | null;
  level: string;
  price: number;
}

interface LessonRow {
  id: number;
  courseId: number;
  moduleId: number | null;
  title: string;
  description: string | null;
  videoUrl: string | null;
  orderIndex: number;
  durationMinutes: number | null;
  isFree: boolean;
}

export default function ContentManagerPage({ params }: { params: { id: string } }) {
  const courseId = Number(params.id);
  const router = useRouter();
  const { toast } = useToast();
  const { user, isLoading: authLoading } = useAuth();
  const role = user?.role?.toUpperCase();

  const [course, setCourse] = useState<CourseDetail | null>(null);
  const [modules, setModules] = useState<ModuleType[]>([]);
  const [lessons, setLessons] = useState<LessonRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  // Per-module collapse state. First module starts open by default.
  const [expanded, setExpanded] = useState<Record<number, boolean>>({});

  // Inline forms
  const [showAddModule, setShowAddModule] = useState(false);
  const [moduleDraft, setModuleDraft] = useState({ title: "", description: "" });
  const [editingModuleId, setEditingModuleId] = useState<number | null>(null);
  const [addLessonForModule, setAddLessonForModule] = useState<number | null>(null);
  const [lessonDraft, setLessonDraft] = useState({
    title: "",
    description: "",
    durationMinutes: "",
    isFree: false,
  });

  // Edit-lesson modal
  const [editingLessonId, setEditingLessonId] = useState<number | null>(null);
  const [editLessonForm, setEditLessonForm] = useState({
    title: "",
    description: "",
    durationMinutes: "",
    isFree: false,
  });

  // Video uploader modal
  const [videoUploaderFor, setVideoUploaderFor] = useState<LessonRow | null>(null);

  // Quizzes for this course — loaded once and refreshed after each
  // create/delete from the modal.
  const [quizzes, setQuizzes] = useState<QuizType[]>([]);
  const [showAddQuiz, setShowAddQuiz] = useState(false);

  // Bulk-upload modal — opens an isolated workflow that may take
  // minutes; doesn't share state with the inline forms.
  const [showBulkUpload, setShowBulkUpload] = useState(false);

  // Publish-readiness panel
  const [publishMissing, setPublishMissing] = useState<string[] | null>(null);
  const [showPublishPanel, setShowPublishPanel] = useState(false);

  const [busy, setBusy] = useState(false);

  // Auth guard
  useEffect(() => {
    if (authLoading) return;
    if (!user) { router.push(`/login?redirect=/instructor/courses/${courseId}/content`); return; }
    if (role !== "INSTRUCTOR" && role !== "ADMIN" && role !== "SYSTEM_ADMIN") { router.push("/dashboard"); return; }
  }, [user, authLoading, role, courseId, router]);

  const fetchAll = async () => {
    setLoading(true);
    setError("");
    try {
      const [c, mods, less] = await Promise.all([
        getCourse(String(courseId)) as Promise<CourseDetail>,
        getCourseModules(courseId),
        getCourseLessons(courseId) as Promise<LessonRow[]>,
      ]);
      setCourse(c);
      const sortedMods = [...(mods ?? [])].sort((a, b) => a.orderIndex - b.orderIndex);
      setModules(sortedMods);
      setLessons(less ?? []);
      // Quizzes load alongside content but are independent — failures
      // don't block the rest of the page.
      try {
        const qs = await listInstructorQuizzes(courseId);
        setQuizzes(qs ?? []);
      } catch {
        setQuizzes([]);
      }
      // Auto-expand the first module on first load only.
      setExpanded((prev) => {
        if (Object.keys(prev).length > 0) return prev;
        return sortedMods.length > 0 ? { [sortedMods[0].id]: true } : {};
      });
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to load course content");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (!user || (role !== "INSTRUCTOR" && role !== "ADMIN" && role !== "SYSTEM_ADMIN")) return;
    fetchAll();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [user, courseId]);

  // ── Helpers ─────────────────────────────────────────────────────

  const lessonsForModule = (moduleId: number) =>
    lessons
      .filter((l) => l.moduleId === moduleId)
      .sort((a, b) => a.orderIndex - b.orderIndex);

  const moduleDuration = (moduleId: number) =>
    lessonsForModule(moduleId).reduce((s, l) => s + (l.durationMinutes ?? 0), 0);

  const orphanLessons = lessons.filter((l) => l.moduleId == null);

  // ── Module CRUD ─────────────────────────────────────────────────

  const submitNewModule = async () => {
    if (!moduleDraft.title.trim()) { toast("error", "Module title is required."); return; }
    setBusy(true);
    try {
      await createModule(courseId, {
        title: moduleDraft.title.trim(),
        description: moduleDraft.description.trim() || undefined,
      });
      setModuleDraft({ title: "", description: "" });
      setShowAddModule(false);
      await fetchAll();
      toast("success", "Module added.");
    } catch (err) {
      toast("error", err instanceof Error ? err.message : "Couldn't add module");
    } finally {
      setBusy(false);
    }
  };

  const beginEditModule = (m: ModuleType) => {
    setEditingModuleId(m.id);
    setModuleDraft({ title: m.title, description: m.description ?? "" });
  };

  const submitEditModule = async () => {
    if (editingModuleId == null) return;
    setBusy(true);
    try {
      await updateModule(editingModuleId, {
        title: moduleDraft.title.trim(),
        description: moduleDraft.description.trim() || undefined,
      });
      setEditingModuleId(null);
      setModuleDraft({ title: "", description: "" });
      await fetchAll();
      toast("success", "Module updated.");
    } catch (err) {
      toast("error", err instanceof Error ? err.message : "Couldn't update module");
    } finally {
      setBusy(false);
    }
  };

  const handleDeleteModule = async (id: number) => {
    if (!confirm("Delete this module? Lessons inside will become unassigned (they'll appear under \"Other Lessons\").")) return;
    setBusy(true);
    try {
      await deleteModule(id);
      await fetchAll();
      toast("success", "Module deleted.");
    } catch (err) {
      toast("error", err instanceof Error ? err.message : "Couldn't delete module");
    } finally {
      setBusy(false);
    }
  };

  // ── Lesson CRUD ─────────────────────────────────────────────────

  const submitNewLesson = async (moduleId: number) => {
    if (!lessonDraft.title.trim()) { toast("error", "Lesson title is required."); return; }
    setBusy(true);
    try {
      await createLesson(courseId, {
        title: lessonDraft.title.trim(),
        description: lessonDraft.description.trim() || undefined,
        durationMinutes: lessonDraft.durationMinutes
          ? Math.max(0, parseInt(lessonDraft.durationMinutes, 10))
          : undefined,
        isFree: lessonDraft.isFree,
        moduleId,
      });
      setLessonDraft({ title: "", description: "", durationMinutes: "", isFree: false });
      setAddLessonForModule(null);
      await fetchAll();
      toast("success", "Lesson added.");
    } catch (err) {
      toast("error", err instanceof Error ? err.message : "Couldn't add lesson");
    } finally {
      setBusy(false);
    }
  };

  const beginEditLesson = (l: LessonRow) => {
    setEditingLessonId(l.id);
    setEditLessonForm({
      title: l.title,
      description: l.description ?? "",
      durationMinutes: l.durationMinutes != null ? String(l.durationMinutes) : "",
      isFree: l.isFree,
    });
  };

  const submitEditLesson = async () => {
    if (editingLessonId == null) return;
    setBusy(true);
    try {
      await updateLesson(editingLessonId, {
        title: editLessonForm.title.trim(),
        description: editLessonForm.description.trim() || null,
        durationMinutes: editLessonForm.durationMinutes
          ? Math.max(0, parseInt(editLessonForm.durationMinutes, 10))
          : null,
        isFree: editLessonForm.isFree,
      });
      setEditingLessonId(null);
      await fetchAll();
      toast("success", "Lesson updated.");
    } catch (err) {
      toast("error", err instanceof Error ? err.message : "Couldn't update lesson");
    } finally {
      setBusy(false);
    }
  };

  const handleDeleteLesson = async (id: number) => {
    if (!confirm("Delete this lesson? This cannot be undone.")) return;
    setBusy(true);
    try {
      await deleteLesson(id);
      await fetchAll();
      toast("success", "Lesson deleted.");
    } catch (err) {
      toast("error", err instanceof Error ? err.message : "Couldn't delete lesson");
    } finally {
      setBusy(false);
    }
  };

  // Move lesson up/down within its module — drag-and-drop is the
  // stretch goal but instructors on touch devices need a fallback,
  // so we ship arrow buttons and skip drag entirely.
  const moveLesson = async (moduleId: number, lessonId: number, direction: -1 | 1) => {
    const ordered = lessonsForModule(moduleId);
    const idx = ordered.findIndex((l) => l.id === lessonId);
    const swapIdx = idx + direction;
    if (idx < 0 || swapIdx < 0 || swapIdx >= ordered.length) return;
    const newOrder = [...ordered];
    [newOrder[idx], newOrder[swapIdx]] = [newOrder[swapIdx], newOrder[idx]];
    setBusy(true);
    try {
      await reorderLessons(newOrder.map((l) => l.id));
      await fetchAll();
    } catch (err) {
      toast("error", err instanceof Error ? err.message : "Reorder failed");
    } finally {
      setBusy(false);
    }
  };

  // ── Publish flow ────────────────────────────────────────────────

  const handlePublishClick = async () => {
    if (!course) return;
    if (course.isPublished) {
      setBusy(true);
      try {
        await unpublishCourse(course.id);
        await fetchAll();
        toast("success", "Course unpublished.");
      } catch (err) {
        toast("error", err instanceof Error ? err.message : "Unpublish failed");
      } finally {
        setBusy(false);
      }
      return;
    }
    // Pre-flight check before flipping the publish bit so we can show
    // a friendly summary panel instead of just a toast on failure.
    setBusy(true);
    try {
      const r = await getPublishReadiness(course.id);
      setPublishMissing(r.missing);
      setShowPublishPanel(true);
      if (r.ready) {
        await publishCourse(course.id);
        await fetchAll();
        toast("success", "Course published.");
      }
    } catch (err) {
      toast("error", err instanceof Error ? err.message : "Couldn't check readiness");
    } finally {
      setBusy(false);
    }
  };

  // ── Render guards ───────────────────────────────────────────────

  if (authLoading || loading) {
    return (
      <section className="mx-auto max-w-4xl px-6 pt-32 pb-20 flex items-center justify-center">
        <Loader2 size={28} className="animate-spin text-sage-navy" />
      </section>
    );
  }

  if (error || !course) {
    return (
      <section className="mx-auto max-w-md px-6 pt-32 pb-20 text-center">
        <AlertCircle size={36} className="text-red-400 mx-auto mb-3" />
        <p className="text-gray-700 mb-4">{error || "Course not found"}</p>
        <Link href="/instructor" className="text-sage-navy underline text-sm">
          Back to my courses
        </Link>
      </section>
    );
  }

  const totalLessons = lessons.length;

  return (
    <section className="mx-auto max-w-4xl px-6 pt-28 pb-20 min-h-screen">
      <Link
        href="/instructor"
        className="inline-flex items-center gap-1 text-sm text-gray-500 hover:text-sage-navy mb-4"
      >
        <ChevronLeft size={16} /> Back to my courses
      </Link>

      <div className="flex flex-col sm:flex-row sm:items-end sm:justify-between gap-3 mb-2">
        <div>
          <h1 className="text-3xl font-bold text-gray-900">{course.title}</h1>
          <p className="text-xs text-gray-500 mt-1">Content Manager</p>
        </div>
        <button
          onClick={handlePublishClick}
          disabled={busy}
          className={cn(
            "inline-flex items-center justify-center gap-1.5 px-4 py-2.5 rounded-lg text-sm font-semibold transition cursor-pointer disabled:opacity-50",
            course.isPublished
              ? "text-amber-700 bg-amber-50 hover:bg-amber-100"
              : "bg-sage-navy text-white hover:bg-sage-navy-deep"
          )}
        >
          {busy ? <Loader2 size={14} className="animate-spin" /> :
            course.isPublished ? <GlobeLock size={14} /> : <Globe size={14} />}
          {course.isPublished ? "Unpublish" : "Publish"}
        </button>
      </div>

      <div className="flex items-center gap-2 mb-6 flex-wrap text-xs">
        <span className={cn(
          "font-semibold uppercase tracking-wide px-2 py-0.5 rounded-full",
          course.isPublished ? "bg-emerald-100 text-emerald-700" : "bg-amber-100 text-amber-700"
        )}>
          {course.isPublished ? "Published" : "Draft"}
        </span>
        <span className="text-gray-500">
          {modules.length} module{modules.length === 1 ? "" : "s"} · {totalLessons} lesson{totalLessons === 1 ? "" : "s"}
        </span>
      </div>

      {/* Publish panel — shown after a publish attempt */}
      {showPublishPanel && publishMissing && (
        <div className={cn(
          "mb-6 rounded-xl border p-4 text-sm",
          publishMissing.length === 0
            ? "bg-emerald-50 border-emerald-200 text-emerald-800"
            : "bg-amber-50 border-amber-200 text-amber-800"
        )}>
          <div className="flex items-start gap-2">
            {publishMissing.length === 0
              ? <CheckCircle2 size={16} className="mt-0.5 shrink-0" />
              : <AlertTriangle size={16} className="mt-0.5 shrink-0" />
            }
            <div className="flex-1">
              <p className="font-semibold mb-1">
                {publishMissing.length === 0 ? "Course published!" : "Cannot publish yet:"}
              </p>
              {publishMissing.length > 0 && (
                <ul className="list-disc list-inside space-y-0.5">
                  {publishMissing.map((m, i) => <li key={i}>{m}</li>)}
                </ul>
              )}
            </div>
            <button
              onClick={() => setShowPublishPanel(false)}
              className="text-current opacity-60 hover:opacity-100 cursor-pointer"
            >
              <X size={14} />
            </button>
          </div>
        </div>
      )}

      {/* Add module CTA + bulk upload entry. Bulk upload is a separate
          workflow — single-lesson upload still lives on each lesson row. */}
      <div className="mb-4 flex flex-wrap items-center gap-2">
        {!showAddModule ? (
          <>
            <button
              onClick={() => { setShowAddModule(true); setEditingModuleId(null); setModuleDraft({ title: "", description: "" }); }}
              className="inline-flex items-center gap-1.5 px-4 py-2 rounded-lg text-sm font-semibold bg-sage-navy text-white hover:bg-sage-navy-deep transition cursor-pointer"
            >
              <Plus size={14} /> Add Module
            </button>
            <button
              onClick={() => setShowBulkUpload(true)}
              className="inline-flex items-center gap-1.5 px-4 py-2 rounded-lg text-sm font-semibold border border-sage-navy text-sage-navy hover:bg-sage-navy/5 transition cursor-pointer"
            >
              <FolderUp size={14} /> Bulk Upload Videos
            </button>
          </>
        ) : (
          <ModuleForm
            draft={moduleDraft}
            setDraft={setModuleDraft}
            busy={busy}
            submitLabel="Add Module"
            onSubmit={submitNewModule}
            onCancel={() => { setShowAddModule(false); setModuleDraft({ title: "", description: "" }); }}
          />
        )}
      </div>

      {/* Module list */}
      <div className="space-y-4">
        {modules.map((m, mIdx) => {
          const isOpen = !!expanded[m.id];
          const isEditingThisModule = editingModuleId === m.id;
          const moduleLessons = lessonsForModule(m.id);
          return (
            <motion.div
              key={m.id}
              layout
              initial={{ opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ delay: mIdx * 0.03 }}
              className="bg-white rounded-2xl border border-gray-100 shadow-sm overflow-hidden"
            >
              {/* Module header (or inline edit) */}
              {isEditingThisModule ? (
                <div className="p-4 border-b border-gray-100">
                  <ModuleForm
                    draft={moduleDraft}
                    setDraft={setModuleDraft}
                    busy={busy}
                    submitLabel="Save"
                    onSubmit={submitEditModule}
                    onCancel={() => { setEditingModuleId(null); setModuleDraft({ title: "", description: "" }); }}
                  />
                </div>
              ) : (
                <div className="flex items-center gap-3 px-4 py-3 hover:bg-gray-50/60 transition">
                  <button
                    onClick={() => setExpanded((p) => ({ ...p, [m.id]: !isOpen }))}
                    className="flex-1 flex items-center gap-3 text-left cursor-pointer min-w-0"
                  >
                    <ChevronDown
                      size={16}
                      className={cn("text-gray-400 shrink-0 transition", isOpen ? "rotate-0" : "-rotate-90")}
                    />
                    <div className="flex-1 min-w-0">
                      <p className="font-semibold text-gray-900 truncate">
                        Module {mIdx + 1}: {m.title}
                      </p>
                      <p className="text-xs text-gray-500">
                        {moduleLessons.length} lesson{moduleLessons.length === 1 ? "" : "s"}
                        {moduleDuration(m.id) > 0 && ` · ${moduleDuration(m.id)} min`}
                      </p>
                    </div>
                  </button>
                  <button
                    onClick={() => beginEditModule(m)}
                    className="p-2 text-gray-400 hover:text-gray-700 transition cursor-pointer"
                    aria-label="Edit module"
                  >
                    <Edit3 size={14} />
                  </button>
                  <button
                    onClick={() => handleDeleteModule(m.id)}
                    className="p-2 text-gray-400 hover:text-red-500 transition cursor-pointer"
                    aria-label="Delete module"
                  >
                    <Trash2 size={14} />
                  </button>
                </div>
              )}

              {/* Lessons */}
              <AnimatePresence initial={false}>
                {isOpen && !isEditingThisModule && (
                  <motion.div
                    initial={{ height: 0, opacity: 0 }}
                    animate={{ height: "auto", opacity: 1 }}
                    exit={{ height: 0, opacity: 0 }}
                    transition={{ duration: 0.2 }}
                    className="overflow-hidden"
                  >
                    <div className="px-4 pb-4 pt-1 space-y-2">
                      {moduleLessons.length === 0 ? (
                        <p className="text-xs text-gray-400 italic px-1 py-2">No lessons yet.</p>
                      ) : (
                        moduleLessons.map((l, lIdx) => (
                          <LessonCard
                            key={l.id}
                            lesson={l}
                            index={lIdx}
                            isFirst={lIdx === 0}
                            isLast={lIdx === moduleLessons.length - 1}
                            busy={busy}
                            onMoveUp={() => moveLesson(m.id, l.id, -1)}
                            onMoveDown={() => moveLesson(m.id, l.id, 1)}
                            onEdit={() => beginEditLesson(l)}
                            onDelete={() => handleDeleteLesson(l.id)}
                            onUploadVideo={() => setVideoUploaderFor(l)}
                          />
                        ))
                      )}

                      {/* Add lesson inline */}
                      {addLessonForModule === m.id ? (
                        <LessonForm
                          draft={lessonDraft}
                          setDraft={setLessonDraft}
                          busy={busy}
                          submitLabel="Add Lesson"
                          onSubmit={() => submitNewLesson(m.id)}
                          onCancel={() => {
                            setAddLessonForModule(null);
                            setLessonDraft({ title: "", description: "", durationMinutes: "", isFree: false });
                          }}
                        />
                      ) : (
                        <button
                          onClick={() => {
                            setAddLessonForModule(m.id);
                            setLessonDraft({ title: "", description: "", durationMinutes: "", isFree: false });
                          }}
                          className="w-full inline-flex items-center justify-center gap-1.5 px-3 py-2 rounded-lg text-xs font-medium text-sage-navy border border-dashed border-sage-navy/40 hover:bg-sage-navy/5 transition cursor-pointer"
                        >
                          <Plus size={12} /> Add Lesson to this module
                        </button>
                      )}
                    </div>
                  </motion.div>
                )}
              </AnimatePresence>
            </motion.div>
          );
        })}

        {/* Orphan lessons — lessons created without a module assignment.
            Surfaced so instructors can still edit them (or we'd be hiding
            data). They're not eligible for publish. */}
        {orphanLessons.length > 0 && (
          <div className="bg-white rounded-2xl border border-amber-200 shadow-sm p-4">
            <div className="flex items-center gap-2 mb-3">
              <AlertTriangle size={14} className="text-amber-600" />
              <p className="font-semibold text-amber-800 text-sm">
                Other Lessons (not in any module)
              </p>
            </div>
            <p className="text-xs text-amber-700 mb-3">
              These lessons aren't attached to a module. Edit them to assign a module, or delete.
            </p>
            <div className="space-y-2">
              {orphanLessons.map((l, lIdx) => (
                <LessonCard
                  key={l.id}
                  lesson={l}
                  index={lIdx}
                  isFirst
                  isLast
                  busy={busy}
                  onMoveUp={() => {}}
                  onMoveDown={() => {}}
                  onEdit={() => beginEditLesson(l)}
                  onDelete={() => handleDeleteLesson(l.id)}
                  onUploadVideo={() => setVideoUploaderFor(l)}
                />
              ))}
            </div>
          </div>
        )}

        {modules.length === 0 && orphanLessons.length === 0 && (
          <div className="text-center py-12 bg-white rounded-2xl border border-gray-100">
            <p className="text-sm text-gray-500 mb-3">No modules yet — start by adding one.</p>
            <button
              onClick={() => setShowAddModule(true)}
              className="inline-flex items-center gap-1.5 px-4 py-2 rounded-lg text-sm font-semibold bg-sage-navy text-white hover:bg-sage-navy-deep transition cursor-pointer"
            >
              <Plus size={14} /> Add your first module
            </button>
          </div>
        )}
      </div>

      {/* ── Quizzes section ─────────────────────────────────────── */}
      <div className="mt-10">
        <div className="flex items-center justify-between mb-4">
          <div>
            <h2 className="text-xl font-bold text-gray-900 flex items-center gap-2">
              <Brain size={18} className="text-sage-navy" /> Quizzes
            </h2>
            <p className="text-xs text-gray-500 mt-0.5">
              Attach quizzes to lessons, modules, or the course as a final assessment.
            </p>
          </div>
          <button
            onClick={() => setShowAddQuiz(true)}
            className="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg text-xs font-semibold bg-sage-navy text-white hover:bg-sage-navy-deep transition cursor-pointer"
          >
            <Plus size={12} /> Add Quiz
          </button>
        </div>

        {quizzes.length === 0 ? (
          <div className="bg-white rounded-2xl border border-gray-100 p-6 text-center">
            <p className="text-sm text-gray-500">No quizzes yet.</p>
          </div>
        ) : (
          <div className="space-y-3">
            {quizzes.map((q) => {
              const attachLabel = q.lessonId
                ? `Lesson · ${q.lessonTitle ?? "—"}`
                : q.moduleId
                  ? `Module · ${q.moduleTitle ?? "—"}`
                  : "Course final";
              return (
                <div
                  key={q.id}
                  className="bg-white rounded-xl border border-gray-100 shadow-sm p-4"
                >
                  <div className="flex items-start gap-3">
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-2 flex-wrap mb-0.5">
                        <p className="font-semibold text-gray-900 text-sm truncate">{q.title}</p>
                        <span className="text-[10px] font-semibold uppercase tracking-wide px-2 py-0.5 rounded-full bg-sage-navy/10 text-sage-navy">
                          {attachLabel}
                        </span>
                        {!q.isActive && (
                          <span className="text-[10px] font-semibold uppercase tracking-wide px-2 py-0.5 rounded-full bg-gray-100 text-gray-500">
                            Inactive
                          </span>
                        )}
                      </div>
                      <p className="text-xs text-gray-500">
                        {q.questionCount ?? 0} question{(q.questionCount ?? 0) === 1 ? "" : "s"}
                        {" · "}Pass {q.passThreshold}%
                        {q.maxAttempts != null && ` · ${q.maxAttempts} attempts`}
                        {q.timeLimitMinutes != null && ` · ${q.timeLimitMinutes} min limit`}
                      </p>
                    </div>
                    <div className="flex items-center gap-2 shrink-0">
                      <Link
                        href={`/instructor/quizzes/${q.id}/questions`}
                        className="inline-flex items-center gap-1 px-3 py-1.5 rounded-lg text-xs font-medium text-gray-700 bg-gray-100 hover:bg-gray-200 transition"
                      >
                        <Edit3 size={12} /> Edit Questions
                      </Link>
                      <button
                        onClick={async () => {
                          if (!confirm("Delete this quiz and all its questions?")) return;
                          try {
                            await deleteInstructorQuiz(q.id);
                            toast("success", "Quiz deleted.");
                            setQuizzes((prev) => prev.filter((x) => x.id !== q.id));
                          } catch (err) {
                            toast("error", err instanceof Error ? err.message : "Couldn't delete");
                          }
                        }}
                        className="p-2 text-gray-400 hover:text-red-500 transition cursor-pointer"
                        aria-label="Delete quiz"
                      >
                        <Trash2 size={13} />
                      </button>
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>

      <AddQuizModal
        isOpen={showAddQuiz}
        onClose={() => setShowAddQuiz(false)}
        courseId={courseId}
        modules={modules.map((m) => ({ id: m.id, title: m.title }))}
        lessons={lessons.map((l) => ({ id: l.id, title: l.title }))}
        onCreated={fetchAll}
      />

      <BulkUploadModal
        isOpen={showBulkUpload}
        onClose={() => setShowBulkUpload(false)}
        courseId={courseId}
        onUploaded={fetchAll}
      />

      {/* Edit lesson modal */}
      <AnimatePresence>
        {editingLessonId !== null && (
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            className="fixed inset-0 z-50 flex items-center justify-center px-4 bg-black/40 backdrop-blur-sm"
            onClick={() => setEditingLessonId(null)}
          >
            <motion.div
              initial={{ scale: 0.95, opacity: 0 }}
              animate={{ scale: 1, opacity: 1 }}
              exit={{ scale: 0.95, opacity: 0 }}
              transition={{ duration: 0.18 }}
              onClick={(e) => e.stopPropagation()}
              className="bg-white rounded-2xl shadow-xl w-full max-w-md p-6"
            >
              <div className="flex items-start justify-between mb-4">
                <h2 className="text-lg font-bold text-gray-900">Edit Lesson</h2>
                <button
                  onClick={() => setEditingLessonId(null)}
                  className="text-gray-400 hover:text-gray-600 cursor-pointer"
                  aria-label="Close"
                >
                  <X size={18} />
                </button>
              </div>
              <LessonFormFields
                draft={editLessonForm}
                setDraft={setEditLessonForm}
              />
              <div className="flex items-center justify-end gap-2 mt-5">
                <button
                  onClick={() => setEditingLessonId(null)}
                  disabled={busy}
                  className="px-4 py-2 rounded-lg text-sm font-medium text-gray-600 border border-gray-300 hover:bg-gray-50 disabled:opacity-50 transition cursor-pointer"
                >
                  Cancel
                </button>
                <button
                  onClick={submitEditLesson}
                  disabled={busy}
                  className="inline-flex items-center gap-1.5 px-4 py-2 rounded-lg text-sm font-semibold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-50 transition cursor-pointer"
                >
                  {busy ? <Loader2 size={14} className="animate-spin" /> : <Save size={14} />}
                  Save Changes
                </button>
              </div>
            </motion.div>
          </motion.div>
        )}
      </AnimatePresence>

      {/* Video uploader modal */}
      {videoUploaderFor && (
        <LessonVideoUploader
          lessonId={videoUploaderFor.id}
          lessonTitle={videoUploaderFor.title}
          currentVideoUrl={videoUploaderFor.videoUrl}
          onChanged={({ videoUrl, durationMinutes }) => {
            // Optimistic local update so the card refreshes immediately
            // without waiting for the next fetchAll().
            setLessons((prev) =>
              prev.map((l) =>
                l.id === videoUploaderFor.id
                  ? { ...l, videoUrl, durationMinutes: durationMinutes ?? l.durationMinutes }
                  : l
              )
            );
            setVideoUploaderFor((cur) => cur ? { ...cur, videoUrl, durationMinutes: durationMinutes ?? cur.durationMinutes } : cur);
          }}
          onClose={() => { setVideoUploaderFor(null); fetchAll(); }}
        />
      )}
    </section>
  );
}

// ─── Subcomponents ─────────────────────────────────────────────────

interface ModuleFormProps {
  draft: { title: string; description: string };
  setDraft: (d: { title: string; description: string }) => void;
  busy: boolean;
  submitLabel: string;
  onSubmit: () => void;
  onCancel: () => void;
}

function ModuleForm({ draft, setDraft, busy, submitLabel, onSubmit, onCancel }: ModuleFormProps) {
  return (
    <div className="bg-white rounded-xl border border-gray-200 p-4 space-y-3">
      <div>
        <label className="block text-xs font-medium text-gray-700 mb-1">Module title <span className="text-red-500">*</span></label>
        <input
          type="text"
          value={draft.title}
          onChange={(e) => setDraft({ ...draft, title: e.target.value })}
          placeholder="e.g. Getting Started"
          className="w-full px-3 py-2 rounded-lg border border-gray-300 text-sm focus:outline-none focus:ring-2 focus:ring-sage-navy/30"
        />
      </div>
      <div>
        <label className="block text-xs font-medium text-gray-700 mb-1">Description</label>
        <input
          type="text"
          value={draft.description}
          onChange={(e) => setDraft({ ...draft, description: e.target.value })}
          placeholder="One-line summary"
          className="w-full px-3 py-2 rounded-lg border border-gray-300 text-sm focus:outline-none focus:ring-2 focus:ring-sage-navy/30"
        />
      </div>
      <div className="flex items-center justify-end gap-2">
        <button
          onClick={onCancel}
          disabled={busy}
          className="px-3 py-1.5 rounded-lg text-xs font-medium text-gray-600 border border-gray-300 hover:bg-gray-50 disabled:opacity-50 transition cursor-pointer"
        >
          Cancel
        </button>
        <button
          onClick={onSubmit}
          disabled={busy || !draft.title.trim()}
          className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-50 transition cursor-pointer"
        >
          {busy && <Loader2 size={12} className="animate-spin" />}
          {submitLabel}
        </button>
      </div>
    </div>
  );
}

interface LessonFormProps {
  draft: { title: string; description: string; durationMinutes: string; isFree: boolean };
  setDraft: (d: { title: string; description: string; durationMinutes: string; isFree: boolean }) => void;
  busy: boolean;
  submitLabel: string;
  onSubmit: () => void;
  onCancel: () => void;
}

function LessonForm({ draft, setDraft, busy, submitLabel, onSubmit, onCancel }: LessonFormProps) {
  return (
    <div className="bg-gray-50 rounded-lg border border-gray-200 p-3 space-y-3">
      <LessonFormFields draft={draft} setDraft={setDraft} />
      <div className="flex items-center justify-end gap-2">
        <button
          onClick={onCancel}
          disabled={busy}
          className="px-3 py-1.5 rounded-lg text-xs font-medium text-gray-600 border border-gray-300 hover:bg-gray-100 disabled:opacity-50 transition cursor-pointer"
        >
          Cancel
        </button>
        <button
          onClick={onSubmit}
          disabled={busy || !draft.title.trim()}
          className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-50 transition cursor-pointer"
        >
          {busy && <Loader2 size={12} className="animate-spin" />}
          {submitLabel}
        </button>
      </div>
    </div>
  );
}

function LessonFormFields({
  draft, setDraft,
}: {
  draft: { title: string; description: string; durationMinutes: string; isFree: boolean };
  setDraft: (d: { title: string; description: string; durationMinutes: string; isFree: boolean }) => void;
}) {
  return (
    <>
      <div>
        <label className="block text-xs font-medium text-gray-700 mb-1">Lesson title <span className="text-red-500">*</span></label>
        <input
          type="text"
          value={draft.title}
          onChange={(e) => setDraft({ ...draft, title: e.target.value })}
          placeholder="e.g. Introduction to React"
          className="w-full px-3 py-2 rounded-lg border border-gray-300 bg-white text-sm focus:outline-none focus:ring-2 focus:ring-sage-navy/30"
        />
      </div>
      <div>
        <label className="block text-xs font-medium text-gray-700 mb-1">Description</label>
        <textarea
          value={draft.description}
          onChange={(e) => setDraft({ ...draft, description: e.target.value })}
          rows={2}
          placeholder="What will students learn in this lesson?"
          className="w-full px-3 py-2 rounded-lg border border-gray-300 bg-white text-sm focus:outline-none focus:ring-2 focus:ring-sage-navy/30 resize-y"
        />
      </div>
      <div className="grid grid-cols-2 gap-3">
        <div>
          <label className="block text-xs font-medium text-gray-700 mb-1">Duration (min)</label>
          <input
            type="number"
            min="0"
            value={draft.durationMinutes}
            onChange={(e) => setDraft({ ...draft, durationMinutes: e.target.value })}
            placeholder="15"
            className="w-full px-3 py-2 rounded-lg border border-gray-300 bg-white text-sm focus:outline-none focus:ring-2 focus:ring-sage-navy/30"
          />
          <p className="text-[10px] text-gray-400 mt-1">Auto-filled when you upload a video.</p>
        </div>
        <div className="flex items-end">
          <label className="flex items-center gap-2 text-sm text-gray-700 cursor-pointer">
            <input
              type="checkbox"
              checked={draft.isFree}
              onChange={(e) => setDraft({ ...draft, isFree: e.target.checked })}
              className="w-4 h-4 rounded border-gray-300 text-sage-navy focus:ring-sage-navy"
            />
            Free preview
          </label>
        </div>
      </div>
    </>
  );
}

interface LessonCardProps {
  lesson: LessonRow;
  index: number;
  isFirst: boolean;
  isLast: boolean;
  busy: boolean;
  onMoveUp: () => void;
  onMoveDown: () => void;
  onEdit: () => void;
  onDelete: () => void;
  onUploadVideo: () => void;
}

function LessonCard({
  lesson, index, isFirst, isLast, busy,
  onMoveUp, onMoveDown, onEdit, onDelete, onUploadVideo,
}: LessonCardProps) {
  const hasVideo = !!lesson.videoUrl;
  return (
    <div className="bg-white rounded-lg border border-gray-200 p-3">
      <div className="flex items-start gap-3">
        {/* Reorder controls — arrow buttons (drag-and-drop is the
            stretch goal; arrows give us touch-friendly UX). */}
        <div className="flex flex-col gap-0.5 -mt-0.5">
          <button
            onClick={onMoveUp}
            disabled={isFirst || busy}
            className="p-0.5 text-gray-300 hover:text-gray-700 disabled:opacity-30 disabled:cursor-not-allowed cursor-pointer"
            aria-label="Move up"
          >
            <ArrowUp size={12} />
          </button>
          <button
            onClick={onMoveDown}
            disabled={isLast || busy}
            className="p-0.5 text-gray-300 hover:text-gray-700 disabled:opacity-30 disabled:cursor-not-allowed cursor-pointer"
            aria-label="Move down"
          >
            <ArrowDown size={12} />
          </button>
        </div>
        <div className="w-7 h-7 rounded-md bg-gray-100 text-gray-600 text-xs font-bold flex items-center justify-center shrink-0">
          {index + 1}
        </div>
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2 flex-wrap">
            <p className="font-medium text-gray-900 text-sm truncate">{lesson.title}</p>
            {lesson.isFree && (
              <span className="text-[10px] font-semibold uppercase px-1.5 py-0.5 rounded-full bg-teal-100 text-teal-700">
                FREE
              </span>
            )}
          </div>
          <div className="flex items-center gap-3 text-xs mt-0.5">
            {lesson.durationMinutes != null && lesson.durationMinutes > 0 && (
              <span className="text-gray-500">{lesson.durationMinutes} min</span>
            )}
            {hasVideo ? (
              <span className="inline-flex items-center gap-1 text-emerald-700">
                <CheckCircle2 size={11} /> Video uploaded
              </span>
            ) : (
              <span className="inline-flex items-center gap-1 text-amber-700">
                <AlertTriangle size={11} /> No video yet
              </span>
            )}
          </div>
        </div>
        <div className="flex items-center gap-1 shrink-0">
          <button
            onClick={onEdit}
            className="p-1.5 text-gray-500 hover:text-gray-900 transition cursor-pointer"
            aria-label="Edit lesson"
            title="Edit"
          >
            <Edit3 size={13} />
          </button>
          <button
            onClick={onUploadVideo}
            className={cn(
              "inline-flex items-center gap-1 px-2 py-1 rounded-md text-[11px] font-semibold transition cursor-pointer",
              hasVideo
                ? "text-emerald-700 bg-emerald-50 hover:bg-emerald-100"
                : "text-sage-navy bg-sage-navy/10 hover:bg-sage-navy/15"
            )}
          >
            {hasVideo ? <Play size={11} /> : <Upload size={11} />}
            {hasVideo ? "Video" : "Upload"}
          </button>
          <button
            onClick={onDelete}
            className="p-1.5 text-gray-400 hover:text-red-500 transition cursor-pointer"
            aria-label="Delete lesson"
            title="Delete"
          >
            <Trash2 size={13} />
          </button>
        </div>
      </div>
    </div>
  );
}

