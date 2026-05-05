"use client";

import { useState, useEffect } from "react";
import { useRouter } from "next/navigation";
import { motion } from "framer-motion";
import {
  Loader2, LayoutDashboard, Users, BookOpen, UserCheck,
  Eye, EyeOff, Trash2, Check, X, TrendingUp, GraduationCap, DollarSign,
} from "lucide-react";
import { useAuth } from "@/lib/auth-context";
import {
  getAnalytics, getUsers, getAdminCourses, publishCourse, unpublishCourse,
  deleteCourse, getPendingInstructorRequests, approveInstructor, rejectInstructor,
} from "@/lib/api";
import { cn, fadeUp } from "@/lib/utils";

type Tab = "overview" | "users" | "courses" | "requests";

interface AnalyticsData {
  totalUsers: number; totalCourses: number; totalEnrollments: number; totalRevenue: number;
  recentUsers: Array<{ id: number; fullName: string; email: string; role: string; createdAt: string }>;
}

interface UserItem {
  id: number; fullName: string; email: string; role: string; createdAt: string;
}

interface CourseItem {
  id: number; title: string; level: string; isPublished: boolean;
  enrolledCount: number; category: string;
  instructor?: { fullName: string } | null;
}

interface InstructorRequest {
  id: number; userId: number; fullName: string; email: string;
  status: string; createdAt: string;
}

export default function AdminPage() {
  const router = useRouter();
  const { user, isAuthenticated, isLoading: authLoading } = useAuth();

  const [tab, setTab] = useState<Tab>("overview");
  const [analytics, setAnalytics] = useState<AnalyticsData | null>(null);
  const [users, setUsers] = useState<UserItem[]>([]);
  const [courses, setCourses] = useState<CourseItem[]>([]);
  const [requests, setRequests] = useState<InstructorRequest[]>([]);
  const [loading, setLoading] = useState(true);

  const role = user?.role?.toUpperCase();

  useEffect(() => {
    if (authLoading) return;
    if (!isAuthenticated) { router.push("/login?redirect=/admin"); return; }
    if (role !== "ADMIN") { router.push("/dashboard"); return; }

    const fetchData = async () => {
      setLoading(true);
      try {
        const [analyticsData, usersData, coursesData, requestsData] = await Promise.all([
          getAnalytics(),
          getUsers(),
          getAdminCourses(),
          getPendingInstructorRequests(),
        ]);
        setAnalytics(analyticsData as AnalyticsData);
        setUsers(usersData as UserItem[]);
        setCourses(coursesData as CourseItem[]);
        setRequests(requestsData as InstructorRequest[]);
      } catch { /* */ }
      finally { setLoading(false); }
    };
    fetchData();
  }, [authLoading, isAuthenticated, role, router]);

  const handlePublish = async (courseId: number, publish: boolean) => {
    try {
      if (publish) await publishCourse(courseId);
      else await unpublishCourse(courseId);
      setCourses((prev) => prev.map((c) => c.id === courseId ? { ...c, isPublished: publish } : c));
    } catch (err) { alert(err instanceof Error ? err.message : "Failed"); }
  };

  const handleDeleteCourse = async (courseId: number) => {
    if (!confirm("Delete this course?")) return;
    try {
      await deleteCourse(courseId);
      setCourses((prev) => prev.filter((c) => c.id !== courseId));
    } catch (err) { alert(err instanceof Error ? err.message : "Failed"); }
  };

  const handleApprove = async (requestId: number) => {
    try {
      await approveInstructor(requestId);
      setRequests((prev) => prev.filter((r) => r.id !== requestId));
    } catch (err) { alert(err instanceof Error ? err.message : "Failed"); }
  };

  const handleReject = async (requestId: number) => {
    try {
      await rejectInstructor(requestId);
      setRequests((prev) => prev.filter((r) => r.id !== requestId));
    } catch (err) { alert(err instanceof Error ? err.message : "Failed"); }
  };

  if (authLoading || loading) {
    return <div className="flex items-center justify-center min-h-screen pt-24"><Loader2 className="w-8 h-8 animate-spin text-[#0F5132]" /></div>;
  }

  const tabs: { key: Tab; label: string; icon: React.ReactNode }[] = [
    { key: "overview", label: "Overview", icon: <LayoutDashboard className="w-4 h-4" /> },
    { key: "users", label: "Users", icon: <Users className="w-4 h-4" /> },
    { key: "courses", label: "Courses", icon: <BookOpen className="w-4 h-4" /> },
    { key: "requests", label: "Requests", icon: <UserCheck className="w-4 h-4" /> },
  ];

  return (
    <section className="min-h-screen pt-28 pb-20 px-6">
      <div className="mx-auto max-w-7xl flex gap-8">
        {/* Sidebar */}
        <div className="hidden md:block w-56 shrink-0">
          <div className="bg-white/60 backdrop-blur-xl border border-zinc-200 rounded-2xl p-4 sticky top-28">
            <h2 className="text-sm font-bold text-zinc-900 mb-4 px-2">Admin Panel</h2>
            <nav className="space-y-1">
              {tabs.map((t) => (
                <button
                  key={t.key}
                  onClick={() => setTab(t.key)}
                  className={cn(
                    "w-full flex items-center gap-2.5 px-3 py-2.5 rounded-xl text-sm font-medium transition-all",
                    tab === t.key
                      ? "bg-[#0F5132]/10 text-[#0F5132] border border-[#0F5132]/20"
                      : "text-zinc-600 hover:text-zinc-900 hover:bg-white/60"
                  )}
                >
                  {t.icon} {t.label}
                  {t.key === "requests" && requests.length > 0 && (
                    <span className="ml-auto text-xs bg-[#D4A017] text-zinc-900 px-1.5 py-0.5 rounded-full">{requests.length}</span>
                  )}
                </button>
              ))}
            </nav>
          </div>
        </div>

        {/* Mobile tab bar */}
        <div className="md:hidden fixed bottom-0 left-0 right-0 z-50 bg-[#050510]/90 backdrop-blur-xl border-t border-zinc-200 px-4 py-2 flex gap-2">
          {tabs.map((t) => (
            <button key={t.key} onClick={() => setTab(t.key)}
              className={cn("flex-1 flex flex-col items-center gap-1 py-2 rounded-xl text-xs", tab === t.key ? "text-[#0F5132]" : "text-zinc-500")}>
              {t.icon}<span>{t.label}</span>
            </button>
          ))}
        </div>

        {/* Content */}
        <div className="flex-1 min-w-0">
          {/* OVERVIEW */}
          {tab === "overview" && analytics && (
            <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }}>
              <h1 className="text-2xl font-bold text-zinc-900 mb-8">Overview</h1>

              <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4 mb-10">
                {[
                  { label: "Total Users", value: analytics.totalUsers, icon: <Users className="w-5 h-5" />, color: "#0F5132" },
                  { label: "Total Courses", value: analytics.totalCourses, icon: <BookOpen className="w-5 h-5" />, color: "#D4A017" },
                  { label: "Enrollments", value: analytics.totalEnrollments, icon: <GraduationCap className="w-5 h-5" />, color: "#0A3D26" },
                  { label: "Revenue", value: `₹${analytics.totalRevenue}`, icon: <DollarSign className="w-5 h-5" />, color: "#10b981" },
                ].map((card) => (
                  <div key={card.label} className="bg-white/60 backdrop-blur-xl border border-zinc-200 rounded-2xl p-5">
                    <div className="flex items-center gap-3 mb-3">
                      <div className="p-2 rounded-lg" style={{ backgroundColor: `${card.color}15`, color: card.color }}>{card.icon}</div>
                      <span className="text-xs text-zinc-600">{card.label}</span>
                    </div>
                    <p className="text-2xl font-bold text-zinc-900">{card.value}</p>
                  </div>
                ))}
              </div>

              {/* Recent users */}
              {analytics.recentUsers && analytics.recentUsers.length > 0 && (
                <div>
                  <h3 className="text-lg font-semibold text-zinc-900 mb-4">Recent Users</h3>
                  <div className="bg-white/60 backdrop-blur-xl border border-zinc-200 rounded-2xl overflow-hidden">
                    <table className="w-full text-sm">
                      <thead><tr className="border-b border-zinc-200 text-zinc-600">
                        <th className="text-left px-4 py-3 font-medium">Name</th>
                        <th className="text-left px-4 py-3 font-medium">Email</th>
                        <th className="text-left px-4 py-3 font-medium">Role</th>
                        <th className="text-left px-4 py-3 font-medium">Joined</th>
                      </tr></thead>
                      <tbody>
                        {analytics.recentUsers.map((u) => (
                          <tr key={u.id} className="border-b border-zinc-200 hover:bg-white/[0.02]">
                            <td className="px-4 py-3 text-zinc-900">{u.fullName}</td>
                            <td className="px-4 py-3 text-zinc-600">{u.email}</td>
                            <td className="px-4 py-3"><span className={cn("text-xs px-2 py-0.5 rounded-full",
                              u.role === "ADMIN" ? "bg-[#D4A017]/20 text-[#D4A017]" :
                              u.role === "INSTRUCTOR" ? "bg-[#0F5132]/20 text-[#0F5132]" :
                              "bg-white/80 text-zinc-600"
                            )}>{u.role}</span></td>
                            <td className="px-4 py-3 text-zinc-500">{new Date(u.createdAt).toLocaleDateString()}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </div>
              )}
            </motion.div>
          )}

          {/* USERS */}
          {tab === "users" && (
            <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }}>
              <h1 className="text-2xl font-bold text-zinc-900 mb-8">All Users ({users.length})</h1>
              <div className="bg-white/60 backdrop-blur-xl border border-zinc-200 rounded-2xl overflow-hidden">
                <div className="overflow-x-auto">
                  <table className="w-full text-sm">
                    <thead><tr className="border-b border-zinc-200 text-zinc-600">
                      <th className="text-left px-4 py-3 font-medium">ID</th>
                      <th className="text-left px-4 py-3 font-medium">Name</th>
                      <th className="text-left px-4 py-3 font-medium">Email</th>
                      <th className="text-left px-4 py-3 font-medium">Role</th>
                      <th className="text-left px-4 py-3 font-medium">Joined</th>
                    </tr></thead>
                    <tbody>
                      {users.map((u) => (
                        <tr key={u.id} className="border-b border-zinc-200 hover:bg-white/[0.02]">
                          <td className="px-4 py-3 text-zinc-500">#{u.id}</td>
                          <td className="px-4 py-3 text-zinc-900">{u.fullName}</td>
                          <td className="px-4 py-3 text-zinc-600">{u.email}</td>
                          <td className="px-4 py-3"><span className={cn("text-xs px-2 py-0.5 rounded-full",
                            u.role === "ADMIN" ? "bg-[#D4A017]/20 text-[#D4A017]" :
                            u.role === "INSTRUCTOR" ? "bg-[#0F5132]/20 text-[#0F5132]" :
                            "bg-white/80 text-zinc-600"
                          )}>{u.role}</span></td>
                          <td className="px-4 py-3 text-zinc-500">{new Date(u.createdAt).toLocaleDateString()}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            </motion.div>
          )}

          {/* COURSES */}
          {tab === "courses" && (
            <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }}>
              <h1 className="text-2xl font-bold text-zinc-900 mb-8">All Courses ({courses.length})</h1>
              <div className="space-y-3">
                {courses.map((course) => (
                  <div key={course.id} className="bg-white/60 backdrop-blur-xl border border-zinc-200 rounded-2xl p-4 flex items-center gap-4">
                    <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-[#D4A017]/30 to-[#0F5132]/20 flex items-center justify-center text-zinc-900 font-bold text-sm shrink-0">
                      {course.title.charAt(0)}
                    </div>
                    <div className="flex-1 min-w-0">
                      <p className="text-sm font-semibold text-zinc-900 truncate">{course.title}</p>
                      <div className="flex items-center gap-3 text-xs text-zinc-500 mt-0.5">
                        <span>{course.level}</span>
                        <span>{course.enrolledCount} enrolled</span>
                        <span>{course.instructor?.fullName || "N/A"}</span>
                        <span className={course.isPublished ? "text-green-400" : "text-amber-400"}>
                          {course.isPublished ? "Published" : "Draft"}
                        </span>
                      </div>
                    </div>
                    <div className="flex items-center gap-2">
                      <button onClick={() => handlePublish(course.id, !course.isPublished)}
                        className="p-2 rounded-lg hover:bg-white/60 text-zinc-600 hover:text-zinc-900 transition-colors">
                        {course.isPublished ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                      </button>
                      <button onClick={() => handleDeleteCourse(course.id)}
                        className="p-2 rounded-lg hover:bg-red-500/10 text-zinc-600 hover:text-red-400 transition-colors">
                        <Trash2 className="w-4 h-4" />
                      </button>
                    </div>
                  </div>
                ))}
                {courses.length === 0 && <p className="text-zinc-500 text-center py-12">No courses yet.</p>}
              </div>
            </motion.div>
          )}

          {/* INSTRUCTOR REQUESTS */}
          {tab === "requests" && (
            <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }}>
              <h1 className="text-2xl font-bold text-zinc-900 mb-8">Instructor Requests ({requests.length})</h1>
              {requests.length === 0 ? (
                <div className="text-center py-16 bg-white/60 backdrop-blur-xl border border-zinc-200 rounded-2xl">
                  <UserCheck className="w-8 h-8 text-zinc-600 mx-auto mb-3" />
                  <p className="text-zinc-500 text-sm">No pending requests.</p>
                </div>
              ) : (
                <div className="space-y-3">
                  {requests.map((req) => (
                    <div key={req.id} className="bg-white/60 backdrop-blur-xl border border-zinc-200 rounded-2xl p-4 flex items-center gap-4">
                      <div className="w-10 h-10 rounded-full bg-gradient-to-br from-[#D4A017] to-[#0F5132] flex items-center justify-center text-zinc-900 font-bold text-sm shrink-0">
                        {req.fullName.charAt(0)}
                      </div>
                      <div className="flex-1">
                        <p className="text-sm font-semibold text-zinc-900">{req.fullName}</p>
                        <p className="text-xs text-zinc-500">{req.email}</p>
                        <p className="text-xs text-zinc-600 mt-0.5">Requested: {new Date(req.createdAt).toLocaleDateString()}</p>
                      </div>
                      <div className="flex items-center gap-2">
                        <button onClick={() => handleApprove(req.id)}
                          className="p-2 rounded-lg bg-green-500/10 text-green-400 hover:bg-green-500/20 transition-colors" title="Approve">
                          <Check className="w-4 h-4" />
                        </button>
                        <button onClick={() => handleReject(req.id)}
                          className="p-2 rounded-lg bg-red-500/10 text-red-400 hover:bg-red-500/20 transition-colors" title="Reject">
                          <X className="w-4 h-4" />
                        </button>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </motion.div>
          )}
        </div>
      </div>
    </section>
  );
}
