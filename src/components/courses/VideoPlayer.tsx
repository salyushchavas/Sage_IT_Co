"use client";

import { motion } from "framer-motion";
import { Play, Lock } from "lucide-react";

interface VideoPlayerProps {
  videoUrl?: string | null;
  title: string;
  isFree: boolean;
}

export function VideoPlayer({ videoUrl, title, isFree }: VideoPlayerProps) {
  if (!videoUrl) {
    return (
      <div className="aspect-video bg-gray-100 rounded-xl flex flex-col items-center justify-center text-gray-400">
        {isFree ? (
          <>
            <Play size={32} className="mb-2" />
            <p className="text-sm">No video uploaded yet</p>
          </>
        ) : (
          <>
            <Lock size={32} className="mb-2" />
            <p className="text-sm">Enroll to access this video</p>
          </>
        )}
      </div>
    );
  }

  return (
    <motion.div
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      className="rounded-xl overflow-hidden bg-black"
    >
      <video
        src={videoUrl}
        controls
        controlsList="nodownload"
        onContextMenu={(e) => e.preventDefault()}
        className="w-full aspect-video"
        playsInline
      >
        <track kind="captions" />
        Your browser does not support the video tag.
      </video>
      <div className="bg-gray-900 px-4 py-2">
        <p className="text-white text-sm font-medium truncate">{title}</p>
      </div>
    </motion.div>
  );
}
