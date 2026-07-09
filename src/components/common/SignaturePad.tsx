"use client";

import { useEffect, useRef, useState, type ReactNode } from "react";
import {
  AlertCircle,
  PenLine,
  Type as TypeIcon,
  Upload as UploadIcon,
  X,
} from "lucide-react";
import SignatureCanvas from "react-signature-canvas";

const MAX_SIGNATURE_BYTES = 5 * 1024 * 1024;
const INK = { r: 17, g: 24, b: 39 }; // #111827

type Method = "type" | "draw" | "upload";

/**
 * Build AM — the three "Type" signature styles. Each renders the typed name
 * in a self-hosted handwriting font (see globals.css @font-face) and rasterises
 * to a transparent-background PNG, so it flows through the same pipeline as the
 * drawn / uploaded signature.
 */
const SIG_STYLES: { key: string; label: string; family: string; px: number }[] = [
  { key: "flowing", label: "Flowing", family: "Sage Sig Flowing", px: 58 },
  { key: "elegant", label: "Elegant", family: "Sage Sig Elegant", px: 66 },
  { key: "casual", label: "Casual", family: "Sage Sig Casual", px: 60 },
];

interface Props {
  /** Notified whenever the active signature data URL changes. Pass
   *  null when the pad is cleared / file removed. */
  onChange: (dataUrl: string | null) => void;
  /** Optional disabled flag -- ignores every input while a parent
   *  flow is in progress (e.g., approve-and-sign submitting). */
  disabled?: boolean;
  /** Canvas height in pixels. Defaults to 130. */
  height?: number;
  /** Unique id for the file-input -- only needed if two pads can
   *  render on the same page. */
  fileInputId?: string;
  /** Build AM — pre-fills the "Type" tab's name (e.g. the consultant's
   *  legal name), so the generated signature defaults to their real name. */
  suggestedName?: string;
}

/**
 * Signature input with THREE ways to sign, all emitting a base64 PNG data URL
 * via {@link Props#onChange}:
 *  - Type   : type a name and pick one of three generated handwriting styles.
 *  - Draw   : draw on a canvas (react-signature-canvas).
 *  - Upload : upload a photo/scan; the tool extracts the signature by removing
 *             the (light) background so the ink lands on a transparent PNG.
 *
 * Backend normalisation re-encodes every signature to a 190x76 PNG (96-DPI
 * pHYs), so all three paths render at the same physical size in the final PDF.
 */
export default function SignaturePad({
  onChange,
  disabled = false,
  height = 130,
  fileInputId = "sage-signature-upload",
  suggestedName,
}: Props) {
  const [method, setMethod] = useState<Method>("type");
  const [data, setData] = useState<string | null>(null);
  const [error, setError] = useState("");
  const [mounted, setMounted] = useState(false);
  const [fontsReady, setFontsReady] = useState(false);

  // Type-mode state.
  const [typedName, setTypedName] = useState(suggestedName ?? "");
  const [selectedStyle, setSelectedStyle] = useState<number | null>(0);
  const nameTouched = useRef(false);

  // Upload-mode state.
  const [removeBg, setRemoveBg] = useState(true);
  const lastFileRef = useRef<File | null>(null);

  const sigRef = useRef<SignatureCanvas | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  // Stable onChange so the type-mode generation effect doesn't re-run on every
  // parent render (the parent typically passes an inline callback).
  const onChangeRef = useRef(onChange);
  onChangeRef.current = onChange;

  useEffect(() => {
    setMounted(true);
  }, []);

  // Load the three handwriting fonts up-front so canvas rasterisation is ready.
  useEffect(() => {
    let cancelled = false;
    const fontSet = (document as unknown as { fonts?: FontFaceSet }).fonts;
    if (!fontSet) {
      setFontsReady(true);
      return;
    }
    Promise.all(
      SIG_STYLES.map((s) => fontSet.load(`48px '${s.family}'`).catch(() => null)),
    ).then(() => {
      if (!cancelled) setFontsReady(true);
    });
    return () => {
      cancelled = true;
    };
  }, []);

  // Keep the typed name in sync with the suggested name until the user edits it.
  useEffect(() => {
    if (!nameTouched.current && suggestedName && !typedName) {
      setTypedName(suggestedName);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [suggestedName]);

  const emit = (next: string | null) => {
    setData(next);
    onChangeRef.current(next);
  };

  // Type mode — (re)generate the signature whenever the name or style changes.
  useEffect(() => {
    if (method !== "type") return;
    if (selectedStyle == null || !typedName.trim()) {
      emit(null);
      return;
    }
    let cancelled = false;
    const style = SIG_STYLES[selectedStyle];
    (async () => {
      const url = await renderTypedSignature(typedName, style.family, style.px);
      if (!cancelled) emit(url);
    })();
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [method, selectedStyle, typedName, fontsReady]);

  const switchMethod = (next: Method) => {
    if (disabled) return;
    setError("");
    sigRef.current?.clear();
    if (fileInputRef.current) fileInputRef.current.value = "";
    lastFileRef.current = null;
    if (next !== "type") emit(null);
    setMethod(next);
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

  const processFile = (file: File, extract: boolean) => {
    const reader = new FileReader();
    reader.onload = () => {
      const dataUrl = String(reader.result || "");
      if (!dataUrl.startsWith("data:image/")) {
        setError("Couldn't read this file.");
        return;
      }
      if (!extract) {
        emit(dataUrl);
        setError("");
        return;
      }
      const img = new Image();
      img.onload = () => {
        const cleaned = extractSignature(img);
        emit(cleaned ?? dataUrl);
        setError(cleaned ? "" : "Couldn't isolate the signature — using the original image.");
      };
      img.onerror = () => {
        emit(dataUrl);
        setError("Couldn't process the image — using the original.");
      };
      img.src = dataUrl;
    };
    reader.onerror = () => setError("Couldn't read this file.");
    reader.readAsDataURL(file);
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
    lastFileRef.current = file;
    processFile(file, removeBg);
  };

  // Re-run extraction when the "remove background" toggle flips.
  const toggleRemoveBg = () => {
    if (disabled) return;
    const next = !removeBg;
    setRemoveBg(next);
    if (lastFileRef.current) processFile(lastFileRef.current, next);
  };

  const tab = (m: Method, icon: ReactNode, label: string) => (
    <button
      type="button"
      onClick={() => switchMethod(m)}
      disabled={disabled}
      className={
        "inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md text-[12px] font-semibold transition cursor-pointer disabled:opacity-50 " +
        (method === m
          ? "bg-sage-navy text-white"
          : "bg-white text-gray-600 border border-stone-300 hover:border-sage-navy/50")
      }
    >
      {icon} {label}
    </button>
  );

  return (
    <div className="space-y-3">
      <div className="flex items-center gap-2 flex-wrap">
        {tab("type", <TypeIcon size={12} />, "Type")}
        {tab("draw", <PenLine size={12} />, "Draw")}
        {tab("upload", <UploadIcon size={12} />, "Upload")}
      </div>

      {method === "type" && (
        <div className="space-y-2.5">
          <input
            type="text"
            value={typedName}
            onChange={(e) => {
              nameTouched.current = true;
              setTypedName(e.target.value);
            }}
            disabled={disabled}
            placeholder="Type your full name"
            className="w-full px-3 py-2 text-[14px] rounded-md border border-stone-300 focus:outline-none focus:ring-2 focus:ring-sage-copper/40 disabled:bg-gray-50"
          />
          {!fontsReady ? (
            <p className="text-[11px] text-gray-400">Loading signature styles…</p>
          ) : (
            <div className="grid grid-cols-1 sm:grid-cols-3 gap-2">
              {SIG_STYLES.map((s, i) => (
                <button
                  key={s.key}
                  type="button"
                  onClick={() => setSelectedStyle(i)}
                  disabled={disabled}
                  aria-pressed={selectedStyle === i}
                  className={
                    "rounded-lg border px-2 py-3 text-center overflow-hidden transition cursor-pointer disabled:opacity-50 " +
                    (selectedStyle === i
                      ? "border-sage-navy ring-2 ring-sage-navy/30 bg-sage-navy/5"
                      : "border-stone-300 hover:border-sage-navy/50 bg-white")
                  }
                >
                  <span
                    className="block truncate text-gray-900 leading-tight"
                    style={{ fontFamily: `'${s.family}', cursive`, fontSize: 26 }}
                  >
                    {typedName.trim() || "Your name"}
                  </span>
                  <span className="mt-1 block text-[10px] font-semibold uppercase tracking-wider text-gray-400">
                    {s.label}
                  </span>
                </button>
              ))}
            </div>
          )}
          <p className="text-[11px] text-gray-500">
            Pick a style — this becomes your signature. By adopting it you agree
            it is the legal equivalent of your handwritten signature.
          </p>
        </div>
      )}

      {method === "upload" && (
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
              PNG or JPG, max 5 MB. A photo on white paper works — the background
              is removed automatically.
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
          <label className="flex items-center gap-2 text-[11px] text-gray-600 cursor-pointer select-none">
            <input
              type="checkbox"
              checked={removeBg}
              onChange={toggleRemoveBg}
              disabled={disabled}
              className="h-3.5 w-3.5 accent-sage-navy"
            />
            Remove background (isolate the signature ink)
          </label>
        </>
      )}

      {method === "draw" && (
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
          <div className="flex items-center justify-end">
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
        <div className="mt-1 flex items-start gap-3">
          <div className="rounded-lg border border-dashed border-gray-300 bg-white p-1.5">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img
              src={data}
              alt="Signature preview"
              style={{ maxWidth: 220, maxHeight: 64 }}
            />
          </div>
          <button
            type="button"
            onClick={() => {
              if (disabled) return;
              sigRef.current?.clear();
              if (fileInputRef.current) fileInputRef.current.value = "";
              lastFileRef.current = null;
              if (method === "type") setSelectedStyle(null);
              emit(null);
            }}
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

// ── Canvas helpers ───────────────────────────────────────────────

/** Crop a canvas to the bounding box of its non-transparent pixels; returns a
 *  trimmed PNG data URL, or null when the canvas is fully transparent. */
function trimTransparent(canvas: HTMLCanvasElement): string | null {
  const ctx = canvas.getContext("2d");
  if (!ctx) return null;
  const { width, height } = canvas;
  if (!width || !height) return null;
  const px = ctx.getImageData(0, 0, width, height).data;
  let top = height,
    left = width,
    right = -1,
    bottom = -1;
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      if (px[(y * width + x) * 4 + 3] > 8) {
        if (x < left) left = x;
        if (x > right) right = x;
        if (y < top) top = y;
        if (y > bottom) bottom = y;
      }
    }
  }
  if (right < left || bottom < top) return null;
  const pad = 8;
  left = Math.max(0, left - pad);
  top = Math.max(0, top - pad);
  right = Math.min(width - 1, right + pad);
  bottom = Math.min(height - 1, bottom + pad);
  const w = right - left + 1;
  const h = bottom - top + 1;
  const out = document.createElement("canvas");
  out.width = w;
  out.height = h;
  const octx = out.getContext("2d");
  if (!octx) return null;
  octx.drawImage(canvas, left, top, w, h, 0, 0, w, h);
  return out.toDataURL("image/png");
}

/** Render a typed name in a handwriting font to a trimmed, transparent PNG. */
async function renderTypedSignature(
  name: string,
  family: string,
  px: number,
): Promise<string | null> {
  const text = name.trim();
  if (!text) return null;
  try {
    const fontSet = (document as unknown as { fonts?: FontFaceSet }).fonts;
    if (fontSet) await fontSet.load(`${px}px '${family}'`);
  } catch {
    /* fall through — a fallback font still renders something legible */
  }
  const measureCtx = document.createElement("canvas").getContext("2d");
  if (!measureCtx) return null;
  measureCtx.font = `${px}px '${family}'`;
  const textW = measureCtx.measureText(text).width;
  const w = Math.max(160, Math.ceil(textW) + px);
  const h = Math.ceil(px * 2);
  const scale = 2; // hi-DPI for crisp strokes
  const canvas = document.createElement("canvas");
  canvas.width = w * scale;
  canvas.height = h * scale;
  const ctx = canvas.getContext("2d");
  if (!ctx) return null;
  ctx.scale(scale, scale);
  ctx.font = `${px}px '${family}'`;
  ctx.fillStyle = `rgb(${INK.r},${INK.g},${INK.b})`;
  ctx.textBaseline = "middle";
  ctx.textAlign = "center";
  ctx.fillText(text, w / 2, h / 2);
  return trimTransparent(canvas) ?? canvas.toDataURL("image/png");
}

/**
 * Extract a signature from a photo/scan: treat light pixels as background
 * (transparent) and dark pixels as ink, recolouring the ink to a consistent
 * dark tone with anti-aliased edges. Returns a trimmed, transparent PNG, or
 * null on failure. Original transparency is preserved (min of the two alphas),
 * so a clean transparent PNG passes through unharmed.
 */
function extractSignature(img: HTMLImageElement): string | null {
  const iw = img.naturalWidth || img.width;
  const ih = img.naturalHeight || img.height;
  if (!iw || !ih) return null;
  const maxDim = 1600;
  const s = Math.min(1, maxDim / Math.max(iw, ih));
  const cw = Math.max(1, Math.round(iw * s));
  const ch = Math.max(1, Math.round(ih * s));
  const canvas = document.createElement("canvas");
  canvas.width = cw;
  canvas.height = ch;
  const ctx = canvas.getContext("2d");
  if (!ctx) return null;
  ctx.drawImage(img, 0, 0, cw, ch);
  let imgData: ImageData;
  try {
    imgData = ctx.getImageData(0, 0, cw, ch);
  } catch {
    return null; // tainted canvas (shouldn't happen for a local file)
  }
  const d = imgData.data;
  const WHITE = 205; // >= this luminance → background
  const DARK = 90; // <= this luminance → solid ink
  for (let i = 0; i < d.length; i += 4) {
    const lum = 0.299 * d[i] + 0.587 * d[i + 1] + 0.114 * d[i + 2];
    let alpha: number;
    if (lum >= WHITE) alpha = 0;
    else if (lum <= DARK) alpha = 255;
    else alpha = Math.round((255 * (WHITE - lum)) / (WHITE - DARK));
    alpha = Math.min(alpha, d[i + 3]); // respect existing transparency
    d[i] = INK.r;
    d[i + 1] = INK.g;
    d[i + 2] = INK.b;
    d[i + 3] = alpha;
  }
  ctx.putImageData(imgData, 0, 0);
  return trimTransparent(canvas) ?? canvas.toDataURL("image/png");
}
