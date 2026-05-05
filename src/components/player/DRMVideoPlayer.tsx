"use client";

import { useRef, useState, useEffect, useCallback } from "react";
import { cn } from "@/lib/utils";
import {
  Play,
  Pause,
  Volume2,
  VolumeX,
  Maximize,
  Minimize,
} from "lucide-react";
import ProtectedOverlay from "./ProtectedOverlay";

interface DRMVideoPlayerProps {
  src: string;
  token?: string;
  userEmail?: string;
  onProgress?: (percent: number) => void;
  className?: string;
}

export default function DRMVideoPlayer({
  src,
  token,
  userEmail,
  onProgress,
  className,
}: DRMVideoPlayerProps) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const [playing, setPlaying] = useState(false);
  const [currentTime, setCurrentTime] = useState(0);
  const [duration, setDuration] = useState(0);
  const [volume, setVolume] = useState(1);
  const [muted, setMuted] = useState(false);
  const [isFullscreen, setIsFullscreen] = useState(false);
  const [showControls, setShowControls] = useState(true);
  const hideTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  // DevTools detection
  useEffect(() => {
    const threshold = 160;
    const check = () => {
      const widthThreshold =
        window.outerWidth - window.innerWidth > threshold;
      const heightThreshold =
        window.outerHeight - window.innerHeight > threshold;
      if (widthThreshold || heightThreshold) {
        if (videoRef.current && !videoRef.current.paused) {
          videoRef.current.pause();
          setPlaying(false);
        }
      }
    };
    const interval = setInterval(check, 1000);
    return () => clearInterval(interval);
  }, []);

  // Disable right-click on video
  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    const handler = (e: Event) => e.preventDefault();
    el.addEventListener("contextmenu", handler);
    return () => el.removeEventListener("contextmenu", handler);
  }, []);

  const togglePlay = useCallback(() => {
    const v = videoRef.current;
    if (!v) return;
    if (v.paused) {
      v.play();
      setPlaying(true);
    } else {
      v.pause();
      setPlaying(false);
    }
  }, []);

  const toggleMute = () => {
    const v = videoRef.current;
    if (!v) return;
    v.muted = !v.muted;
    setMuted(v.muted);
  };

  const handleVolumeChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const v = videoRef.current;
    if (!v) return;
    const val = parseFloat(e.target.value);
    v.volume = val;
    setVolume(val);
    if (val === 0) {
      v.muted = true;
      setMuted(true);
    } else if (muted) {
      v.muted = false;
      setMuted(false);
    }
  };

  const handleSeek = (e: React.ChangeEvent<HTMLInputElement>) => {
    const v = videoRef.current;
    if (!v) return;
    v.currentTime = parseFloat(e.target.value);
  };

  const toggleFullscreen = () => {
    const el = containerRef.current;
    if (!el) return;
    if (!document.fullscreenElement) {
      el.requestFullscreen();
      setIsFullscreen(true);
    } else {
      document.exitFullscreen();
      setIsFullscreen(false);
    }
  };

  const handleTimeUpdate = () => {
    const v = videoRef.current;
    if (!v) return;
    setCurrentTime(v.currentTime);
    if (v.duration && onProgress) {
      onProgress((v.currentTime / v.duration) * 100);
    }
  };

  const handleMouseMove = () => {
    setShowControls(true);
    if (hideTimer.current) clearTimeout(hideTimer.current);
    hideTimer.current = setTimeout(() => {
      if (playing) setShowControls(false);
    }, 3000);
  };

  const formatTime = (s: number) => {
    const m = Math.floor(s / 60);
    const sec = Math.floor(s % 60);
    return `${m}:${sec.toString().padStart(2, "0")}`;
  };

  const videoSrc = token ? `${src}?token=${encodeURIComponent(token)}` : src;
  const progress = duration > 0 ? (currentTime / duration) * 100 : 0;

  return (
    <div
      ref={containerRef}
      className={cn(
        "relative bg-black rounded-xl overflow-hidden group select-none",
        className
      )}
      onMouseMove={handleMouseMove}
      onMouseLeave={() => playing && setShowControls(false)}
    >
      <video
        ref={videoRef}
        src={videoSrc}
        className="w-full h-full object-contain"
        onTimeUpdate={handleTimeUpdate}
        onLoadedMetadata={() => {
          if (videoRef.current) setDuration(videoRef.current.duration);
        }}
        onEnded={() => setPlaying(false)}
        onClick={togglePlay}
        playsInline
        controlsList="nodownload"
        disablePictureInPicture
      />

      {/* Watermark */}
      {userEmail && <ProtectedOverlay email={userEmail} />}

      {/* Center play button when paused */}
      {!playing && (
        <button
          onClick={togglePlay}
          className="absolute inset-0 flex items-center justify-center z-10"
        >
          <div className="w-16 h-16 rounded-full bg-white/10 backdrop-blur-xl border border-white/20 flex items-center justify-center">
            <Play className="w-7 h-7 text-white ml-1" />
          </div>
        </button>
      )}

      {/* Controls bar */}
      <div
        className={cn(
          "absolute bottom-0 left-0 right-0 bg-gradient-to-t from-black/80 to-transparent p-4 pt-10 transition-opacity duration-300 z-20",
          showControls ? "opacity-100" : "opacity-0 pointer-events-none"
        )}
      >
        {/* Progress bar */}
        <input
          type="range"
          min={0}
          max={duration || 0}
          step={0.1}
          value={currentTime}
          onChange={handleSeek}
          className="w-full h-1 mb-3 appearance-none bg-white/20 rounded-full cursor-pointer [&::-webkit-slider-thumb]:appearance-none [&::-webkit-slider-thumb]:w-3 [&::-webkit-slider-thumb]:h-3 [&::-webkit-slider-thumb]:rounded-full [&::-webkit-slider-thumb]:bg-[#22C55E]"
          style={{
            background: `linear-gradient(to right, #22C55E ${progress}%, rgba(255,255,255,0.2) ${progress}%)`,
          }}
        />

        <div className="flex items-center gap-3">
          {/* Play/Pause */}
          <button onClick={togglePlay} className="text-white hover:text-[#22C55E] transition">
            {playing ? <Pause className="w-5 h-5" /> : <Play className="w-5 h-5" />}
          </button>

          {/* Time */}
          <span className="text-xs text-zinc-300 font-mono min-w-[80px]">
            {formatTime(currentTime)} / {formatTime(duration)}
          </span>

          <div className="flex-1" />

          {/* Volume */}
          <button onClick={toggleMute} className="text-white hover:text-[#22C55E] transition">
            {muted || volume === 0 ? (
              <VolumeX className="w-5 h-5" />
            ) : (
              <Volume2 className="w-5 h-5" />
            )}
          </button>
          <input
            type="range"
            min={0}
            max={1}
            step={0.05}
            value={muted ? 0 : volume}
            onChange={handleVolumeChange}
            className="w-20 h-1 appearance-none bg-white/20 rounded-full cursor-pointer [&::-webkit-slider-thumb]:appearance-none [&::-webkit-slider-thumb]:w-2.5 [&::-webkit-slider-thumb]:h-2.5 [&::-webkit-slider-thumb]:rounded-full [&::-webkit-slider-thumb]:bg-white"
          />

          {/* Fullscreen */}
          <button onClick={toggleFullscreen} className="text-white hover:text-[#22C55E] transition">
            {isFullscreen ? <Minimize className="w-5 h-5" /> : <Maximize className="w-5 h-5" />}
          </button>
        </div>
      </div>
    </div>
  );
}
