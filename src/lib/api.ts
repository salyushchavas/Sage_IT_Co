const BASE_URL = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";

// Exported alias used by the participant-lifecycle code paths that
// build raw multipart requests outside of apiFetch().
export const API_BASE_URL = BASE_URL;

// Returned by /api/participants/enroll — the new participant ID
// plus the auth tokens minted for the same session.
export interface RegistrationResponse {
  accessToken: string;
  refreshToken: string;
  user: UserDTO;
  participantId?: string | null;
}

// ─── Types ──────────────────────────────────────────────────────────

// Spring Boot wraps all responses in ApiResponse<T>
interface ApiResponse<T> {
  success: boolean;
  message: string;
  data: T;
}

export interface UserDTO {
  id: number;
  email: string;
  fullName: string;
  role: string;
  avatarUrl: string | null;
  bio: string | null;
  // Phase 1B+ participant-lifecycle fields. All optional because
  // non-participant users (instructors, admins, students enrolled
  // via the legacy /signup flow) leave them null.
  participantId?: string | null;
  currentStatus?: string | null;
  phone?: string | null;
  selectedTechnology?: string | null;
  availability?: string | null;
  // Gate flags populated by the backend so the routing guard can
  // tell which onboarding step the participant has completed.
  acknowledgmentComplete?: boolean;
  documentsComplete?: boolean;
  programSelectionComplete?: boolean;
  agreementComplete?: boolean;
  checkUploadComplete?: boolean;
  // Cached profile-completion percentage. The banner watches this so
  // it can re-fetch when a step elsewhere on the page flips it.
  profileCompletionPct?: number;
  // Optional fields surfaced on the Profile tab.
  location?: string | null;
  createdAt?: string | null;
}

export interface AuthResponse {
  accessToken: string;
  refreshToken: string;
  user: UserDTO;
}

export interface InstructorStudent {
  studentName: string;
  email: string;
  courseTitle: string;
  enrolledAt: string;
}

// ─── Core fetch helper ──────────────────────────────────────────────

export async function apiFetch<T = unknown>(
  endpoint: string,
  options: RequestInit = {}
): Promise<T> {
  const token =
    typeof window !== "undefined" ? localStorage.getItem("access_token") : null;

  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    ...(options.headers as Record<string, string>),
  };

  if (token) {
    headers["Authorization"] = `Bearer ${token}`;
  }

  const res = await fetch(`${BASE_URL}${endpoint}`, { ...options, headers });

  // Handle 401 — try refresh, but don't redirect for non-auth failures
  if (res.status === 401) {
    const refreshed = await tryRefresh();
    if (refreshed) {
      headers["Authorization"] = `Bearer ${localStorage.getItem("access_token")}`;
      const retry = await fetch(`${BASE_URL}${endpoint}`, { ...options, headers });
      if (retry.ok) return retry.json();
    }
    // Only redirect to login if we have no valid token at all
    // (not for 401s caused by enrollment/permission checks)
    const hasToken = typeof window !== "undefined" && localStorage.getItem("access_token");
    if (!hasToken && typeof window !== "undefined") {
      window.location.href = "/login";
    }
    throw new Error("Unauthorized");
  }

  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(body.message || body.detail || `API error ${res.status}`);
  }

  if (res.status === 204) return undefined as T;
  return res.json();
}

async function tryRefresh(): Promise<boolean> {
  const refreshToken =
    typeof window !== "undefined" ? localStorage.getItem("refresh_token") : null;
  if (!refreshToken) return false;

  try {
    const res = await fetch(`${BASE_URL}/api/auth/refresh`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ refreshToken }),  // camelCase for Spring Boot
    });
    if (!res.ok) return false;
    const wrapper: ApiResponse<AuthResponse> = await res.json();
    localStorage.setItem("access_token", wrapper.data.accessToken);
    localStorage.setItem("refresh_token", wrapper.data.refreshToken);
    return true;
  } catch {
    return false;
  }
}

// ─── Auth ───────────────────────────────────────────────────────────

export async function register(data: { fullName: string; email: string; password: string }): Promise<AuthResponse> {
  const wrapper = await apiFetch<ApiResponse<AuthResponse>>("/api/auth/register", {
    method: "POST",
    body: JSON.stringify(data),
  });
  return wrapper.data;
}

export async function login(data: { email: string; password: string }): Promise<AuthResponse> {
  const wrapper = await apiFetch<ApiResponse<AuthResponse>>("/api/auth/login", {
    method: "POST",
    body: JSON.stringify(data),
  });
  return wrapper.data;
}

export function logout() {
  localStorage.removeItem("access_token");
  localStorage.removeItem("refresh_token");
  return Promise.resolve();
}

// ─── User / Profile ─────────────────────────────────────────────────

export async function getProfile(): Promise<UserDTO> {
  const wrapper = await apiFetch<ApiResponse<UserDTO>>("/api/users/profile");
  return wrapper.data;
}

// ─── Courses ────────────────────────────────────────────────────────

export async function getCourses(params?: { level?: string; search?: string }) {
  const qs = params ? "?" + new URLSearchParams(params as Record<string, string>).toString() : "";
  const wrapper = await apiFetch<ApiResponse<unknown[]>>(`/api/courses${qs}`);
  return wrapper.data;
}

export async function getInstructorStudents() {
  const wrapper = await apiFetch<ApiResponse<Array<{ studentName: string; email: string; courseTitle: string; enrolledAt: string }>>>("/api/instructor/students");
  return wrapper.data;
}

export async function getCourse(id: string) {
  const wrapper = await apiFetch<ApiResponse<unknown>>(`/api/courses/${id}`);
  return wrapper.data;
}

// ─── Enrollments ────────────────────────────────────────────────────

export async function getMyCourses() {
  const wrapper = await apiFetch<ApiResponse<unknown[]>>("/api/courses/my");
  return wrapper.data;
}

export async function publishCourse(courseId: number) {
  const wrapper = await apiFetch<ApiResponse<unknown>>(`/api/courses/${courseId}/publish`, { method: "PUT" });
  return wrapper.data;
}

export async function unpublishCourse(courseId: number) {
  const wrapper = await apiFetch<ApiResponse<unknown>>(`/api/courses/${courseId}/unpublish`, { method: "PUT" });
  return wrapper.data;
}

export async function enroll(courseId: number) {
  const wrapper = await apiFetch<ApiResponse<unknown>>(`/api/enrollments/${courseId}`, { method: "POST" });
  return wrapper.data;
}

export async function getEnrollments() {
  const wrapper = await apiFetch<ApiResponse<unknown[]>>("/api/enrollments");
  return wrapper.data;
}

export async function getAdminCourses() {
  const wrapper = await apiFetch<ApiResponse<unknown[]>>("/api/admin/courses");
  return wrapper.data;
}

// ─── Subscriptions ──────────────────────────────────────────────────

export async function getSubscriptionStatus() {
  const wrapper = await apiFetch<ApiResponse<unknown>>("/api/subscriptions/status");
  return wrapper.data;
}

// ─── Admin ──────────────────────────────────────────────────────────

export async function getAnalytics() {
  const wrapper = await apiFetch<ApiResponse<unknown>>("/api/admin/analytics");
  return wrapper.data;
}

export async function getUsers() {
  const wrapper = await apiFetch<ApiResponse<unknown[]>>("/api/admin/users");
  return wrapper.data;
}

// ─── Instructor Requests ────────────────────────────────────────

export async function requestInstructor() {
  return apiFetch<ApiResponse<unknown>>("/api/users/request-instructor", { method: "POST" });
}

export async function getPendingInstructorRequests() {
  const wrapper = await apiFetch<ApiResponse<unknown[]>>("/api/admin/instructor-requests");
  return wrapper.data;
}

export async function approveInstructor(requestId: number) {
  return apiFetch<ApiResponse<unknown>>(`/api/admin/approve-instructor/${requestId}`, { method: "PUT" });
}

export async function rejectInstructor(requestId: number) {
  return apiFetch<ApiResponse<unknown>>(`/api/admin/reject-instructor/${requestId}`, { method: "PUT" });
}

// ─── Course Management ──────────────────────────────────────────

export async function createCourse(data: { title: string; description?: string; shortDescription?: string; level?: string; price?: number; category?: string; tags?: string }) {
  const wrapper = await apiFetch<ApiResponse<unknown>>("/api/courses", {
    method: "POST",
    body: JSON.stringify(data),
  });
  return wrapper.data;
}

export async function updateCourse(id: number, data: Record<string, unknown>) {
  const wrapper = await apiFetch<ApiResponse<unknown>>(`/api/courses/${id}`, {
    method: "PUT",
    body: JSON.stringify(data),
  });
  return wrapper.data;
}

export async function deleteCourse(id: number) {
  return apiFetch<ApiResponse<unknown>>(`/api/courses/${id}`, { method: "DELETE" });
}

// ─── Lessons ────────────────────────────────────────────────────

export async function getCourseLessons(courseId: number | string) {
  const wrapper = await apiFetch<ApiResponse<unknown[]>>(`/api/courses/${courseId}/lessons`);
  return wrapper.data;
}

export async function createLesson(courseId: number | string, data: { title: string; description?: string; videoUrl?: string; orderIndex?: number; durationMinutes?: number; isFree?: boolean }) {
  const wrapper = await apiFetch<ApiResponse<unknown>>(`/api/courses/${courseId}/lessons`, {
    method: "POST",
    body: JSON.stringify(data),
  });
  return wrapper.data;
}

export async function updateLesson(lessonId: number, data: Record<string, unknown>) {
  const wrapper = await apiFetch<ApiResponse<unknown>>(`/api/lessons/${lessonId}`, {
    method: "PUT",
    body: JSON.stringify(data),
  });
  return wrapper.data;
}

export async function deleteLesson(lessonId: number) {
  return apiFetch<ApiResponse<unknown>>(`/api/lessons/${lessonId}`, { method: "DELETE" });
}

export async function completeLesson(lessonId: number) {
  return apiFetch<ApiResponse<unknown>>(`/api/lessons/${lessonId}/complete`, { method: "POST" });
}

// ─── Assignments ────────────────────────────────────────────────

export async function getCourseAssignments(courseId: number | string) {
  const wrapper = await apiFetch<ApiResponse<unknown[]>>(`/api/courses/${courseId}/assignments`);
  return wrapper.data;
}

export async function submitAssignment(assignmentId: number, content: string) {
  const wrapper = await apiFetch<ApiResponse<unknown>>(`/api/assignments/${assignmentId}/submit`, {
    method: "POST",
    body: JSON.stringify({ content }),
  });
  return wrapper.data;
}

// ─── Quiz ───────────────────────────────────────────────────────

export async function getLessonQuiz(lessonId: number) {
  const wrapper = await apiFetch<ApiResponse<unknown>>(`/api/lessons/${lessonId}/quiz`);
  return wrapper.data;
}

export async function submitQuiz(quizId: number, answers: Record<number, string>) {
  const wrapper = await apiFetch<ApiResponse<unknown>>(`/api/quizzes/${quizId}/submit`, {
    method: "POST",
    body: JSON.stringify({ answers }),
  });
  return wrapper.data;
}

export async function createQuiz(lessonId: number, title: string) {
  const wrapper = await apiFetch<ApiResponse<unknown>>(`/api/lessons/${lessonId}/quiz`, {
    method: "POST",
    body: JSON.stringify({ title }),
  });
  return wrapper.data;
}

export async function addQuizQuestion(quizId: number, data: { questionText: string; optionA: string; optionB: string; optionC?: string; optionD?: string; correctAnswer: string }) {
  const wrapper = await apiFetch<ApiResponse<unknown>>(`/api/quizzes/${quizId}/questions`, {
    method: "POST",
    body: JSON.stringify(data),
  });
  return wrapper.data;
}

// ─── Certificates ───────────────────────────────────────────────

export async function generateCertificate(courseId: number | string) {
  const wrapper = await apiFetch<ApiResponse<{ id: number; certificateUrl: string; issuedAt: string }>>(`/api/certificates/generate/${courseId}`, { method: "POST" });
  return wrapper.data;
}

export async function checkCertificate(courseId: number | string) {
  const wrapper = await apiFetch<ApiResponse<{ exists: boolean; certificateUrl?: string; issuedAt?: string }>>(`/api/certificates/check/${courseId}`);
  return wrapper.data;
}

export async function getMyCertificates() {
  const wrapper = await apiFetch<ApiResponse<Array<{ id: number; courseTitle: string; certificateUrl: string; issuedAt: string }>>>("/api/certificates/my");
  return wrapper.data;
}

// ─── Video Upload ───────────────────────────────────────────────

export async function uploadLessonVideo(lessonId: number, file: File) {
  const token = typeof window !== "undefined" ? localStorage.getItem("access_token") : null;
  const formData = new FormData();
  formData.append("file", file);

  const res = await fetch(`${BASE_URL}/api/lessons/${lessonId}/upload-video`, {
    method: "POST",
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    body: formData,  // No Content-Type header — browser sets multipart boundary
  });

  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(body.message || "Upload failed");
  }

  const wrapper = await res.json();
  return wrapper.data as { lessonId: number; videoUrl: string };
}

// ─── Tasks ──────────────────────────────────────────────────────

export async function getLessonTasks(lessonId: number) {
  const wrapper = await apiFetch<ApiResponse<Array<{
    id: number; title: string; description: string; instruction: string;
    type: string; orderIndex: number; unlocked: boolean; completed: boolean;
  }>>>(`/api/lessons/${lessonId}/tasks`);
  return wrapper.data;
}

export async function completeTask(taskId: number) {
  return apiFetch<ApiResponse<unknown>>(`/api/tasks/${taskId}/complete`, { method: "POST" });
}

// ═══════════════════════════════════════════════════════════════════
// PARTICIPANT LIFECYCLE — Day 2B port from Spire
// Phases 1B (enrollment) through 4 (welcome / team assembly).
// Backend mounts these at /api/participants/*, /api/agreement/*, etc.
// ═══════════════════════════════════════════════════════════════════
// ─── Phase 1B: participant enrollment ────────────────────────────────

export interface ParticipantEnrollRequest {
  fullName: string;
  email: string;
  phone: string;
  password: string;
}

/**
 * Phase 1B enrollment — wider than the legacy {@link register}.
 * Body matches the backend ParticipantEnrollRequest record;
 * response shape stays {@link RegistrationResponse} so call sites
 * can route to /verify-email the same way.
 */
export async function enrollParticipant(
  data: ParticipantEnrollRequest,
): Promise<RegistrationResponse> {
  const wrapper = await apiFetch<ApiResponse<RegistrationResponse>>(
    "/api/participants/enroll",
    {
      method: "POST",
      body: JSON.stringify(data),
    },
  );
  return wrapper.data;
}

/** Fetches the caller's full profile incl. participantId + currentStatus. */
export async function getParticipantMe(): Promise<UserDTO> {
  const wrapper = await apiFetch<ApiResponse<UserDTO>>("/api/participants/me");
  return wrapper.data;
}

// ─── Phase 1C: progressive profile completion ──────────────────────

export interface ProfileCompletionStep {
  key:
    | "BASIC_INFO"
    | "ACKNOWLEDGMENT"
    | "DOCUMENTS"
    | "PROGRAM_SELECTION"
    | "AGREEMENT"
    | "CHECK_UPLOAD";
  title: string;
  description: string;
  estimatedTime: string;
  completed: boolean;
}

export interface ProfileCompletion {
  completionPercentage: number;
  completedSteps: number;
  totalSteps: number;
  /** Backend ships {@code isComplete} (Jackson serialises {@code isXxx}). */
  isComplete?: boolean;
  /** Lombok-generated alternate name. Some downstream wrappers re-key as {@code complete}. */
  complete?: boolean;
  nextStep: ProfileCompletionStep["key"] | "COMPLETE";
  steps: ProfileCompletionStep[];
}

/** Reads the participant's progressive-completion snapshot. */
export async function getProfileCompletion(): Promise<ProfileCompletion> {
  const wrapper = await apiFetch<ApiResponse<ProfileCompletion>>(
    "/api/participants/profile/completion",
  );
  return wrapper.data;
}

export interface BasicInfoSubmit {
  location?: string;
  availability: string;
  selectedTechnology: string;
  targetExperienceLevel: string;
}

/** Submits Step 1 ("About You") of the dashboard checklist. */
export async function submitBasicInfo(
  data: BasicInfoSubmit,
): Promise<{ success: boolean; completion: ProfileCompletion }> {
  const wrapper = await apiFetch<
    ApiResponse<{ success: boolean; completion: ProfileCompletion }>
  >("/api/participants/profile/basic-info", {
    method: "POST",
    body: JSON.stringify(data),
  });
  return wrapper.data;
}

// ─── Phase 1C: wishlist ────────────────────────────────────────────

export interface WishlistItem {
  id: number;
  kind: "COURSE" | "SERVICE";
  targetId: number;
  title: string;
  thumbnailUrl: string | null;
  price: number | null;
  addedAt: string;
}

export async function getWishlist(): Promise<WishlistItem[]> {
  const wrapper = await apiFetch<ApiResponse<WishlistItem[]>>("/api/participants/wishlist");
  return wrapper.data ?? [];
}

export async function addToWishlist(courseId: number): Promise<void> {
  await apiFetch<ApiResponse<Record<string, unknown>>>("/api/participants/wishlist/add", {
    method: "POST",
    body: JSON.stringify({ courseId }),
  });
}

export async function removeFromWishlist(courseId: number): Promise<void> {
  await apiFetch<ApiResponse<Record<string, unknown>>>(
    `/api/participants/wishlist/${courseId}`,
    { method: "DELETE" },
  );
}

export async function enrollAllFromWishlist(): Promise<{
  success: boolean;
  enrolledCount: number;
  skipped: string[];
}> {
  const wrapper = await apiFetch<
    ApiResponse<{ success: boolean; enrolledCount: number; skipped: string[] }>
  >("/api/participants/wishlist/enroll-all", { method: "POST" });
  return wrapper.data;
}

export interface ParticipantProfileUpdate {
  fullName?: string;
  phone?: string;
  location?: string;
  bio?: string;
  availability?: string;
}

export async function getParticipantProfile(): Promise<UserDTO> {
  const wrapper = await apiFetch<ApiResponse<UserDTO>>("/api/participants/profile");
  return wrapper.data;
}

export async function updateParticipantProfile(
  body: ParticipantProfileUpdate,
): Promise<UserDTO> {
  const wrapper = await apiFetch<ApiResponse<UserDTO>>(
    "/api/participants/profile",
    { method: "PUT", body: JSON.stringify(body) },
  );
  return wrapper.data;
}

// ─── Phase 2A: acknowledgment ────────────────────────────────────────

export interface AcknowledgmentSubmitRequest {
  legalName: string;
  /** Base64 data-URL (data:image/png;base64,…). */
  signatureImage: string;
  signatureMethod: "draw" | "upload";
  interestAccepted: boolean;
  documentationConsent: boolean;
  communicationConsent: boolean;
  /** Pinned to the exact text version the user accepted ("ACK-v1.0"). */
  acknowledgmentVersion: string;
}

export interface AcknowledgmentSubmitResponse {
  acknowledgmentId: number;
  version: string;
  nextStep: string;
  success: boolean;
}

/**
 * Phase 2A step-4 submission. Server enforces the workflow gate
 * (status &gt;= ID_EMAIL_SENT) and is idempotent — re-submitting
 * once already past ACKNOWLEDGMENT_ACCEPTED returns the existing
 * row rather than writing a duplicate.
 */
export async function submitAcknowledgment(
  body: AcknowledgmentSubmitRequest,
): Promise<AcknowledgmentSubmitResponse> {
  const wrapper = await apiFetch<ApiResponse<AcknowledgmentSubmitResponse>>(
    "/api/participants/acknowledgments",
    { method: "POST", body: JSON.stringify(body) },
  );
  return wrapper.data;
}

// ─── Phase 2B: document vault ──────────────────────────────────────

export type DocumentType =
  | "GOVERNMENT_ID" | "WORK_AUTHORIZATION" | "RESUME"
  | "SSN_DOCUMENT" | "DRIVERS_LICENSE" | "OTHER";

export type DocumentReviewStatus =
  | "PENDING" | "APPROVED" | "REJECTED" | "NOT_APPLICABLE";

export interface ParticipantDocument {
  id: number;
  documentType: DocumentType;
  fileName: string | null;
  fileSize: number | null;
  reviewStatus: DocumentReviewStatus;
  reviewerNotes: string | null;
  uploadedAt: string | null;
  reviewedAt: string | null;
  notApplicable: boolean;
}

/** Uploads a single file as the named documentType (multipart/form-data). */
export async function uploadParticipantDocument(
  documentType: DocumentType, file: File,
): Promise<ParticipantDocument> {
  const token = typeof window === "undefined"
    ? null : localStorage.getItem("access_token");
  const form = new FormData();
  form.append("file", file);
  form.append("documentType", documentType);
  const res = await fetch(`${API_BASE_URL}/api/participants/documents/upload`, {
    method: "POST",
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    body: form,
  });
  if (!res.ok) {
    let msg = `Upload failed (${res.status})`;
    try {
      const body = await res.json();
      if (body?.message) msg = body.message;
    } catch { /* ignore */ }
    throw new Error(msg);
  }
  const body = (await res.json()) as ApiResponse<ParticipantDocument>;
  return body.data;
}

export async function listParticipantDocuments(): Promise<ParticipantDocument[]> {
  const wrapper = await apiFetch<ApiResponse<ParticipantDocument[]>>("/api/participants/documents");
  return wrapper.data ?? [];
}

export async function deleteParticipantDocument(documentId: number): Promise<void> {
  await apiFetch<ApiResponse<unknown>>(
    `/api/participants/documents/${documentId}`,
    { method: "DELETE" });
}

export async function markDocumentNotApplicable(
  documentType: DocumentType,
): Promise<ParticipantDocument> {
  const wrapper = await apiFetch<ApiResponse<ParticipantDocument>>(
    "/api/participants/documents/mark-na",
    { method: "POST", body: JSON.stringify({ documentType }) },
  );
  return wrapper.data;
}

export interface CompleteDocumentsResponse {
  success: boolean;
  missing: DocumentType[];
  message?: string;
  nextStep?: string;
}

export async function completeDocuments(): Promise<CompleteDocumentsResponse> {
  const wrapper = await apiFetch<ApiResponse<CompleteDocumentsResponse>>(
    "/api/participants/documents/complete",
    { method: "POST" },
  );
  return wrapper.data;
}

/**
 * Streams the underlying file via the auth-gated view endpoint and
 * opens it in a new tab. For Cloudinary-backed documents the server
 * returns a 5-minute signed URL; for local-disk documents the
 * server streams the bytes inline.
 */
export async function viewParticipantDocument(documentId: number): Promise<void> {
  const token = typeof window === "undefined"
    ? null : localStorage.getItem("access_token");
  const res = await fetch(`${API_BASE_URL}/api/participants/documents/${documentId}/view`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
  if (!res.ok) throw new Error(`Couldn't load document (${res.status})`);
  const contentType = res.headers.get("content-type") ?? "";
  if (contentType.includes("application/json")) {
    // Cloudinary path: ApiResponse with { url, expiresIn }.
    const body = (await res.json()) as ApiResponse<{ url: string }>;
    const url = body?.data?.url;
    if (url) window.open(url, "_blank", "noopener,noreferrer");
    return;
  }
  // Local-disk path: blob stream.
  const blob = await res.blob();
  const objectUrl = URL.createObjectURL(blob);
  window.open(objectUrl, "_blank", "noopener,noreferrer");
  setTimeout(() => URL.revokeObjectURL(objectUrl), 60_000);
}

// ─── Phase 3A: program selection ───────────────────────────────────

export interface ProgramSelectionRequest {
  program?: string;
  phase?: string;
  skillset?: string;
  targetJobTitle?: string;
  coachingPreference?: string;
  availability?: string;
  servicePackage?: string;
  serviceSummaryVersion?: string;
  notes?: string;
}

export interface ProgramSelectionDTO {
  id: number;
  program: string | null;
  phase: string | null;
  skillset: string | null;
  targetJobTitle: string | null;
  coachingPreference: string | null;
  availability: string | null;
  servicePackage: string | null;
  serviceSummaryVersion: string | null;
  notes: string | null;
  selectionDate: string | null;
}

export interface ProgramSelectionSubmitResponse {
  selectionId: number;
  serviceSummaryVersion: string;
  nextStep: string;
  success: boolean;
}

/** Returns the current saved selection (or null), used to pre-fill the form. */
export async function getProgramSelection(): Promise<ProgramSelectionDTO | null> {
  const wrapper = await apiFetch<ApiResponse<ProgramSelectionDTO | null>>(
    "/api/participants/program-selection",
  );
  return wrapper.data ?? null;
}

/**
 * Partial save — survives a refresh / sign-out. Server doesn't
 * validate or transition workflow on this path.
 */
export async function saveProgramSelectionDraft(
  body: ProgramSelectionRequest,
): Promise<ProgramSelectionDTO> {
  const wrapper = await apiFetch<ApiResponse<ProgramSelectionDTO>>(
    "/api/participants/program-selection/draft",
    { method: "POST", body: JSON.stringify(body) },
  );
  return wrapper.data;
}

/**
 * Final submit. Server validates required fields, transitions
 * workflow to PROGRAM_SELECTED, fires the confirmation email.
 */
export async function submitProgramSelection(
  body: ProgramSelectionRequest,
): Promise<ProgramSelectionSubmitResponse> {
  const wrapper = await apiFetch<ApiResponse<ProgramSelectionSubmitResponse>>(
    "/api/participants/program-selection",
    { method: "POST", body: JSON.stringify(body) },
  );
  return wrapper.data;
}

// ─── Phase 3B: participant agreement signing (one-click) ───────────

export interface SignAgreementRequest {
  legalName: string;
  signatureImage: string;
  signatureMethod: "draw" | "upload";
}

export async function signParticipantAgreement(
  body: SignAgreementRequest,
): Promise<{ success: boolean; status: string; nextStep: string; alreadySigned?: boolean }> {
  const wrapper = await apiFetch<ApiResponse<{
    success: boolean; status: string; nextStep: string; alreadySigned?: boolean;
  }>>(
    "/api/participants/agreement/sign",
    { method: "POST", body: JSON.stringify(body) },
  );
  return wrapper.data;
}

// ─── Phase 3B: check soft-copy uploads ────────────────────────────

export interface CheckDocumentDTO {
  id: number;
  checkNumber: string | null;
  amount: number | null;
  checkDate: string | null;
  notes: string | null;
  reviewStatus: string;
  uploadedAt: string | null;
}

export async function uploadCheckSoftCopy(
  file: File,
  meta: { checkNumber?: string; amount?: number; checkDate?: string; notes?: string },
): Promise<CheckDocumentDTO> {
  const token = typeof window === "undefined"
    ? null : localStorage.getItem("access_token");
  const form = new FormData();
  form.append("file", file);
  if (meta.checkNumber) form.append("checkNumber", meta.checkNumber);
  if (meta.amount !== undefined && meta.amount !== null) form.append("amount", String(meta.amount));
  if (meta.checkDate) form.append("checkDate", meta.checkDate);
  if (meta.notes) form.append("notes", meta.notes);
  const res = await fetch(`${API_BASE_URL}/api/participants/checks/upload`, {
    method: "POST",
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    body: form,
  });
  if (!res.ok) {
    let msg = `Upload failed (${res.status})`;
    try {
      const body = await res.json();
      if (body?.message) msg = body.message;
    } catch { /* ignore */ }
    throw new Error(msg);
  }
  const body = (await res.json()) as ApiResponse<CheckDocumentDTO>;
  return body.data;
}

export async function markCheckNotApplicable(): Promise<void> {
  await apiFetch<ApiResponse<unknown>>(
    "/api/participants/checks/mark-na",
    { method: "POST" });
}

export async function listMyChecks(): Promise<CheckDocumentDTO[]> {
  const wrapper = await apiFetch<ApiResponse<CheckDocumentDTO[]>>(
    "/api/participants/checks");
  return wrapper.data ?? [];
}

// ─── Phase 4: welcome / team-assembly status ──────────────────────

export interface WelcomeStatus {
  workflowStatus?: string;
  welcomeEmailSent?: boolean;
  coordinatorIntroSent?: boolean;
  ermAssigned?: boolean;
  coachesAssigned?: boolean;
  dashboardReady?: boolean;
  ermName?: string | null;
  ermEmail?: string | null;
  /** label → coach name (or "Awaiting assignment"). */
  coaches?: Record<string, string>;
}

export async function getWelcomeStatus(): Promise<WelcomeStatus> {
  const wrapper = await apiFetch<ApiResponse<WelcomeStatus>>(
    "/api/participants/welcome-status");
  return wrapper.data ?? {};
}

/** Re-runs the OnboardingService chain (idempotent). Used by the
 *  /welcome page when the user clicks "Check now". */
export async function refreshWelcomeStatus(): Promise<WelcomeStatus> {
  const wrapper = await apiFetch<ApiResponse<WelcomeStatus>>(
    "/api/participants/welcome-status/refresh",
    { method: "POST" });
  return wrapper.data ?? {};
}

// ─── Phase 5A: participant dashboard ───────────────────────────────

export interface ParticipantDashboard {
  participantId: string | null;
  fullName: string | null;
  email: string | null;
  currentStatus: string | null;
  roadmapTotal: number;
  roadmapStep: number;
  roadmapLabels: string[];
  nextAction: { label: string; href: string };
  program?: {
    program?: string;
    phase?: string;
    skillset?: string;
    targetJobTitle?: string;
    availability?: string;
  };
  team?: {
    ermName?: string | null;
    ermEmail?: string | null;
    coaches?: Record<string, string>;
  };
  recentActivity?: { title: string; category: string; createdAt: string }[];
  stats?: { weeksEnrolled: number; reportsSubmitted: number };
  currentWeekStart?: string;
  currentWeekEnd?: string;
  currentWeekReportStatus?: string;
  currentWeekReportId?: number;
}

export async function getParticipantDashboard(): Promise<ParticipantDashboard> {
  const wrapper = await apiFetch<ApiResponse<ParticipantDashboard>>(
    "/api/participants/dashboard");
  return wrapper.data;
}

export interface ParticipantTeam {
  erm?: { name: string | null; email: string | null; bio: string | null };
  coaches?: Record<string, string>;
}

export async function getParticipantTeam(): Promise<ParticipantTeam> {
  const wrapper = await apiFetch<ApiResponse<ParticipantTeam>>(
    "/api/participants/team");
  return wrapper.data ?? {};
}

/**
 * Maps a participant currentStatus to the route the onboarding
 * guard should send them to. Pages not listed here are considered
 * "general" (dashboard, admin, courses, etc.).
 */
export function getOnboardingRoute(status: string | null | undefined): string {
  switch (status) {
    case "DRAFT_STARTED":
    case "BASIC_INFO_SUBMITTED":
      return "/enroll";
    case "EMAIL_VERIFICATION_PENDING":
      return "/verify-email";
    // Phase 1C: every post-verification status lands on /dashboard.
    // The remaining onboarding steps (acknowledgment, documents,
    // program selection, agreement, check upload) live inside the
    // "Complete Your Profile" tab and are no longer a hard gate.
    // The dashboard's gate banner + checklist tab handle the rest.
    case "EMAIL_VERIFIED":
    case "PARTICIPANT_ID_CREATED":
    case "ID_EMAIL_SENT":
    case "ACKNOWLEDGMENT_ACCEPTED":
    case "DOC_REVIEW_PENDING":
    case "DOCUMENTS_SUBMITTED":
    case "PROGRAM_SELECTED":
    case "AGREEMENT_SENT":
    case "AGREEMENT_COMPLETED":
    case "CHECK_COPY_UPLOADED":
    case "SIGNED_AGREEMENT_SENT_TO_ERM":
    case "WELCOME_SENT":
    case "DEEPTHI_INTRO_SENT":
    case "ERM_ASSIGNED":
    case "COACHES_ASSIGNED":
    case "DASHBOARD_ENABLED":
    case "WEEKLY_REPORTING_ACTIVE":
    case "EMPLOYMENT_ACCEPTED":
    case "PHASE_1_COMPLETED":
    case "PAYMENT_PLAN_ACCEPTED":
    case "CHECK_TRACKING_ADDED":
    case "INVOICING_ACTIVE":
    case "PAYMENTS_TRACKED":
      return "/dashboard";
    default:
      // Unknown / null status. We can't tell where the user is in
      // their lifecycle. Returning "/enroll" was unsafe — it bounced
      // signed-in users with a real participant_id back to the
      // public enrollment page if there was ANY brief moment of
      // missing currentStatus. Return "/dashboard" instead and let
      // the dashboard guard run its own refresh + fallback to
      // /enroll only when participantId is genuinely absent.
      if (typeof window !== "undefined") {
        console.warn("[onboarding] unknown status", status,
                "— routing to /dashboard for re-evaluation");
      }
      return "/dashboard";
  }
}

/** Coarse "is this user past onboarding?" check the routing guard uses. */
export function isDashboardStatus(status: string | null | undefined): boolean {
  return getOnboardingRoute(status) === "/dashboard";
}

// ─── Agreement (Terms of Service acceptance) ───────────────────────

export interface TermsSection {
  title: string;
  content: string;
}

export interface TermsResponse {
  version: string;
  lastUpdated: string;
  sections: TermsSection[];
}

export type AgreementStatusValue =
  | "NOT_STARTED"
  | "WAITING_REPLY"
  | "CODE_SENT"
  | "VERIFIED";

export interface AgreementStatus {
  status: AgreementStatusValue;
  accepted: boolean;
  version: string;
  agreementExpiresAt?: string | null;
  acceptedAt?: string | null;
  legalName?: string | null;
}

/** Public — no auth required. Fetches the active terms document. */
export async function getTerms(): Promise<TermsResponse> {
  const wrapper = await apiFetch<ApiResponse<TermsResponse>>("/api/agreement/terms");
  return wrapper.data;
}

/**
 * Polled by the agreement page every few seconds while the user
 * is in WAITING_REPLY — the IMAP cron flips the row to CODE_SENT
 * once it sees the YES reply, at which point the page enables the
 * OTP entry boxes.
 */
export async function getAgreementStatus(): Promise<AgreementStatus> {
  const wrapper = await apiFetch<ApiResponse<AgreementStatus>>("/api/auth/agreement/check-status");
  return wrapper.data;
}

/**
 * Submits the agreement acceptance form. Server validates the legal
 * name + checkbox state and emails the user a "Reply YES" prompt;
 * no OTP yet — that comes after the IMAP cron sees the reply.
 * Returns {@code alreadyAccepted: true} on idempotent re-submits.
 */
export async function acceptAgreement(payload: {
  legalName: string;
  termsAccepted: boolean;
  contentPolicyAccepted: boolean;
  // Base64 data-URL of the user's drawn / uploaded signature.
  // Server-side validation requires the data:image/* prefix and
  // a payload under ~2 MB.
  signatureImage: string;
  signatureMethod: "draw" | "upload";
}): Promise<{ success: boolean; alreadyAccepted: boolean; status: AgreementStatusValue; message: string; expiresAt?: string }> {
  const wrapper = await apiFetch<ApiResponse<{
    success: boolean; alreadyAccepted: boolean;
    status: AgreementStatusValue; message: string; expiresAt?: string;
  }>>(
    "/api/auth/agreement/accept",
    { method: "POST", body: JSON.stringify(payload) },
  );
  return wrapper.data;
}

export async function verifyAgreementCode(code: string): Promise<void> {
  await apiFetch<ApiResponse<unknown>>("/api/auth/agreement/verify-code", {
    method: "POST",
    body: JSON.stringify({ code }),
  });
}

export async function resendAgreementCode(): Promise<{ cooldownSeconds: number }> {
  const wrapper = await apiFetch<ApiResponse<{ cooldownSeconds: number }>>(
    "/api/auth/agreement/resend",
    { method: "POST" },
  );
  return wrapper.data ?? { cooldownSeconds: 60 };
}

/**
 * Fetches the signed-agreement PDF as a blob with the user's JWT
 * attached, then triggers a browser download. Used by both the
 * student profile and the admin user-detail panel; the backend
 * gates the call to the owning user or any admin.
 */
export async function downloadSignedAgreementPdf(
  relativePath: string,
  filename = "Spire-Agreement.pdf",
): Promise<void> {
  const token = typeof window === "undefined"
    ? null
    : localStorage.getItem("access_token");
  const url = relativePath.startsWith("http")
    ? relativePath
    : `${API_BASE_URL}${relativePath}`;
  const res = await fetch(url, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
  if (!res.ok) {
    throw new Error(`Download failed (${res.status})`);
  }
  const blob = await res.blob();
  const objectUrl = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = objectUrl;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(objectUrl);
}

export async function completeOnboarding(): Promise<UserDTO> {
  const wrapper = await apiFetch<ApiResponse<UserDTO>>(
    "/api/users/complete-onboarding",
    { method: "PUT" }
  );
  return wrapper.data;
}

// ─── Email verification (OTP) ───────────────────────────────────────

export async function verifyCode(email: string, code: string): Promise<AuthResponse> {
  const wrapper = await apiFetch<ApiResponse<AuthResponse>>("/api/auth/verify-code", {
    method: "POST",
    body: JSON.stringify({ email, code }),
  });
  return wrapper.data;
}

export async function resendVerificationCode(email: string): Promise<{ cooldownSeconds: number }> {
  const wrapper = await apiFetch<ApiResponse<{ cooldownSeconds: number }>>("/api/auth/resend-code", {
    method: "POST",
    body: JSON.stringify({ email }),
  });
  return wrapper.data ?? { cooldownSeconds: 60 };
}


// ═══════════════════════════════════════════════════════════════════
// PHASE 9D APPEND -- weekly reports, employment, payments
// Ported from Spire api.ts (Phase 6 + 7 sections).
// ═══════════════════════════════════════════════════════════════════

// ─── Weekly reports ───────────────────────────────────────────────

export interface WeeklyReportJobSubmission {
  company?: string;
  client?: string;
  jobTitle?: string;
  technology?: string;
  portal?: string;
  applicationLink?: string;
  submissionDate?: string;
  status?: string;
  followUpDate?: string;
}

export interface WeeklyReportRequest {
  weekStart?: string;
  weekEnd?: string;
  jobSubmissions?: WeeklyReportJobSubmission[];
  resumeActivities?: Record<string, string>;
  interviewTraining?: Record<string, string>;
  communications?: Record<string, string>;
}

export interface WeeklyReportDTO {
  id: number;
  weekStart: string | null;
  weekEnd: string | null;
  submissionDueDate: string | null;
  submittedAt: string | null;
  ermReviewDate: string | null;
  ermNotes: string | null;
  /** JSON-encoded form payload. */
  reportData: string | null;
  status: string;
}

export async function submitWeeklyReport(body: WeeklyReportRequest): Promise<WeeklyReportDTO> {
  const wrapper = await apiFetch<ApiResponse<WeeklyReportDTO>>(
    "/api/participants/reports/weekly",
    { method: "POST", body: JSON.stringify(body) });
  return wrapper.data;
}

export async function saveWeeklyReportDraft(body: WeeklyReportRequest): Promise<WeeklyReportDTO> {
  const wrapper = await apiFetch<ApiResponse<WeeklyReportDTO>>(
    "/api/participants/reports/weekly/draft",
    { method: "POST", body: JSON.stringify(body) });
  return wrapper.data;
}

export async function listWeeklyReports(): Promise<WeeklyReportDTO[]> {
  const wrapper = await apiFetch<ApiResponse<WeeklyReportDTO[]>>(
    "/api/participants/reports/weekly");
  return wrapper.data ?? [];
}

export async function getWeeklyReport(id: number): Promise<WeeklyReportDTO> {
  const wrapper = await apiFetch<ApiResponse<WeeklyReportDTO>>(
    `/api/participants/reports/weekly/${id}`);
  return wrapper.data;
}

// ─── Phase 6: employment + Phase 1 completion ────────────────────

export interface EmploymentAcceptRequest {
  employer: string;
  jobTitle: string;
  startDate: string;        // YYYY-MM-DD
  location?: string | null;
  employmentType?: string | null;
  offerDocumentUrl?: string | null;
  notes?: string | null;
}

export interface EmploymentStatus {
  submitted: boolean;
  ermVerified: boolean;
  ermName?: string | null;
  ermEmail?: string | null;
  details?: {
    id: number;
    employerClient: string | null;
    jobTitle: string | null;
    startDate: string | null;
    location: string | null;
    employmentType: string | null;
    offerDocumentUrl: string | null;
    notes: string | null;
    acceptanceDate: string | null;
    ermVerifiedDate: string | null;
    ermNotes: string | null;
  };
  phase1?: {
    acceptedAt: string | null;
    acknowledgmentVersion: string | null;
    ermApproved: boolean;
    ermApprovedDate: string | null;
  };
}

export async function acceptEmployment(body: EmploymentAcceptRequest): Promise<{
  success: boolean; pendingVerification: boolean; employmentId: number;
}> {
  const wrapper = await apiFetch<ApiResponse<{
    success: boolean; pendingVerification: boolean; employmentId: number;
  }>>("/api/participants/employment/accept",
    { method: "POST", body: JSON.stringify(body) });
  return wrapper.data;
}

export async function uploadOfferDocument(file: File): Promise<{ url: string }> {
  const token = typeof window === "undefined"
    ? null : localStorage.getItem("access_token");
  const form = new FormData();
  form.append("file", file);
  const res = await fetch(`${API_BASE_URL}/api/participants/employment/offer-upload`, {
    method: "POST",
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    body: form,
  });
  if (!res.ok) {
    let msg = `Upload failed (${res.status})`;
    try { const b = await res.json(); if (b?.message) msg = b.message; } catch {}
    throw new Error(msg);
  }
  const body = (await res.json()) as ApiResponse<{ url: string }>;
  return body.data;
}

export async function getEmploymentStatus(): Promise<EmploymentStatus> {
  const wrapper = await apiFetch<ApiResponse<EmploymentStatus>>(
    "/api/participants/employment/status");
  return wrapper.data ?? { submitted: false, ermVerified: false };
}

export async function acceptPhase1Completion(version = "PH1-v1.0"): Promise<{
  success: boolean; paymentEnabled: boolean; phaseCompletionId: number; acceptedAt: string;
}> {
  const wrapper = await apiFetch<ApiResponse<{
    success: boolean; paymentEnabled: boolean; phaseCompletionId: number; acceptedAt: string;
  }>>("/api/participants/phases/phase-1-complete",
    { method: "POST", body: JSON.stringify({
      acknowledgmentAccepted: true, acknowledgmentVersion: version,
    }) });
  return wrapper.data;
}

// ── ERM-side ─────────────────────────────────────────────────────

// ─── Phase 7: payment plans, invoices, ledger, check tracking ────

export interface PaymentScheduleItem {
  dueDate: string | null;
  amount: string | number | null;
  label?: string | null;
}

export interface PaymentPlanDTO {
  id: number;
  planId: string;
  userId: number;
  totalAmount: string | number | null;
  installments: number | null;
  schedule: string | null;
  acceptanceTextVersion: string | null;
  acceptedAt: string | null;
  ipAddress: string | null;
  status: string;
}

export interface ParticipantPaymentPlanResponse {
  plan: PaymentPlanDTO | null;
  schedule: PaymentScheduleItem[];
  acknowledgmentVersion: string;
}

export interface InvoiceDTO {
  id: number;
  invoiceNumber: string;
  userId: number;
  paymentPlanId: number | null;
  amount: string | number | null;
  dueDate: string | null;
  issueDate: string | null;
  paidDate: string | null;
  balance: string | number | null;
  status: string;
}

export interface PaymentLedgerDTO {
  id: number;
  invoiceId: number | null;
  userId: number;
  amountReceived: string | number | null;
  receiptDate: string | null;
  method: string | null;
  adjustment: string | number | null;
  balance: string | number | null;
  notes: string | null;
  financeReviewer: string | null;
  createdAt: string | null;
}

export interface PaymentSummary {
  totalDue?: string | number;
  totalPaid?: string | number;
  balance?: string | number;
  overdue?: string | number;
  nextDueAmount?: string | number | null;
  nextDueDate?: string | null;
  nextDueInvoice?: string | null;
}

export interface CheckTrackingDTO {
  id: number;
  checkNumber: string | null;
  carrier: string | null;
  trackingId: string | null;
  mailedDate: string | null;
  expectedReceiptDate: string | null;
  receivedDate: string | null;
  status: string;
}

export async function getParticipantPaymentPlan(): Promise<ParticipantPaymentPlanResponse> {
  const wrapper = await apiFetch<ApiResponse<ParticipantPaymentPlanResponse>>(
    "/api/participants/payments/plan");
  return wrapper.data ?? { plan: null, schedule: [], acknowledgmentVersion: "PPL-v1.0" };
}

export async function acceptPaymentPlan(planId?: number, version = "PPL-v1.0"): Promise<unknown> {
  const wrapper = await apiFetch<ApiResponse<unknown>>(
    "/api/participants/payments/plan/accept",
    { method: "POST", body: JSON.stringify({
      accepted: true,
      planId: planId ?? null,
      acknowledgmentVersion: version,
    }) });
  return wrapper.data;
}

export async function submitCheckTracking(body: {
  checkNumber: string;
  carrier: string;
  trackingId: string;
  mailedDate: string;
  expectedReceiptDate?: string | null;
}): Promise<unknown> {
  const wrapper = await apiFetch<ApiResponse<unknown>>(
    "/api/participants/payments/check-tracking",
    { method: "POST", body: JSON.stringify(body) });
  return wrapper.data;
}

export async function listParticipantCheckTracking(): Promise<CheckTrackingDTO[]> {
  const wrapper = await apiFetch<ApiResponse<CheckTrackingDTO[]>>(
    "/api/participants/payments/check-tracking");
  return wrapper.data ?? [];
}

export async function listParticipantInvoices(): Promise<InvoiceDTO[]> {
  const wrapper = await apiFetch<ApiResponse<InvoiceDTO[]>>(
    "/api/participants/payments/invoices");
  return wrapper.data ?? [];
}

export async function getParticipantPaymentSummary(): Promise<PaymentSummary> {
  const wrapper = await apiFetch<ApiResponse<PaymentSummary>>(
    "/api/participants/payments/summary");
  return wrapper.data ?? {};
}

export async function listParticipantPaymentHistory(): Promise<PaymentLedgerDTO[]> {
  const wrapper = await apiFetch<ApiResponse<PaymentLedgerDTO[]>>(
    "/api/participants/payments/history");
  return wrapper.data ?? [];
}

// ═══════════════════════════════════════════════════════════════════
// PHASE 10A APPEND -- ERM dashboard helpers + admin assignment
// ═══════════════════════════════════════════════════════════════════

export interface ErmPendingEmploymentRow {
  userId: number;
  participantId: string | null;
  fullName: string | null;
  employmentId: number;
  employerClient: string | null;
  jobTitle: string | null;
  startDate: string | null;
  location: string | null;
  employmentType: string | null;
  offerDocumentUrl: string | null;
  notes: string | null;
  acceptanceDate: string | null;
}

export async function getErmPendingEmployment(): Promise<ErmPendingEmploymentRow[]> {
  const wrapper = await apiFetch<ApiResponse<ErmPendingEmploymentRow[]>>(
    "/api/erm/employment/pending");
  return wrapper.data ?? [];
}

export async function verifyEmployment(participantId: number, notes = ""): Promise<unknown> {
  const wrapper = await apiFetch<ApiResponse<unknown>>(
    `/api/erm/employment/${participantId}/verify`,
    { method: "PUT", body: JSON.stringify({ verified: true, notes }) });
  return wrapper.data;
}

export interface ErmPendingPhaseRow {
  userId: number;
  participantId: string | null;
  fullName: string | null;
  phaseCompletionId: number;
  acceptedAt: string | null;
  acknowledgmentVersion: string | null;
}

export async function getErmPendingPhaseApprovals(): Promise<ErmPendingPhaseRow[]> {
  const wrapper = await apiFetch<ApiResponse<ErmPendingPhaseRow[]>>(
    "/api/erm/phases/pending");
  return wrapper.data ?? [];
}

export async function approvePhase1(participantId: number, notes = ""): Promise<unknown> {
  const wrapper = await apiFetch<ApiResponse<unknown>>(
    `/api/erm/phases/${participantId}/approve`,
    { method: "PUT", body: JSON.stringify({ approved: true, notes }) });
  return wrapper.data;
}

export interface ErmRosterRow {
  userId: number;
  participantId: string | null;
  fullName: string | null;
  email: string | null;
  program: string | null;
  technology: string | null;
  targetJobTitle: string | null;
  currentStatus: string | null;
  lastActivity: string | null;
}

export async function getErmRoster(): Promise<ErmRosterRow[]> {
  const wrapper = await apiFetch<ApiResponse<ErmRosterRow[]>>("/api/erm/participants");
  return wrapper.data ?? [];
}

export async function getErmParticipantDetail(participantId: number): Promise<Record<string, unknown>> {
  const wrapper = await apiFetch<ApiResponse<Record<string, unknown>>>(
    `/api/erm/participants/${participantId}`);
  return wrapper.data ?? {};
}

export async function getErmReports(): Promise<WeeklyReportDTO[]> {
  const wrapper = await apiFetch<ApiResponse<WeeklyReportDTO[]>>("/api/erm/reports");
  return wrapper.data ?? [];
}

export async function reviewErmReport(reportId: number, notes: string): Promise<WeeklyReportDTO> {
  const wrapper = await apiFetch<ApiResponse<WeeklyReportDTO>>(
    `/api/erm/reports/${reportId}/review`,
    { method: "PUT", body: JSON.stringify({ notes }) });
  return wrapper.data;
}

export async function addErmNote(
  participantId: number,
  note: string,
  escalation = false,
): Promise<unknown> {
  const wrapper = await apiFetch<ApiResponse<unknown>>(
    `/api/erm/participants/${participantId}/notes`,
    { method: "POST", body: JSON.stringify({ note, escalation }) });
  return wrapper.data;
}

export async function assignErmToParticipant(
  participantId: number,
  ermUserId: number,
): Promise<unknown> {
  const wrapper = await apiFetch<ApiResponse<unknown>>(
    `/api/admin/assignments/erm/${participantId}`,
    { method: "PUT", body: JSON.stringify({ ermUserId }) });
  return wrapper.data;
}

export async function assignCoachToParticipant(
  participantId: number,
  coachUserId: number,
  coachRole: string,
): Promise<unknown> {
  const wrapper = await apiFetch<ApiResponse<unknown>>(
    `/api/admin/assignments/coach/${participantId}`,
    { method: "PUT", body: JSON.stringify({ coachUserId, coachRole }) });
  return wrapper.data;
}

// ── Finance side ──────────────────────────────────────────────────
