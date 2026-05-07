"use client";

import { useState, useRef } from "react";
import { motion } from "framer-motion";
import { Upload, Film, X, Loader2, CheckCircle2 } from "lucide-react";
import { uploadLessonVideo } from "@/lib/api";

interface VideoUploadProps {
  lessonId: number;
  currentVideoUrl: string | null;
  onUploadComplete: (videoUrl: string) => void;
}

const MAX_SIZE_MB = 500;
const MAX_SIZE_BYTES = MAX_SIZE_MB * 1024 * 1024;

export default function VideoUpload({
  lessonId,
  currentVideoUrl,
  onUploadComplete,
}: VideoUploadProps) {
  const [file, setFile] = useState<File | null>(null);
  const [preview, setPreview] = useState<string | null>(currentVideoUrl);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    setError(null);
    setSuccess(false);
    const selected = e.target.files?.[0];
    if (!selected) return;

    if (selected.size > MAX_SIZE_BYTES) {
      setError(`File exceeds ${MAX_SIZE_MB}MB limit`);
      return;
    }

    if (!selected.type.startsWith("video/")) {
      setError("Please select a video file");
      return;
    }

    setFile(selected);
    setPreview(URL.createObjectURL(selected));
  };

  const handleUpload = async () => {
    if (!file) return;
    setUploading(true);
    setError(null);
    try {
      const result = await uploadLessonVideo(lessonId, file);
      setSuccess(true);
      setFile(null);
      onUploadComplete(result.videoUrl);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Upload failed");
    } finally {
      setUploading(false);
    }
  };

  const clearFile = () => {
    setFile(null);
    setPreview(currentVideoUrl);
    setError(null);
    setSuccess(false);
    if (inputRef.current) inputRef.current.value = "";
  };

  return (
    <motion.div
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      className="bg-white/60 backdrop-blur-xl border border-zinc-200 rounded-2xl p-6 space-y-4"
    >
      <h3 className="text-zinc-900 font-semibold flex items-center gap-2">
        <Film className="w-5 h-5 text-[#1B2A5C]" />
        Video Upload
      </h3>

      {/* Preview */}
      {preview && (
        <div className="relative aspect-video rounded-xl overflow-hidden bg-black border border-zinc-200">
          <video
            src={preview}
            controls
            controlsList="nodownload"
            onContextMenu={(e) => e.preventDefault()}
            className="w-full h-full object-contain"
          >
            <track kind="captions" />
          </video>
        </div>
      )}

      {/* File input area */}
      <div
        onClick={() => inputRef.current?.click()}
        className="border-2 border-dashed border-zinc-200 rounded-xl p-8 text-center cursor-pointer hover:border-[#1B2A5C]/30 transition-colors"
      >
        <Upload className="w-8 h-8 text-zinc-500 mx-auto mb-2" />
        <p className="text-zinc-600 text-sm">
          {file ? file.name : "Click to select a video file"}
        </p>
        <p className="text-zinc-600 text-xs mt-1">Max {MAX_SIZE_MB}MB</p>
        <input
          ref={inputRef}
          type="file"
          accept="video/*"
          onChange={handleFileChange}
          className="hidden"
        />
      </div>

      {error && <p className="text-red-400 text-sm">{error}</p>}
      {success && (
        <p className="text-emerald-400 text-sm flex items-center gap-1">
          <CheckCircle2 className="w-4 h-4" /> Uploaded successfully
        </p>
      )}

      {/* Actions */}
      <div className="flex items-center gap-3">
        <button
          onClick={handleUpload}
          disabled={!file || uploading}
          className="px-5 py-2.5 rounded-xl btn-fill text-white font-medium text-sm hover:opacity-90 transition-opacity disabled:opacity-40 disabled:cursor-not-allowed flex items-center gap-2"
        >
          {uploading ? (
            <>
              <Loader2 className="w-4 h-4 animate-spin" />
              Uploading...
            </>
          ) : (
            <>
              <Upload className="w-4 h-4" />
              Upload
            </>
          )}
        </button>
        {file && (
          <button
            onClick={clearFile}
            className="p-2.5 rounded-xl bg-white/60 border border-zinc-200 text-zinc-600 hover:text-zinc-900 transition-colors"
          >
            <X className="w-4 h-4" />
          </button>
        )}
      </div>
    </motion.div>
  );
}
