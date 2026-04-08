"use client";

import { useState } from "react";
import { motion } from "framer-motion";
import { Play, Trash2, CheckCircle2, Clock, Gift, Loader2 } from "lucide-react";
import { completeLesson } from "@/lib/api";

interface LessonItemProps {
  id: number;
  title: string;
  description: string;
  orderIndex: number;
  durationMinutes: number;
  isFree: boolean;
  videoUrl: string | null;
  canManage: boolean;
  canComplete: boolean;
  index: number;
  onDelete?: (id: number) => void;
  onClick?: (id: number) => void;
  onComplete?: (id: number) => void;
}

export default function LessonItem({
  id,
  title,
  description,
  orderIndex,
  durationMinutes,
  isFree,
  canManage,
  canComplete,
  index,
  onDelete,
  onClick,
  onComplete,
}: LessonItemProps) {
  const [completing, setCompleting] = useState(false);
  const [completed, setCompleted] = useState(false);

  const handleComplete = async (e: React.MouseEvent) => {
    e.stopPropagation();
    if (completing || completed) return;
    setCompleting(true);
    try {
      await completeLesson(id);
      setCompleted(true);
      onComplete?.(id);
    } catch {
      // error silently handled
    } finally {
      setCompleting(false);
    }
  };

  const handleDelete = (e: React.MouseEvent) => {
    e.stopPropagation();
    onDelete?.(id);
  };

  return (
    <motion.div
      initial={{ opacity: 0, x: -16 }}
      animate={{ opacity: 1, x: 0 }}
      transition={{ duration: 0.3, delay: index * 0.05 }}
      onClick={() => onClick?.(id)}
      className="group flex items-center gap-4 p-4 rounded-xl bg-white/5 backdrop-blur-xl border border-white/10 hover:border-[#00d4ff]/20 transition-all cursor-pointer"
    >
      {/* Order number */}
      <div className="flex-shrink-0 w-10 h-10 rounded-lg bg-gradient-to-br from-[#7c3aed]/30 to-[#00d4ff]/20 flex items-center justify-center text-white font-semibold text-sm">
        {orderIndex}
      </div>

      {/* Content */}
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-2">
          <h4 className="text-white font-medium truncate">{title}</h4>
          {isFree && (
            <span className="flex items-center gap-1 text-xs text-emerald-400 bg-emerald-500/15 border border-emerald-500/20 px-2 py-0.5 rounded-full">
              <Gift className="w-3 h-3" />
              Free
            </span>
          )}
          {completed && (
            <CheckCircle2 className="w-4 h-4 text-emerald-400 flex-shrink-0" />
          )}
        </div>
        {description && (
          <p className="text-zinc-500 text-sm truncate mt-0.5">{description}</p>
        )}
      </div>

      {/* Duration */}
      <span className="flex items-center gap-1 text-xs text-zinc-500 flex-shrink-0">
        <Clock className="w-3.5 h-3.5" />
        {durationMinutes}m
      </span>

      {/* Actions */}
      <div className="flex items-center gap-2 flex-shrink-0">
        {canComplete && !completed && (
          <button
            onClick={handleComplete}
            disabled={completing}
            className="p-2 rounded-lg bg-emerald-500/10 border border-emerald-500/20 text-emerald-400 hover:bg-emerald-500/20 transition-colors disabled:opacity-50"
            title="Mark complete"
          >
            {completing ? (
              <Loader2 className="w-4 h-4 animate-spin" />
            ) : (
              <CheckCircle2 className="w-4 h-4" />
            )}
          </button>
        )}
        {onClick && (
          <button
            onClick={(e) => {
              e.stopPropagation();
              onClick(id);
            }}
            className="p-2 rounded-lg bg-[#00d4ff]/10 border border-[#00d4ff]/20 text-[#00d4ff] hover:bg-[#00d4ff]/20 transition-colors"
            title="Play lesson"
          >
            <Play className="w-4 h-4" />
          </button>
        )}
        {canManage && onDelete && (
          <button
            onClick={handleDelete}
            className="p-2 rounded-lg bg-red-500/10 border border-red-500/20 text-red-400 hover:bg-red-500/20 transition-colors"
            title="Delete lesson"
          >
            <Trash2 className="w-4 h-4" />
          </button>
        )}
      </div>
    </motion.div>
  );
}
