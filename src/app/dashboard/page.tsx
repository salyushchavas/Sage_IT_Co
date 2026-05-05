"use client";

import { useState, useEffect } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { motion } from "framer-motion";
import {
  Loader2, BookOpen, Plus, Eye, EyeOff, Trash2, Users, ShieldCheck, GraduationCap,
} from "lucide-react";
import { useAuth } from "@/lib/auth-context";
import {
  getEnrollments, getMyCourses, publishCourse, unpublishCourse,
  deleteCourse, getInstructorStudents, requestInstructor,
} from "@/lib/api";
import { cn, fadeUp, staggerContainer } from "@/lib/utils";
import EnrolledCourses from "@/components/dashboard/EnrolledCourses";
import StreakCard from "@/components/dashboard/StreakCard";

interface MyCourse {
  id: number; title: string; level: string; isPublished: boolean;
  enrolledCount: number; lessonsCount: number; category: string;
}

interface EnrollmentItem {
  id: number; courseId: number; courseTitle: string; courseThumbnailUrl?: string | null;
  courseCategory?: string; progress?: number; enrolledAt: string;
}

interface StudentItem {
  studentName: string; email: string; courseTitle: string; enrolledAt: string;
}

export default function DashboardPage() {
  const router = useRouter();
  const { user, isAuthenticated, isLoading: authLoading } = useAuth();

  const [enrollments, setEnrollments] = useState<EnrollmentItem[]>([]);
  const [myCourses, setMyCourses] = useState<MyCourse[]>([]);
  const [students, setStudents] = useState<StudentItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [requestingInstructor, setRequestingInstructor] = useState(false);
  const [instructorMsg, setInstructorMsg] = useState("");

  const role = user?.role?.toUpperCase();
  const isInstructor = role === "INSTRUCTOR";
  const isAdmin = role === "ADMIN";

  useEffect(() => {
    if (authLoading) return;
    if (!isAuthenticated) { router.push("/login?redirect=/dashboard"); return; }

    const fetchData = async () => {
      setLoading(true);
      try {
        const enrollData = await getEnrollments();
        setEnrollments(enrollData as EnrollmentItem[]);

        if (isInstructor || isAdmin) {
          const courses = await getMyCourses();
          setMyCourses(courses as MyCourse[]);
          try {
            const s = await getInstructorStudents();
            setStudents(s as StudentItem[]);
          } catch { /* no students */ }
        }
      } catch { /* */ }
      finally { setLoading(false); }
    };
    fetchData();
  }, [authLoading, isAuthenticated, isInstructor, isAdmin, router]);

  const handlePublish = async (courseId: number, publish: boolean) => {
    try {
      if (publish) await publishCourse(courseId);
      else await unpublishCourse(courseId);
      setMyCourses((prev) => prev.map((c) => c.id === courseId ? { ...c, isPublished: publish } : c));
    } catch (err) { alert(err instanceof Error ? err.message : "Failed"); }
  };

  const handleDelete = async (courseId: number) => {
    if (!confirm("Delete this course?")) return;
    try {
      await deleteCourse(courseId);
      setMyCourses((prev) => prev.filter((c) => c.id !== courseId));
    } catch (err) { alert(err instanceof Error ? err.message : "Failed"); }
  };

  const handleRequestInstructor = async () => {
    setRequestingInstructor(true); setInstructorMsg("");
    try {
      await requestInstructor();
      setInstructorMsg("Request submitted! An admin will review it soon.");
    } catch (err) {
      setInstructorMsg(err instanceof Error ? err.message : "Failed to submit request");
    } finally { setRequestingInstructor(false); }
  };

  if (authLoading || loading) {
    return <div className="flex items-center justify-center min-h-screen pt-24"><Loader2 className="w-8 h-8 animate-spin text-[#0F5132]" /></div>;
  }

  return (
    <section className="min-h-screen pt-28 pb-20 px-6">
      <div className="mx-auto max-w-6xl">
        {/* Header */}
        <motion.div initial={{ opacity: 0, y: 20 }} animate={{ opacity: 1, y: 0 }} className="mb-10">
          <h1 className="text-3xl font-bold text-zinc-900 mb-1">
            Welcome back, <span className="bg-gradient-to-r from-[#0F5132] to-[#D4A017] bg-clip-text text-transparent">{user?.fullName}</span>
          </h1>
          <p className="text-zinc-600 text-sm">
            {isAdmin ? "Admin Dashboard" : isInstructor ? "Instructor Dashboard" : "Student Dashboard"}
          </p>
        </motion.div>

        {/* Gamification */}
        <div className="grid gap-6 md:grid-cols-3 mb-10">
          <StreakCard streak={7} xp={1250} level="Developer" className="md:col-span-1" />
          <div className="md:col-span-2 bg-white/60 backdrop-blur-xl border border-zinc-200 rounded-2xl p-6">
            <div className="grid grid-cols-3 gap-4 text-center">
              <div>
                <p className="text-2xl font-bold text-[#0F5132]">{enrollments.length}</p>
                <p className="text-xs text-zinc-500 mt-1">Enrolled Courses</p>
              </div>
              <div>
                <p className="text-2xl font-bold text-[#D4A017]">{myCourses.length}</p>
                <p className="text-xs text-zinc-500 mt-1">{isInstructor || isAdmin ? "My Courses" : "Completed"}</p>
              </div>
              <div>
                <p className="text-2xl font-bold text-[#0A3D26]">{students.length}</p>
                <p className="text-xs text-zinc-500 mt-1">{isInstructor || isAdmin ? "Students" : "Certificates"}</p>
              </div>
            </div>
          </div>
        </div>

        {/* Admin link */}
        {isAdmin && (
          <Link href="/admin" className="inline-flex items-center gap-2 mb-8 px-5 py-2.5 rounded-xl bg-[#D4A017]/20 border border-[#D4A017]/30 text-[#D4A017] text-sm font-medium hover:bg-[#D4A017]/30 transition-colors">
            <ShieldCheck className="w-4 h-4" /> Go to Admin Panel
          </Link>
        )}

        {/* Enrolled Courses */}
        <div className="mb-12">
          <h2 className="text-xl font-bold text-zinc-900 mb-6 flex items-center gap-2">
            <GraduationCap className="w-5 h-5 text-[#0F5132]" /> Enrolled Courses
          </h2>
          <EnrolledCourses enrollments={enrollments} />
        </div>

        {/* Become Instructor (students only) */}
        {!isInstructor && !isAdmin && (
          <div className="mb-12 bg-white/60 backdrop-blur-xl border border-zinc-200 rounded-2xl p-6">
            <h3 className="text-lg font-semibold text-zinc-900 mb-2">Become an Instructor</h3>
            <p className="text-sm text-zinc-600 mb-4">Share your knowledge and create courses on Sage IT.</p>
            <button onClick={handleRequestInstructor} disabled={requestingInstructor}
              className="px-5 py-2.5 rounded-xl bg-gradient-to-r from-[#0F5132] to-[#D4A017] text-zinc-900 text-sm font-semibold disabled:opacity-50 hover:shadow-[0_0_20px_rgba(15,81,50,0.2)] transition">
              {requestingInstructor ? "Submitting..." : "Request Instructor Access"}
            </button>
            {instructorMsg && <p className={cn("text-xs mt-3", instructorMsg.includes("submitted") ? "text-green-400" : "text-red-400")}>{instructorMsg}</p>}
          </div>
        )}

        {/* Instructor: My Courses */}
        {(isInstructor || isAdmin) && (
          <div className="mb-12">
            <div className="flex items-center justify-between mb-6">
              <h2 className="text-xl font-bold text-zinc-900 flex items-center gap-2">
                <BookOpen className="w-5 h-5 text-[#D4A017]" /> My Courses
              </h2>
              <Link href="/courses/create" className="flex items-center gap-1.5 text-sm font-medium text-[#0F5132] hover:text-[#0A3D26] transition-colors">
                <Plus className="w-4 h-4" /> Create Course
              </Link>
            </div>

            {myCourses.length === 0 ? (
              <div className="text-center py-12 bg-white/60 backdrop-blur-xl border border-zinc-200 rounded-2xl">
                <BookOpen className="w-8 h-8 mx-auto text-zinc-600 mb-3" />
                <p className="text-zinc-500 text-sm">No courses yet. Create your first course!</p>
              </div>
            ) : (
              <motion.div variants={staggerContainer} initial="hidden" animate="visible" className="space-y-3">
                {myCourses.map((course) => (
                  <motion.div key={course.id} variants={fadeUp}
                    className="bg-white/60 backdrop-blur-xl border border-zinc-200 rounded-2xl p-4 flex items-center gap-4">
                    <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-[#D4A017]/30 to-[#0F5132]/20 flex items-center justify-center text-zinc-900 font-bold text-sm shrink-0">
                      {course.title.charAt(0)}
                    </div>
                    <div className="flex-1 min-w-0">
                      <Link href={`/courses/${course.id}`} className="text-sm font-semibold text-zinc-900 hover:text-[#0F5132] transition-colors truncate block">
                        {course.title}
                      </Link>
                      <div className="flex items-center gap-3 text-xs text-zinc-500 mt-0.5">
                        <span>{course.level}</span>
                        <span>{course.lessonsCount} lessons</span>
                        <span>{course.enrolledCount} enrolled</span>
                        <span className={course.isPublished ? "text-green-400" : "text-amber-400"}>
                          {course.isPublished ? "Published" : "Draft"}
                        </span>
                      </div>
                    </div>
                    <div className="flex items-center gap-2">
                      <button onClick={() => handlePublish(course.id, !course.isPublished)}
                        className="p-2 rounded-lg hover:bg-white/60 text-zinc-600 hover:text-zinc-900 transition-colors" title={course.isPublished ? "Unpublish" : "Publish"}>
                        {course.isPublished ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                      </button>
                      <button onClick={() => handleDelete(course.id)}
                        className="p-2 rounded-lg hover:bg-red-500/10 text-zinc-600 hover:text-red-400 transition-colors" title="Delete">
                        <Trash2 className="w-4 h-4" />
                      </button>
                    </div>
                  </motion.div>
                ))}
              </motion.div>
            )}
          </div>
        )}

        {/* Instructor: Students Table */}
        {(isInstructor || isAdmin) && students.length > 0 && (
          <div>
            <h2 className="text-xl font-bold text-zinc-900 mb-6 flex items-center gap-2">
              <Users className="w-5 h-5 text-[#0A3D26]" /> Your Students
            </h2>
            <div className="bg-white/60 backdrop-blur-xl border border-zinc-200 rounded-2xl overflow-hidden">
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b border-zinc-200 text-zinc-600">
                      <th className="text-left px-4 py-3 font-medium">Student</th>
                      <th className="text-left px-4 py-3 font-medium">Email</th>
                      <th className="text-left px-4 py-3 font-medium">Course</th>
                      <th className="text-left px-4 py-3 font-medium">Enrolled</th>
                    </tr>
                  </thead>
                  <tbody>
                    {students.map((s, i) => (
                      <tr key={i} className="border-b border-zinc-200 hover:bg-white/[0.02] transition-colors">
                        <td className="px-4 py-3 text-zinc-900">{s.studentName}</td>
                        <td className="px-4 py-3 text-zinc-600">{s.email}</td>
                        <td className="px-4 py-3 text-zinc-500">{s.courseTitle}</td>
                        <td className="px-4 py-3 text-zinc-500">{new Date(s.enrolledAt).toLocaleDateString()}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        )}
      </div>
    </section>
  );
}
