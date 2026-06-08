"use client";

import { useEffect, useRef, useState } from "react";
import { AlertCircle, PenLine, Upload as UploadIcon, X } from "lucide-react";
import SignatureCanvas from "react-signature-canvas";

const MAX_SIGNATURE_BYTES = 5 * 1024 * 1024;

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
 * Build N — upload-first signature input. The consultant lands in
 * UPLOAD mode (drag/click an image of their signature) with a small
 * "Or draw your signature instead" link revealing the canvas. Draw
 * stays available but is the secondary path. Both modes emit a
 * base64 data URL via {@link Props#onChange}; the parent decides
 * what to do with it (POST it, embed in a preview, etc.).
 *
 * Backend normalisation re-encodes every signature -- drawn or
 * uploaded, PNG or JPEG -- to a 190x76 PNG with a 96-DPI pHYs tag,
 * so both paths render at the same physical size in the final PDF.
 *
 * react-signature-canvas requires DOM measurements before it can
 * render, so the canvas is gated behind a "mounted" effect to avoid
 * the server-render / hydration mismatch.
 */
export default function SignaturePad({
  onChange,
  disabled = false,
  height = 130,
  fileInputId = "sage-signature-upload",
}: Props) {
  const [method, setMethod] = useState<"draw" | "upload">("upload");
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
      setError("File too large (max 5 MB).");
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
      {method === "upload" ? (
        <>
          <label
            htmlFor={fileInputId}
            className={
              "block rounded-xl border-2 border-dashed bg-gray-50 px-4 py-6 text-center transition " +
              (disabled
                ? "border-gray-200 cursor-not-allowed"
                : "border-gray-300 hover:border-sage-navy hover:bg-sage-navy/5 cursor-pointer")
            }
          >
            <UploadIcon size={20} className="mx-auto text-sage-navy mb-2" />
            <p className="text-sm font-semibold text-gray-800">
              Upload your signature
            </p>
            <p className="text-[11px] text-gray-500 mt-1">
              PNG or JPG, max 5 MB. A transparent-background PNG looks
              cleanest.
            </p>
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
          <div className="flex items-center justify-end">
            <button
              type="button"
              onClick={() => switchMethod("draw")}
              disabled={disabled}
              className="inline-flex items-center gap-1 text-[11px] font-semibold text-gray-500 hover:text-sage-navy disabled:opacity-50 cursor-pointer"
            >
              <PenLine size={11} /> Or draw your signature instead
            </button>
          </div>
        </>
      ) : (
        <>
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
          <div className="flex items-center justify-between gap-2 flex-wrap">
            <button
              type="button"
              onClick={() => switchMethod("upload")}
              disabled={disabled}
              className="inline-flex items-center gap-1 text-[11px] font-semibold text-gray-500 hover:text-sage-navy disabled:opacity-50 cursor-pointer"
            >
              <UploadIcon size={11} /> Upload an image instead
            </button>
            <button
              type="button"
              onClick={handleClearDrawn}
              disabled={disabled || !data}
              className="text-[11px] font-semibold text-sage-navy hover:text-sage-navy-deep disabled:text-gray-400 disabled:cursor-not-allowed cursor-pointer"
            >
              Clear &amp; re-sign
            </button>
          </div>
        </>
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
