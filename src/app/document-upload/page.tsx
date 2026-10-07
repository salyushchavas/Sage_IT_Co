"use client";

import { Suspense, useEffect, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import Link from "next/link";
import { motion } from "framer-motion";
import {
  AlertCircle, CheckCircle2, ChevronRight, Circle, Eye, FileText, Loader2, Lock,
  RefreshCw, Save, ShieldCheck, Trash2, Upload as UploadIcon,
} from "lucide-react";

import OnboardingLayout from "@/components/layouts/OnboardingLayout";
import { PROFILE_STEPS } from "@/components/OnboardingProgressBar";
import {
  completeDocuments,
  dashboardRouteForRole,
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
import { formatDateMedium } from "@/lib/datetime";

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
  // Set while finishing the step: refreshing the user re-runs the gate
  // below, which must not replace the redirect to the next step.
  const finishingRef = useRef(false);

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
    if (finishingRef.current) return;
    // Staff have no participant steps: their own dashboard, not the Apply form.
    if (dashboardRouteForRole(user.role) !== "/dashboard") {
      router.replace(dashboardRouteForRole(user.role));
      return;
    }
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
    const question = doc.notApplicable
      ? "Withdraw your \"not applicable\" request?"
      : `Remove ${doc.fileName || "this document"}?`;
    if (!confirm(question)) return;
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
        finishingRef.current = true;
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

  /** One requirement's state, for its card and the checklist. */
  const requirementState = (met: boolean, waiting: boolean): { label: string; tone: Tone } =>
    met ? { label: "Complete", tone: "green" }
      : waiting ? { label: "Awaiting approval", tone: "amber" }
      : { label: "Required", tone: "neutral" };

  const slotWaiting = (s: SlotConfig) =>
    slotDocs(s.type).some((d) => d.reviewStatus === "EXCEPTION_REQUESTED");

  /** The files (or "not applicable" marker), upload area and actions of one document type. */
  const renderSlotBody = (slot: SlotConfig, opts: { allowNotApplicable: boolean }) => {
    const docs = slot.multiple ? slotDocs(slot.type) : (slotDoc(slot.type) ? [slotDoc(slot.type)!] : []);
    const marker = !slot.multiple && docs.length > 0 && docs[0].notApplicable ? docs[0] : null;
    const declined = marker?.reviewStatus === "EXCEPTION_DECLINED";
    const files = marker ? [] : docs;
    const uploading = uploadingType === slot.type;
    const errorHere = uploadError && uploadError.type === slot.type ? uploadError.message : null;
    const browse = () => fileInputs.current[slot.type]?.click();
    // A pending "not applicable" request can still be replaced by an upload
    // (the upload takes the request's place).
    const showDropZone = !uploading
      && ((files.length === 0 && !marker) || declined || marker?.reviewStatus === "EXCEPTION_REQUESTED" || slot.multiple);

    return (
      <div className="space-y-2.5">
        {files.map((d) => (
          <div key={d.id} className="space-y-2">
            <div className={
              "flex items-center gap-3 rounded-lg border px-3 py-2.5 "
              + (d.reviewStatus === "REJECTED" ? "border-red-200 bg-red-50/40" : "border-gray-200 bg-white")
            }>
              <span className="shrink-0 inline-flex items-center justify-center w-9 h-9 rounded-md bg-sage-navy/5 text-sage-navy">
                <FileText size={16} />
              </span>
              <div className="min-w-0 flex-1">
                <p className="text-sm font-medium text-gray-900 truncate">{d.fileName ?? "Document"}</p>
                <p className="text-xs text-gray-500">
                  {formatBytes(d.fileSize)}{d.uploadedAt ? ` · Uploaded ${formatDateMedium(d.uploadedAt)}` : ""}
                </p>
              </div>
              <Chip tone={DOC_STATUS[d.reviewStatus]?.tone ?? "neutral"}>
                {DOC_STATUS[d.reviewStatus]?.label ?? d.reviewStatus}
              </Chip>
              <div className="flex items-center shrink-0">
                <IconButton label="View" onClick={() => handleView(d)}><Eye size={15} /></IconButton>
                {!slot.multiple && d.reviewStatus !== "APPROVED" && (
                  <IconButton label="Replace" onClick={browse}><RefreshCw size={15} /></IconButton>
                )}
                {d.reviewStatus !== "APPROVED" && (
                  <IconButton label="Remove" onClick={() => handleRemove(d)} danger><Trash2 size={15} /></IconButton>
                )}
              </div>
            </div>
            {d.reviewStatus === "REJECTED" && (
              <div className="flex flex-wrap items-center gap-x-3 gap-y-1.5 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-800">
                <AlertCircle size={14} className="shrink-0" />
                <span className="flex-1 min-w-0">
                  <strong>Sent back by our team{d.reviewerNotes ? ":" : "."}</strong>{" "}
                  {d.reviewerNotes ?? "Please upload a new file."}
                </span>
                <button type="button" onClick={browse} className="font-semibold underline underline-offset-2 hover:text-red-900 cursor-pointer">
                  Upload a new file
                </button>
              </div>
            )}
          </div>
        ))}

        {marker && (
          <div className={
            "rounded-lg border border-dashed px-3 py-2.5 text-sm "
            + (declined ? "border-red-200 bg-red-50/40" : "border-gray-300 bg-gray-50")
          }>
            <div className="flex flex-wrap items-center gap-2">
              <span className="font-medium text-gray-800">You told us this doesn&apos;t apply to you.</span>
              <Chip tone={DOC_STATUS[marker.reviewStatus]?.tone ?? "neutral"}>
                {DOC_STATUS[marker.reviewStatus]?.label ?? marker.reviewStatus}
              </Chip>
              {(marker.reviewStatus === "NOT_APPLICABLE" || marker.reviewStatus === "EXCEPTION_REQUESTED") && (
                <button
                  type="button"
                  onClick={() => handleRemove(marker)}
                  className="ml-auto text-xs font-semibold text-gray-600 hover:text-gray-900 underline underline-offset-2 cursor-pointer"
                >
                  Undo
                </button>
              )}
            </div>
            {marker.exceptionReason && (
              <p className="mt-1 text-xs text-gray-600">Your reason: {marker.exceptionReason}</p>
            )}
            {marker.reviewStatus === "EXCEPTION_REQUESTED" && (
              <p className="mt-1 text-xs text-gray-500">Our team will review this and email you. You can upload the document instead at any time.</p>
            )}
            {declined && (
              <p className="mt-1 text-xs text-red-700">
                We need this document{marker.reviewerNotes ? `: ${marker.reviewerNotes}` : "."} Please upload it below.
              </p>
            )}
          </div>
        )}

        {uploading && (
          <div className="flex items-center gap-2.5 rounded-lg border border-sage-navy/20 bg-sage-navy/5 px-3 py-3 text-sm text-sage-navy">
            <Loader2 size={16} className="animate-spin" /> Uploading and checking your file…
          </div>
        )}

        {showDropZone && (
          <DropZone
            onBrowse={browse}
            onFile={(f) => handleFilePicked(slot.type, f)}
            label={slot.multiple ? (files.length > 0 ? "Add another file" : "Upload a file") : `Upload ${slot.label}`}
            compact={files.length > 0}
          />
        )}

        {opts.allowNotApplicable && naFor !== slot.type && (
          <button
            type="button"
            onClick={() => handleMarkNA(slot)}
            className="text-xs font-medium text-gray-500 hover:text-sage-navy underline underline-offset-2 cursor-pointer"
          >
            {slot.photoId ? "I don't have a driver's license or a State ID" : "I don't have this document"}
          </button>
        )}

        {naFor === slot.type && (
          <div className="rounded-lg border border-gray-200 bg-gray-50 p-3 space-y-2">
            <label htmlFor={`na-reason-${slot.type}`} className="block text-xs text-gray-700">
              Tell us why. Our team reviews the request and emails you; this requirement counts as done once it&apos;s approved.
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
              className="w-full px-3 py-2 text-sm rounded-lg border border-gray-200 bg-white text-gray-900 placeholder-gray-400 transition focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy resize-none"
            />
            <div className="flex flex-wrap items-center gap-2">
              <button
                type="button"
                onClick={() => handleMarkNA(slot)}
                disabled={naBusy || naReason.trim().length < 5}
                className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-50 disabled:cursor-not-allowed transition cursor-pointer"
              >
                {naBusy && <Loader2 size={12} className="animate-spin" />}
                Send request
              </button>
              <button
                type="button"
                onClick={() => { setNaFor(null); setNaReason(""); }}
                className="px-3 py-1.5 rounded-lg text-xs font-semibold text-gray-600 hover:text-gray-900 hover:bg-gray-100 transition cursor-pointer"
              >
                Cancel
              </button>
            </div>
          </div>
        )}

        {errorHere && (
          <p className="inline-flex items-center gap-1.5 text-xs text-red-600">
            <AlertCircle size={13} /> {errorHere}
          </p>
        )}
      </div>
    );
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
      <OnboardingLayout steps={PROFILE_STEPS} currentStep={3} contentMaxWidth="xl">
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

  const [workAuthSlot, resumeSlot] = REQUIRED_SLOTS;
  const photoState = requirementState(photoIdSatisfied, photoIdWaiting);
  const workState = requirementState(requiredSatisfied(workAuthSlot), slotWaiting(workAuthSlot));
  const resumeState = requirementState(requiredSatisfied(resumeSlot), slotWaiting(resumeSlot));
  const photoHasAnything = PHOTO_ID_SLOTS.some((s) => slotDocs(s.type).length > 0);
  const checklist: { label: string; state: { label: string; tone: Tone } }[] = [
    { label: "Photo ID", state: photoState },
    { label: "Work authorization", state: workState },
    { label: "Resume", state: resumeState },
  ];

  return (
    <OnboardingLayout steps={PROFILE_STEPS} currentStep={3} contentMaxWidth="5xl">
      {/* One hidden file picker per document type, in page order. */}
      {SLOTS.map((slot) => (
        <input
          key={slot.type}
          ref={(el) => { fileInputs.current[slot.type] = el; }}
          type="file"
          accept="application/pdf,image/png,image/jpeg,image/jpg"
          className="hidden"
          aria-label={`Choose a file for ${slot.label}`}
          onChange={(e) => handleFilePicked(slot.type, e.target.files?.[0])}
        />
      ))}

      <div className="grid grid-cols-[minmax(0,1fr)] gap-6 lg:grid-cols-[minmax(0,1fr)_17.5rem] items-start">
        <motion.section
          initial={{ opacity: 0, y: 12 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.3 }}
          className="min-w-0 bg-white rounded-2xl shadow-lg border border-gray-100"
        >
          <header className="px-5 sm:px-7 pt-6 pb-5 border-b border-gray-100">
            <p className="text-[11px] font-semibold uppercase tracking-wider text-sage-copper-deep">Documents</p>
            <h1 className="font-serif text-2xl sm:text-[1.7rem] font-bold text-gray-900 mt-1">
              Upload your documents
            </h1>
            <p className="text-sm text-gray-600 mt-1.5 max-w-2xl">
              We use these to confirm your identity and that you can work in the US. Upload clear copies;
              you can replace a file until our team approves it.
            </p>
            <div className="mt-4 lg:hidden">
              <ProgressSummary done={completedRequired} total={requiredCount} pct={progressPct} />
            </div>
          </header>

          <div className="px-5 sm:px-7 py-6 space-y-8">
            <div>
              <SectionTitle title="Required documents" detail={`${completedRequired} of ${requiredCount} complete`} />
              <ol className="space-y-4">
                <RequirementCard
                  index={1}
                  title="Photo ID"
                  description="Your driver's license or your State ID. One is enough; you can upload both."
                  state={photoState}
                >
                  <div className="space-y-4">
                    {PHOTO_ID_SLOTS.map((slot) => (
                      <div key={slot.type} className="space-y-2">
                        <p className="text-xs font-semibold text-gray-700">{slot.label}</p>
                        {renderSlotBody(slot, { allowNotApplicable: false })}
                      </div>
                    ))}
                  </div>
                  {!photoIdSatisfied && !photoHasAnything && naFor !== "DRIVERS_LICENSE" && (
                    <button
                      type="button"
                      onClick={() => handleMarkNA(PHOTO_ID_SLOTS[0])}
                      className="mt-3 text-xs font-medium text-gray-500 hover:text-sage-navy underline underline-offset-2 cursor-pointer"
                    >
                      I don&apos;t have a driver&apos;s license or a State ID
                    </button>
                  )}
                </RequirementCard>

                <RequirementCard
                  index={2}
                  title="Work authorization"
                  description={workAuthSlot.description}
                  state={workState}
                >
                  {renderSlotBody(workAuthSlot, { allowNotApplicable: !!workAuthSlot.allowNotApplicable
                    && slotDocs(workAuthSlot.type).length === 0 })}
                </RequirementCard>

                <RequirementCard
                  index={3}
                  title="Resume"
                  description={resumeSlot.description}
                  state={resumeState}
                >
                  {renderSlotBody(resumeSlot, { allowNotApplicable: false })}
                </RequirementCard>
              </ol>
            </div>

            <div>
              <SectionTitle title="Optional" />
              <div className="space-y-4">
                <div className="rounded-xl border border-gray-200 p-4 sm:p-5">
                  <div className="flex items-start justify-between gap-3">
                    <div className="min-w-0 flex-1">
                      <label htmlFor="ssn-last4" className="text-sm font-semibold text-gray-900">
                        Social Security Number (last 4 digits)
                      </label>
                      <p id="ssn-hint" className="text-xs text-gray-500 mt-0.5">
                        We only need the last 4 digits; the first five stay hidden. Please don&apos;t upload your SSN card.
                      </p>
                    </div>
                    <span className="shrink-0"><Chip tone={ssnSaved ? "green" : "neutral"}>{ssnSaved ? "Added" : "Optional"}</Chip></span>
                  </div>
                  <div className="mt-3 flex flex-wrap items-center gap-2">
                    {/* Shown the way a masked SSN reads (XXX-XX-1234); only the last 4 are typed. */}
                    <div className="inline-flex items-center rounded-lg border border-gray-200 bg-white transition focus-within:border-sage-navy focus-within:ring-1 focus-within:ring-sage-navy">
                      <span
                        aria-hidden="true"
                        className="pl-3 py-2 font-mono text-sm tracking-[0.15em] text-gray-400 select-none"
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
                        className="w-[8ch] pr-3 py-2 bg-transparent font-mono text-sm tracking-[0.15em] text-gray-900 placeholder-gray-300 focus:outline-none"
                      />
                    </div>
                    <button
                      type="button"
                      onClick={() => handleSaveSsn(ssn)}
                      disabled={ssnBusy || ssn === ssnSaved}
                      className="inline-flex items-center gap-1.5 px-3.5 py-2 rounded-lg text-xs font-semibold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-50 disabled:cursor-not-allowed transition cursor-pointer"
                    >
                      {ssnBusy && <Loader2 size={12} className="animate-spin" />}
                      Save
                    </button>
                    {ssnSaved && (
                      <button
                        type="button"
                        onClick={() => handleSaveSsn("")}
                        disabled={ssnBusy}
                        className="px-3 py-2 rounded-lg text-xs font-semibold text-gray-600 hover:text-gray-900 hover:bg-gray-100 transition cursor-pointer"
                      >
                        Remove
                      </button>
                    )}
                  </div>
                  {ssnSaved && !ssnNote && (
                    <p className="mt-2 inline-flex items-center gap-1.5 text-xs text-gray-500">
                      <Lock size={12} /> On file as <span className="font-mono text-gray-700">XXX-XX-{ssnSaved}</span> (stored encrypted)
                    </p>
                  )}
                  {ssnNote && (
                    <p className={"mt-2 text-xs " + (ssnNote.ok ? "text-emerald-700" : "text-red-600")}>
                      {ssnNote.text}
                    </p>
                  )}
                </div>

                {OPTIONAL_SLOTS.map((slot) => (
                  <div key={slot.type} className="rounded-xl border border-gray-200 p-4 sm:p-5">
                    <div className="flex items-start justify-between gap-3 mb-3">
                      <div className="min-w-0 flex-1">
                        <p className="text-sm font-semibold text-gray-900">{slot.label}</p>
                        <p className="text-xs text-gray-500 mt-0.5">{slot.description}</p>
                      </div>
                      <span className="shrink-0"><Chip tone="neutral">Optional</Chip></span>
                    </div>
                    {renderSlotBody(slot, { allowNotApplicable: false })}
                  </div>
                ))}
              </div>
            </div>

            {completeError && (
              <p className="inline-flex items-center gap-1.5 text-sm text-red-600">
                <AlertCircle size={14} /> {completeError}
              </p>
            )}
          </div>

          <footer className="flex flex-col sm:flex-row sm:items-center sm:justify-end gap-3 px-5 sm:px-7 py-4 border-t border-gray-100 bg-gray-50/70 rounded-b-2xl">
            {missingCount === 0 && (
              <p className="sm:mr-auto inline-flex items-center gap-1.5 text-xs font-medium text-emerald-700">
                <CheckCircle2 size={14} /> All required documents are in.
              </p>
            )}
            <div className="flex flex-col-reverse sm:flex-row gap-2 shrink-0 whitespace-nowrap">
              <button
                type="button"
                onClick={handleSaveLater}
                disabled={refreshing || completing}
                className="inline-flex items-center justify-center gap-2 px-4 py-2.5 rounded-lg text-sm font-semibold bg-white border border-gray-200 text-gray-700 hover:border-sage-navy hover:text-sage-navy disabled:opacity-60 transition cursor-pointer"
              >
                <Save size={14} /> Save and finish later
              </button>
              <button
                type="button"
                onClick={handleContinue}
                disabled={!canContinue}
                className={
                  "inline-flex items-center justify-center gap-2 px-5 py-2.5 rounded-lg text-sm font-semibold transition "
                  + (canContinue
                    ? "bg-sage-navy text-white hover:bg-sage-navy-deep shadow-sm cursor-pointer"
                    : "bg-gray-200 text-gray-500 cursor-not-allowed")
                }
              >
                {completing && <Loader2 size={14} className="animate-spin" />}
                {completing ? "Submitting…" : "Continue to Program"}
                {!completing && <ChevronRight size={15} />}
              </button>
            </div>
          </footer>
        </motion.section>

        <aside className="space-y-4 lg:sticky lg:top-6">
          <div className="hidden lg:block bg-white rounded-2xl border border-gray-100 shadow-sm p-5">
            <p className="text-sm font-semibold text-gray-900">Your checklist</p>
            <div className="mt-3">
              <ProgressSummary done={completedRequired} total={requiredCount} pct={progressPct} />
            </div>
            <ul className="mt-4 space-y-2.5">
              {checklist.map((c) => (
                <li key={c.label} className="flex items-center justify-between gap-2 text-sm">
                  <span className="inline-flex items-center gap-2 text-gray-700">
                    {c.state.tone === "green"
                      ? <CheckCircle2 size={16} className="text-emerald-600" />
                      : <Circle size={16} className={c.state.tone === "amber" ? "text-amber-500" : "text-gray-300"} />}
                    {c.label}
                  </span>
                  <span className={"text-[11px] font-medium " + (c.state.tone === "green" ? "text-emerald-700" : c.state.tone === "amber" ? "text-amber-700" : "text-gray-400")}>
                    {c.state.tone === "green" ? "Done" : c.state.tone === "amber" ? "Waiting" : "To do"}
                  </span>
                </li>
              ))}
            </ul>
          </div>

          <div className="bg-white rounded-2xl border border-gray-100 shadow-sm p-5">
            <p className="text-sm font-semibold text-gray-900">File guidelines</p>
            <ul className="mt-3 space-y-2 text-xs text-gray-600">
              <li className="flex gap-2"><FileText size={14} className="shrink-0 text-gray-400" /> PDF, JPG or PNG, up to 10 MB each</li>
              <li className="flex gap-2"><Eye size={14} className="shrink-0 text-gray-400" /> All four corners visible and the text easy to read</li>
              <li className="flex gap-2"><CheckCircle2 size={14} className="shrink-0 text-gray-400" /> IDs and permits must be current, not expired</li>
            </ul>
          </div>

          <div className="rounded-2xl border border-sage-navy/10 bg-sage-navy/5 p-5">
            <p className="inline-flex items-center gap-2 text-sm font-semibold text-sage-navy">
              <ShieldCheck size={16} /> Your privacy
            </p>
            <p className="mt-2 text-xs text-gray-600 leading-relaxed">
              Your files are encrypted and stored securely. Only authorized team members can open them,
              and every view is recorded.
            </p>
          </div>
        </aside>
      </div>
    </OnboardingLayout>
  );
}

// ── Presentational pieces ──────────────────────────────────────

type Tone = "neutral" | "navy" | "green" | "amber" | "red";

const TONE_CLASS: Record<Tone, string> = {
  neutral: "bg-gray-100 text-gray-600 ring-gray-200",
  navy: "bg-sage-navy/10 text-sage-navy ring-sage-navy/20",
  green: "bg-emerald-50 text-emerald-700 ring-emerald-200",
  amber: "bg-amber-50 text-amber-800 ring-amber-200",
  red: "bg-red-50 text-red-700 ring-red-200",
};

/** What each review status reads as, for the participant. */
const DOC_STATUS: Record<string, { label: string; tone: Tone }> = {
  PENDING: { label: "In review", tone: "navy" },
  APPROVED: { label: "Approved", tone: "green" },
  REJECTED: { label: "Sent back", tone: "red" },
  NOT_APPLICABLE: { label: "Not applicable", tone: "neutral" },
  EXCEPTION_REQUESTED: { label: "Awaiting approval", tone: "amber" },
  EXCEPTION_APPROVED: { label: "Exemption approved", tone: "green" },
  EXCEPTION_DECLINED: { label: "Exemption declined", tone: "red" },
};

function Chip({ tone, children }: { tone: Tone; children: React.ReactNode }) {
  return (
    <span className={`inline-flex items-center whitespace-nowrap rounded-full px-2 py-0.5 text-[11px] font-semibold ring-1 ring-inset ${TONE_CLASS[tone]}`}>
      {children}
    </span>
  );
}

function SectionTitle({ title, detail }: { title: string; detail?: string }) {
  return (
    <div className="flex items-baseline justify-between gap-3 mb-3">
      <h2 className="text-sm font-semibold text-gray-900">{title}</h2>
      {detail && <span className="text-xs text-gray-500">{detail}</span>}
    </div>
  );
}

function RequirementCard({ index, title, description, state, children }: {
  index: number;
  title: string;
  description: string;
  state: { label: string; tone: Tone };
  children: React.ReactNode;
}) {
  const done = state.tone === "green";
  return (
    <li className={"rounded-xl border p-4 sm:p-5 transition-colors " + (done ? "border-emerald-200 bg-emerald-50/30" : "border-gray-200 bg-white")}>
      <div className="flex items-start gap-3 mb-4">
        <span className={
          "shrink-0 inline-flex items-center justify-center w-7 h-7 rounded-full text-xs font-bold "
          + (done ? "bg-emerald-600 text-white" : "bg-sage-navy/10 text-sage-navy")
        }>
          {done ? <CheckCircle2 size={15} /> : index}
        </span>
        <div className="min-w-0 flex-1">
          <div className="flex items-start justify-between gap-3">
            <h3 className="min-w-0 text-sm font-semibold text-gray-900">{title}</h3>
            <span className="shrink-0"><Chip tone={state.tone}>{state.label}</Chip></span>
          </div>
          <p className="text-xs text-gray-500 mt-0.5">{description}</p>
        </div>
      </div>
      <div className="sm:pl-10">{children}</div>
    </li>
  );
}

function ProgressSummary({ done, total, pct }: { done: number; total: number; pct: number }) {
  return (
    <div>
      <div className="flex items-center justify-between text-xs">
        <span className="text-gray-600"><strong className="text-gray-900">{done} of {total}</strong> required complete</span>
        <span className="font-semibold text-sage-navy">{pct}%</span>
      </div>
      <div className="mt-1.5 grid gap-1" style={{ gridTemplateColumns: `repeat(${total}, minmax(0, 1fr))` }}>
        {Array.from({ length: total }, (_, i) => (
          <span key={i} className={"h-1.5 rounded-full " + (i < done ? "bg-sage-navy" : "bg-gray-200")} />
        ))}
      </div>
    </div>
  );
}

function IconButton({ label, onClick, danger, children }: {
  label: string;
  onClick: () => void;
  danger?: boolean;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      title={label}
      aria-label={label}
      className={
        "inline-flex items-center justify-center w-8 h-8 rounded-md transition cursor-pointer "
        + (danger ? "text-gray-400 hover:text-red-600 hover:bg-red-50" : "text-gray-500 hover:text-sage-navy hover:bg-sage-navy/5")
      }
    >
      {children}
    </button>
  );
}

/** Click to browse, or drop a file onto it. */
function DropZone({ onBrowse, onFile, label, compact }: {
  onBrowse: () => void;
  onFile: (file: File) => void;
  label: string;
  compact?: boolean;
}) {
  const [over, setOver] = useState(false);
  return (
    <button
      type="button"
      onClick={onBrowse}
      onDragOver={(e) => { e.preventDefault(); setOver(true); }}
      onDragLeave={() => setOver(false)}
      onDrop={(e) => {
        e.preventDefault();
        setOver(false);
        const f = e.dataTransfer.files?.[0];
        if (f) onFile(f);
      }}
      className={
        "w-full rounded-lg border-2 border-dashed text-center transition cursor-pointer focus:outline-none focus-visible:ring-2 focus-visible:ring-sage-navy/40 "
        + (compact ? "px-4 py-3 " : "px-4 py-5 ")
        + (over ? "border-sage-navy bg-sage-navy/5" : "border-gray-200 bg-gray-50/60 hover:border-sage-navy/50 hover:bg-sage-navy/[0.03]")
      }
    >
      <span className={"flex items-center justify-center gap-3 " + (compact ? "" : "flex-col sm:flex-row")}>
        <span className="inline-flex items-center justify-center w-9 h-9 rounded-full bg-white border border-gray-200 text-sage-navy">
          <UploadIcon size={16} />
        </span>
        <span className="text-left">
          <span className="block text-sm font-semibold text-sage-navy">{label}</span>
          <span className="block text-xs text-gray-500">Drag a file here or click to browse · PDF, JPG or PNG, up to 10 MB</span>
        </span>
      </span>
    </button>
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
