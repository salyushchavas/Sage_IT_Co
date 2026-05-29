"use client";

import { Suspense, useEffect, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import Link from "next/link";
import { motion, AnimatePresence } from "framer-motion";
import {
  AlertCircle, ArrowDown, CheckCircle2, FileText, Loader2,
  PenLine, Upload as UploadIcon, X,
} from "lucide-react";
import SignatureCanvas from "react-signature-canvas";

import OnboardingLayout from "@/components/layouts/OnboardingLayout";
import { useAuth } from "@/lib/auth-context";
import {
  getProgramSelection, getTerms,
  signParticipantAgreement,
  type ProgramSelectionDTO, type TermsResponse, type UserDTO,
} from "@/lib/api";

/**
 * On-site agreement signing.
 *
 * One screen, one action: review the program details + agreement
 * terms, type your legal name, sign, accept. Server persists the
 * verified row, generates + emails the signed PDF, transitions
 * workflow to AGREEMENT_COMPLETED, then we redirect to /check-upload
 * (where the participant either uploads check soft-copies or marks
 * the step not applicable).
 *
 * Check upload moved out of this page per PRD Section 4 — it lives
 * on its own /check-upload page now.
 */

const AGREEMENT_VERSION = "v1.0";
const ACK_VERSION = "ACK-v1.0";
const SVC_VERSION = "SVC-v1.0";
const MAX_SIGNATURE_BYTES = 2 * 1024 * 1024;

function AgreementPageInner() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const fromProfile = searchParams.get("from") === "profile";
  const { user, isAuthenticated, isLoading: authLoading, refreshUser } = useAuth();

  const [gateChecked, setGateChecked] = useState(false);
  const [gateError, setGateError] = useState("");

  const [profile, setProfile] = useState<UserDTO | null>(null);
  const [program, setProgram] = useState<ProgramSelectionDTO | null>(null);
  const [terms, setTerms] = useState<TermsResponse | null>(null);

  const [scrolledToBottom, setScrolledToBottom] = useState(false);
  const [confirmAccept, setConfirmAccept] = useState(false);
  const [legalName, setLegalName] = useState("");

  const [signatureMethod, setSignatureMethod] = useState<"draw" | "upload">("draw");
  const [signatureData, setSignatureData] = useState<string | null>(null);
  const [signatureError, setSignatureError] = useState("");
  const sigRef = useRef<SignatureCanvas | null>(null);
  const sigFileInputRef = useRef<HTMLInputElement | null>(null);
  const [signatureMounted, setSignatureMounted] = useState(false);
  useEffect(() => { setSignatureMounted(true); }, []);

  const [signing, setSigning] = useState(false);
  const [signError, setSignError] = useState("");
  const [signed, setSigned] = useState(false);

  useEffect(() => {
    if (authLoading) return;
    if (!isAuthenticated) {
      router.replace("/login");
      return;
    }
    if (!user) return;
    // Phase 1C — gate on agreementComplete + participantId.
    if (user.agreementComplete) {
      router.replace("/dashboard?tab=complete-profile");
      return;
    }
    if (!user.participantId) {
      router.replace("/enroll");
      return;
    }
    let cancelled = false;
    (async () => {
      try {
        setProfile(user);
        if (user.fullName) setLegalName(user.fullName);
        const [progRes, termsRes] = await Promise.allSettled([
          getProgramSelection(),
          getTerms(),
        ]);
        if (cancelled) return;
        if (progRes.status === "fulfilled") setProgram(progRes.value);
        if (termsRes.status === "fulfilled") setTerms(termsRes.value);
        setGateChecked(true);
      } catch (err) {
        if (!cancelled) {
          setGateError(err instanceof Error ? err.message : "Couldn't load agreement context");
          setGateChecked(true);
        }
      }
    })();
    return () => { cancelled = true; };
  }, [authLoading, isAuthenticated, user, router]);

  const nameWordCount = legalName.trim().split(/\s+/).filter(Boolean).length;
  const canSign =
    scrolledToBottom
    && confirmAccept
    && nameWordCount >= 2
    && !!signatureData
    && !signing
    && !signed;

  const switchSignatureMethod = (next: "draw" | "upload") => {
    setSignatureMethod(next);
    setSignatureData(null);
    setSignatureError("");
    sigRef.current?.clear();
    if (sigFileInputRef.current) sigFileInputRef.current.value = "";
  };
  const handleDrawEnd = () => {
    const pad = sigRef.current;
    if (!pad || pad.isEmpty()) { setSignatureData(null); return; }
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
      setSignatureError("Use a PNG or JPG file."); return;
    }
    if (file.size > MAX_SIGNATURE_BYTES) {
      setSignatureError("File too large (max 2 MB)."); return;
    }
    const reader = new FileReader();
    reader.onload = () => {
      const dataUrl = String(reader.result || "");
      if (!dataUrl.startsWith("data:image/")) {
        setSignatureError("Couldn't read this file."); return;
      }
      setSignatureData(dataUrl);
      setSignatureError("");
    };
    reader.onerror = () => setSignatureError("Couldn't read this file.");
    reader.readAsDataURL(file);
  };

  const handleScroll = (e: React.UIEvent<HTMLDivElement>) => {
    const t = e.currentTarget;
    if (t.scrollHeight - t.scrollTop - t.clientHeight < 14) {
      setScrolledToBottom(true);
    }
  };

  const handleSign = async () => {
    if (!canSign || !signatureData) return;
    setSigning(true);
    setSignError("");
    try {
      await signParticipantAgreement({
        legalName: legalName.trim(),
        signatureImage: signatureData,
        signatureMethod,
      });
      setSigned(true);
      // Refresh the in-memory user so the auth context picks up
      // the new currentStatus (AGREEMENT_COMPLETED) before we
      // navigate. Without this, /dashboard's guard would see the
      // stale PROGRAM_SELECTED status on the next soft-navigation.
      await refreshUser();
      // Back to the checklist with the next step (check upload)
      // highlighted when the user came from there. Direct deep
      // links land on the checklist without the step hash.
      setTimeout(() => {
        router.push(
          fromProfile
            ? "/dashboard?tab=complete-profile&step=CHECK_UPLOAD"
            : "/dashboard?tab=complete-profile",
        );
      }, 1200);
    } catch (err) {
      setSignError(err instanceof Error ? err.message : "Couldn't sign agreement");
    } finally {
      setSigning(false);
    }
  };

  if (authLoading || !gateChecked) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-50">
        <Loader2 size={28} className="animate-spin text-sage-navy" />
      </div>
    );
  }
  if (gateError) {
    return (
      <OnboardingLayout currentStep={7} contentMaxWidth="xl">
        <div className="bg-white rounded-2xl shadow-lg border border-gray-100 p-6 text-center">
          <AlertCircle size={20} className="text-red-600 inline-block mb-2" />
          <p className="text-sm text-red-700">{gateError}</p>
          <Link href="/program-selection"
            className="text-xs text-sage-navy font-semibold hover:underline mt-3 inline-block">
            ← Back to program selection
          </Link>
        </div>
      </OnboardingLayout>
    );
  }

  return (
    <OnboardingLayout currentStep={7} contentMaxWidth="3xl">
      <AnimatePresence mode="wait">
        {!signed ? (
          <motion.section
            key="form"
            initial={{ opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0 }}
            transition={{ duration: 0.25 }}
            className="bg-white rounded-2xl shadow-lg border border-gray-100 px-5 py-5 sm:px-7 sm:py-6"
          >
            <h1 className="font-serif text-xl sm:text-2xl font-bold text-gray-900">
              Review and sign your agreement
            </h1>
            <p className="text-gray-500 mt-1 text-sm">
              Confirm your details, add your digital signature, then sign your
              agreement. The next step is uploading check soft-copies (or marking
              that step not applicable).
            </p>

            <div className="mt-4 rounded-xl border border-gray-200 bg-gray-50/60 p-4 grid grid-cols-1 sm:grid-cols-2 gap-3 text-sm">
              <SummaryRow label="Name" value={profile?.fullName} />
              <SummaryRow label="Participant ID" value={profile?.participantId} mono />
              <SummaryRow label="Email" value={profile?.email} />
              <SummaryRow label="Phone" value={profile?.phone} />
              <SummaryRow label="Program" value={program?.program} />
              <SummaryRow label="Phase" value={program?.phase} />
              <SummaryRow label="Skillset" value={program?.skillset} />
              <SummaryRow label="Target role" value={program?.targetJobTitle} />
              <SummaryRow label="Availability" value={program?.availability} />
              <SummaryRow
                label="Versions"
                value={`${AGREEMENT_VERSION} · ${ACK_VERSION} · ${SVC_VERSION}`}
                mono
              />
            </div>

            <div className="mt-5">
              <p className="text-[11px] uppercase tracking-wider font-semibold text-gray-500 mb-1.5">
                Terms of Service {terms?.version ?? AGREEMENT_VERSION}
              </p>
              <div
                onScroll={handleScroll}
                className="rounded-xl border border-gray-200 bg-white overflow-y-auto p-4 text-sm text-gray-700 leading-relaxed"
                style={{ height: "min(35vh, 240px)" }}
              >
                {!terms ? (
                  <p className="text-gray-400 italic">Loading terms…</p>
                ) : (
                  <div className="space-y-3">
                    {terms.sections.map((s) => (
                      <div key={s.title}>
                        <p className="text-sm font-bold text-gray-900">{s.title}</p>
                        <p className="text-sm text-gray-700 mt-1">{s.content}</p>
                      </div>
                    ))}
                  </div>
                )}
              </div>
              {!scrolledToBottom && terms && (
                <p className="mt-1 inline-flex items-center gap-1 text-[11px] text-amber-700">
                  <ArrowDown size={11} /> Scroll to read all terms before continuing.
                </p>
              )}
            </div>

            <label className="mt-3 flex items-start gap-2.5 text-sm text-gray-700 cursor-pointer">
              <input
                type="checkbox"
                checked={confirmAccept}
                onChange={(e) => setConfirmAccept(e.target.checked)}
                disabled={!scrolledToBottom}
                className="mt-0.5 w-4 h-4 rounded border-gray-300 text-sage-navy focus:ring-sage-copper disabled:opacity-50"
              />
              <span>
                I confirm all information above is correct and I accept this agreement.{" "}
                <span className="text-red-500">*</span>
              </span>
            </label>

            <div className="mt-5 grid grid-cols-1 sm:grid-cols-2 gap-4">
              <div>
                <label className="block text-[13px] font-medium text-gray-700 mb-1">
                  Legal name <span className="text-red-500">*</span>
                </label>
                <input
                  type="text"
                  value={legalName}
                  onChange={(e) => setLegalName(e.target.value)}
                  placeholder="Your full legal name"
                  className="w-full px-3.5 py-2.5 text-sm rounded-lg border border-gray-200 bg-white focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy"
                />
                {legalName.trim() && nameWordCount < 2 && (
                  <p className="text-[11px] text-red-500 mt-1">Enter first and last name.</p>
                )}
              </div>
              <div>
                <label className="block text-[13px] font-medium text-gray-700 mb-1">
                  Signature method
                </label>
                <div className="inline-flex rounded-lg border border-gray-200 bg-gray-50 p-1">
                  <button
                    type="button"
                    onClick={() => switchSignatureMethod("draw")}
                    className={
                      "inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-bold transition cursor-pointer "
                      + (signatureMethod === "draw"
                          ? "bg-sage-navy text-white shadow-sm"
                          : "bg-transparent text-gray-600 hover:text-sage-navy")
                    }
                  >
                    <PenLine size={12} /> Draw
                  </button>
                  <button
                    type="button"
                    onClick={() => switchSignatureMethod("upload")}
                    className={
                      "inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-bold transition cursor-pointer "
                      + (signatureMethod === "upload"
                          ? "bg-sage-navy text-white shadow-sm"
                          : "bg-transparent text-gray-600 hover:text-sage-navy")
                    }
                  >
                    <UploadIcon size={12} /> Upload
                  </button>
                </div>
              </div>
            </div>

            <div className="mt-3">
              {signatureMethod === "draw" ? (
                <div>
                  <div className="rounded-xl border border-gray-200 bg-white overflow-hidden" style={{ height: 130 }}>
                    {signatureMounted ? (
                      <SignatureCanvas
                        ref={(el) => { sigRef.current = el; }}
                        penColor="#111827"
                        canvasProps={{
                          className: "w-full h-full",
                          style: { width: "100%", height: 130, background: "#fff", cursor: "crosshair", touchAction: "none" },
                        }}
                        onEnd={handleDrawEnd}
                      />
                    ) : (
                      <div className="h-full flex items-center justify-center text-xs text-gray-400">Loading…</div>
                    )}
                  </div>
                  <div className="mt-1.5 flex items-center justify-between">
                    <p className="text-[11px] text-gray-500">Sign here with your mouse or finger.</p>
                    <button type="button" onClick={handleClearDrawn}
                      disabled={!signatureData}
                      className="text-[11px] font-semibold text-sage-navy hover:text-sage-navy-deep disabled:text-gray-400 disabled:cursor-not-allowed cursor-pointer"
                    >Clear &amp; re-sign</button>
                  </div>
                </div>
              ) : (
                <label
                  htmlFor="agree-sig-upload"
                  className="block rounded-xl border-2 border-dashed border-gray-300 hover:border-sage-navy hover:bg-sage-navy/5 bg-gray-50 px-4 py-5 text-center cursor-pointer transition"
                >
                  <UploadIcon size={18} className="mx-auto text-gray-400 mb-1.5" />
                  <p className="text-sm font-semibold text-gray-700">Click to upload</p>
                  <p className="text-[11px] text-gray-500 mt-0.5">PNG / JPG, max 2 MB</p>
                  <input ref={sigFileInputRef} id="agree-sig-upload" type="file"
                    accept="image/png,image/jpeg,image/jpg"
                    onChange={(e) => handleSignatureFile(e.target.files?.[0])} className="hidden" />
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
                    <img src={signatureData} alt="Your signature" style={{ maxWidth: 200, maxHeight: 60 }} />
                  </div>
                  <button type="button"
                    onClick={() => switchSignatureMethod(signatureMethod)}
                    className="inline-flex items-center gap-1 text-[11px] font-semibold text-gray-500 hover:text-red-600 cursor-pointer"
                  >
                    <X size={11} /> Remove
                  </button>
                </div>
              )}
            </div>

            {signError && (
              <p className="mt-3 inline-flex items-center gap-1.5 text-sm text-red-600">
                <AlertCircle size={14} /> {signError}
              </p>
            )}

            <button type="button" onClick={handleSign} disabled={!canSign}
              className={
                "mt-5 w-full inline-flex items-center justify-center gap-2 px-4 py-3 rounded-lg text-sm font-bold transition "
                + (canSign
                    ? "bg-sage-navy text-white hover:bg-sage-navy-deep shadow-md hover:shadow-lg cursor-pointer"
                    : "bg-gray-200 text-gray-500 cursor-not-allowed")
              }
            >
              {signing && <Loader2 size={14} className="animate-spin" />}
              {signing ? "Signing…" : "Sign Agreement →"}
            </button>
          </motion.section>
        ) : (
          <motion.section
            key="done"
            initial={{ opacity: 0, scale: 0.96 }}
            animate={{ opacity: 1, scale: 1 }}
            className="bg-white rounded-2xl shadow-lg border border-gray-100 px-5 py-8 sm:py-10 text-center"
          >
            <div className="inline-flex items-center justify-center w-14 h-14 rounded-2xl bg-emerald-100 text-emerald-700 mb-4">
              <CheckCircle2 size={26} />
            </div>
            <h1 className="text-2xl font-bold text-gray-900">Agreement signed</h1>
            <p className="text-sm text-gray-600 mt-2 max-w-md mx-auto">
              Your signed agreement has been emailed to you. Next: upload check
              soft-copies (or mark this step not applicable).
            </p>
          </motion.section>
        )}
      </AnimatePresence>
    </OnboardingLayout>
  );
}

function SummaryRow({ label, value, mono }: {
  label: string;
  value?: string | null;
  mono?: boolean;
}) {
  return (
    <div className="flex flex-col gap-0.5">
      <span className="text-[10px] uppercase tracking-wider font-semibold text-gray-500">{label}</span>
      <span className={mono ? "font-mono text-[13px] text-gray-800" : "text-[13px] text-gray-800"}>
        {value ?? <FileText size={12} className="inline text-gray-300" />}
      </span>
    </div>
  );
}

export default function AgreementPage() {
  return (
    <Suspense fallback={
      <div className="min-h-screen flex items-center justify-center bg-gray-50">
        <Loader2 size={28} className="animate-spin text-sage-navy" />
      </div>
    }>
      <AgreementPageInner />
    </Suspense>
  );
}
