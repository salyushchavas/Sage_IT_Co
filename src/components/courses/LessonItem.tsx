"use client";

import { useState } from "react";
import { motion } from "framer-motion";
import { Play, Lock, Trash2, CheckCircle, Loader2, Circle } from "lucide-react";
import { completeLesson } from "@/lib/api";
import { cn } from "@/lib/utils";

interface LessonItemProps {
  id: number;
  title: string;
  description?: string | null;
  orderIndex: number;
  durationMinutes?: number | null;
  isFree: boolean;
  videoUrl?: string | null;
  canManage?: boolean;
  canComplete?: boolean;  // student is enrolled
  /** Controlled completion state from server. If unset, falls back to local state. */
  completed?: boolean;
  /** First uncompleted lesson — gets a play icon and accent border. */
  isCurrent?: boolean;
  /**
   * The viewer is browsing as a non-enrolled, non-admin visitor.
   * In that case we hide all action affordances (FREE badge, play
   * icon, Complete button) and show a lock — they need to enroll
   * before any interaction.
   */
  lockedForVisitor?: boolean;
  index?: number;
  onDelete?: (id: number) => void;
  onComplete?: () => void;  // callback after completion
  onClick?: () => void;
}

export function LessonItem({
  id,
  title,
  orderIndex,
  durationMinutes,
  isFree,
  videoUrl,
  canManage = false,
  canComplete = false,
  completed: completedProp,
  isCurrent = false,
  lockedForVisitor = false,
  index = 0,
  onDelete,
  onComplete,
  onClick,
}: LessonItemProps) {
  // For non-enrolled, non-admin visitors: every lesson is locked,
  // regardless of free-preview flags. Enrollment is the gate.
  const hasAccess = !lockedForVisitor && (isFree || !!videoUrl);
  const [completing, setCompleting] = useState(false);
  const [optimisticDone, setOptimisticDone] = useState(false);
  const completed = completedProp ?? optimisticDone;

  const handleComplete = async (e: React.MouseEvent) => {
    e.stopPropagation();
    setCompleting(true);
    try {
      await completeLesson(id);
      setOptimisticDone(true);
      onComplete?.();
    } catch {
      // silently fail — user may not be enrolled
    } finally {
      setCompleting(false);
    }
  };

  return (
    <motion.div
      initial={{ opacity: 0, x: -10 }}
      animate={{ opacity: 1, x: 0 }}
      transition={{ delay: index * 0.05, duration: 0.3 }}
      whileHover={{ scale: hasAccess ? 1.01 : 1, transition: { duration: 0.15 } }}
      onClick={hasAccess ? onClick : undefined}
      className={cn(
        "flex items-center gap-4 p-4 rounded-xl border transition-all",
        completed ? "bg-teal-50/50 border-teal-200" :
        isCurrent && hasAccess ? "bg-white border-l-4 border-l-sage-navy border-y-gray-200 border-r-gray-200 hover:shadow-md cursor-pointer" :
        hasAccess ? "bg-white border-gray-200 hover:border-teal-300 hover:shadow-md cursor-pointer" :
        "bg-gray-50 border-gray-100 cursor-default"
      )}
    >
      {/* Status indicator: completed -> check, current -> play, otherwise empty circle */}
      <div className={cn(
        "w-9 h-9 rounded-lg flex items-center justify-center text-sm font-bold flex-shrink-0",
        completed ? "bg-teal-200 text-teal-700" :
        isCurrent && hasAccess ? "bg-sage-navy text-white" :
        hasAccess ? "bg-teal-100 text-teal-700" : "bg-gray-200 text-gray-400"
      )}>
        {completed ? <CheckCircle size={18} /> :
         isCurrent && hasAccess ? <Play size={16} className="ml-0.5" /> :
         hasAccess ? orderIndex : <Circle size={16} />}
      </div>

      {/* Lesson info */}
      <div className="flex-1 min-w-0">
        <p className={cn("font-medium text-sm", hasAccess ? "text-gray-900" : "text-gray-500")}>
          {title}
        </p>
        {durationMinutes && (
          <p className="text-xs text-gray-400 mt-0.5">{durationMinutes} min</p>
        )}
      </div>

      {/* Actions */}
      <div className="flex items-center gap-2 flex-shrink-0">
        {/* The FREE badge was removed — there is no real free-preview
            system on the platform yet (the `isFree` flag was a holdover
            from seed data and didn't actually let visitors watch).
            Showing "FREE" on a lesson inside a paid course was just
            misleading, so the badge is gone until a proper preview
            flow exists. */}

        {completed && (
          <span className="text-[10px] font-semibold px-2 py-0.5 rounded-full bg-teal-100 text-teal-600">DONE</span>
        )}

        {/* Mark Complete button (for enrolled students with access) */}
        {hasAccess && canComplete && !completed && !canManage && (
          <button onClick={handleComplete} disabled={completing}
            className="text-[10px] font-semibold px-2.5 py-1 rounded-full bg-sage-navy text-white hover:bg-sage-navy-deep transition disabled:opacity-50 flex items-center gap-1">
            {completing ? <Loader2 size={10} className="animate-spin" /> : <CheckCircle size={10} />}
            {completing ? "..." : "Complete"}
          </button>
        )}

        {/* Visitor or genuinely-locked premium lesson — show the lock. */}
        {(!hasAccess || lockedForVisitor) && !completed && (
          <Lock size={16} className="text-gray-300" />
        )}

        {hasAccess && !completed && !canComplete && (
          <Play size={16} className="text-teal-600" />
        )}

        {canManage && onDelete && (
          <button onClick={(e) => { e.stopPropagation(); onDelete(id); }}
            className="text-gray-300 hover:text-red-500 transition ml-1">
            <Trash2 size={14} />
          </button>
        )}
      </div>
    </motion.div>
  );
}
