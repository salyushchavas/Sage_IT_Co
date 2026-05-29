"use client";

import { useEffect, useState } from "react";
import { Loader2, Plus, Trash2, BookOpen } from "lucide-react";

import {
  addMentorToCourse,
  getAdminCourses,
  getCourseMentors,
  getUsers,
  removeMentorFromCourse,
  type CourseMentor,
} from "@/lib/api";

interface CourseRow {
  id: number;
  title: string;
}
interface UserRow {
  id: number;
  fullName: string;
  email: string;
  role: string;
}

export function AdminMentorPoolsTab() {
  const [courses, setCourses] = useState<CourseRow[]>([]);
  const [users, setUsers] = useState<UserRow[]>([]);
  const [selectedCourse, setSelectedCourse] = useState<number | null>(null);
  const [mentors, setMentors] = useState<CourseMentor[]>([]);
  const [loadingMentors, setLoadingMentors] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [newMentorId, setNewMentorId] = useState<string>("");

  useEffect(() => {
    let cancelled = false;
    Promise.all([getAdminCourses(), getUsers("all")])
      .then(([c, u]) => {
        if (cancelled) return;
        setCourses((c as CourseRow[]) ?? []);
        setUsers((u as UserRow[]) ?? []);
      })
      .catch((e) => { if (!cancelled) setError(e instanceof Error ? e.message : "Couldn't load"); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, []);

  useEffect(() => {
    if (!selectedCourse) { setMentors([]); return; }
    let cancelled = false;
    setLoadingMentors(true);
    getCourseMentors(selectedCourse)
      .then((m) => { if (!cancelled) setMentors(m ?? []); })
      .catch((e) => { if (!cancelled) setError(e instanceof Error ? e.message : "Couldn't load mentors"); })
      .finally(() => { if (!cancelled) setLoadingMentors(false); });
    return () => { cancelled = true; };
  }, [selectedCourse]);

  const addMentor = async () => {
    if (!selectedCourse || !newMentorId) return;
    try {
      const m = await addMentorToCourse(selectedCourse, Number(newMentorId));
      setMentors((prev) => [...prev, m]);
      setNewMentorId("");
    } catch (e) {
      alert(e instanceof Error ? e.message : "Couldn't add mentor");
    }
  };

  const removeMentor = async (userId: number) => {
    if (!selectedCourse) return;
    if (!window.confirm("Remove this mentor from the course?")) return;
    try {
      await removeMentorFromCourse(selectedCourse, userId);
      setMentors((prev) => prev.filter((m) => m.mentorId !== userId));
    } catch (e) {
      alert(e instanceof Error ? e.message : "Couldn't remove");
    }
  };

  const mentorCandidates = users.filter((u) =>
    ["INSTRUCTOR", "COACH", "TECHNICAL_ADVISOR"].includes(u.role.toUpperCase())
    && !mentors.some((m) => m.mentorId === u.id),
  );

  return (
    <div>
      <h1 className="text-2xl font-bold text-zinc-900 mb-5">Mentor Pools</h1>

      {error && (
        <div className="mb-4 px-4 py-2.5 rounded-lg bg-red-50 border border-red-200 text-red-700 text-sm">{error}</div>
      )}

      {loading ? (
        <div className="text-center py-10"><Loader2 size={20} className="animate-spin text-sage-navy inline" /></div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-[260px_minmax(0,1fr)] gap-6">
          <aside className="space-y-1">
            <h2 className="text-xs uppercase tracking-wider font-semibold text-zinc-500 mb-2">Courses</h2>
            {courses.length === 0 ? (
              <p className="text-xs text-zinc-400 italic">No courses.</p>
            ) : (
              courses.map((c) => (
                <button
                  key={c.id}
                  onClick={() => setSelectedCourse(c.id)}
                  className={
                    "w-full text-left px-3 py-2 rounded-lg text-sm transition cursor-pointer flex items-center gap-2 " +
                    (selectedCourse === c.id
                      ? "bg-sage-navy text-white"
                      : "hover:bg-zinc-100 text-zinc-700")
                  }
                >
                  <BookOpen size={13} />
                  <span className="truncate">{c.title}</span>
                </button>
              ))
            )}
          </aside>

          <section>
            {!selectedCourse ? (
              <p className="text-zinc-400 italic">Pick a course to manage its mentor pool.</p>
            ) : (
              <>
                <div className="flex items-end gap-2 mb-4 flex-wrap">
                  <div className="flex-1 min-w-[200px]">
                    <label className="block text-xs font-semibold text-zinc-600 mb-1">Add mentor</label>
                    <select
                      value={newMentorId}
                      onChange={(e) => setNewMentorId(e.target.value)}
                      className="w-full px-3 py-2 text-sm rounded-md border border-zinc-200"
                    >
                      <option value="">-- Pick instructor / coach / advisor --</option>
                      {mentorCandidates.map((u) => (
                        <option key={u.id} value={u.id}>
                          {u.fullName} ({u.role}) · {u.email}
                        </option>
                      ))}
                    </select>
                  </div>
                  <button
                    onClick={addMentor}
                    disabled={!newMentorId}
                    className="inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-50 cursor-pointer"
                  >
                    <Plus size={12} /> Add
                  </button>
                </div>

                {loadingMentors ? (
                  <div className="text-center py-6"><Loader2 size={18} className="animate-spin text-sage-navy inline" /></div>
                ) : (
                  <div className="bg-white/60 backdrop-blur-xl border border-zinc-200 rounded-2xl overflow-hidden">
                    <table className="w-full text-sm">
                      <thead>
                        <tr className="border-b border-zinc-200 text-zinc-600">
                          <th className="text-left px-4 py-3 font-medium">Mentor</th>
                          <th className="text-left px-4 py-3 font-medium">Active students</th>
                          <th className="text-left px-4 py-3 font-medium">Max</th>
                          <th className="text-left px-4 py-3 font-medium">Status</th>
                          <th className="text-right px-4 py-3 font-medium">Actions</th>
                        </tr>
                      </thead>
                      <tbody>
                        {mentors.length === 0 ? (
                          <tr><td colSpan={5} className="px-4 py-6 text-center text-zinc-400 italic">No mentors assigned.</td></tr>
                        ) : (
                          mentors.map((m) => (
                            <tr key={m.id} className="border-b border-zinc-100">
                              <td className="px-4 py-3">
                                <div className="font-medium text-zinc-900">{m.mentorName}</div>
                                <div className="text-xs text-zinc-500">{m.mentorEmail}</div>
                              </td>
                              <td className="px-4 py-3 tabular-nums">{m.activeStudentCount}</td>
                              <td className="px-4 py-3 tabular-nums text-zinc-500">{m.maxStudents}</td>
                              <td className="px-4 py-3">
                                <span className={"text-[10px] px-2 py-0.5 rounded-full font-semibold " + (m.isActive ? "bg-emerald-100 text-emerald-700" : "bg-zinc-100 text-zinc-500")}>
                                  {m.isActive ? "ACTIVE" : "INACTIVE"}
                                </span>
                              </td>
                              <td className="px-4 py-3 text-right">
                                <button onClick={() => removeMentor(m.mentorId)} className="p-1.5 rounded hover:bg-red-50 text-zinc-500 hover:text-red-700 transition" title="Remove">
                                  <Trash2 size={13} />
                                </button>
                              </td>
                            </tr>
                          ))
                        )}
                      </tbody>
                    </table>
                  </div>
                )}
              </>
            )}
          </section>
        </div>
      )}
    </div>
  );
}
