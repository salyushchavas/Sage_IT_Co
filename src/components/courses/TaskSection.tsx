"use client";

import { useState, useEffect, useCallback } from "react";
import { motion } from "framer-motion";
import {
  Code2,
  Search,
  MessageSquare,
  Flame,
  Loader2,
  CheckCircle2,
  ListTodo,
  Lock,
} from "lucide-react";
import { getLessonTasks, completeTask } from "@/lib/api";

interface Task {
  id: number;
  title: string;
  description: string;
  instruction: string;
  type: string;
  orderIndex: number;
  unlocked: boolean;
  completed: boolean;
}

interface TaskSectionProps {
  lessonId: number;
  isAuthenticated: boolean;
}

const typeConfig: Record<string, { icon: typeof Code2; color: string; bg: string }> = {
  PRACTICE: { icon: Code2, color: "text-cyan-400", bg: "bg-cyan-500/15 border-cyan-500/20" },
  RESEARCH: { icon: Search, color: "text-amber-400", bg: "bg-amber-500/15 border-amber-500/20" },
  REFLECTION: {
    icon: MessageSquare,
    color: "text-violet-400",
    bg: "bg-violet-500/15 border-violet-500/20",
  },
  CHALLENGE: { icon: Flame, color: "text-red-400", bg: "bg-red-500/15 border-red-500/20" },
};

export default function TaskSection({ lessonId, isAuthenticated }: TaskSectionProps) {
  const [tasks, setTasks] = useState<Task[]>([]);
  const [loading, setLoading] = useState(false);
  const [completingId, setCompletingId] = useState<number | null>(null);

  const fetchTasks = useCallback(async () => {
    if (!isAuthenticated) return;
    setLoading(true);
    try {
      const data = await getLessonTasks(lessonId);
      setTasks(data ?? []);
    } catch {
      // no tasks
    } finally {
      setLoading(false);
    }
  }, [lessonId, isAuthenticated]);

  useEffect(() => {
    fetchTasks();
  }, [fetchTasks]);

  const handleComplete = async (taskId: number) => {
    setCompletingId(taskId);
    try {
      await completeTask(taskId);
      setTasks((prev) =>
        prev.map((t) => (t.id === taskId ? { ...t, completed: true } : t))
      );
    } catch {
      // silent
    } finally {
      setCompletingId(null);
    }
  };

  if (!isAuthenticated) return null;
  if (loading) {
    return (
      <div className="flex items-center justify-center py-8">
        <Loader2 className="w-6 h-6 text-[#22C55E] animate-spin" />
      </div>
    );
  }
  if (tasks.length === 0) return null;

  return (
    <motion.div
      initial={{ opacity: 0, y: 16 }}
      animate={{ opacity: 1, y: 0 }}
      className="bg-white/60 backdrop-blur-xl border border-zinc-200 rounded-2xl p-6 space-y-4"
    >
      <div className="flex items-center gap-2">
        <ListTodo className="w-5 h-5 text-[#14653F]" />
        <h3 className="text-zinc-900 font-semibold">Tasks</h3>
        <span className="text-xs text-zinc-500">
          {tasks.filter((t) => t.completed).length}/{tasks.length} completed
        </span>
      </div>

      <div className="space-y-3">
        {tasks.map((task, i) => {
          const cfg = typeConfig[task.type] ?? typeConfig.PRACTICE;
          const Icon = cfg.icon;

          return (
            <motion.div
              key={task.id}
              initial={{ opacity: 0, x: -12 }}
              animate={{ opacity: 1, x: 0 }}
              transition={{ delay: i * 0.05 }}
              className={`flex items-start gap-4 p-4 rounded-xl border transition-all ${
                task.completed
                  ? "bg-emerald-500/5 border-emerald-500/10"
                  : task.unlocked
                  ? "bg-white/[0.03] border-zinc-200 hover:border-zinc-200"
                  : "bg-white/[0.02] border-zinc-200 opacity-60"
              }`}
            >
              {/* Type icon */}
              <div
                className={`flex-shrink-0 w-9 h-9 rounded-lg border flex items-center justify-center ${cfg.bg}`}
              >
                <Icon className={`w-4 h-4 ${cfg.color}`} />
              </div>

              {/* Content */}
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2">
                  <h4 className="text-zinc-900 text-sm font-medium">{task.title}</h4>
                  <span
                    className={`text-[10px] uppercase tracking-wider font-semibold px-2 py-0.5 rounded-full border ${cfg.bg} ${cfg.color}`}
                  >
                    {task.type}
                  </span>
                </div>
                <p className="text-zinc-500 text-sm mt-1">{task.description}</p>
                {task.instruction && task.unlocked && (
                  <p className="text-zinc-600 text-xs mt-2 italic">{task.instruction}</p>
                )}
              </div>

              {/* Action */}
              <div className="flex-shrink-0">
                {task.completed ? (
                  <CheckCircle2 className="w-5 h-5 text-emerald-400" />
                ) : !task.unlocked ? (
                  <Lock className="w-5 h-5 text-zinc-600" />
                ) : (
                  <button
                    onClick={() => handleComplete(task.id)}
                    disabled={completingId === task.id}
                    className="px-3 py-1.5 rounded-lg bg-[#22C55E]/10 border border-[#22C55E]/20 text-[#22C55E] text-xs font-medium hover:bg-[#22C55E]/20 transition-colors disabled:opacity-50"
                  >
                    {completingId === task.id ? (
                      <Loader2 className="w-3.5 h-3.5 animate-spin" />
                    ) : (
                      "Complete"
                    )}
                  </button>
                )}
              </div>
            </motion.div>
          );
        })}
      </div>
    </motion.div>
  );
}
