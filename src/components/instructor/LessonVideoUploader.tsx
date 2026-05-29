"use client";

import { useRef, useState } from "react";
import { Loader2, Upload, X, Film, Play, Trash2 } from "lucide-react";
import {
  uploadLessonVideoWithProgress, clearLessonVideo, type VideoUploadHandle,
} from "@/lib/api";
import { useToast } from "@/components/ui/Toast";

const MAX_BYTES = 500 * 1024 * 1024;
const ACCEPTED = ["video/mp4", "video/webm", "video/quicktime", "video/x-matroska"];

interface Props {
  lessonId: number;
  lessonTitle: string;
  currentVideoUrl: string | null;
  onChanged: (next: { videoUrl: string | null; durationMinutes?: number | null }) => void;
  onClose: () => void;
}

function formatBytes(n: number) {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  if (n < 1024 * 1024 * 1024) return `${(n / 1024 / 1024).toFixed(1)} MB`;
  return `${(n / 1024 / 1024 / 1024).toFixed(2)} GB`;
}

export function LessonVideoUploader({
  lessonId, lessonTitle, currentVideoUrl, onChanged, onClose,
}: Props) {
  const { toast } = useToast();
  const fileRef = useRef<HTMLInputElement | null>(null);

  const [file, setFile] = useState<File | null>(null);
  const [progress, setProgress] = useState(0);
  const [error, setError] = useState("");
  const [uploadHandle, setUploadHandle] = useState<VideoUploadHandle | null>(null);
  const [showPreview, setShowPreview] = useState(false);
  const [removing, setRemoving] = useState(false);
  const [dragging, setDragging] = useState(false);

  const validateAndStart = (selected: File) => {
    setError("");
    if (!selected.type.startsWith("video/") && !ACCEPTED.includes(selected.type)) {
      setError("Only video files are allowed (MP4, WebM, MOV, MKV).");
      return;
    }
    if (selected.size > MAX_BYTES) {
      setError("File must be 500MB or smaller.");
      return;
    }
    setFile(selected);
    setProgress(0);

    const handle = uploadLessonVideoWithProgress(lessonId, selected, (p) => setProgress(p));
    setUploadHandle(handle);

    handle.promise
      .then((res) => {
        toast("success", "Video uploaded.");
        onChanged({ videoUrl: res.videoUrl, durationMinutes: res.durationMinutes });
        setUploadHandle(null);
        setFile(null);
        setProgress(0);
      })
      .catch((err: Error) => {
        if (err.message === "Upload cancelled") {
          setError("");
        } else {
          setError(err.message || "Upload failed");
        }
        setUploadHandle(null);
      });
  };

  const onFileSelect = (e: React.ChangeEvent<HTMLInputElement>) => {
    const selected = e.target.files?.[0];
    if (selected) validateAndStart(selected);
    if (fileRef.current) fileRef.current.value = "";
  };

  const onDrop = (e: React.DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    setDragging(false);
    const dropped = e.dataTransfer.files?.[0];
    if (dropped) validateAndStart(dropped);
  };

  const cancel = () => {
    uploadHandle?.cancel();
    setUploadHandle(null);
    setFile(null);
    setProgress(0);
  };

  const remove = async () => {
    if (!confirm("Remove the current video? Students will lose access until you upload a new one.")) return;
    setRemoving(true);
    try {
      await clearLessonVideo(lessonId);
      onChanged({ videoUrl: null });
      toast("success", "Video removed.");
    } catch (err) {
      toast("error", err instanceof Error ? err.message : "Couldn't remove video");
    } finally {
      setRemoving(false);
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center px-4 bg-black/40 backdrop-blur-sm"
      onClick={onClose}
    >
      <div
        className="bg-white rounded-2xl shadow-xl w-full max-w-lg p-6"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-start justify-between mb-4">
          <div>
            <h2 className="text-lg font-bold text-gray-900">Upload Video</h2>
            <p className="text-xs text-gray-500 line-clamp-1">{lessonTitle}</p>
          </div>
          <button
            onClick={onClose}
            className="text-gray-400 hover:text-gray-600 cursor-pointer"
            aria-label="Close"
          >
            <X size={18} />
          </button>
        </div>

        {currentVideoUrl && !file && !uploadHandle && (
          <div className="mb-4 p-3 rounded-lg bg-emerald-50 border border-emerald-200 flex items-center gap-3">
            <Film size={18} className="text-emerald-600 shrink-0" />
            <div className="flex-1 min-w-0">
              <p className="text-sm font-semibold text-emerald-800">Video uploaded</p>
              <p className="text-xs text-emerald-700/80 truncate">{currentVideoUrl}</p>
            </div>
            <button
              onClick={() => setShowPreview(true)}
              className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-lg text-xs font-semibold text-emerald-700 bg-emerald-100 hover:bg-emerald-200 transition cursor-pointer"
            >
              <Play size={11} /> Preview
            </button>
            <button
              onClick={remove}
              disabled={removing}
              className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-lg text-xs font-medium text-red-700 bg-red-50 hover:bg-red-100 disabled:opacity-50 transition cursor-pointer"
              aria-label="Remove video"
            >
              {removing ? <Loader2 size={11} className="animate-spin" /> : <Trash2 size={11} />}
            </button>
          </div>
        )}

        {showPreview && currentVideoUrl && (
          <div className="mb-4 rounded-lg overflow-hidden bg-black">
            <video src={currentVideoUrl} controls className="w-full max-h-72" />
            <button
              onClick={() => setShowPreview(false)}
              className="block w-full py-1.5 text-xs text-gray-500 bg-gray-50 hover:bg-gray-100"
            >
              Close preview
            </button>
          </div>
        )}

        {uploadHandle && file ? (
          <div className="space-y-3">
            <div className="flex items-center gap-3">
              <Film size={18} className="text-sage-navy" />
              <div className="flex-1 min-w-0">
                <p className="text-sm font-medium text-gray-900 truncate">{file.name}</p>
                <p className="text-xs text-gray-500">{formatBytes(file.size)}</p>
              </div>
            </div>
            <div>
              <div className="flex items-center justify-between mb-1.5 text-xs">
                <span className="text-gray-600">
                  {progress < 100 ? "Uploading…" : "Finalizing…"}
                </span>
                <span className="font-semibold text-sage-navy tabular-nums">{progress}%</span>
              </div>
              <div className="h-2 bg-gray-100 rounded-full overflow-hidden">
                <div
                  className="h-full bg-gradient-to-r from-sage-navy to-sage-navy-deep transition-all"
                  style={{ width: `${progress}%` }}
                />
              </div>
            </div>
            <button
              onClick={cancel}
              className="w-full px-4 py-2 rounded-lg text-sm font-medium text-red-700 border border-red-200 hover:bg-red-50 transition cursor-pointer"
            >
              Cancel Upload
            </button>
          </div>
        ) : (
          <div
            onDragOver={(e) => { e.preventDefault(); setDragging(true); }}
            onDragLeave={() => setDragging(false)}
            onDrop={onDrop}
            onClick={() => fileRef.current?.click()}
            role="button"
            tabIndex={0}
            className={`flex flex-col items-center justify-center gap-2 px-6 py-10 rounded-lg border-2 border-dashed cursor-pointer transition ${
              dragging
                ? "border-sage-navy bg-sage-navy/10"
                : "border-gray-300 hover:border-sage-navy/50 hover:bg-sage-navy/5"
            }`}
          >
            <Upload size={28} className="text-gray-400" />
            <p className="text-sm font-medium text-gray-700">
              {currentVideoUrl ? "Click or drop to replace video" : "Click to select video or drag and drop"}
            </p>
            <p className="text-[11px] text-gray-400">MP4, WebM, MOV, MKV — up to 500MB</p>
          </div>
        )}

        {error && (
          <p className="text-xs text-red-700 mt-3">{error}</p>
        )}

        <input
          ref={fileRef}
          type="file"
          accept="video/*"
          onChange={onFileSelect}
          className="hidden"
        />
      </div>
    </div>
  );
}
