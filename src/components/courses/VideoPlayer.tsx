"use client";

import { motion } from "framer-motion";
import { Lock, Play } from "lucide-react";

interface VideoPlayerProps {
  videoUrl: string | null;
  title: string;
  isFree: boolean;
}

export default function VideoPlayer({ videoUrl, title, isFree }: VideoPlayerProps) {
  if (!videoUrl) {
    return (
      <motion.div
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        className="relative w-full aspect-video rounded-2xl bg-white/60 backdrop-blur-xl border border-zinc-200 flex flex-col items-center justify-center gap-3"
      >
        {!isFree ? (
          <>
            <Lock className="w-12 h-12 text-zinc-600" />
            <p className="text-zinc-600 text-sm">Enroll to unlock this video</p>
          </>
        ) : (
          <>
            <Play className="w-12 h-12 text-zinc-600" />
            <p className="text-zinc-600 text-sm">No video available yet</p>
          </>
        )}
      </motion.div>
    );
  }

  return (
    <motion.div
      initial={{ opacity: 0, y: 16 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.4 }}
      className="relative w-full aspect-video rounded-2xl overflow-hidden bg-black border border-zinc-200"
    >
      <video
        src={videoUrl}
        title={title}
        controls
        controlsList="nodownload"
        onContextMenu={(e) => e.preventDefault()}
        className="w-full h-full object-contain bg-black"
      >
        <track kind="captions" />
        Your browser does not support the video tag.
      </video>
    </motion.div>
  );
}
