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
  completeDocuments,
  deleteParticipantDocument,
  documentSatisfiesRequirement,
  getSsnLast4,
  listParticipantDocuments,
  markDocumentNotApplicable,
  saveSsnLast4,
  uploadParticipantDocument,
  viewParticipantDocument,
  type DocumentType,
  type ParticipantDocument,
  loginHere,
} from "@/lib/api";
import { useAuth } from "@/lib/auth-context";

/**
 * Step 5 — Secure document vault.
 *
 * What the participant needs (the list asked for on 30 Sep):
 *   - a photo ID: a driver's license or a State ID (one is enough,
 *     both is fine)
 *   - work authorization
 *   - a resume
 * Each needs an upload, or a "Not applicable" request (with a reason)
 * that Operations approved, before Continue is enabled — the backend
 * re-checks the same rule on /complete so the gate is unbypassable.
 * Optional: the last 4 digits of the SSN (typed, never a document) and
 * any extra supporting files.
 *
 * When Operations sends a document back (rejected upload or declined
 * request) the page opens again, even after the step was finished, so
 * the participant can upload a new one.
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
  /** A driver's license or a State ID: either one covers the photo ID. */
  photoId?: boolean;
}

const SLOTS: ReadonlyArray<SlotConfig> = [
  { type: "DRIVERS_LICENSE",   label: "Driver's License",             description: "A clear photo or scan of your driver's license", required: true, photoId: true, allowNotApplicable: true },
  { type: "GOVERNMENT_ID",     label: "State ID",                     description: "Your State-issued ID card", required: true, photoId: true, allowNotApplicable: true },
  { type: "WORK_AUTHORIZATION", label: "Work Authorization",          description: "H-1B, H-2B, EAD, green card or other proof that you can work in the US", required: true, allowNotApplicable: true },
  { type: "RESUME",            label: "Resume",                       description: "Your updated resume, or an older one if that's what you have", required: true },
  { type: "OTHER",             label: "Additional Supporting Documents", description: "Any additional files reviewers should see", required: false, multiple: true },
];

const PHOTO_ID_SLOTS = SLOTS.filter((s) => s.photoId);
const REQUIRED_SLOTS = SLOTS.filter((s) => s.required && !s.photoId);
const OPTIONAL_SLOTS = SLOTS.filter((s) => !s.required);
const PHOTO_ID_LABEL = "Driver's License or State ID";

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
  // "Not applicable" on a required document: the reason box.
  const [naFor, setNaFor] = useState<DocumentType | null>(null);
  const [naReason, setNaReason] = useState("");
  const [naBusy, setNaBusy] = useState(false);
  // The last 4 digits of the SSN: optional, typed, saved on their own.
  const [ssn, setSsn] = useState("");
  const [ssnSaved, setSsnSaved] = useState("");
  const [ssnBusy, setSsnBusy] = useState(false);
  const [ssnNote, setSsnNote] = useState<{ ok: boolean; text: string } | null>(null);
  const fileInputs = useRef<Partial<Record<DocumentType, HTMLInputElement | null>>>({});

  // ── Gate + load ───────────────────────────────────────────────
  // Phase 1C — gate on documentsComplete + participantId, not the
  // workflow enum (which jumps to DASHBOARD_ENABLED on signup now).
  useEffect(() => {
    if (authLoading) return;
    if (!isAuthenticated) {
      router.replace(loginHere());
      return;
    }
    if (!user) return;
    if (!user.participantId) {
      router.replace("/enroll");
      return;
    }
    // The step before this one must be done first (the acknowledgment).
    if (!user.acknowledgmentComplete) {
      router.replace("/acknowledgment");
      return;
    }
    let cancelled = false;
    (async () => {
      try {
        const docs = await listParticipantDocuments();
        if (cancelled) return;
        // A finished step only opens again when Operations sent a
        // document back; otherwise there's nothing to do here.
        const sentBack = docs.some((d) =>
          d.reviewStatus === "REJECTED" || d.reviewStatus === "EXCEPTION_DECLINED");
        if (user.documentsComplete && !sentBack) {
          router.replace("/dashboard?tab=complete-profile");
          return;
        }
        setDocuments(docs);
        setGateChecked(true);
        // Optional, so a failure here never blocks the page.
        getSsnLast4()
          .then((v) => { if (!cancelled) { setSsn(v); setSsnSaved(v); } })
          .catch(() => {});
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

  const requiredSatisfied = (s: SlotConfig): boolean =>
    slotDocs(s.type).some(documentSatisfiesRequirement);

  // One photo ID (either slot) covers that requirement.
  const photoIdSatisfied = PHOTO_ID_SLOTS.some(requiredSatisfied);
  const photoIdWaiting = !photoIdSatisfied
    && PHOTO_ID_SLOTS.some((s) => slotDocs(s.type).some((d) => d.reviewStatus === "EXCEPTION_REQUESTED"));

  const requiredCount = REQUIRED_SLOTS.length + 1;
  const completedRequired = REQUIRED_SLOTS.filter(requiredSatisfied).length + (photoIdSatisfied ? 1 : 0);
  const progressPct = Math.round((completedRequired / requiredCount) * 100);
  const missingRequired = REQUIRED_SLOTS.filter((s) => !requiredSatisfied(s));
  const missingCount = missingRequired.length + (photoIdSatisfied ? 0 : 1);
  const canContinue = missingCount === 0 && !completing;

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

  const handleMarkNA = async (slot: SlotConfig) => {
    // A required document needs a reason for Operations first.
    if (slot.required && naFor !== slot.type) {
      setNaFor(slot.type);
      setNaReason("");
      setUploadError(null);
      return;
    }
    setNaBusy(true);
    setUploadError(null);
    try {
      await markDocumentNotApplicable(slot.type, slot.required ? naReason.trim() : undefined);
      setNaFor(null);
      setNaReason("");
      await refreshDocuments();
    } catch (err) {
      setUploadError({ type: slot.type, message: err instanceof Error ? err.message : "Couldn't mark as N/A." });
    } finally {
      setNaBusy(false);
    }
  };

  const handleSaveSsn = async (value: string) => {
    setSsnNote(null);
    if (value !== "" && !/^\d{4}$/.test(value)) {
      setSsnNote({ ok: false, text: "Enter exactly the last 4 digits, or leave it empty." });
      return;
    }
    setSsnBusy(true);
    try {
      const saved = await saveSsnLast4(value);
      setSsn(saved);
      setSsnSaved(saved);
      setSsnNote({ ok: true, text: saved ? `Saved as XXX-XX-${saved}, stored encrypted.` : "Removed." });
    } catch (err) {
      setSsnNote({ ok: false, text: err instanceof Error ? err.message : "Couldn't save." });
    } finally {
      setSsnBusy(false);
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
    // A "not applicable" marker: plain N/A (optional) or an exception
    // request Operations decides (required).
    const marker = !slot.multiple && docs.length > 0 && docs[0].notApplicable ? docs[0] : null;
    const isMarkedNA = marker !== null;
    const declined = marker?.reviewStatus === "EXCEPTION_DECLINED";
    const filesOk = docs.length > 0 && !isMarkedNA && docs.every((d) => d.reviewStatus !== "REJECTED");
    const uploading = uploadingType === slot.type;
    const errorHere = uploadError && uploadError.type === slot.type ? uploadError.message : null;

    return (
      <li key={slot.type} className="p-4 sm:p-5 first:pt-4 last:pb-4 border-b border-gray-100 last:border-b-0">
        <div className="flex items-start gap-3">
          <div className="shrink-0 mt-0.5">
            {filesOk ? (
              <span className="inline-flex items-center justify-center w-6 h-6 rounded-full bg-emerald-100 text-emerald-700">
                <CheckCircle2 size={14} />
              </span>
            ) : isMarkedNA && !declined ? (
              <span className="inline-flex items-center justify-center w-6 h-6 rounded-full bg-gray-200 text-gray-500 text-[10px] font-bold">
                N/A
              </span>
            ) : (
              <span className="inline-flex items-center justify-center w-6 h-6 rounded-full bg-white border border-gray-300 text-gray-400 text-[11px] font-bold">
                {slot.required && !(slot.photoId && photoIdSatisfied) ? "!" : "○"}
              </span>
            )}
          </div>
          <div className="flex-1 min-w-0">
            <div className="flex items-center gap-2 flex-wrap">
              <p className="text-sm font-semibold text-gray-900">{slot.label}</p>
              {slot.photoId ? (
                <span className="text-[10px] font-bold uppercase tracking-wider text-sage-navy bg-sage-navy/10 px-1.5 py-0.5 rounded">One is enough</span>
              ) : slot.required ? (
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
                      className="text-sage-navy hover:text-sage-navy-deep cursor-pointer"
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

            {marker?.reviewStatus === "NOT_APPLICABLE" && (
              <p className="mt-2 text-xs text-gray-500 italic">Marked Not Applicable.</p>
            )}
            {marker?.reviewStatus === "EXCEPTION_REQUESTED" && (
              <div className="mt-2 space-y-0.5">
                <p className="text-xs text-gray-500 italic">
                  Marked Not Applicable. Waiting for Operations to approve.
                </p>
                {marker.exceptionReason && (
                  <p className="text-[11px] text-gray-500 italic">Your reason: {marker.exceptionReason}</p>
                )}
              </div>
            )}
            {marker?.reviewStatus === "EXCEPTION_APPROVED" && (
              <p className="mt-2 text-xs text-gray-500 italic">Not applicable, approved by Operations.</p>
            )}
            {declined && (
              <p className="mt-2 text-[11px] text-red-700 italic">
                Operations needs this document{marker?.reviewerNotes ? `: ${marker.reviewerNotes}` : "."}
              </p>
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
              {(((docs.length === 0 || slot.multiple) && !isMarkedNA) || declined) && (
                <button
                  type="button"
                  onClick={() => fileInputs.current[slot.type]?.click()}
                  disabled={uploading}
                  className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-60 disabled:cursor-not-allowed transition cursor-pointer"
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
                  className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-bold bg-white border border-gray-200 text-gray-700 hover:border-sage-navy hover:text-sage-navy disabled:opacity-60 transition cursor-pointer"
                >
                  {uploading ? <Loader2 size={12} className="animate-spin" /> : <UploadIcon size={12} />}
                  {docs[0].reviewStatus === "REJECTED" ? "Upload again" : "Replace"}
                </button>
              )}
              {slot.allowNotApplicable && (!isMarkedNA || declined)
                && !(slot.photoId && (photoIdSatisfied || docs.length > 0))
                && docs[0]?.reviewStatus !== "APPROVED" && naFor !== slot.type && (
                <button
                  type="button"
                  onClick={() => handleMarkNA(slot)}
                  className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold bg-transparent text-gray-500 hover:text-gray-800 hover:bg-gray-100 transition cursor-pointer"
                >
                  Not applicable
                </button>
              )}
              {(marker?.reviewStatus === "NOT_APPLICABLE" || marker?.reviewStatus === "EXCEPTION_REQUESTED") && (
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

            {naFor === slot.type && (
              <div className="mt-2 space-y-1.5">
                <label htmlFor={`na-reason-${slot.type}`} className="block text-[11px] text-gray-600">
                  This document is required. Tell Operations why it doesn&apos;t apply to you; they&apos;ll review your request.
                </label>
                <textarea
                  id={`na-reason-${slot.type}`}
                  value={naReason}
                  onChange={(e) => setNaReason(e.target.value)}
                  rows={2}
                  maxLength={1000}
                  placeholder={slot.photoId
                    ? "For example: I don't have a driver's license or a State ID yet."
                    : "For example: I'm a US citizen, so I don't have a visa or work permit."}
                  className="w-full px-3 py-2 text-xs rounded-lg border border-gray-200 bg-white text-gray-900 placeholder-gray-400 transition focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy resize-none"
                />
                <div className="flex flex-wrap items-center gap-2">
                  <button
                    type="button"
                    onClick={() => handleMarkNA(slot)}
                    disabled={naBusy || naReason.trim().length < 5}
                    className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-60 disabled:cursor-not-allowed transition cursor-pointer"
                  >
                    {naBusy && <Loader2 size={12} className="animate-spin" />}
                    Send to Operations
                  </button>
                  <button
                    type="button"
                    onClick={() => { setNaFor(null); setNaReason(""); }}
                    className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold bg-transparent text-gray-500 hover:text-gray-800 hover:bg-gray-100 transition cursor-pointer"
                  >
                    Cancel
                  </button>
                </div>
              </div>
            )}

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
  }, [documents, uploadingType, uploadError, naFor, naReason, naBusy, photoIdSatisfied]);

  if (authLoading || !gateChecked) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-50">
        <Loader2 size={28} className="animate-spin text-sage-navy" />
      </div>
    );
  }

  if (gateError) {
    return (
      <OnboardingLayout currentStep={5} contentMaxWidth="xl">
        <div className="bg-white rounded-2xl shadow-lg border border-gray-100 p-6 text-center">
          <AlertCircle size={20} className="text-red-600 inline-block mb-2" />
          <p className="text-sm text-red-700">{gateError}</p>
          <Link href="/acknowledgment" className="text-xs text-sage-navy font-semibold hover:underline mt-3 inline-block">
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
            <span className="font-mono text-sage-navy font-bold">{progressPct}%</span>
          </div>
          <div className="mt-1.5 h-2 w-full rounded-full bg-gray-100 overflow-hidden">
            <div
              className="h-full bg-sage-navy transition-all"
              style={{ width: `${progressPct}%` }}
            />
          </div>
        </div>

        {/* Photo ID: either one */}
        <div className="mt-5">
          <p className="text-[11px] uppercase tracking-wider text-gray-500 font-semibold mb-1.5">
            Photo ID
          </p>
          <p className="text-xs text-gray-500 mb-1.5">
            Upload your driver&apos;s license or your State ID. One is enough; if you have both, you can upload both.
          </p>
          <ul className="rounded-xl border border-gray-200 bg-white divide-y divide-gray-100">
            {PHOTO_ID_SLOTS.map(renderSlot)}
          </ul>
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

        {/* Optional: SSN digits (typed) and extra files */}
        <div className="mt-5">
          <p className="text-[11px] uppercase tracking-wider text-gray-500 font-semibold mb-1.5">
            Optional
          </p>
          <ul className="rounded-xl border border-gray-200 bg-white divide-y divide-gray-100">
            <li className="p-4 sm:p-5 first:pt-4 border-b border-gray-100">
              <div className="flex items-start gap-3">
                <div className="shrink-0 mt-0.5">
                  {ssnSaved ? (
                    <span className="inline-flex items-center justify-center w-6 h-6 rounded-full bg-emerald-100 text-emerald-700">
                      <CheckCircle2 size={14} />
                    </span>
                  ) : (
                    <span className="inline-flex items-center justify-center w-6 h-6 rounded-full bg-white border border-gray-300 text-gray-400 text-[11px] font-bold">
                      ○
                    </span>
                  )}
                </div>
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 flex-wrap">
                    <label htmlFor="ssn-last4" className="text-sm font-semibold text-gray-900">
                      Social Security Number (last 4 digits)
                    </label>
                    <span className="text-[10px] font-bold uppercase tracking-wider text-gray-500 bg-gray-100 px-1.5 py-0.5 rounded">Optional</span>
                  </div>
                  <p id="ssn-hint" className="text-xs text-gray-500 mt-0.5">
                    We only need the last 4 digits; the first five stay hidden. Please don&apos;t upload your SSN card.
                  </p>
                  <div className="mt-2 flex flex-wrap items-center gap-2">
                    {/* Shown the way a masked SSN reads (XXX-XX-1234); only the last 4 are typed. */}
                    <div className="inline-flex items-center rounded-lg border border-gray-200 bg-white transition focus-within:border-sage-navy focus-within:ring-1 focus-within:ring-sage-navy">
                      <span
                        aria-hidden="true"
                        className="pl-3 py-1.5 font-mono text-sm tracking-[0.15em] text-gray-400 select-none"
                      >
                        XXX-XX-
                      </span>
                      <input
                        id="ssn-last4"
                        type="text"
                        inputMode="numeric"
                        autoComplete="off"
                        maxLength={4}
                        value={ssn}
                        onChange={(e) => { setSsn(e.target.value.replace(/\D/g, "").slice(0, 4)); setSsnNote(null); }}
                        onKeyDown={(e) => { if (e.key === "Enter") handleSaveSsn(ssn); }}
                        placeholder="____"
                        aria-describedby="ssn-hint"
                        className="w-[8ch] pr-3 py-1.5 bg-transparent font-mono text-sm tracking-[0.15em] text-gray-900 placeholder-gray-300 focus:outline-none"
                      />
                    </div>
                    <button
                      type="button"
                      onClick={() => handleSaveSsn(ssn)}
                      disabled={ssnBusy || ssn === ssnSaved}
                      className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-60 disabled:cursor-not-allowed transition cursor-pointer"
                    >
                      {ssnBusy && <Loader2 size={12} className="animate-spin" />}
                      Save
                    </button>
                    {ssnSaved && (
                      <button
                        type="button"
                        onClick={() => handleSaveSsn("")}
                        disabled={ssnBusy}
                        className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold bg-transparent text-gray-500 hover:text-gray-800 hover:bg-gray-100 transition cursor-pointer"
                      >
                        Remove
                      </button>
                    )}
                  </div>
                  {ssnSaved && !ssnNote && (
                    <p className="mt-1.5 inline-flex items-center gap-1 text-[11px] text-gray-500">
                      <Lock size={11} /> On file as <span className="font-mono text-gray-700">XXX-XX-{ssnSaved}</span> (stored encrypted)
                    </p>
                  )}
                  {ssnNote && (
                    <p className={"mt-1.5 text-[11px] " + (ssnNote.ok ? "text-emerald-700" : "text-red-600")}>
                      {ssnNote.text}
                    </p>
                  )}
                </div>
              </div>
            </li>
            {OPTIONAL_SLOTS.map(renderSlot)}
          </ul>
        </div>

        {/* Missing list */}
        {missingCount > 0 && (
          <div className="mt-4 rounded-lg border border-amber-200 bg-amber-50 px-4 py-3">
            <p className="text-xs font-semibold text-amber-800 inline-flex items-center gap-1.5">
              <AlertCircle size={12} />
              {missingCount} required {missingCount === 1 ? "document" : "documents"} still missing:
            </p>
            <ul className="mt-1 text-xs text-amber-900 list-disc list-inside">
              {!photoIdSatisfied && (
                <li>
                  {PHOTO_ID_LABEL}
                  {photoIdWaiting && " (not applicable: waiting for Operations)"}
                </li>
              )}
              {missingRequired.map((s) => (
                <li key={s.type}>
                  {s.label}
                  {slotDoc(s.type)?.reviewStatus === "EXCEPTION_REQUESTED" && " (not applicable: waiting for Operations)"}
                </li>
              ))}
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
            className="inline-flex items-center justify-center gap-2 px-4 py-2.5 rounded-lg text-sm font-semibold bg-white border border-gray-200 text-gray-700 hover:border-sage-navy hover:text-sage-navy disabled:opacity-60 transition cursor-pointer"
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
                  ? "bg-sage-navy text-white hover:bg-sage-navy-deep shadow-md hover:shadow-lg cursor-pointer"
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
      <div className="min-h-screen flex items-center justify-center bg-gray-50">
        <Loader2 size={28} className="animate-spin text-sage-navy" />
      </div>
    }>
      <DocumentUploadPageInner />
    </Suspense>
  );
}
