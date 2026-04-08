"use client";

import { useState, useEffect } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { motion } from "framer-motion";
import {
  Clock, BookOpen, Loader2, AlertCircle, Plus, ChevronLeft, Star,
  ChevronDown, ChevronUp, Play, FileText, Award, Download, Video, ClipboardList,
} from "lucide-react";
import { useAuth } from "@/lib/auth-context";
import {
  getCourse, getCourseLessons, getCourseAssignments, enroll,
  createLesson, deleteLesson, checkCertificate, generateCertificate,
  completeLesson, getLessonQuiz, getLessonTasks,
} from "@/lib/api";
import { cn } from "@/lib/utils";

interface CourseData {
  id: number; title: string; slug: string; description: string; shortDescription: string;
  level: string; price: number; isFree: boolean; durationHours: number; category: string;
  rating: number; ratingsCount: number; lessonsCount: number; enrolledCount: number;
  thumbnailUrl: string | null; isPublished: boolean;
  instructor: { id: number; fullName: string; email: string; avatarUrl: string | null } | null;
}

interface LessonData {
  id: number; courseId: number; title: string; description: string | null;
  videoUrl: string | null; orderIndex: number; durationMinutes: number | null;
  isFree: boolean;
}

interface AssignmentData {
  id: number; title: string; description: string; assignmentType: string;
  dueDate: string | null; unlocked: boolean;
}

const levelStyle: Record<string, string> = {
  BEGINNER: "bg-cyan-500/20 border-cyan-500/30 text-cyan-400",
  INTERMEDIATE: "bg-amber-500/20 border-amber-500/30 text-amber-400",
  ADVANCED: "bg-red-500/20 border-red-500/30 text-red-400",
};

export default function CourseDetailPage({ params }: { params: { id: string } }) {
  const { id } = params;
  const router = useRouter();
  const { user, isAuthenticated } = useAuth();

  const [course, setCourse] = useState<CourseData | null>(null);
  const [lessons, setLessons] = useState<LessonData[]>([]);
  const [assignments, setAssignments] = useState<AssignmentData[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [enrolling, setEnrolling] = useState(false);
  const [enrollMsg, setEnrollMsg] = useState("");
  const [enrolled, setEnrolled] = useState(false);
  const [expandedLesson, setExpandedLesson] = useState<number | null>(null);

  // Certificate
  const [certificate, setCertificate] = useState<{ exists: boolean; certificateUrl?: string } | null>(null);
  const [generatingCert, setGeneratingCert] = useState(false);
  const [certError, setCertError] = useState("");

  // Add lesson
  const [showAddLesson, setShowAddLesson] = useState(false);
  const [newLesson, setNewLesson] = useState({ title: "", description: "", videoUrl: "", durationMinutes: "", isFree: false });
  const [addingLesson, setAddingLesson] = useState(false);

  const isOwner = user && course?.instructor?.id === user.id;
  const isAdmin = user?.role?.toUpperCase() === "ADMIN";
  const canManage = isOwner || isAdmin;

  useEffect(() => {
    const fetchData = async () => {
      setLoading(true);
      try {
        const courseData = await getCourse(id);
        setCourse(courseData as CourseData);
        const lessonData = await getCourseLessons(id);
        setLessons((lessonData || []) as LessonData[]);
        try {
          const assignmentData = await getCourseAssignments(id);
          setAssignments((assignmentData || []) as AssignmentData[]);
          setEnrolled(true);
          try { const c = await checkCertificate(id); setCertificate(c); } catch { /* not eligible */ }
        } catch { /* not enrolled */ }
      } catch (err) {
        setError(err instanceof Error ? err.message : "Failed to load course");
      } finally {
        setLoading(false);
      }
    };
    fetchData();
  }, [id]);

  const handleEnroll = async () => {
    if (!isAuthenticated) { router.push(`/login?redirect=/courses/${id}`); return; }
    setEnrolling(true); setEnrollMsg("");
    try {
      await enroll(Number(id));
      setEnrollMsg("Enrolled successfully!");
      setEnrolled(true);
      const lessonData = await getCourseLessons(id);
      setLessons((lessonData || []) as LessonData[]);
      try { const a = await getCourseAssignments(id); setAssignments((a || []) as AssignmentData[]); } catch { /* */ }
    } catch (err) {
      setEnrollMsg(err instanceof Error ? err.message : "Failed to enroll");
    } finally { setEnrolling(false); }
  };

  const handleAddLesson = async (e: React.FormEvent) => {
    e.preventDefault();
    setAddingLesson(true);
    try {
      await createLesson(id, {
        title: newLesson.title,
        description: newLesson.description || undefined,
        videoUrl: newLesson.videoUrl || undefined,
        durationMinutes: newLesson.durationMinutes ? parseInt(newLesson.durationMinutes) : undefined,
        isFree: newLesson.isFree,
      });
      setNewLesson({ title: "", description: "", videoUrl: "", durationMinutes: "", isFree: false });
      setShowAddLesson(false);
      const lessonData = await getCourseLessons(id);
      setLessons((lessonData || []) as LessonData[]);
    } catch (err) {
      alert(err instanceof Error ? err.message : "Failed to add lesson");
    } finally { setAddingLesson(false); }
  };

  const handleDeleteLesson = async (lessonId: number) => {
    if (!confirm("Delete this lesson?")) return;
    try {
      await deleteLesson(lessonId);
      setLessons((prev) => prev.filter((l) => l.id !== lessonId));
    } catch (err) {
      alert(err instanceof Error ? err.message : "Failed to delete");
    }
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center min-h-screen pt-24">
        <Loader2 className="w-8 h-8 animate-spin text-[#00d4ff]" />
      </div>
    );
  }

  if (error || !course) {
    return (
      <div className="flex flex-col items-center justify-center min-h-screen pt-24 px-6">
        <AlertCircle className="w-12 h-12 text-red-400 mb-4" />
        <p className="text-zinc-300 mb-4">{error || "Course not found"}</p>
        <Link href="/courses" className="text-[#00d4ff] hover:underline text-sm">Back to courses</Link>
      </div>
    );
  }

  return (
    <section className="min-h-screen pt-28 pb-20 px-6">
      <div className="mx-auto max-w-5xl">
        {/* Back */}
        <Link href="/courses" className="inline-flex items-center gap-1 text-sm text-zinc-500 hover:text-[#00d4ff] mb-6 transition-colors">
          <ChevronLeft className="w-4 h-4" /> Back to courses
        </Link>

        {/* Header */}
        <motion.div initial={{ opacity: 0, y: 20 }} animate={{ opacity: 1, y: 0 }} className="grid lg:grid-cols-3 gap-8 mb-12">
          {/* Info */}
          <div className="lg:col-span-2">
            <span className={cn("text-xs font-semibold px-2.5 py-1 rounded-full border inline-block mb-3", levelStyle[course.level] || levelStyle.BEGINNER)}>
              {course.level}
            </span>
            <h1 className="text-3xl sm:text-4xl font-bold text-white mb-4">{course.title}</h1>
            <p className="text-zinc-400 mb-4 leading-relaxed">{course.description || course.shortDescription}</p>

            <div className="flex flex-wrap items-center gap-4 text-sm text-zinc-500 mb-6">
              <span className="flex items-center gap-1"><Clock className="w-4 h-4" /> {course.durationHours}h</span>
              <span className="flex items-center gap-1"><BookOpen className="w-4 h-4" /> {course.lessonsCount} lessons</span>
              <span className="flex items-center gap-1"><Star className="w-4 h-4 text-amber-400" /> {course.rating} ({course.ratingsCount})</span>
              <span>{course.enrolledCount.toLocaleString()} enrolled</span>
            </div>

            {course.instructor && (
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-full bg-gradient-to-br from-[#7c3aed] to-[#00d4ff] flex items-center justify-center text-sm font-bold text-white">
                  {course.instructor.fullName.charAt(0)}
                </div>
                <div>
                  <p className="text-sm font-semibold text-white">{course.instructor.fullName}</p>
                  <p className="text-xs text-zinc-500">Instructor</p>
                </div>
              </div>
            )}
          </div>

          {/* Sidebar card */}
          <div className="bg-white/5 backdrop-blur-xl border border-white/10 rounded-2xl p-6">
            <div className="text-3xl font-bold text-white mb-1">
              {course.isFree ? "Free" : `₹${course.price}`}
            </div>
            <p className="text-sm text-zinc-500 mb-6">{course.isFree ? "No payment required" : "One-time payment"}</p>

            <button
              onClick={handleEnroll}
              disabled={enrolling}
              className={cn(
                "w-full py-3 rounded-xl text-sm font-semibold transition-all flex items-center justify-center gap-2",
                "bg-gradient-to-r from-[#00d4ff] via-[#7c3aed] to-[#06b6d4] text-white",
                "hover:shadow-[0_0_30px_rgba(0,212,255,0.3)] hover:scale-[1.02]",
                "disabled:opacity-50"
              )}
            >
              {enrolling ? <><Loader2 className="w-4 h-4 animate-spin" /> Enrolling...</> : "Enroll Now"}
            </button>

            {enrollMsg && (
              <p className={cn("text-xs mt-3 text-center", enrollMsg.includes("success") ? "text-green-400" : "text-red-400")}>{enrollMsg}</p>
            )}

            <div className="mt-6 space-y-2 text-sm text-zinc-400">
              <p>Category: <span className="text-white">{course.category}</span></p>
              <p>Level: <span className="text-white">{course.level}</span></p>
              <p>Lessons: <span className="text-white">{course.lessonsCount}</span></p>
            </div>
          </div>
        </motion.div>

        {/* Lessons */}
        <motion.div initial={{ opacity: 0, y: 20 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: 0.2 }}>
          <div className="flex items-center justify-between mb-6">
            <h2 className="text-2xl font-bold text-white">Course Lessons</h2>
            {canManage && (
              <button onClick={() => setShowAddLesson(!showAddLesson)} className="flex items-center gap-1.5 text-sm font-medium text-[#00d4ff] hover:text-[#06b6d4] transition-colors">
                <Plus className="w-4 h-4" /> Add Lesson
              </button>
            )}
          </div>

          {/* Add lesson form */}
          {showAddLesson && canManage && (
            <motion.form
              initial={{ opacity: 0, height: 0 }}
              animate={{ opacity: 1, height: "auto" }}
              onSubmit={handleAddLesson}
              className="bg-white/5 backdrop-blur-xl border border-white/10 rounded-2xl p-6 mb-6 space-y-4"
            >
              <input type="text" placeholder="Lesson title *" required value={newLesson.title}
                onChange={(e) => setNewLesson((p) => ({ ...p, title: e.target.value }))}
                className="w-full px-4 py-2.5 rounded-xl bg-white/5 border border-white/10 text-white text-sm placeholder-zinc-600 outline-none focus:border-[#00d4ff]/40" />
              <input type="text" placeholder="Description (optional)" value={newLesson.description}
                onChange={(e) => setNewLesson((p) => ({ ...p, description: e.target.value }))}
                className="w-full px-4 py-2.5 rounded-xl bg-white/5 border border-white/10 text-white text-sm placeholder-zinc-600 outline-none focus:border-[#00d4ff]/40" />
              <div className="grid grid-cols-2 gap-4">
                <input type="url" placeholder="Video URL (optional)" value={newLesson.videoUrl}
                  onChange={(e) => setNewLesson((p) => ({ ...p, videoUrl: e.target.value }))}
                  className="px-4 py-2.5 rounded-xl bg-white/5 border border-white/10 text-white text-sm placeholder-zinc-600 outline-none focus:border-[#00d4ff]/40" />
                <input type="number" placeholder="Duration (min)" value={newLesson.durationMinutes}
                  onChange={(e) => setNewLesson((p) => ({ ...p, durationMinutes: e.target.value }))}
                  className="px-4 py-2.5 rounded-xl bg-white/5 border border-white/10 text-white text-sm placeholder-zinc-600 outline-none focus:border-[#00d4ff]/40" />
              </div>
              <label className="flex items-center gap-2 text-sm text-zinc-400">
                <input type="checkbox" checked={newLesson.isFree}
                  onChange={(e) => setNewLesson((p) => ({ ...p, isFree: e.target.checked }))}
                  className="rounded bg-white/10 border-white/20" />
                Free preview lesson
              </label>
              <div className="flex gap-3">
                <button type="submit" disabled={addingLesson}
                  className="px-6 py-2.5 rounded-xl bg-gradient-to-r from-[#00d4ff] to-[#7c3aed] text-white text-sm font-semibold disabled:opacity-50">
                  {addingLesson ? "Adding..." : "Add Lesson"}
                </button>
                <button type="button" onClick={() => setShowAddLesson(false)}
                  className="px-6 py-2.5 rounded-xl border border-white/10 text-sm text-zinc-400 hover:text-white transition-colors">
                  Cancel
                </button>
              </div>
            </motion.form>
          )}

          {/* Lesson list */}
          {lessons.length === 0 ? (
            <div className="text-center py-12 bg-white/5 backdrop-blur-xl border border-white/10 rounded-2xl">
              <BookOpen className="w-8 h-8 mx-auto text-zinc-600 mb-3" />
              <p className="text-zinc-500">No lessons yet</p>
            </div>
          ) : (
            <div className="space-y-3">
              {lessons.map((lesson, idx) => {
                const isExpanded = expandedLesson === lesson.id;
                return (
                  <motion.div
                    key={lesson.id}
                    initial={{ opacity: 0, y: 12 }}
                    animate={{ opacity: 1, y: 0 }}
                    transition={{ delay: idx * 0.05 }}
                  >
                    <div className="bg-white/5 backdrop-blur-xl border border-white/10 rounded-2xl overflow-hidden">
                      {/* Lesson header */}
                      <button
                        onClick={() => setExpandedLesson(isExpanded ? null : lesson.id)}
                        className="w-full flex items-center gap-4 p-4 text-left hover:bg-white/[0.02] transition-colors"
                      >
                        <div className="w-8 h-8 rounded-lg bg-[#00d4ff]/10 border border-[#00d4ff]/20 flex items-center justify-center text-xs font-bold text-[#00d4ff] shrink-0">
                          {idx + 1}
                        </div>
                        <div className="flex-1 min-w-0">
                          <h3 className="text-sm font-semibold text-white truncate">{lesson.title}</h3>
                          <div className="flex items-center gap-3 text-xs text-zinc-500 mt-0.5">
                            {lesson.durationMinutes && <span>{lesson.durationMinutes} min</span>}
                            {lesson.videoUrl && <span className="flex items-center gap-1"><Video className="w-3 h-3" /> Video</span>}
                            {lesson.isFree && <span className="text-[#06b6d4]">Free</span>}
                          </div>
                        </div>
                        {canManage && (
                          <button
                            onClick={(e) => { e.stopPropagation(); handleDeleteLesson(lesson.id); }}
                            className="text-xs text-red-400 hover:text-red-300 px-2"
                          >
                            Delete
                          </button>
                        )}
                        {isExpanded ? <ChevronUp className="w-4 h-4 text-zinc-500" /> : <ChevronDown className="w-4 h-4 text-zinc-500" />}
                      </button>

                      {/* Expanded content */}
                      {isExpanded && (
                        <div className="px-4 pb-4 space-y-3 border-t border-white/5 pt-3">
                          {lesson.description && (
                            <p className="text-sm text-zinc-400">{lesson.description}</p>
                          )}
                          {lesson.videoUrl && (
                            <div className="rounded-xl overflow-hidden bg-black aspect-video flex items-center justify-center">
                              <video
                                src={lesson.videoUrl}
                                controls
                                controlsList="nodownload"
                                className="w-full h-full"
                              />
                            </div>
                          )}
                          {enrolled && !canManage && (
                            <button
                              onClick={async () => {
                                try { await completeLesson(lesson.id); } catch { /* */ }
                              }}
                              className="text-xs text-[#00d4ff] hover:underline"
                            >
                              Mark as complete
                            </button>
                          )}
                        </div>
                      )}
                    </div>
                  </motion.div>
                );
              })}
            </div>
          )}
        </motion.div>

        {/* Assignments */}
        {assignments.length > 0 && (
          <motion.div initial={{ opacity: 0, y: 20 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: 0.4 }} className="mt-12">
            <h2 className="text-2xl font-bold text-white mb-6">Assignments</h2>
            <div className="space-y-3">
              {assignments.map((a, idx) => (
                <motion.div
                  key={a.id}
                  initial={{ opacity: 0, y: 12 }}
                  animate={{ opacity: 1, y: 0 }}
                  transition={{ delay: idx * 0.05 }}
                  className={cn(
                    "bg-white/5 backdrop-blur-xl border border-white/10 rounded-2xl p-4 flex items-start gap-4",
                    !a.unlocked && "opacity-50"
                  )}
                >
                  <div className="w-8 h-8 rounded-lg bg-[#7c3aed]/10 border border-[#7c3aed]/20 flex items-center justify-center shrink-0">
                    <ClipboardList className="w-4 h-4 text-[#7c3aed]" />
                  </div>
                  <div className="flex-1">
                    <h3 className="text-sm font-semibold text-white">{a.title}</h3>
                    <p className="text-xs text-zinc-500 mt-0.5">{a.description}</p>
                    <div className="flex items-center gap-3 mt-2 text-xs text-zinc-600">
                      <span className="uppercase">{a.assignmentType}</span>
                      {a.dueDate && <span>Due: {new Date(a.dueDate).toLocaleDateString()}</span>}
                      {!a.unlocked && <span className="text-amber-400">Locked</span>}
                    </div>
                  </div>
                </motion.div>
              ))}
            </div>
          </motion.div>
        )}

        {/* Certificate */}
        {enrolled && (
          <motion.div initial={{ opacity: 0, y: 20 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: 0.5 }} className="mt-12">
            <div className="bg-white/5 backdrop-blur-xl border border-white/10 rounded-2xl p-8 text-center">
              <Award className="w-10 h-10 text-[#00d4ff] mx-auto mb-3" />
              <h2 className="text-2xl font-bold text-white mb-2">Course Certificate</h2>

              {certificate?.exists && certificate.certificateUrl ? (
                <>
                  <p className="text-green-400 font-medium mb-4">You&apos;ve earned your certificate!</p>
                  <a
                    href={`${process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080"}${certificate.certificateUrl}`}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="inline-flex items-center gap-2 px-6 py-3 rounded-xl bg-gradient-to-r from-[#00d4ff] to-[#7c3aed] text-white text-sm font-semibold hover:shadow-[0_0_20px_rgba(0,212,255,0.3)] transition"
                  >
                    <Download className="w-4 h-4" /> Download Certificate (PDF)
                  </a>
                </>
              ) : (
                <>
                  <p className="text-zinc-400 mb-4 text-sm max-w-md mx-auto">
                    Complete all lessons, pass all quizzes, and submit all assignments to earn your certificate.
                  </p>
                  {certError && <p className="text-red-400 text-sm mb-3">{certError}</p>}
                  <button
                    onClick={async () => {
                      setGeneratingCert(true); setCertError("");
                      try {
                        const result = await generateCertificate(id);
                        setCertificate({ exists: true, certificateUrl: result.certificateUrl });
                      } catch (err) {
                        setCertError(err instanceof Error ? err.message : "Not eligible yet");
                      } finally { setGeneratingCert(false); }
                    }}
                    disabled={generatingCert}
                    className="inline-flex items-center gap-2 px-6 py-3 rounded-xl bg-gradient-to-r from-[#7c3aed] to-[#00d4ff] text-white text-sm font-semibold hover:shadow-[0_0_20px_rgba(124,58,237,0.3)] transition disabled:opacity-50"
                  >
                    {generatingCert ? <><Loader2 className="w-4 h-4 animate-spin" /> Generating...</> : <><Award className="w-4 h-4" /> Generate Certificate</>}
                  </button>
                </>
              )}
            </div>
          </motion.div>
        )}
      </div>
    </section>
  );
}
