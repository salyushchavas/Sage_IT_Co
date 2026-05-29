"use client";

import { Suspense, useEffect, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import Link from "next/link";
import { motion } from "framer-motion";
import {
  AlertCircle, ArrowDown, CheckCircle2, Loader2,
  PenLine, Upload, X,
} from "lucide-react";
import SignatureCanvas from "react-signature-canvas";

import OnboardingLayout from "@/components/layouts/OnboardingLayout";
import { useAuth } from "@/lib/auth-context";
import { submitAcknowledgment } from "@/lib/api";

/**
 * Step 4 — Acknowledgment of Interest and Program Acceptance.
 *
 * The participant reads a versioned, scrollable text block, types
 * their legal name, captures a digital signature (draw or upload),
 * and confirms three consent boxes. The backend enforces the gate
 * (status &gt;= ID_EMAIL_SENT) and idempotency; this page mirrors
 * those gates client-side so the experience is consistent.
 */

const ACK_VERSION = "ACK-v1.0";
const MAX_SIGNATURE_BYTES = 2 * 1024 * 1024;

const ACKNOWLEDGMENT_CLAUSES: ReadonlyArray<string> = [
  "I am expressing genuine interest in the career development and professional services offered by Sage IT Co.",
  "I understand that Sage IT Co provides career coaching, resume administration, interview preparation, technical development, and job-navigation support.",
  "I consent to providing required identification and documentation for program enrollment and verification.",
  "I consent to receiving program-related communications via email, phone, and the platform dashboard.",
  "I understand that my information will be handled securely and in accordance with the company's privacy and data policies.",
  "I acknowledge that completion of program phases and services is subject to my active participation and compliance with program requirements.",
];

function AcknowledgmentPageInner() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const fromProfile = searchParams.get("from") === "profile";
  const { user, isAuthenticated, isLoading: authLoading, refreshUser } = useAuth();

  // ── Gate state ────────────────────────────────────────────────
  const [gateChecked, setGateChecked] = useState(false);
  const [gateError, setGateError] = useState("");

  // ── Form state ────────────────────────────────────────────────
  const [hasScrolledToBottom, setHasScrolledToBottom] = useState(false);
  const [legalName, setLegalName] = useState("");
  const [interestAccepted, setInterestAccepted] = useState(false);
  const [documentationConsent, setDocumentationConsent] = useState(false);
  const [communicationConsent, setCommunicationConsent] = useState(false);

  // ── Signature state ───────────────────────────────────────────
  const [signatureMethod, setSignatureMethod] = useState<"draw" | "upload">("draw");
  const [signatureData, setSignatureData] = useState<string | null>(null);
  const [signatureError, setSignatureError] = useState("");
  const sigRef = useRef<SignatureCanvas | null>(null);
  const sigFileInputRef = useRef<HTMLInputElement | null>(null);
  const [signatureMounted, setSignatureMounted] = useState(false);
  useEffect(() => { setSignatureMounted(true); }, []);

  // ── Submit state ──────────────────────────────────────────────
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState("");

  // ── Auth + routing guard ──────────────────────────────────────
  // Phase 1C — gate on the per-step completion flag, not the
  // status enum (which now jumps straight to DASHBOARD_ENABLED on
  // signup). Two outcomes:
  //   - acknowledgmentComplete = true  → step already done, bounce
  //     back to the checklist tab.
  //   - participantId missing          → user hasn't even started
  //     onboarding; send them to /enroll.
  // Otherwise render the form.
  useEffect(() => {
    if (authLoading) return;
    if (!isAuthenticated) {
      router.replace("/login");
      return;
    }
    if (!user) return;
    if (user.acknowledgmentComplete) {
      router.replace("/dashboard?tab=complete-profile");
      return;
    }
    if (!user.participantId) {
      router.replace("/enroll");
      return;
    }
    setGateChecked(true);
  }, [authLoading, isAuthenticated, user, router]);

  // ── Computed flags ────────────────────────────────────────────
  const nameWordCount = legalName.trim().split(/\s+/).filter(Boolean).length;
  const canSubmit =
    hasScrolledToBottom
    && nameWordCount >= 2
    && !!signatureData
    && interestAccepted
    && documentationConsent
    && communicationConsent
    && !submitting;

  // ── Signature handlers ────────────────────────────────────────
  const switchSignatureMethod = (next: "draw" | "upload") => {
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

  // ── Scroll tracking ───────────────────────────────────────────
  const handleTermsScroll = (e: React.UIEvent<HTMLDivElement>) => {
    const t = e.currentTarget;
    if (t.scrollHeight - t.scrollTop - t.clientHeight < 12) {
      setHasScrolledToBottom(true);
    }
  };

  // ── Submit ────────────────────────────────────────────────────
  const handleSubmit = async () => {
    if (!canSubmit || !signatureData) return;
    setSubmitting(true);
    setSubmitError("");
    try {
      const result = await submitAcknowledgment({
        legalName: legalName.trim(),
        signatureImage: signatureData,
        signatureMethod,
        interestAccepted,
        documentationConsent,
        communicationConsent,
        acknowledgmentVersion: ACK_VERSION,
      });
      void result;
      // Refresh the auth context so the next page sees the freshly
      // flipped acknowledgmentComplete flag instead of bouncing
      // back here on its own guard.
      await refreshUser();
      // Came from the dashboard checklist → return to it with the
      // next step highlighted. Direct deep links fall through to
      // the legacy chain (which now also routes to the checklist,
      // just without the step hash).
      router.replace(
        fromProfile
          ? "/dashboard?tab=complete-profile&step=DOCUMENTS"
          : "/dashboard?tab=complete-profile",
      );
    } catch (err) {
      setSubmitError(err instanceof Error ? err.message : "Couldn't submit acknowledgment");
    } finally {
      setSubmitting(false);
    }
  };

  // ── Render ────────────────────────────────────────────────────
  if (authLoading || !gateChecked) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-[#F8FAFC]">
        <Loader2 size={28} className="animate-spin text-[#1B2A5C]" />
      </div>
    );
  }

  if (gateError) {
    return (
      <OnboardingLayout currentStep={4} contentMaxWidth="xl">
        <div className="bg-white rounded-2xl shadow-lg border border-gray-100 p-6 text-center">
          <AlertCircle size={20} className="text-red-600 inline-block mb-2" />
          <p className="text-sm text-red-700">{gateError}</p>
          <Link href="/participant-id" className="text-xs text-[#1B2A5C] font-semibold hover:underline mt-3 inline-block">
            ← Back to participant ID
          </Link>
        </div>
      </OnboardingLayout>
    );
  }

  return (
    <OnboardingLayout currentStep={4} contentMaxWidth="2xl">
      <motion.section
        initial={{ opacity: 0, y: 12 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.3 }}
        className="bg-white rounded-2xl shadow-lg border border-gray-100 px-5 py-5 sm:px-7 sm:py-6"
      >
        <h1 className="font-serif text-xl sm:text-2xl font-bold text-gray-900 text-center">
          Please review and accept the following
        </h1>
        <p className="text-gray-500 mt-1 text-center text-xs sm:text-sm">
          Logged in as <span className="font-mono text-gray-700">{user?.email}</span>
        </p>

        {/* Versioned, scrollable acknowledgment text. */}
        <div className="mt-5">
          <p className="text-[11px] uppercase tracking-wider text-gray-500 font-semibold mb-1.5">
            Acknowledgment of Interest and Program Acceptance
            <span className="ml-2 font-mono normal-case text-gray-400">{ACK_VERSION}</span>
          </p>
          <div
            onScroll={handleTermsScroll}
            className="rounded-xl border border-gray-200 bg-gray-50/60 p-4 sm:p-5 text-sm text-gray-700 leading-relaxed overflow-y-auto"
            style={{ height: "min(40vh, 260px)" }}
          >
            <p className="text-sm font-semibold text-gray-900 mb-3">
              By accepting this acknowledgment, I confirm:
            </p>
            <ol className="space-y-3">
              {ACKNOWLEDGMENT_CLAUSES.map((clause, idx) => (
                <li key={idx} className="flex gap-2.5">
                  <span className="text-[#1B2A5C] font-bold shrink-0">{idx + 1}.</span>
                  <span className="text-sm text-gray-700">{clause}</span>
                </li>
              ))}
            </ol>
          </div>
          {!hasScrolledToBottom && (
            <p className="mt-1.5 inline-flex items-center gap-1 text-[11px] text-amber-700">
              <ArrowDown size={11} /> Scroll to read all clauses before accepting.
            </p>
          )}
        </div>

        {/* Legal name input. */}
        <div className="mt-5">
          <label className="block text-[13px] font-medium text-gray-700 mb-1">
            Your legal name <span className="text-gray-400 text-[11px]">(as confirmation)</span>{" "}
            <span className="text-red-500">*</span>
          </label>
          <input
            type="text"
            value={legalName}
            onChange={(e) => setLegalName(e.target.value)}
            placeholder="Type your full legal name"
            disabled={!hasScrolledToBottom || submitting}
            className="w-full px-3.5 py-2.5 text-sm rounded-lg border border-gray-200 bg-white text-gray-900 placeholder-gray-400 transition focus:outline-none focus:border-[#1B2A5C] focus:ring-1 focus:ring-[#1B2A5C] disabled:bg-gray-50 disabled:cursor-not-allowed"
          />
          {legalName.trim() && nameWordCount < 2 && (
            <p className="text-[11px] text-red-500 mt-1">Please enter both first and last name.</p>
          )}
        </div>

        {/* Signature pad. */}
        <div className="mt-5">
          <label className="block text-[13px] font-medium text-gray-700 mb-1">
            Digital signature <span className="text-red-500">*</span>
          </label>
          <div className="inline-flex rounded-lg border border-gray-200 bg-gray-50 p-1 mb-2">
            <button
              type="button"
              onClick={() => switchSignatureMethod("draw")}
              disabled={!hasScrolledToBottom}
              className={
                "inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-bold transition "
                + (signatureMethod === "draw"
                    ? "bg-[#1B2A5C] text-white shadow-sm"
                    : "bg-transparent text-gray-600 hover:text-[#1B2A5C]")
                + " disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer"
              }
            >
              <PenLine size={12} /> Draw signature
            </button>
            <button
              type="button"
              onClick={() => switchSignatureMethod("upload")}
              disabled={!hasScrolledToBottom}
              className={
                "inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-bold transition "
                + (signatureMethod === "upload"
                    ? "bg-[#1B2A5C] text-white shadow-sm"
                    : "bg-transparent text-gray-600 hover:text-[#1B2A5C]")
                + " disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer"
              }
            >
              <Upload size={12} /> Upload signature
            </button>
          </div>

          {signatureMethod === "draw" ? (
            <div>
              <div className="rounded-xl border border-gray-200 bg-white overflow-hidden" style={{ height: 140 }}>
                {signatureMounted ? (
                  <SignatureCanvas
                    ref={(el) => { sigRef.current = el; }}
                    penColor="#111827"
                    canvasProps={{
                      className: "w-full h-full",
                      style: {
                        width: "100%",
                        height: 140,
                        background: "#ffffff",
                        cursor: hasScrolledToBottom ? "crosshair" : "not-allowed",
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
                <p className="text-[11px] text-gray-500">Sign here with your mouse or finger.</p>
                <button
                  type="button"
                  onClick={handleClearDrawn}
                  disabled={!signatureData || submitting}
                  className="text-[11px] font-semibold text-[#1B2A5C] hover:text-[#2D4480] disabled:text-gray-400 disabled:cursor-not-allowed cursor-pointer"
                >
                  Clear
                </button>
              </div>
            </div>
          ) : (
            <div>
              <label
                htmlFor="ack-sig-upload"
                className={
                  "block rounded-xl border-2 border-dashed bg-gray-50 px-4 py-5 text-center transition "
                  + (hasScrolledToBottom
                      ? "border-gray-300 hover:border-[#1B2A5C] hover:bg-[#F0F2F8] cursor-pointer"
                      : "border-gray-200 opacity-60 cursor-not-allowed")
                }
                onDragOver={(e) => { e.preventDefault(); }}
                onDrop={(e) => {
                  e.preventDefault();
                  if (!hasScrolledToBottom) return;
                  handleSignatureFile(e.dataTransfer.files?.[0]);
                }}
              >
                <Upload size={18} className="mx-auto text-gray-400 mb-1.5" />
                <p className="text-sm font-semibold text-gray-700">Click to upload or drag and drop</p>
                <p className="text-[11px] text-gray-500 mt-0.5">
                  PNG, JPG, JPEG (max 2 MB) — transparent background recommended
                </p>
                <input
                  ref={sigFileInputRef}
                  id="ack-sig-upload"
                  type="file"
                  accept="image/png,image/jpeg,image/jpg"
                  disabled={!hasScrolledToBottom}
                  onChange={(e) => handleSignatureFile(e.target.files?.[0])}
                  className="hidden"
                />
              </label>
            </div>
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
                <img src={signatureData} alt="Your signature" style={{ maxWidth: 200, maxHeight: 70, display: "block" }} />
              </div>
              <button
                type="button"
                onClick={() => switchSignatureMethod(signatureMethod)}
                className="inline-flex items-center gap-1 text-[11px] font-semibold text-gray-500 hover:text-red-600 cursor-pointer"
              >
                <X size={11} /> Remove
              </button>
            </div>
          )}
        </div>

        {/* Consent checkboxes. */}
        <div className="mt-5 space-y-2.5">
          <label className="flex items-start gap-2.5 text-sm text-gray-700 cursor-pointer">
            <input
              type="checkbox"
              checked={interestAccepted}
              onChange={(e) => setInterestAccepted(e.target.checked)}
              disabled={!hasScrolledToBottom}
              className="mt-0.5 w-4 h-4 rounded border-gray-300 text-[#1B2A5C] focus:ring-[#C87D5C] disabled:opacity-50"
            />
            <span>
              I have read and accept the{" "}
              <span className="font-bold">Acknowledgment of Interest and Program Acceptance</span>{" "}
              ({ACK_VERSION}).
            </span>
          </label>
          <label className="flex items-start gap-2.5 text-sm text-gray-700 cursor-pointer">
            <input
              type="checkbox"
              checked={documentationConsent}
              onChange={(e) => setDocumentationConsent(e.target.checked)}
              disabled={!hasScrolledToBottom}
              className="mt-0.5 w-4 h-4 rounded border-gray-300 text-[#1B2A5C] focus:ring-[#C87D5C] disabled:opacity-50"
            />
            <span>I consent to providing required identification and documentation through the secure portal.</span>
          </label>
          <label className="flex items-start gap-2.5 text-sm text-gray-700 cursor-pointer">
            <input
              type="checkbox"
              checked={communicationConsent}
              onChange={(e) => setCommunicationConsent(e.target.checked)}
              disabled={!hasScrolledToBottom}
              className="mt-0.5 w-4 h-4 rounded border-gray-300 text-[#1B2A5C] focus:ring-[#C87D5C] disabled:opacity-50"
            />
            <span>I consent to receiving program-related communications from Sage IT Co.</span>
          </label>
        </div>

        {submitError && (
          <p className="mt-3 inline-flex items-center gap-1.5 text-sm text-red-600">
            <AlertCircle size={14} /> {submitError}
          </p>
        )}

        <button
          type="button"
          onClick={handleSubmit}
          disabled={!canSubmit}
          className={
            "mt-5 w-full inline-flex items-center justify-center gap-2 text-sm font-bold py-2.5 rounded-lg transition-all "
            + (canSubmit
                ? "bg-[#1B2A5C] hover:bg-[#2D4480] text-white shadow-md hover:shadow-lg cursor-pointer active:scale-[0.99]"
                : "bg-gray-200 text-gray-500 cursor-not-allowed")
          }
        >
          {submitting && <Loader2 size={15} className="animate-spin" />}
          {submitting ? "Submitting…" : <><CheckCircle2 size={15} /> Accept and Continue →</>}
        </button>
      </motion.section>
    </OnboardingLayout>
  );
}

export default function AcknowledgmentPage() {
  // Suspense boundary required by useSearchParams (Next.js 14 App Router).
  return (
    <Suspense fallback={
      <div className="min-h-screen flex items-center justify-center bg-[#F8FAFC]">
        <Loader2 size={28} className="animate-spin text-[#1B2A5C]" />
      </div>
    }>
      <AcknowledgmentPageInner />
    </Suspense>
  );
}
