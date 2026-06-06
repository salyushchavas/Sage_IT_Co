"use client";

import { useEffect, useRef, useState } from "react";
import { AlertCircle, PenLine, Upload as UploadIcon, X } from "lucide-react";
import SignatureCanvas from "react-signature-canvas";

const MAX_SIGNATURE_BYTES = 2 * 1024 * 1024;

interface Props {
  /** Notified whenever the active signature data URL changes. Pass
   *  null when the pad is cleared / file removed. */
  onChange: (dataUrl: string | null) => void;
  /** Optional disabled flag -- ignores draw + upload while a parent
   *  flow is in progress (e.g., approve-and-sign submitting). */
  disabled?: boolean;
  /** Canvas height in pixels. Defaults to 130. */
  height?: number;
  /** Unique id for the file-input -- only needed if two pads can
   *  render on the same page (the approve-and-sign modal vs the
   *  consultant sign page never coexist, but better safe). */
  fileInputId?: string;
}

/**
 * Draw-or-upload signature pad. Lifted out of /consultant/[appId]/sign
 * so the ERM approve-and-sign modal can reuse the same canvas + upload
 * UX rather than duplicating ~100 lines of JSX. Always emits a base64
 * data URL via {@link Props#onChange}; the parent decides what to do
 * with it (POST it, embed in a preview, etc.).
 *
 * react-signature-canvas requires DOM measurements before it can render,
 * so the canvas is gated behind a "mounted" effect to avoid the
 * server-render / hydration mismatch.
 */
export default function SignaturePad({
  onChange,
  disabled = false,
  height = 130,
  fileInputId = "sage-signature-upload",
}: Props) {
  const [method, setMethod] = useState<"draw" | "upload">("draw");
  const [data, setData] = useState<string | null>(null);
  const [error, setError] = useState("");
  const [mounted, setMounted] = useState(false);
  const sigRef = useRef<SignatureCanvas | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    setMounted(true);
  }, []);

  const emit = (next: string | null) => {
    setData(next);
    onChange(next);
  };

  const switchMethod = (next: "draw" | "upload") => {
    if (disabled) return;
    setMethod(next);
    setError("");
    sigRef.current?.clear();
    if (fileInputRef.current) fileInputRef.current.value = "";
    emit(null);
  };

  const handleDrawEnd = () => {
    const pad = sigRef.current;
    if (!pad || pad.isEmpty()) {
      emit(null);
      return;
    }
    emit(pad.toDataURL("image/png"));
    setError("");
  };

  const handleClearDrawn = () => {
    sigRef.current?.clear();
    emit(null);
  };

  const handleFile = (file: File | null | undefined) => {
    if (!file) return;
    if (!/^image\/(png|jpe?g)$/i.test(file.type)) {
      setError("Use a PNG or JPG file.");
      return;
    }
    if (file.size > MAX_SIGNATURE_BYTES) {
      setError("File too large (max 2 MB).");
      return;
    }
    const reader = new FileReader();
    reader.onload = () => {
      const dataUrl = String(reader.result || "");
      if (!dataUrl.startsWith("data:image/")) {
        setError("Couldn't read this file.");
        return;
      }
      emit(dataUrl);
      setError("");
    };
    reader.onerror = () => setError("Couldn't read this file.");
    reader.readAsDataURL(file);
  };

  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between">
        <span className="block text-[11px] font-semibold text-gray-600">
          Signature method
        </span>
        <div className="inline-flex rounded-lg border border-gray-200 bg-gray-50 p-1">
          <button
            type="button"
            onClick={() => switchMethod("draw")}
            disabled={disabled}
            className={
              "inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-bold transition cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed " +
              (method === "draw"
                ? "bg-sage-navy text-white shadow-sm"
                : "bg-transparent text-gray-600 hover:text-sage-navy")
            }
          >
            <PenLine size={12} /> Draw
          </button>
          <button
            type="button"
            onClick={() => switchMethod("upload")}
            disabled={disabled}
            className={
              "inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-bold transition cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed " +
              (method === "upload"
                ? "bg-sage-navy text-white shadow-sm"
                : "bg-transparent text-gray-600 hover:text-sage-navy")
            }
          >
            <UploadIcon size={12} /> Upload
          </button>
        </div>
      </div>

      {method === "draw" ? (
        <div>
          <div
            className="rounded-xl border border-gray-200 bg-white overflow-hidden"
            style={{ height }}
          >
            {mounted ? (
              <SignatureCanvas
                ref={(el) => {
                  sigRef.current = el;
                }}
                penColor="#111827"
                canvasProps={{
                  className: "w-full h-full",
                  style: {
                    width: "100%",
                    height,
                    background: "#fff",
                    cursor: disabled ? "not-allowed" : "crosshair",
                    touchAction: "none",
                  },
                }}
                onEnd={disabled ? undefined : handleDrawEnd}
              />
            ) : (
              <div className="h-full flex items-center justify-center text-xs text-gray-400">
                Loading…
              </div>
            )}
          </div>
          <div className="mt-1.5 flex items-center justify-between">
            <p className="text-[11px] text-gray-500">
              Sign here with your mouse or finger.
            </p>
            <button
              type="button"
              onClick={handleClearDrawn}
              disabled={disabled || !data}
              className="text-[11px] font-semibold text-sage-navy hover:text-sage-navy-deep disabled:text-gray-400 disabled:cursor-not-allowed cursor-pointer"
            >
              Clear &amp; re-sign
            </button>
          </div>
        </div>
      ) : (
        <label
          htmlFor={fileInputId}
          className={
            "block rounded-xl border-2 border-dashed bg-gray-50 px-4 py-5 text-center transition " +
            (disabled
              ? "border-gray-200 cursor-not-allowed"
              : "border-gray-300 hover:border-sage-navy hover:bg-sage-navy/5 cursor-pointer")
          }
        >
          <UploadIcon size={18} className="mx-auto text-gray-400 mb-1.5" />
          <p className="text-sm font-semibold text-gray-700">Click to upload</p>
          <p className="text-[11px] text-gray-500 mt-0.5">PNG / JPG, max 2 MB</p>
          <input
            ref={fileInputRef}
            id={fileInputId}
            type="file"
            accept="image/png,image/jpeg,image/jpg"
            disabled={disabled}
            onChange={(e) => handleFile(e.target.files?.[0])}
            className="hidden"
          />
        </label>
      )}

      {error && (
        <p className="mt-1.5 inline-flex items-center gap-1.5 text-[11px] text-red-600">
          <AlertCircle size={11} /> {error}
        </p>
      )}

      {data && (
        <div className="mt-2 flex items-start gap-3">
          <div className="rounded-lg border border-dashed border-gray-300 bg-white p-1.5">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img
              src={data}
              alt="Signature preview"
              style={{ maxWidth: 200, maxHeight: 60 }}
            />
          </div>
          <button
            type="button"
            onClick={() => switchMethod(method)}
            disabled={disabled}
            className="inline-flex items-center gap-1 text-[11px] font-semibold text-gray-500 hover:text-red-600 cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed"
          >
            <X size={11} /> Remove
          </button>
        </div>
      )}
    </div>
  );
}
