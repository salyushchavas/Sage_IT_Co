"use client";

import { Suspense, useEffect, useMemo, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import Link from "next/link";
import { motion } from "framer-motion";
import {
  AlertCircle, CheckCircle2, FileText, Loader2, Lock, Save,
  Trash2, Upload as UploadIcon, Eye,
} from "lucide-react";

import OnboardingLayout from "@/components/layouts/OnboardingLayout";
import {
  completeDocuments, deleteParticipantDocument,
  listParticipantDocuments,
  markDocumentNotApplicable, uploadParticipantDocument,
  viewParticipantDocument,
  type DocumentType, type ParticipantDocument,
} from "@/lib/api";
import { useAuth } from "@/lib/auth-context";

/**
 * Step 5 — Secure document vault.
 *
 * The participant has three required slots (Government ID, Work
 * Authorization, Resume) plus optional slots (SSN, Driver's
 * License, Other). Required slots must each carry an upload OR a
 * "Not Applicable" marker before Continue is enabled — the backend
 * re-checks the same rule on /complete so the gate is unbypassable.
 *
 * "Save and Continue Later" exits the page; the uploads persisted
 * so far stay on record and the routing guard sends the user back
 * here next login.
 */

interface SlotConfig {
  type: DocumentType;
  label: string;
  description: string;
  required: boolean;
  /** Slots that support an explicit "Not applicable" toggle. */
  allowNotApplicable?: boolean;
  /** OTHER bucket accepts multiple uploads. */
  multiple?: boolean;
}

const SLOTS: ReadonlyArray<SlotConfig> = [
  { type: "GOVERNMENT_ID",     label: "Government-issued ID",        description: "Passport, Aadhaar, or Driving Licence", required: true },
  { type: "WORK_AUTHORIZATION", label: "Work Authorization / Visa",   description: "Visa, work permit, or other proof of work authorization", required: true, allowNotApplicable: true },
  { type: "RESUME",            label: "Resume / CV",                  description: "Your most recent resume", required: true },
  { type: "SSN_DOCUMENT",      label: "SSN Document",                 description: "If applicable to your jurisdiction", required: false, allowNotApplicable: true },
  { type: "DRIVERS_LICENSE",   label: "Driving Licence",              description: "Separate from primary ID if applicable", required: false, allowNotApplicable: true },
  { type: "OTHER",             label: "Additional Supporting Documents", description: "Any additional files reviewers should see", required: false, multiple: true },
];

const REQUIRED_SLOTS = SLOTS.filter((s) => s.required);
const OPTIONAL_SLOTS = SLOTS.filter((s) => !s.required);

function formatBytes(bytes: number | null | undefined): string {
  if (!bytes && bytes !== 0) return "—";
  if (bytes < 1024) return bytes + " B";
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(0) + " KB";
  return (bytes / (1024 * 1024)).toFixed(1) + " MB";
}

function DocumentUploadPageInner() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const fromProfile = searchParams.get("from") === "profile";
  const { user, isAuthenticated, isLoading: authLoading, refreshUser } = useAuth();

  const [gateChecked, setGateChecked] = useState(false);
  const [gateError, setGateError] = useState("");
  const [documents, setDocuments] = useState<ParticipantDocument[]>([]);
  const [refreshing, setRefreshing] = useState(true);

  const [uploadingType, setUploadingType] = useState<DocumentType | null>(null);
  const [uploadError, setUploadError] = useState<{ type: DocumentType; message: string } | null>(null);
  const [completeError, setCompleteError] = useState("");
  const [completing, setCompleting] = useState(false);
  const fileInputs = useRef<Partial<Record<DocumentType, HTMLInputElement | null>>>({});

  // ── Gate + load ───────────────────────────────────────────────
  // Phase 1C — gate on documentsComplete + participantId, not the
  // workflow enum (which jumps to DASHBOARD_ENABLED on signup now).
  useEffect(() => {
    if (authLoading) return;
    if (!isAuthenticated) {
      router.replace("/login");
      return;
    }
    if (!user) return;
    if (user.documentsComplete) {
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
        const docs = await listParticipantDocuments();
        if (cancelled) return;
        setDocuments(docs);
        setGateChecked(true);
      } catch (err) {
        if (cancelled) return;
        setGateError(err instanceof Error ? err.message : "Couldn't load documents.");
        setGateChecked(true);
      } finally {
        if (!cancelled) setRefreshing(false);
      }
    })();
    return () => { cancelled = true; };
  }, [authLoading, isAuthenticated, user, router]);

  // ── Slot status helpers ───────────────────────────────────────

  /** For single-instance slots returns the latest doc (or null);
   *  for the OTHER bucket returns null since multiple are allowed. */
  const slotDoc = (type: DocumentType): ParticipantDocument | null => {
    const matches = documents.filter((d) => d.documentType === type);
    if (matches.length === 0) return null;
    // Latest by id (DB autoincrement).
    return matches.reduce((a, b) => (a.id > b.id ? a : b));
  };

  /** All docs of a given type (used for the multi-allowed OTHER slot). */
  const slotDocs = (type: DocumentType): ParticipantDocument[] =>
    documents.filter((d) => d.documentType === type);

  const requiredSatisfied = (s: SlotConfig): boolean => {
    const docs = slotDocs(s.type);
    return docs.some((d) => d.reviewStatus !== "REJECTED");
  };

  const requiredCount = REQUIRED_SLOTS.length;
  const completedRequired = REQUIRED_SLOTS.filter(requiredSatisfied).length;
  const progressPct = Math.round((completedRequired / requiredCount) * 100);
  const missingRequired = REQUIRED_SLOTS.filter((s) => !requiredSatisfied(s));
  const canContinue = missingRequired.length === 0 && !completing;

  // ── Actions ──────────────────────────────────────────────────

  const refreshDocuments = async () => {
    try {
      const docs = await listParticipantDocuments();
      setDocuments(docs);
    } catch (err) {
      setCompleteError(err instanceof Error ? err.message : "Couldn't refresh documents");
    }
  };

  const handleFilePicked = async (type: DocumentType, file: File | null | undefined) => {
    if (!file) return;
    setUploadError(null);
    if (file.size > 10 * 1024 * 1024) {
      setUploadError({ type, message: "File too large (max 10 MB)." });
      return;
    }
    const okType = /\.(pdf|jpe?g|png)$/i.test(file.name)
      || ["application/pdf", "image/png", "image/jpeg", "image/jpg"].includes(file.type);
    if (!okType) {
      setUploadError({ type, message: "Only PDF, JPG, or PNG files are allowed." });
      return;
    }
    setUploadingType(type);
    try {
      await uploadParticipantDocument(type, file);
      await refreshDocuments();
    } catch (err) {
      setUploadError({ type, message: err instanceof Error ? err.message : "Upload failed" });
    } finally {
      setUploadingType(null);
      // Reset the input so re-selecting the same file fires onChange.
      const el = fileInputs.current[type];
      if (el) el.value = "";
    }
  };

  const handleRemove = async (doc: ParticipantDocument) => {
    if (!confirm(`Remove ${doc.fileName || "this document"}?`)) return;
    try {
      await deleteParticipantDocument(doc.id);
      await refreshDocuments();
    } catch (err) {
      alert(err instanceof Error ? err.message : "Couldn't remove the document.");
    }
  };

  const handleMarkNA = async (type: DocumentType) => {
    try {
      await markDocumentNotApplicable(type);
      await refreshDocuments();
    } catch (err) {
      alert(err instanceof Error ? err.message : "Couldn't mark as N/A.");
    }
  };

  const handleView = async (doc: ParticipantDocument) => {
    try {
      await viewParticipantDocument(doc.id);
    } catch (err) {
      alert(err instanceof Error ? err.message : "Couldn't open the document.");
    }
  };

  const handleSaveLater = () => {
    // Per PRD: no status change. Uploads are already persisted —
    // bounce to /dashboard so the user has somewhere to land. Next
    // login the routing guard sends them back here.
    router.push("/dashboard");
  };

  const handleContinue = async () => {
    if (!canContinue) return;
    setCompleting(true);
    setCompleteError("");
    try {
      const res = await completeDocuments();
      if (res.success) {
        await refreshUser();
        router.replace(
          fromProfile
            ? "/dashboard?tab=complete-profile&step=PROGRAM_SELECTION"
            : "/dashboard?tab=complete-profile",
        );
      } else {
        setCompleteError(res.message ?? "Some required documents are still missing.");
      }
    } catch (err) {
      setCompleteError(err instanceof Error ? err.message : "Couldn't continue");
    } finally {
      setCompleting(false);
    }
  };

  // ── Render ───────────────────────────────────────────────────

  const renderSlot = useMemo(() => (slot: SlotConfig) => {
    const docs = slot.multiple ? slotDocs(slot.type) : (slotDoc(slot.type) ? [slotDoc(slot.type)!] : []);
    const isMarkedNA = docs.length > 0 && docs[0].reviewStatus === "NOT_APPLICABLE";
    const uploading = uploadingType === slot.type;
    const errorHere = uploadError && uploadError.type === slot.type ? uploadError.message : null;

    return (
      <li key={slot.type} className="p-4 sm:p-5 first:pt-4 last:pb-4 border-b border-gray-100 last:border-b-0">
        <div className="flex items-start gap-3">
          <div className="shrink-0 mt-0.5">
            {docs.length > 0 && !isMarkedNA ? (
              <span className="inline-flex items-center justify-center w-6 h-6 rounded-full bg-emerald-100 text-emerald-700">
                <CheckCircle2 size={14} />
              </span>
            ) : isMarkedNA ? (
              <span className="inline-flex items-center justify-center w-6 h-6 rounded-full bg-gray-200 text-gray-500 text-[10px] font-bold">
                N/A
              </span>
            ) : (
              <span className="inline-flex items-center justify-center w-6 h-6 rounded-full bg-white border border-gray-300 text-gray-400 text-[11px] font-bold">
                {slot.required ? "!" : "○"}
              </span>
            )}
          </div>
          <div className="flex-1 min-w-0">
            <div className="flex items-center gap-2 flex-wrap">
              <p className="text-sm font-semibold text-gray-900">{slot.label}</p>
              {slot.required ? (
                <span className="text-[10px] font-bold uppercase tracking-wider text-red-600 bg-red-50 px-1.5 py-0.5 rounded">Required</span>
              ) : (
                <span className="text-[10px] font-bold uppercase tracking-wider text-gray-500 bg-gray-100 px-1.5 py-0.5 rounded">Optional</span>
              )}
            </div>
            <p className="text-xs text-gray-500 mt-0.5">{slot.description}</p>

            {/* Uploaded files list */}
            {docs.length > 0 && !isMarkedNA && (
              <div className="mt-2 space-y-1.5">
                {docs.map((d) => (
                  <div
                    key={d.id}
                    className={`flex items-center gap-2 rounded-lg border px-3 py-2 text-xs ${
                      d.reviewStatus === "REJECTED"
                        ? "border-red-200 bg-red-50/50"
                        : "border-gray-200 bg-gray-50/60"
                    }`}
                  >
                    <FileText size={14} className="shrink-0 text-gray-500" />
                    <span className="flex-1 min-w-0 truncate font-medium text-gray-800">
                      {d.fileName ?? "Document"}
                    </span>
                    <span className="text-gray-500 shrink-0">{formatBytes(d.fileSize)}</span>
                    {d.reviewStatus === "REJECTED" && (
                      <span className="text-[10px] font-bold uppercase tracking-wider text-red-700 bg-red-100 px-1.5 py-0.5 rounded">
                        Rejected
                      </span>
                    )}
                    {d.reviewStatus === "APPROVED" && (
                      <span className="text-[10px] font-bold uppercase tracking-wider text-emerald-700 bg-emerald-100 px-1.5 py-0.5 rounded">
                        Approved
                      </span>
                    )}
                    <button
                      type="button"
                      onClick={() => handleView(d)}
                      className="text-[#1B2A5C] hover:text-[#2D4480] cursor-pointer"
                      aria-label="View"
                    >
                      <Eye size={14} />
                    </button>
                    {d.reviewStatus !== "APPROVED" && (
                      <button
                        type="button"
                        onClick={() => handleRemove(d)}
                        className="text-gray-400 hover:text-red-600 cursor-pointer"
                        aria-label="Remove"
                      >
                        <Trash2 size={14} />
                      </button>
                    )}
                  </div>
                ))}
                {docs[0] && docs[0].reviewerNotes && docs[0].reviewStatus === "REJECTED" && (
                  <p className="text-[11px] text-red-700 italic px-1">
                    Operations note: {docs[0].reviewerNotes}
                  </p>
                )}
              </div>
            )}

            {isMarkedNA && (
              <p className="mt-2 text-xs text-gray-500 italic">Marked Not Applicable.</p>
            )}

            {/* Upload + N/A actions */}
            <div className="mt-2 flex flex-wrap items-center gap-2">
              <input
                ref={(el) => { fileInputs.current[slot.type] = el; }}
                type="file"
                accept="application/pdf,image/png,image/jpeg,image/jpg"
                className="hidden"
                onChange={(e) => handleFilePicked(slot.type, e.target.files?.[0])}
              />
              {(docs.length === 0 || slot.multiple) && !isMarkedNA && (
                <button
                  type="button"
                  onClick={() => fileInputs.current[slot.type]?.click()}
                  disabled={uploading}
                  className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-bold bg-[#1B2A5C] text-white hover:bg-[#2D4480] disabled:opacity-60 disabled:cursor-not-allowed transition cursor-pointer"
                >
                  {uploading ? <Loader2 size={12} className="animate-spin" /> : <UploadIcon size={12} />}
                  {uploading ? "Uploading…" : (docs.length > 0 ? "Add another" : "Upload")}
                </button>
              )}
              {!slot.multiple && docs.length > 0 && !isMarkedNA && docs[0].reviewStatus !== "APPROVED" && (
                <button
                  type="button"
                  onClick={() => fileInputs.current[slot.type]?.click()}
                  disabled={uploading}
                  className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-bold bg-white border border-gray-200 text-gray-700 hover:border-[#1B2A5C] hover:text-[#1B2A5C] disabled:opacity-60 transition cursor-pointer"
                >
                  {uploading ? <Loader2 size={12} className="animate-spin" /> : <UploadIcon size={12} />}
                  Replace
                </button>
              )}
              {slot.allowNotApplicable && !isMarkedNA && (
                <button
                  type="button"
                  onClick={() => handleMarkNA(slot.type)}
                  className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold bg-transparent text-gray-500 hover:text-gray-800 hover:bg-gray-100 transition cursor-pointer"
                >
                  Not applicable
                </button>
              )}
              {isMarkedNA && (
                <button
                  type="button"
                  onClick={() => {
                    // Removing the N/A marker is just delete-the-row.
                    const naRow = docs[0];
                    if (naRow) handleRemove(naRow);
                  }}
                  className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold bg-transparent text-gray-500 hover:text-gray-800 hover:bg-gray-100 transition cursor-pointer"
                >
                  Undo N/A
                </button>
              )}
            </div>

            {errorHere && (
              <p className="mt-1.5 inline-flex items-center gap-1 text-[11px] text-red-600">
                <AlertCircle size={11} /> {errorHere}
              </p>
            )}
          </div>
        </div>
      </li>
    );
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [documents, uploadingType, uploadError]);

  if (authLoading || !gateChecked) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-[#F8FAFC]">
        <Loader2 size={28} className="animate-spin text-[#1B2A5C]" />
      </div>
    );
  }

  if (gateError) {
    return (
      <OnboardingLayout currentStep={5} contentMaxWidth="xl">
        <div className="bg-white rounded-2xl shadow-lg border border-gray-100 p-6 text-center">
          <AlertCircle size={20} className="text-red-600 inline-block mb-2" />
          <p className="text-sm text-red-700">{gateError}</p>
          <Link href="/acknowledgment" className="text-xs text-[#1B2A5C] font-semibold hover:underline mt-3 inline-block">
            ← Back to acknowledgment
          </Link>
        </div>
      </OnboardingLayout>
    );
  }

  return (
    <OnboardingLayout currentStep={5} contentMaxWidth="3xl">
      <motion.section
        initial={{ opacity: 0, y: 12 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.3 }}
        className="bg-white rounded-2xl shadow-lg border border-gray-100 px-5 py-5 sm:px-7 sm:py-6"
      >
        <h1 className="font-serif text-xl sm:text-2xl font-bold text-gray-900">
          Upload required documents
        </h1>
        <p className="text-gray-500 mt-1 text-sm">
          Please upload the following documents to continue. All documents are stored
          securely and encrypted.
        </p>

        {/* Progress summary */}
        <div className="mt-4">
          <div className="flex items-center justify-between text-xs">
            <span className="text-gray-500">
              <strong className="text-gray-800">{completedRequired}</strong> of{" "}
              <strong className="text-gray-800">{requiredCount}</strong> required documents uploaded
            </span>
            <span className="font-mono text-[#1B2A5C] font-bold">{progressPct}%</span>
          </div>
          <div className="mt-1.5 h-2 w-full rounded-full bg-gray-100 overflow-hidden">
            <div
              className="h-full bg-[#1B2A5C] transition-all"
              style={{ width: `${progressPct}%` }}
            />
          </div>
        </div>

        {/* Required slots */}
        <div className="mt-5">
          <p className="text-[11px] uppercase tracking-wider text-gray-500 font-semibold mb-1.5">
            Required documents
          </p>
          <ul className="rounded-xl border border-gray-200 bg-white divide-y divide-gray-100">
            {REQUIRED_SLOTS.map(renderSlot)}
          </ul>
        </div>

        {/* Optional slots */}
        <div className="mt-5">
          <p className="text-[11px] uppercase tracking-wider text-gray-500 font-semibold mb-1.5">
            Optional documents
          </p>
          <ul className="rounded-xl border border-gray-200 bg-white divide-y divide-gray-100">
            {OPTIONAL_SLOTS.map(renderSlot)}
          </ul>
        </div>

        {/* Missing list */}
        {missingRequired.length > 0 && (
          <div className="mt-4 rounded-lg border border-amber-200 bg-amber-50 px-4 py-3">
            <p className="text-xs font-semibold text-amber-800 inline-flex items-center gap-1.5">
              <AlertCircle size={12} />
              {missingRequired.length} required {missingRequired.length === 1 ? "document" : "documents"} still missing:
            </p>
            <ul className="mt-1 text-xs text-amber-900 list-disc list-inside">
              {missingRequired.map((s) => <li key={s.type}>{s.label}</li>)}
            </ul>
          </div>
        )}

        {completeError && (
          <p className="mt-3 inline-flex items-center gap-1.5 text-sm text-red-600">
            <AlertCircle size={14} /> {completeError}
          </p>
        )}

        {/* Action buttons */}
        <div className="mt-5 flex flex-col sm:flex-row gap-2">
          <button
            type="button"
            onClick={handleSaveLater}
            disabled={refreshing || completing}
            className="inline-flex items-center justify-center gap-2 px-4 py-2.5 rounded-lg text-sm font-semibold bg-white border border-gray-200 text-gray-700 hover:border-[#1B2A5C] hover:text-[#1B2A5C] disabled:opacity-60 transition cursor-pointer"
          >
            <Save size={14} /> Save and continue later
          </button>
          <button
            type="button"
            onClick={handleContinue}
            disabled={!canContinue}
            className={
              "flex-1 inline-flex items-center justify-center gap-2 px-4 py-2.5 rounded-lg text-sm font-bold transition "
              + (canContinue
                  ? "bg-[#1B2A5C] text-white hover:bg-[#2D4480] shadow-md hover:shadow-lg cursor-pointer"
                  : "bg-gray-200 text-gray-500 cursor-not-allowed")
            }
          >
            {completing && <Loader2 size={14} className="animate-spin" />}
            {completing ? "Submitting…" : "Continue to Program →"}
          </button>
        </div>

        <p className="mt-3 inline-flex items-start gap-1.5 text-[11px] text-gray-500">
          <Lock size={11} className="mt-0.5 shrink-0" />
          Your documents are encrypted and stored securely. Only authorized team members
          can access them.
        </p>
      </motion.section>
    </OnboardingLayout>
  );
}

export default function DocumentUploadPage() {
  return (
    <Suspense fallback={
      <div className="min-h-screen flex items-center justify-center bg-[#F8FAFC]">
        <Loader2 size={28} className="animate-spin text-[#1B2A5C]" />
      </div>
    }>
      <DocumentUploadPageInner />
    </Suspense>
  );
}
