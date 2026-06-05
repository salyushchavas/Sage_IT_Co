"use client";

import { useEffect, useRef, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import Link from "next/link";
import {
  AlertCircle,
  Loader2,
  PenLine,
  Upload as UploadIcon,
  X,
} from "lucide-react";
import SignatureCanvas from "react-signature-canvas";

import SplitAuthLayout from "@/components/layout/SplitAuthLayout";
import {
  getConsultantApplicationView,
  signConsultantApplication,
  type ConsultantApplication,
} from "@/lib/api";

const MAX_SIGNATURE_BYTES = 2 * 1024 * 1024;

export default function ConsultantSignPage() {
  const router = useRouter();
  const params = useParams<{ appId: string }>();
  const appId = params?.appId ?? "";

  const [app, setApp] = useState<ConsultantApplication | null>(null);
  const [loading, setLoading] = useState(true);
  const [legalName, setLegalName] = useState("");
  const [confirmAccept, setConfirmAccept] = useState(false);
  const [signatureMethod, setSignatureMethod] = useState<"draw" | "upload">("draw");
  const [signatureData, setSignatureData] = useState<string | null>(null);
  const [signatureError, setSignatureError] = useState("");
  const sigRef = useRef<SignatureCanvas | null>(null);
  const sigFileInputRef = useRef<HTMLInputElement | null>(null);
  const [signatureMounted, setSignatureMounted] = useState(false);
  const [signing, setSigning] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    setSignatureMounted(true);
  }, []);

  useEffect(() => {
    if (!appId) return;
    getConsultantApplicationView(appId)
      .then((data) => {
        setApp(data);
        if (data.consultantName) setLegalName(data.consultantName);
        if (data.status !== "VERIFIED") {
          router.replace(`/consultant/${encodeURIComponent(appId)}/review`);
        }
      })
      .catch(() => {
        router.replace(`/consultant/${encodeURIComponent(appId)}/review`);
      })
      .finally(() => setLoading(false));
  }, [appId, router]);

  const switchMethod = (next: "draw" | "upload") => {
    setSignatureMethod(next);
    setSignatureData(null);
    setSignatureError("");
    sigRef.current?.clear();
    if (sigFileInputRef.current) sigFileInputRef.current.value = "";
  };

  const handleDrawEnd = () => {
    const pad = sigRef.current;
    if (!pad || pad.isEmpty()) {
      setSignatureData(null);
      return;
    }
    setSignatureData(pad.toDataURL("image/png"));
    setSignatureError("");
  };

  const handleClearDrawn = () => {
    sigRef.current?.clear();
    setSignatureData(null);
  };

  const handleSignatureFile = (file: File | null | undefined) => {
    if (!file) return;
    if (!/^image\/(png|jpe?g)$/i.test(file.type)) {
      setSignatureError("Use a PNG or JPG file.");
      return;
    }
    if (file.size > MAX_SIGNATURE_BYTES) {
      setSignatureError("File too large (max 2 MB).");
      return;
    }
    const reader = new FileReader();
    reader.onload = () => {
      const dataUrl = String(reader.result || "");
      if (!dataUrl.startsWith("data:image/")) {
        setSignatureError("Couldn't read this file.");
        return;
      }
      setSignatureData(dataUrl);
      setSignatureError("");
    };
    reader.onerror = () => setSignatureError("Couldn't read this file.");
    reader.readAsDataURL(file);
  };

  const nameWordCount = legalName.trim().split(/\s+/).filter(Boolean).length;
  const canSign =
    nameWordCount >= 2 && !!signatureData && confirmAccept && !signing;

  const handleSign = async () => {
    if (!canSign || !signatureData) return;
    setSigning(true);
    setError("");
    try {
      await signConsultantApplication(appId, legalName.trim(), signatureData);
      router.push(`/consultant/${encodeURIComponent(appId)}/done`);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't sign.");
    } finally {
      setSigning(false);
    }
  };

  if (loading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-50">
        <Loader2 size={28} className="animate-spin text-sage-navy" />
      </div>
    );
  }

  return (
    <SplitAuthLayout
      heroTitle={"One last\nstep — sign."}
      heroSubtitle="Add your full legal name and a digital signature. We email a signed PDF to both you and the operator immediately."
      heroFooter="Step 2 of 2 · Sign"
    >
      <meta name="robots" content="noindex,nofollow" />
      <div className="space-y-4">
        <div>
          <h1 className="font-serif text-2xl font-bold text-gray-900">
            Sign your agreement
          </h1>
          <p className="text-sm text-gray-500 mt-1">
            By signing you confirm everything on the previous screen is correct
            and you accept the agreement.
          </p>
        </div>

        <div>
          <label className="block text-[11px] font-semibold text-gray-600 mb-0.5">
            Legal name <span className="text-red-500">*</span>
          </label>
          <input
            type="text"
            value={legalName}
            onChange={(e) => setLegalName(e.target.value)}
            placeholder="Your full legal name"
            className="w-full px-3 py-2.5 text-sm rounded-lg border border-gray-200 focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy"
          />
          {legalName.trim() && nameWordCount < 2 && (
            <p className="text-[11px] text-red-500 mt-1">
              Enter first and last name.
            </p>
          )}
        </div>

        <div>
          <label className="block text-[11px] font-semibold text-gray-600 mb-1">
            Signature method
          </label>
          <div className="inline-flex rounded-lg border border-gray-200 bg-gray-50 p-1">
            <button
              type="button"
              onClick={() => switchMethod("draw")}
              className={
                "inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-bold transition cursor-pointer " +
                (signatureMethod === "draw"
                  ? "bg-sage-navy text-white shadow-sm"
                  : "bg-transparent text-gray-600 hover:text-sage-navy")
              }
            >
              <PenLine size={12} /> Draw
            </button>
            <button
              type="button"
              onClick={() => switchMethod("upload")}
              className={
                "inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-bold transition cursor-pointer " +
                (signatureMethod === "upload"
                  ? "bg-sage-navy text-white shadow-sm"
                  : "bg-transparent text-gray-600 hover:text-sage-navy")
              }
            >
              <UploadIcon size={12} /> Upload
            </button>
          </div>
        </div>

        <div>
          {signatureMethod === "draw" ? (
            <div>
              <div
                className="rounded-xl border border-gray-200 bg-white overflow-hidden"
                style={{ height: 130 }}
              >
                {signatureMounted ? (
                  <SignatureCanvas
                    ref={(el) => {
                      sigRef.current = el;
                    }}
                    penColor="#111827"
                    canvasProps={{
                      className: "w-full h-full",
                      style: {
                        width: "100%",
                        height: 130,
                        background: "#fff",
                        cursor: "crosshair",
                        touchAction: "none",
                      },
                    }}
                    onEnd={handleDrawEnd}
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
                  disabled={!signatureData}
                  className="text-[11px] font-semibold text-sage-navy hover:text-sage-navy-deep disabled:text-gray-400 disabled:cursor-not-allowed cursor-pointer"
                >
                  Clear &amp; re-sign
                </button>
              </div>
            </div>
          ) : (
            <label
              htmlFor="consult-sig-upload"
              className="block rounded-xl border-2 border-dashed border-gray-300 hover:border-sage-navy hover:bg-sage-navy/5 bg-gray-50 px-4 py-5 text-center cursor-pointer transition"
            >
              <UploadIcon size={18} className="mx-auto text-gray-400 mb-1.5" />
              <p className="text-sm font-semibold text-gray-700">Click to upload</p>
              <p className="text-[11px] text-gray-500 mt-0.5">PNG / JPG, max 2 MB</p>
              <input
                ref={sigFileInputRef}
                id="consult-sig-upload"
                type="file"
                accept="image/png,image/jpeg,image/jpg"
                onChange={(e) => handleSignatureFile(e.target.files?.[0])}
                className="hidden"
              />
            </label>
          )}
          {signatureError && (
            <p className="mt-1.5 inline-flex items-center gap-1.5 text-[11px] text-red-600">
              <AlertCircle size={11} /> {signatureError}
            </p>
          )}
          {signatureData && (
            <div className="mt-2 flex items-start gap-3">
              <div className="rounded-lg border border-dashed border-gray-300 bg-white p-1.5">
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img
                  src={signatureData}
                  alt="Your signature"
                  style={{ maxWidth: 200, maxHeight: 60 }}
                />
              </div>
              <button
                type="button"
                onClick={() => switchMethod(signatureMethod)}
                className="inline-flex items-center gap-1 text-[11px] font-semibold text-gray-500 hover:text-red-600 cursor-pointer"
              >
                <X size={11} /> Remove
              </button>
            </div>
          )}
        </div>

        <label className="flex items-start gap-2.5 text-sm text-gray-700 cursor-pointer">
          <input
            type="checkbox"
            checked={confirmAccept}
            onChange={(e) => setConfirmAccept(e.target.checked)}
            className="mt-0.5 w-4 h-4 rounded border-gray-300 text-sage-navy focus:ring-sage-copper"
          />
          <span>
            I confirm the details on file are correct and I accept this
            agreement. <span className="text-red-500">*</span>
          </span>
        </label>

        {error && (
          <p className="inline-flex items-center gap-1.5 text-sm text-red-600">
            <AlertCircle size={14} /> {error}
          </p>
        )}

        <button
          type="button"
          onClick={handleSign}
          disabled={!canSign}
          className={
            "w-full inline-flex items-center justify-center gap-2 px-4 py-3 rounded-lg text-sm font-bold transition " +
            (canSign
              ? "bg-sage-navy text-white hover:bg-sage-navy-deep shadow-md hover:shadow-lg cursor-pointer"
              : "bg-gray-200 text-gray-500 cursor-not-allowed")
          }
        >
          {signing && <Loader2 size={14} className="animate-spin" />}
          {signing ? "Signing…" : "Sign Agreement →"}
        </button>

        {app && (
          <Link
            href={`/consultant/${encodeURIComponent(appId)}/review`}
            className="block text-center text-xs font-semibold text-gray-500 hover:text-sage-navy"
          >
            ← Back to review
          </Link>
        )}
      </div>
    </SplitAuthLayout>
  );
}
