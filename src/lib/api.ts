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

export async function requestPasswordReset(email: string): Promise<{ message: string }> {
  const wrapper = await apiFetch<ApiResponse<{ message: string }>>(
    "/api/auth/forgot-password",
    { method: "POST", body: JSON.stringify({ email }) },
  );
  return wrapper.data;
}

export async function resetPassword(token: string, newPassword: string): Promise<{ message: string }> {
  const wrapper = await apiFetch<ApiResponse<{ message: string }>>(
    "/api/auth/reset-password",
    { method: "POST", body: JSON.stringify({ token, newPassword }) },
  );
  return wrapper.data;
}

// ─── Rich profile types (admin user-detail + future self-service /profile) ─

export interface ProfileAgreementSummary {
  id: number;
  accepted: boolean;
  status?: AgreementStatusValue;
  legalName: string | null;
  version: string;
  acceptedAt: string | null;
  agreementEmailSentAt?: string | null;
  userReplyReceivedAt?: string | null;
  userReplyContent?: string | null;
  verificationCodeSentAt?: string | null;
  verificationCodeVerifiedAt?: string | null;
  ipAddress: string | null;
  browser: string | null;
  os: string | null;
  recordId: string;
  signedAgreementPdfUrl?: string | null;
  signatureImage?: string | null;
  signatureMethod?: "draw" | "upload" | null;
}

export interface ProfileCourseSummary {
  id: number;
  title: string;
  type: string;
  progressPercent: number;
  completedLessons: number;
  totalLessons: number;
  completed: boolean;
  enrolledAt: string | null;
}

export interface ProfileCertSummary {
  id: number;
  certificateId: string | null;
  courseTitle: string | null;
  certificateUrl: string | null;
  issuedAt: string | null;
}

export interface ProfileData extends UserDTO {
  phone: string | null;
  location: string | null;
  createdAt: string | null;
  enrolledCoursesCount: number;
  completedCoursesCount: number;
  certificatesCount: number;
  streakDays: number;
  totalLessonsCompleted: number;
  totalLearningMinutes: number;
  lastActiveAt: string | null;
  contributions: Record<string, number>;
  enrolledCourses: ProfileCourseSummary[] | null;
  certificates: ProfileCertSummary[] | null;
  agreement: ProfileAgreementSummary | null;
}

export interface UpdateProfileBody {
  fullName: string;
  phone?: string;
  bio?: string;
  location?: string;
}

export async function updateProfile(data: UpdateProfileBody): Promise<ProfileData> {
  const wrapper = await apiFetch<ApiResponse<ProfileData>>("/api/users/profile", {
    method: "PUT",
    body: JSON.stringify(data),
  });
  return wrapper.data;
}

// ─── Announcements ──────────────────────────────────────────────────

export interface Announcement {
  id: number;
  title: string;
  message: string;
  type: "INFO" | "SUCCESS" | "WARNING";
  isActive: boolean;
  expiresAt: string | null;
  createdAt: string;
  createdByName: string | null;
}

export async function getActiveAnnouncements() {
  const wrapper = await apiFetch<ApiResponse<Announcement[]>>("/api/announcements/active");
  return wrapper.data;
}

export async function getAllAnnouncements() {
  const wrapper = await apiFetch<ApiResponse<Announcement[]>>("/api/announcements");
  return wrapper.data;
}

export async function createAnnouncement(data: {
  title: string;
  message: string;
  type: "INFO" | "SUCCESS" | "WARNING";
  isActive?: boolean;
  expiresAt?: string | null;
}) {
  const wrapper = await apiFetch<ApiResponse<Announcement>>("/api/announcements", {
    method: "POST",
    body: JSON.stringify(data),
  });
  return wrapper.data;
}

export async function updateAnnouncement(id: number, data: Partial<{
  title: string;
  message: string;
  type: "INFO" | "SUCCESS" | "WARNING";
  isActive: boolean;
  expiresAt: string | null;
}>) {
  const wrapper = await apiFetch<ApiResponse<Announcement>>(`/api/announcements/${id}`, {
    method: "PUT",
    body: JSON.stringify(data),
  });
  return wrapper.data;
}

export async function deleteAnnouncement(id: number) {
  return apiFetch<ApiResponse<unknown>>(`/api/announcements/${id}`, { method: "DELETE" });
}

// ─── Sales inquiries (B2B custom-pricing chat) ──────────────────────

export interface SalesQuoteItem {
  item: string;
  price: number;
}

export interface SalesMessage {
  id: number;
  senderId: number | null;
  senderName: string | null;
  senderRole: string | null;
  message: string;
  attachmentUrl: string | null;
  isQuote: boolean;
  quotedPrice: number | null;
  quotedItems: string | null;
  quoteStatus: string | null;
  createdAt: string;
}

export interface SalesInquiry {
  id: number;
  userId: number | null;
  studentName: string | null;
  studentEmail: string | null;
  courseId: number | null;
  courseTitle: string | null;
  courseType: string | null;
  instructorId: number | null;
  instructorName: string | null;
  status: "NEW" | "IN_PROGRESS" | "QUOTED" | "CONVERTED" | "CLOSED" | "LOST";
  subject: string;
  budgetRange: string | null;
  createdAt: string;
  updatedAt: string;
  closedAt: string | null;
  lastMessagePreview: string | null;
  lastMessageSenderName: string | null;
  lastMessageAt: string | null;
  messages: SalesMessage[] | null;
}

export function parseQuoteItems(raw: string | null): SalesQuoteItem[] {
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed.map((p: { item?: unknown; price?: unknown }) => ({
      item: typeof p.item === "string" ? p.item : "",
      price: Number(p.price ?? 0),
    }));
  } catch {
    return [];
  }
}

export async function createSalesInquiry(data: {
  courseId: number;
  subject?: string;
  budgetRange?: string;
  message: string;
}) {
  const wrapper = await apiFetch<ApiResponse<SalesInquiry>>("/api/sales/inquiries", {
    method: "POST",
    body: JSON.stringify(data),
  });
  return wrapper.data;
}

export async function getMySalesInquiries() {
  const wrapper = await apiFetch<ApiResponse<SalesInquiry[]>>("/api/sales/inquiries/my");
  return wrapper.data;
}

export async function getSalesInquiry(id: number) {
  const wrapper = await apiFetch<ApiResponse<SalesInquiry>>(`/api/sales/inquiries/${id}`);
  return wrapper.data;
}

export async function postSalesMessage(id: number, message: string) {
  const wrapper = await apiFetch<ApiResponse<SalesInquiry>>(
    `/api/sales/inquiries/${id}/messages`,
    { method: "POST", body: JSON.stringify({ message }) }
  );
  return wrapper.data;
}

export async function acceptSalesQuote(inquiryId: number, messageId: number) {
  const wrapper = await apiFetch<ApiResponse<SalesInquiry>>(
    `/api/sales/inquiries/${inquiryId}/accept-quote`,
    { method: "POST", body: JSON.stringify({ messageId }) }
  );
  return wrapper.data;
}

export async function declineSalesQuote(inquiryId: number, messageId: number) {
  const wrapper = await apiFetch<ApiResponse<SalesInquiry>>(
    `/api/sales/inquiries/${inquiryId}/decline-quote`,
    { method: "POST", body: JSON.stringify({ messageId }) }
  );
  return wrapper.data;
}

export async function closeSalesInquiry(id: number, reason?: string) {
  const wrapper = await apiFetch<ApiResponse<SalesInquiry>>(
    `/api/sales/inquiries/${id}/close`,
    { method: "POST", body: JSON.stringify({ reason: reason ?? null }) }
  );
  return wrapper.data;
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

export async function getUsers(status?: "active" | "inactive" | "all") {
  const qs = status && status !== "all" ? `?status=${status}` : "";
  const wrapper = await apiFetch<ApiResponse<unknown[]>>(`/api/admin/users${qs}`);
  return wrapper.data;
}

export interface AdminUserCounts {
  total: number;
  active: number;
  inactive: number;
}

export async function getUserCountsAsAdmin(): Promise<AdminUserCounts> {
  const wrapper = await apiFetch<ApiResponse<AdminUserCounts>>(
    "/api/admin/users/counts");
  return wrapper.data ?? { total: 0, active: 0, inactive: 0 };
}

export async function getUserProfileAsAdmin(userId: number | string) {
  const wrapper = await apiFetch<ApiResponse<unknown>>(
    `/api/admin/users/${userId}/profile`);
  return wrapper.data;
}

export async function updateUserRoleAsAdmin(userId: number | string, role: string) {
  const wrapper = await apiFetch<ApiResponse<unknown>>(
    `/api/admin/users/${userId}/role`,
    { method: "PUT", body: JSON.stringify({ role }) });
  return wrapper.data;
}

export async function updateUserStatusAsAdmin(userId: number | string, active: boolean) {
  const wrapper = await apiFetch<ApiResponse<unknown>>(
    `/api/admin/users/${userId}/status`,
    { method: "PUT", body: JSON.stringify({ active }) });
  return wrapper.data;
}

export async function reactivateUserAsAdmin(userId: number | string) {
  const wrapper = await apiFetch<ApiResponse<unknown>>(
    `/api/admin/users/${userId}/reactivate`,
    { method: "PUT" });
  return wrapper.data;
}

export async function softDeleteUserAsAdmin(userId: number | string) {
  const wrapper = await apiFetch<ApiResponse<unknown>>(
    `/api/admin/users/${userId}`,
    { method: "DELETE" });
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

export async function createLesson(courseId: number | string, data: { title: string; description?: string; videoUrl?: string; orderIndex?: number; durationMinutes?: number; isFree?: boolean; moduleId?: number }) {
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

// Legacy quiz helpers (kept for QuizBuilder / QuizSection in
// courses/[id]). Spire's richer submitQuiz / addQuizQuestion arrive
// in Batch 5a below under their canonical names.
export async function submitSimpleQuiz(quizId: number, answers: Record<number, string>) {
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

export async function addSimpleQuizQuestion(quizId: number, data: { questionText: string; optionA: string; optionB: string; optionC?: string; optionD?: string; correctAnswer: string }) {
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
  filename = "Sage-Agreement.pdf",
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

// ─── Phase 5B: Operations admin tabs ─────────────────────────────

export interface OperationsQueueRow {
  userId: number;
  participantId?: string | null;
  fullName: string | null;
  email: string | null;
  currentStatus: string | null;
  createdAt?: string | null;
  emailVerified?: boolean;
  agreementStatus?: string | null;
  agreementSentAt?: string | null;
}

export async function getEnrollmentQueue(): Promise<OperationsQueueRow[]> {
  const wrapper = await apiFetch<ApiResponse<OperationsQueueRow[]>>(
    "/api/admin/operations/enrollment-queue");
  return wrapper.data ?? [];
}

export async function getAgreementQueue(): Promise<OperationsQueueRow[]> {
  const wrapper = await apiFetch<ApiResponse<OperationsQueueRow[]>>(
    "/api/admin/operations/agreement-queue");
  return wrapper.data ?? [];
}

export interface AuditRow {
  id: number;
  userId: number;
  recordType: string;
  category: string;
  title: string;
  description: string;
  createdAt: string;
}

export async function getAuditTrail(opts: {
  userId?: number; category?: string; limit?: number;
} = {}): Promise<AuditRow[]> {
  const qs = new URLSearchParams();
  if (opts.userId) qs.set("userId", String(opts.userId));
  if (opts.category) qs.set("category", opts.category);
  if (opts.limit) qs.set("limit", String(opts.limit));
  const wrapper = await apiFetch<ApiResponse<AuditRow[]>>(
    `/api/admin/operations/audit${qs.size ? `?${qs.toString()}` : ""}`);
  return wrapper.data ?? [];
}

export interface OperationsException {
  type: string;
  userId: number;
  fullName: string | null;
  currentStatus: string | null;
  openSince: string | null;
}

export async function getOperationsExceptions(): Promise<OperationsException[]> {
  const wrapper = await apiFetch<ApiResponse<OperationsException[]>>(
    "/api/admin/operations/exceptions");
  return wrapper.data ?? [];
}

export interface StaffPool {
  erm: { id: number; fullName: string; email: string }[];
  coach: { id: number; fullName: string; email: string }[];
  technicalAdvisor: { id: number; fullName: string; email: string }[];
}

export async function getStaffPool(): Promise<StaffPool> {
  const wrapper = await apiFetch<ApiResponse<StaffPool>>(
    "/api/admin/operations/staff-pool");
  return wrapper.data ?? { erm: [], coach: [], technicalAdvisor: [] };
}

export async function getAssignmentQueue(): Promise<Record<string, unknown>[]> {
  const wrapper = await apiFetch<ApiResponse<Record<string, unknown>[]>>(
    "/api/admin/assignments/queue");
  return wrapper.data ?? [];
}

/**
 * Routes a logged-in user to the dashboard URL appropriate for their
 * role. Used by the participant /dashboard gatekeeper (and any other
 * "land here on login" router) to direct staff away from the
 * participant flow.
 *
 *   ERM            -> /erm-dashboard
 *   COACH / TECHNICAL_ADVISOR -> /coach-dashboard
 *   FINANCE        -> /finance-dashboard
 *   OPERATIONS_ADMIN -> /operations
 *   SYSTEM_ADMIN / ADMIN -> /admin (full admin surface; SYSTEM_ADMIN
 *                                   gets the same LMS view + can still
 *                                   reach /operations via sidebar)
 *   INSTRUCTOR     -> /instructor
 *   anything else / participant -> /dashboard
 */
export function dashboardRouteForRole(role: string | null | undefined): string {
  const r = (role ?? "").toUpperCase();
  if (r === "ERM") return "/erm-dashboard";
  if (r === "COACH" || r === "TECHNICAL_ADVISOR") return "/coach-dashboard";
  if (r === "FINANCE") return "/finance-dashboard";
  if (r === "OPERATIONS_ADMIN") return "/operations";
  if (r === "SYSTEM_ADMIN" || r === "ADMIN") return "/admin";
  if (r === "INSTRUCTOR") return "/instructor";
  return "/dashboard";
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

// Phase 10C: Coach dashboard

export interface CoachParticipantRow {
  userId: number;
  participantId: string | null;
  fullName: string | null;
  technology: string | null;
  targetJobTitle: string | null;
  program: string | null;
  phase: string | null;
  coachRole: string | null;
  sessions: number;
  currentStatus: string | null;
}

export interface CoachingSessionDTO {
  id?: number;
  participantUserId: number;
  coachUserId?: number;
  sessionDate?: string | null;
  topic?: string | null;
  notes?: string | null;
  nextSteps?: string | null;
  durationMinutes?: number | null;
  createdAt?: string | null;
}

export interface CoachingTaskDTO {
  id?: number;
  participantUserId: number;
  coachUserId?: number;
  title: string;
  description?: string | null;
  dueDate?: string | null;
  status?: string;
  createdAt?: string | null;
}

export interface CoachingFeedbackDTO {
  id?: number;
  participantUserId: number;
  coachUserId?: number;
  feedbackType?: string;
  content: string;
  rating?: number | null;
  createdAt?: string | null;
}

export async function getCoachParticipants(): Promise<CoachParticipantRow[]> {
  const wrapper = await apiFetch<ApiResponse<CoachParticipantRow[]>>(
    "/api/coaches/participants");
  return wrapper.data ?? [];
}

export async function getCoachParticipantDetail(participantId: number): Promise<Record<string, unknown>> {
  const wrapper = await apiFetch<ApiResponse<Record<string, unknown>>>(
    `/api/coaches/participants/${participantId}`);
  return wrapper.data ?? {};
}

export async function listCoachSessions(participantId?: number): Promise<CoachingSessionDTO[]> {
  const qs = participantId ? `?participantId=${participantId}` : "";
  const wrapper = await apiFetch<ApiResponse<CoachingSessionDTO[]>>(
    `/api/coaches/sessions${qs}`);
  return wrapper.data ?? [];
}

export async function createCoachSession(body: CoachingSessionDTO): Promise<CoachingSessionDTO> {
  const wrapper = await apiFetch<ApiResponse<CoachingSessionDTO>>(
    "/api/coaches/sessions",
    { method: "POST", body: JSON.stringify(body) });
  return wrapper.data;
}

export async function listCoachTasks(participantId?: number): Promise<CoachingTaskDTO[]> {
  const qs = participantId ? `?participantId=${participantId}` : "";
  const wrapper = await apiFetch<ApiResponse<CoachingTaskDTO[]>>(
    `/api/coaches/tasks${qs}`);
  return wrapper.data ?? [];
}

export async function createCoachTask(body: CoachingTaskDTO): Promise<CoachingTaskDTO> {
  const wrapper = await apiFetch<ApiResponse<CoachingTaskDTO>>(
    "/api/coaches/tasks",
    { method: "POST", body: JSON.stringify(body) });
  return wrapper.data;
}

export async function updateCoachTaskStatus(taskId: number, status: string): Promise<CoachingTaskDTO> {
  const wrapper = await apiFetch<ApiResponse<CoachingTaskDTO>>(
    `/api/coaches/tasks/${taskId}/status`,
    { method: "PUT", body: JSON.stringify({ status }) });
  return wrapper.data;
}

export async function listCoachFeedback(participantId?: number): Promise<CoachingFeedbackDTO[]> {
  const qs = participantId ? `?participantId=${participantId}` : "";
  const wrapper = await apiFetch<ApiResponse<CoachingFeedbackDTO[]>>(
    `/api/coaches/feedback${qs}`);
  return wrapper.data ?? [];
}

export async function createCoachFeedback(body: CoachingFeedbackDTO): Promise<CoachingFeedbackDTO> {
  const wrapper = await apiFetch<ApiResponse<CoachingFeedbackDTO>>(
    "/api/coaches/feedback",
    { method: "POST", body: JSON.stringify(body) });
  return wrapper.data;
}

// Phase 10D: Finance dashboard (FINANCE role)

export interface FinancePlanRow {
  id: number;
  planNumber: string;
  userId: number;
  participantId: string | null;
  participantName: string | null;
  totalAmount: string | number | null;
  installments: number | null;
  status: string;
  acceptedAt: string | null;
  schedule: PaymentScheduleItem[];
}

export async function getFinancePlans(): Promise<FinancePlanRow[]> {
  const wrapper = await apiFetch<ApiResponse<FinancePlanRow[]>>("/api/finance/plans");
  return wrapper.data ?? [];
}

export async function createFinancePlan(body: {
  participantId: number;
  totalAmount: number;
  installments: number;
  schedule: { dueDate: string; amount: number; label?: string }[];
}): Promise<PaymentPlanDTO> {
  const wrapper = await apiFetch<ApiResponse<PaymentPlanDTO>>(
    "/api/finance/plans",
    { method: "POST", body: JSON.stringify(body) });
  return wrapper.data;
}

export async function getFinanceInvoices(status?: string): Promise<InvoiceDTO[]> {
  const qs = status ? `?status=${encodeURIComponent(status)}` : "";
  const wrapper = await apiFetch<ApiResponse<InvoiceDTO[]>>(
    `/api/finance/invoices${qs}`);
  return wrapper.data ?? [];
}

export async function generateInvoice(paymentPlanId: number): Promise<InvoiceDTO> {
  const wrapper = await apiFetch<ApiResponse<InvoiceDTO>>(
    "/api/finance/invoices/generate",
    { method: "POST", body: JSON.stringify({ paymentPlanId }) });
  return wrapper.data;
}

export async function bulkGenerateInvoices(): Promise<{ issued: number; invoices: string[] }> {
  const wrapper = await apiFetch<ApiResponse<{ issued: number; invoices: string[] }>>(
    "/api/finance/invoices/bulk-generate",
    { method: "POST" });
  return wrapper.data;
}

export async function markOverdueInvoices(): Promise<{ marked: number }> {
  const wrapper = await apiFetch<ApiResponse<{ marked: number }>>(
    "/api/finance/invoices/mark-overdue",
    { method: "POST" });
  return wrapper.data;
}

export async function getFinanceLedger(): Promise<PaymentLedgerDTO[]> {
  const wrapper = await apiFetch<ApiResponse<PaymentLedgerDTO[]>>(
    "/api/finance/payments");
  return wrapper.data ?? [];
}

export async function recordPaymentReceipt(body: {
  invoiceId: number;
  amountReceived: number;
  receiptDate?: string;
  method?: string;
  notes?: string;
}): Promise<PaymentLedgerDTO> {
  const wrapper = await apiFetch<ApiResponse<PaymentLedgerDTO>>(
    "/api/finance/payments/receive",
    { method: "PUT", body: JSON.stringify(body) });
  return wrapper.data;
}

export interface FinanceTrackingRow extends CheckTrackingDTO {
  paymentPlanId: number;
  userId: number | null;
  participantId: string | null;
  participantName: string | null;
}

export async function getFinanceTrackings(status?: string): Promise<FinanceTrackingRow[]> {
  const qs = status ? `?status=${encodeURIComponent(status)}` : "";
  const wrapper = await apiFetch<ApiResponse<FinanceTrackingRow[]>>(
    `/api/finance/check-tracking${qs}`);
  return wrapper.data ?? [];
}

export async function updateTrackingStatus(
  trackingId: number,
  status: "RECEIVED" | "EXCEPTION" | "IN_TRANSIT" | "RETURNED" | "LOST",
  receivedDate?: string,
): Promise<CheckTrackingDTO> {
  const wrapper = await apiFetch<ApiResponse<CheckTrackingDTO>>(
    `/api/finance/check-tracking/${trackingId}/update`,
    { method: "PUT", body: JSON.stringify({ status, receivedDate: receivedDate ?? null }) });
  return wrapper.data;
}

export interface FinanceDashboardSummary {
  totalPlans: number;
  activePlans: number;
  unpaidInvoices: number;
  overdueInvoices: number;
  totalCollected: string | number;
}

export async function getFinanceDashboard(): Promise<FinanceDashboardSummary> {
  const wrapper = await apiFetch<ApiResponse<FinanceDashboardSummary>>(
    "/api/finance/dashboard");
  return wrapper.data ?? {
    totalPlans: 0, activePlans: 0,
    unpaidInvoices: 0, overdueInvoices: 0, totalCollected: 0,
  };
}

export interface FinanceCheckRow {
  id: number;
  userId: number;
  participantId: string | null;
  participantName: string | null;
  checkNumber: string | null;
  amount: number | null;
  checkDate: string | null;
  notes: string | null;
  reviewStatus: string;
  maskingStatus: string;
  fileUrl: string | null;
  uploadedAt: string | null;
}

export async function getFinanceChecks(status?: string): Promise<FinanceCheckRow[]> {
  const qs = status ? `?status=${encodeURIComponent(status)}` : "";
  const wrapper = await apiFetch<ApiResponse<FinanceCheckRow[]>>(
    `/api/finance/checks${qs}`);
  return wrapper.data ?? [];
}

export async function reviewFinanceCheck(
  checkId: number,
  status: "APPROVED" | "REJECTED",
  notes = "",
): Promise<FinanceCheckRow> {
  const wrapper = await apiFetch<ApiResponse<FinanceCheckRow>>(
    `/api/finance/checks/${checkId}/review`,
    { method: "PUT", body: JSON.stringify({ status, notes }) });
  return wrapper.data;
}

// ─── Phase 11E Batch 2: NextAction + DashboardSummary + Cert verify ──

export type NextActionType =
  | "SESSION_SOON"
  | "ASSIGNMENT_DUE"
  | "CONTINUE_COURSE"
  | "START_COURSE"
  | "BROWSE_COURSES"
  | "ALL_COMPLETE";

export interface NextAction {
  type: NextActionType;
  courseId?: number | null;
  courseTitle?: string | null;
  mentorName?: string | null;
  scheduledAt?: string | null;
  meetingUrl?: string | null;
  assignmentId?: number | null;
  assignmentTitle?: string | null;
  dueDate?: string | null;
  nextLessonId?: number | null;
  nextLessonTitle?: string | null;
  moduleTitle?: string | null;
  progressPercent?: number | null;
  firstLessonId?: number | null;
  firstLessonTitle?: string | null;
  completedCount?: number | null;
  certificateCount?: number | null;
}

export interface DashboardSummary {
  enrolledCourses: Array<{
    id: number;
    title: string;
    type: string;
    progressPercent: number;
    completedLessons: number;
    totalLessons: number;
    lastAccessedAt: string | null;
  }>;
  upcomingSessions: Array<{
    sessionId: number;
    courseTitle: string | null;
    mentorName: string | null;
    scheduledAt: string;
    meetingUrl: string | null;
  }>;
  recentActivity: Array<{ type: string; description: string; timestamp: string }>;
  streakDays: number;
}

export async function getNextAction() {
  const wrapper = await apiFetch<ApiResponse<NextAction>>("/api/users/next-action");
  return wrapper.data;
}

export async function getDashboardSummary() {
  const wrapper = await apiFetch<ApiResponse<DashboardSummary>>("/api/users/dashboard-summary");
  return wrapper.data;
}

export interface CertificateCheck {
  exists: boolean;
  certificateId?: string;
  certificateUrl?: string;
  issuedAt?: string;
  finalScore?: number | null;
  courseTitle?: string;
  verificationUrl?: string;
}

export interface CertificateListItem {
  id: number;
  certificateId: string;
  courseId: number;
  courseTitle: string;
  certificateUrl: string;
  issuedAt: string;
  finalScore: number | null;
}

export interface CertificateVerification {
  valid: boolean;
  certificateId?: string;
  studentName?: string;
  courseTitle?: string;
  issuedAt?: string;
  finalScore?: number | null;
}

/**
 * Public verification -- no auth required. Tries /api/verify/{id}
 * first, falls back to /api/certificates/verify/{id} for older
 * deployments.
 */
export async function verifyCertificate(certificateId: string) {
  const enc = encodeURIComponent(certificateId);
  try {
    const wrapper = await apiFetch<ApiResponse<CertificateVerification>>(
      `/api/verify/${enc}`,
    );
    return wrapper.data;
  } catch {
    const wrapper = await apiFetch<ApiResponse<CertificateVerification>>(
      `/api/certificates/verify/${enc}`,
    );
    return wrapper.data;
  }
}

// ─── Phase 11E Batch 3: admin tabs (Enrollments, Sessions, Revenue,
//                                    Coupons, Sales, Mentor Pools, CSV) ─

export interface AdminEnrollmentRow {
  enrollmentId: number;
  userId: number;
  studentName: string;
  studentEmail: string;
  courseId: number;
  courseTitle: string;
  courseType: string;
  enrolledAt: string;
  progressPercent: number;
  completedLessons: number;
  totalLessons: number;
  completed: boolean;
  mentorName: string | null;
  mentorAssignmentStatus: string | null;
}

export async function getAdminEnrollments() {
  const wrapper = await apiFetch<ApiResponse<AdminEnrollmentRow[]>>(
    "/api/admin/enrollments"
  );
  return wrapper.data;
}

export interface AdminSessionRow {
  sessionId: number;
  studentName: string | null;
  studentEmail: string | null;
  mentorName: string | null;
  courseTitle: string | null;
  status: string;
  topic: string | null;
  requestedAt: string | null;
  scheduledAt: string | null;
  completedAt: string | null;
  meetingUrl: string | null;
}

export async function getAdminSessions() {
  const wrapper = await apiFetch<ApiResponse<AdminSessionRow[]>>(
    "/api/admin/sessions"
  );
  return wrapper.data;
}

export interface RevenueSummary {
  totalRevenue: number;
  revenueThisMonth: number;
  revenueLastMonth: number;
  totalTransactions: number;
  avgOrderValue: number;
  topCoursesByRevenue: Array<{
    courseId: number;
    courseTitle: string;
    type: string;
    enrollments: number;
    revenue: number;
  }>;
}

export interface RevenueTransaction {
  id: number;
  studentName: string | null;
  studentEmail: string | null;
  amount: number;
  currency: string;
  status: string | null;
  razorpayPaymentId: string | null;
  razorpayOrderId: string | null;
  createdAt: string | null;
}

export async function getRevenueSummary() {
  const wrapper = await apiFetch<ApiResponse<RevenueSummary>>("/api/admin/revenue/summary");
  return wrapper.data;
}

export async function getRevenueTransactions(params?: {
  from?: string;
  to?: string;
  status?: string;
}) {
  const qs = params
    ? "?" + new URLSearchParams(
        Object.entries(params).filter(([, v]) => v) as [string, string][]
      ).toString()
    : "";
  const wrapper = await apiFetch<ApiResponse<RevenueTransaction[]>>(
    `/api/admin/revenue/transactions${qs}`
  );
  return wrapper.data;
}

/**
 * CSV exports stream text/csv -- bypass apiFetch and trigger a
 * browser download via blob URL. Filename prefix is "sage-" to
 * match the brand.
 */
export async function downloadAdminCsv(kind: "users" | "enrollments" | "sessions" | "revenue") {
  const token = typeof window !== "undefined" ? localStorage.getItem("access_token") : null;
  const res = await fetch(`${BASE_URL}/api/admin/export/${kind}`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
  if (!res.ok) {
    throw new Error(`Export failed (${res.status})`);
  }
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  const today = new Date().toISOString().slice(0, 10);
  a.download = `sage-${kind}-${today}.csv`;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

// ─── Admin: sales inquiries (B2B) ──

export async function getAdminSalesInquiries() {
  const wrapper = await apiFetch<ApiResponse<SalesInquiry[]>>("/api/admin/sales/inquiries");
  return wrapper.data;
}

export async function getAdminSalesInquiry(id: number) {
  const wrapper = await apiFetch<ApiResponse<SalesInquiry>>(`/api/admin/sales/inquiries/${id}`);
  return wrapper.data;
}

export interface SalesStats {
  totalInquiries: number;
  newCount: number;
  inProgressCount: number;
  quotedCount: number;
  convertedCount: number;
  closedCount: number;
  lostCount: number;
  conversionRate: number;
}

export async function getAdminSalesStats() {
  const wrapper = await apiFetch<ApiResponse<SalesStats>>("/api/admin/sales/stats");
  return wrapper.data;
}

// ─── Coupons (admin CRUD + public validation) ──

export interface Coupon {
  id: number;
  code: string;
  discountType: "PERCENT" | "FLAT";
  discountValue: number;
  minOrderAmount: number | null;
  maxUses: number | null;
  usesCount: number;
  expiresAt: string | null;
  isActive: boolean;
  createdAt: string;
}

export interface CouponValidation {
  code: string;
  discountType: "PERCENT" | "FLAT";
  discountValue: number;
  cartTotal: number;
  discountAmount: number;
  finalTotal: number;
}

export async function validateCoupon(code: string, cartTotal: number) {
  const wrapper = await apiFetch<ApiResponse<CouponValidation>>("/api/coupons/validate", {
    method: "POST",
    body: JSON.stringify({ code, cartTotal }),
  });
  return wrapper.data;
}

export async function getAllCoupons() {
  const wrapper = await apiFetch<ApiResponse<Coupon[]>>("/api/admin/coupons");
  return wrapper.data;
}

export async function createCoupon(data: {
  code: string;
  discountType: "PERCENT" | "FLAT";
  discountValue: number;
  minOrderAmount?: number | null;
  maxUses?: number | null;
  expiresAt?: string | null;
  isActive?: boolean;
}) {
  const wrapper = await apiFetch<ApiResponse<Coupon>>("/api/admin/coupons", {
    method: "POST",
    body: JSON.stringify(data),
  });
  return wrapper.data;
}

export async function updateCoupon(id: number, data: Partial<{
  discountType: "PERCENT" | "FLAT";
  discountValue: number;
  minOrderAmount: number | null;
  maxUses: number | null;
  expiresAt: string | null;
  isActive: boolean;
}>) {
  const wrapper = await apiFetch<ApiResponse<Coupon>>(`/api/admin/coupons/${id}`, {
    method: "PUT",
    body: JSON.stringify(data),
  });
  return wrapper.data;
}

export async function deleteCoupon(id: number) {
  return apiFetch<ApiResponse<unknown>>(`/api/admin/coupons/${id}`, { method: "DELETE" });
}

// ─── Course Mentors (Mentor Pools admin) ──

export interface CourseMentor {
  id: number;
  courseId: number;
  mentorId: number;
  mentorName: string;
  mentorEmail: string;
  activeStudentCount: number;
  maxStudents: number;
  isActive: boolean;
}

export async function getCourseMentors(courseId: number) {
  const wrapper = await apiFetch<ApiResponse<CourseMentor[]>>(
    `/api/admin/courses/${courseId}/mentors`
  );
  return wrapper.data;
}

export async function addMentorToCourse(courseId: number, userId: number) {
  const wrapper = await apiFetch<ApiResponse<CourseMentor>>(
    `/api/admin/courses/${courseId}/mentors`,
    { method: "POST", body: JSON.stringify({ userId }) }
  );
  return wrapper.data;
}

export async function removeMentorFromCourse(courseId: number, userId: number) {
  return apiFetch<ApiResponse<unknown>>(
    `/api/admin/courses/${courseId}/mentors/${userId}`,
    { method: "DELETE" }
  );
}

// ─── Phase 11E Batch 5a: Quizzes (rich), Sessions, Course readiness ──

export interface PublishReadiness {
  ready: boolean;
  missing: string[];
}

export async function getPublishReadiness(id: number) {
  const wrapper = await apiFetch<ApiResponse<PublishReadiness>>(
    `/api/instructor/courses/${id}/publish-readiness`,
  );
  return wrapper.data ?? { ready: false, missing: [] };
}


export type QuizQuestionType = "MULTIPLE_CHOICE" | "TRUE_FALSE" | "MULTI_SELECT";

export interface QuizOption {
  id: number;
  optionText: string;
  isCorrect?: boolean | null;
  orderIndex: number;
}

export interface QuizQuestion {
  id: number;
  questionText: string;
  questionType: QuizQuestionType;
  points: number;
  orderIndex: number;
  explanation?: string | null;
  options: QuizOption[];
}

export interface Quiz {
  id: number;
  courseId: number | null;
  moduleId: number | null;
  lessonId: number | null;
  moduleTitle: string | null;
  lessonTitle: string | null;
  title: string;
  description: string | null;
  passThreshold: number;
  timeLimitMinutes: number | null;
  maxAttempts: number | null;
  isActive: boolean;
  orderIndex: number;
  questions?: QuizQuestion[];
  questionCount?: number;
  attemptCount?: number;
  bestScorePercent?: number | null;
}

export interface QuizQuestionResult {
  questionId: number;
  correct: boolean;
  selectedOptionIds: number[];
  correctOptionIds: number[];
  explanation: string | null;
}

export interface QuizSubmitResult {
  attemptId: number;
  scorePercent: number;
  passed: boolean;
  passThreshold: number;
  attemptNumber: number;
  attemptsRemaining: number | null;
  totalQuestions: number;
  correctCount: number;
  timeTakenSeconds: number | null;
  results: QuizQuestionResult[];
}

export interface QuizAttemptSummary {
  id: number;
  quizId: number;
  scorePercent: number | null;
  passed: boolean | null;
  attemptNumber: number | null;
  startedAt: string | null;
  completedAt: string | null;
  timeTakenSeconds: number | null;
}

export async function createInstructorQuiz(data: {
  courseId: number;
  moduleId?: number | null;
  lessonId?: number | null;
  title: string;
  description?: string;
  passThreshold?: number;
  timeLimitMinutes?: number | null;
  maxAttempts?: number | null;
  isActive?: boolean;
}) {
  const wrapper = await apiFetch<ApiResponse<Quiz>>("/api/instructor/quizzes", {
    method: "POST",
    body: JSON.stringify(data),
  });
  return wrapper.data;
}

export async function listInstructorQuizzes(courseId: number) {
  const wrapper = await apiFetch<ApiResponse<Quiz[]>>(
    `/api/instructor/courses/${courseId}/quizzes`
  );
  return wrapper.data;
}

export async function getInstructorQuiz(quizId: number) {
  const wrapper = await apiFetch<ApiResponse<Quiz>>(`/api/instructor/quizzes/${quizId}`);
  return wrapper.data;
}

export async function updateInstructorQuiz(quizId: number, data: Partial<{
  title: string;
  description: string;
  passThreshold: number;
  timeLimitMinutes: number | null;
  maxAttempts: number | null;
  isActive: boolean;
  orderIndex: number;
}>) {
  const wrapper = await apiFetch<ApiResponse<Quiz>>(`/api/instructor/quizzes/${quizId}`, {
    method: "PUT",
    body: JSON.stringify(data),
  });
  return wrapper.data;
}

export async function deleteInstructorQuiz(quizId: number) {
  return apiFetch<ApiResponse<unknown>>(`/api/instructor/quizzes/${quizId}`, {
    method: "DELETE",
  });
}

export async function addQuizQuestion(quizId: number, data: {
  questionText: string;
  questionType: QuizQuestionType;
  points?: number;
  explanation?: string;
  options: { optionText: string; isCorrect: boolean }[];
}) {
  const wrapper = await apiFetch<ApiResponse<QuizQuestion>>(
    `/api/instructor/quizzes/${quizId}/questions`,
    { method: "POST", body: JSON.stringify(data) }
  );
  return wrapper.data;
}

export async function updateQuizQuestion(questionId: number, data: {
  questionText: string;
  questionType: QuizQuestionType;
  points?: number;
  explanation?: string;
  options: { optionText: string; isCorrect: boolean }[];
}) {
  const wrapper = await apiFetch<ApiResponse<QuizQuestion>>(
    `/api/instructor/questions/${questionId}`,
    { method: "PUT", body: JSON.stringify(data) }
  );
  return wrapper.data;
}

export async function deleteQuizQuestion(questionId: number) {
  return apiFetch<ApiResponse<unknown>>(`/api/instructor/questions/${questionId}`, {
    method: "DELETE",
  });
}

export async function reorderQuizQuestions(quizId: number, questionIds: number[]) {
  return apiFetch<ApiResponse<unknown>>(
    `/api/instructor/quizzes/${quizId}/questions/reorder`,
    { method: "PUT", body: JSON.stringify({ questionIds }) }
  );
}

export async function listCourseQuizzes(courseId: number) {
  const wrapper = await apiFetch<ApiResponse<Quiz[]>>(`/api/courses/${courseId}/quizzes`);
  return wrapper.data;
}

export async function getQuizForStudent(quizId: number) {
  const wrapper = await apiFetch<ApiResponse<Quiz>>(`/api/quizzes/${quizId}`);
  return wrapper.data;
}

export async function submitQuiz(
  quizId: number,
  data: {
    answers: { questionId: number; selectedOptionIds: number[] }[];
    timeTakenSeconds?: number;
  }
) {
  const wrapper = await apiFetch<ApiResponse<QuizSubmitResult>>(
    `/api/quizzes/${quizId}/submit`,
    { method: "POST", body: JSON.stringify(data) }
  );
  return wrapper.data;
}

export async function getMyQuizAttempts(quizId: number) {
  const wrapper = await apiFetch<ApiResponse<QuizAttemptSummary[]>>(
    `/api/quizzes/${quizId}/attempts`
  );
  return wrapper.data;
}

// ─── Sessions (live mentorship) ──

export async function requestSession(enrollmentId: number, topic: string) {
  const wrapper = await apiFetch<ApiResponse<import("./types").SessionRequest>>(
    "/api/sessions",
    { method: "POST", body: JSON.stringify({ enrollmentId, topic }) }
  );
  return wrapper.data;
}

export async function getMySessions() {
  const wrapper = await apiFetch<ApiResponse<import("./types").SessionRequest[]>>(
    "/api/sessions/my"
  );
  return wrapper.data;
}

export async function cancelSession(sessionId: number) {
  const wrapper = await apiFetch<ApiResponse<import("./types").SessionRequest>>(
    `/api/sessions/${sessionId}/cancel`,
    { method: "PUT" }
  );
  return wrapper.data;
}

export async function getMentorSessions() {
  const wrapper = await apiFetch<ApiResponse<import("./types").SessionRequest[]>>(
    "/api/sessions/mentor"
  );
  return wrapper.data;
}

export async function getMentorPendingRequests() {
  const wrapper = await apiFetch<ApiResponse<import("./types").SessionRequest[]>>(
    "/api/sessions/mentor/pending"
  );
  return wrapper.data;
}

export async function acceptSessionRequest(
  sessionId: number,
  scheduledAt: string,
  meetingUrl: string
) {
  const wrapper = await apiFetch<ApiResponse<import("./types").SessionRequest>>(
    `/api/sessions/${sessionId}/accept`,
    { method: "PUT", body: JSON.stringify({ scheduledAt, meetingUrl }) }
  );
  return wrapper.data;
}

export async function completeSession(sessionId: number) {
  const wrapper = await apiFetch<ApiResponse<import("./types").SessionRequest>>(
    `/api/sessions/${sessionId}/complete`,
    { method: "PUT" }
  );
  return wrapper.data;
}

// ─── Phase 11E Batch 5b: Cloudinary + Modules + Course progress ──

export interface CloudinarySignature {
  cloudName: string;
  apiKey: string;
  timestamp: number;
  folder: string;
  signature: string;
}

export async function getCloudinarySignature() {
  const wrapper = await apiFetch<ApiResponse<CloudinarySignature>>(
    "/api/instructor/cloudinary-signature"
  );
  return wrapper.data;
}

export async function uploadCourseThumbnail(courseId: number, file: File) {
  const token = typeof window !== "undefined" ? localStorage.getItem("access_token") : null;
  const formData = new FormData();
  formData.append("file", file);
  const res = await fetch(`${BASE_URL}/api/courses/${courseId}/thumbnail`, {
    method: "POST",
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    body: formData,
  });
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(body.message || "Thumbnail upload failed");
  }
  const wrapper = await res.json();
  return wrapper.data as { thumbnailUrl: string };
}

export interface VideoUploadHandle {
  promise: Promise<{ lessonId: number; videoUrl: string; durationMinutes: number | null }>;
  cancel: () => void;
}

export function uploadLessonVideoWithProgress(
  lessonId: number,
  file: File,
  onProgress?: (percent: number) => void,
): VideoUploadHandle {
  const token = typeof window !== "undefined" ? localStorage.getItem("access_token") : null;
  const xhr = new XMLHttpRequest();
  const promise = new Promise<{ lessonId: number; videoUrl: string; durationMinutes: number | null }>(
    (resolve, reject) => {
      const formData = new FormData();
      formData.append("file", file);
      xhr.open("POST", `${BASE_URL}/api/lessons/${lessonId}/upload-video`);
      if (token) xhr.setRequestHeader("Authorization", `Bearer ${token}`);
      xhr.upload.addEventListener("progress", (e) => {
        if (e.lengthComputable && onProgress) {
          onProgress(Math.round((e.loaded / e.total) * 100));
        }
      });
      xhr.addEventListener("load", () => {
        if (xhr.status >= 200 && xhr.status < 300) {
          try {
            const wrapper = JSON.parse(xhr.responseText);
            resolve(wrapper.data);
          } catch {
            reject(new Error("Malformed upload response"));
          }
        } else {
          let message = `Upload failed (${xhr.status})`;
          try {
            const body = JSON.parse(xhr.responseText);
            if (body.message) message = body.message;
          } catch { /* keep default */ }
          reject(new Error(message));
        }
      });
      xhr.addEventListener("error", () => reject(new Error("Network error during upload")));
      xhr.addEventListener("abort", () => reject(new Error("Upload cancelled")));
      xhr.send(formData);
    }
  );
  return { promise, cancel: () => xhr.abort() };
}

export async function clearLessonVideo(lessonId: number) {
  const wrapper = await apiFetch<ApiResponse<unknown>>(
    `/api/lessons/${lessonId}/video`,
    { method: "DELETE" }
  );
  return wrapper.data;
}

export async function reorderLessons(lessonIds: number[]) {
  return apiFetch<ApiResponse<unknown>>("/api/lessons/reorder", {
    method: "PUT",
    body: JSON.stringify({ lessonIds }),
  });
}

// ─── Modules ──

export async function getCourseModules(courseId: number | string) {
  const wrapper = await apiFetch<ApiResponse<import("./types").Module[]>>(
    `/api/courses/${courseId}/modules`
  );
  return wrapper.data;
}

export async function createModule(
  courseId: number | string,
  data: { title: string; description?: string; orderIndex?: number }
) {
  const wrapper = await apiFetch<ApiResponse<import("./types").Module>>(
    `/api/courses/${courseId}/modules`,
    { method: "POST", body: JSON.stringify(data) }
  );
  return wrapper.data;
}

export async function updateModule(
  moduleId: number,
  data: { title?: string; description?: string; orderIndex?: number }
) {
  const wrapper = await apiFetch<ApiResponse<import("./types").Module>>(
    `/api/modules/${moduleId}`,
    { method: "PUT", body: JSON.stringify(data) }
  );
  return wrapper.data;
}

export async function deleteModule(moduleId: number) {
  return apiFetch<ApiResponse<unknown>>(
    `/api/modules/${moduleId}`,
    { method: "DELETE" }
  );
}

// ─── Course progress ──

export interface LessonProgress {
  lessonId: number;
  title: string;
  orderIndex: number;
  completed: boolean;
  videoPositionSec?: number;
}

export interface ModuleProgress {
  moduleId: number;
  moduleTitle: string;
  orderIndex: number;
  totalLessons: number;
  completedLessons: number;
  progressPercent: number;
  lessons: LessonProgress[];
}

export interface CourseProgress {
  courseId: number;
  enrollmentId: number;
  totalLessons: number;
  completedLessons: number;
  progressPercent: number;
  modules: ModuleProgress[];
  orphanLessons: LessonProgress[];
}

export async function getCourseProgress(courseId: number | string) {
  const wrapper = await apiFetch<ApiResponse<CourseProgress>>(
    `/api/courses/${courseId}/progress`
  );
  return wrapper.data;
}

export async function saveLessonPosition(
  courseId: number | string,
  lessonId: number,
  videoPositionSec: number
) {
  return apiFetch<ApiResponse<unknown>>(
    `/api/users/progress/${courseId}`,
    {
      method: "PUT",
      body: JSON.stringify({
        lessonId,
        videoPositionSec: Math.max(0, Math.round(videoPositionSec)),
      }),
    }
  );
}

// ─── Services (courses where type=SERVICE) ──

export async function getServices() {
  const wrapper = await apiFetch<ApiResponse<unknown[]>>("/api/courses?type=SERVICE");
  return wrapper.data;
}

export async function getService(id: string | number) {
  const wrapper = await apiFetch<ApiResponse<unknown>>(`/api/courses/${id}`);
  return wrapper.data;
}

export async function createService(data: {
  title: string;
  description?: string;
  shortDescription?: string;
  price?: number;
  category?: string;
  trainerId?: number;
}) {
  const wrapper = await apiFetch<ApiResponse<unknown>>("/api/courses", {
    method: "POST",
    body: JSON.stringify({ ...data, type: "SERVICE" }),
  });
  return wrapper.data;
}

// ─── Phase 11E Batch 4: Cart + Checkout ──

export async function addToCart(courseId: number) {
  const wrapper = await apiFetch<ApiResponse<unknown>>(`/api/cart/${courseId}`, { method: "POST" });
  return wrapper.data;
}

export async function getCart() {
  const wrapper = await apiFetch<ApiResponse<unknown[]>>("/api/cart");
  return wrapper.data;
}

export async function removeFromCart(courseId: number) {
  const wrapper = await apiFetch<ApiResponse<unknown>>(`/api/cart/${courseId}`, { method: "DELETE" });
  return wrapper.data;
}

export async function clearCart() {
  const wrapper = await apiFetch<ApiResponse<unknown>>("/api/cart", { method: "DELETE" });
  return wrapper.data;
}

export interface CheckoutResult {
  subtotal: number;
  discount: number;
  total: number;
  couponCode: string | null;
}

export async function checkoutCart(couponCode?: string | null) {
  const wrapper = await apiFetch<ApiResponse<CheckoutResult>>("/api/cart/checkout", {
    method: "POST",
    body: JSON.stringify({ couponCode: couponCode ?? null }),
  });
  return wrapper.data;
}

// ─── Phase 11E Batch 6: Sales B2B instructor inbox ──

export async function getInstructorSalesInquiries() {
  const wrapper = await apiFetch<ApiResponse<SalesInquiry[]>>("/api/sales/inquiries/instructor");
  return wrapper.data;
}

export async function sendSalesQuote(id: number, data: {
  message: string;
  quotedPrice: number;
  quotedItems: SalesQuoteItem[];
}) {
  const wrapper = await apiFetch<ApiResponse<SalesInquiry>>(
    `/api/sales/inquiries/${id}/quote`,
    { method: "POST", body: JSON.stringify(data) }
  );
  return wrapper.data;
}

// ─── Phase 11E Batch 7: User Records / audit log ──

export interface UserRecord {
  id: number;
  userId: number;
  recordType: string;
  category: string;
  title: string;
  description: string;
  details: string | null;
  ipAddress: string | null;
  deviceType: string | null;
  browser: string | null;
  os: string | null;
  city: string | null;
  createdAt: string;
}

export interface UserRecordsPage {
  records: UserRecord[];
  page: number;
  size: number;
  totalPages: number;
  totalElements: number;
  hasNext: boolean;
}

export interface UserRecordsSummary {
  total: number;
  byCategory: Record<string, number>;
}

export async function getUserRecords(
  userId: number | string,
  params: { category?: string; from?: string; to?: string; page?: number; size?: number } = {}
) {
  const qs = "?" + new URLSearchParams(
    Object.entries(params)
      .filter(([, v]) => v !== undefined && v !== null && v !== "")
      .map(([k, v]) => [k, String(v)])
  ).toString();
  const wrapper = await apiFetch<ApiResponse<UserRecordsPage>>(
    `/api/admin/users/${userId}/records${qs}`
  );
  return wrapper.data;
}

export async function getUserRecordsSummary(userId: number | string) {
  const wrapper = await apiFetch<ApiResponse<UserRecordsSummary>>(
    `/api/admin/users/${userId}/records/summary`
  );
  return wrapper.data;
}

/**
 * Streams the CSV directly from the backend (bypasses apiFetch
 * because the response is text/csv, not JSON) and triggers a
 * browser download with a sage-branded filename.
 */
export async function downloadUserRecordsCsv(userId: number | string, fileBaseName: string) {
  const token = typeof window !== "undefined" ? localStorage.getItem("access_token") : null;
  const res = await fetch(`${BASE_URL}/api/admin/users/${userId}/records/download`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
  if (!res.ok) throw new Error(`Download failed (${res.status})`);
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  const today = new Date().toISOString().slice(0, 10);
  // Sanitise the basename: lower-case, dashes only.
  const safe = fileBaseName.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "") || "user";
  a.download = `sage-${safe}-records-${today}.csv`;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

// ─── Agreement-ERM + Consultant (hidden internal feature) ───────────
//
// Two surfaces, completely separate from Sage's /erm-dashboard:
//   - /agreement-erm/*    — single hardcoded operator, JWT in sessionStorage
//   - /consultant/*       — public, applicationId acts as the credential

export type ConsultantApplicationStatus =
  | "DRAFT"
  | "SUBMITTED"
  | "REVISION_REQUESTED"
  | "UPDATED"
  | "VERIFIED"
  // 3B — role-based approval gate between VERIFIED and the ERM countersign.
  | "AWAITING_APPROVALS"
  | "APPROVAL_REVISION_REQUESTED"
  | "READY_TO_SIGN"
  | "SIGNED"
  | "COMPLETED"
  | "CANCELLED"
  | "EXPIRED";

// 3B — per-approver gate record (Manager / Accounts).
export type ApproverRole = "MANAGER" | "ACCOUNTS";
export type ApprovalDecision = "PENDING" | "APPROVED" | "REVISION_REQUESTED";

export interface AgreementApproval {
  id: number;
  applicationId: number;
  role: ApproverRole;
  status: ApprovalDecision;
  note: string | null;
  phase: number;
  round: number;
  // Build K — the specific approver this gate was routed to (null on legacy).
  approverUserId: string | null;
  approverName: string | null;
  decidedBy: string | null;
  decidedByName: string | null;
  decidedAt: string | null;
  decidedIp: string | null;
  createdAt: string;
}

export interface ConsultantApplication {
  id: number;
  applicationId: string;
  ermUserId: number;
  // Phase B — per-ERM ownership. ownerErmId is the owning AgreementUser
  // id; ownerName is the resolved display name (present on list rows for
  // the super-admin's oversight column, null otherwise).
  ownerErmId?: string | null;
  ownerName?: string | null;
  consultantEmail: string;
  consultantName: string | null;
  // Build W — structured name (composed into consultantName server-side).
  firstName: string | null;
  middleName: string | null;
  lastName: string | null;
  consultantPhone: string | null;
  payload: string | null;
  status: ConsultantApplicationStatus;
  revisionNotes: string | null;
  createdAt: string;
  updatedAt: string;
  expiresAt: string;
  signedAt: string | null;
  signatureImage: string | null;
  signedLegalName: string | null;
  signedIp: string | null;
  signedUserAgent: string | null;
  signedPdfUrl: string | null;
  // Phase 1 / Phase 3 additions. Every field nullable -- the consultant
  // fills these via PUT /fill (partial body), and the ERM seeds
  // rate*1/2 + revision tracking from the agreement-erm console.
  ratePeriod1: string | null;
  rateAmount1: string | null;
  ratePeriod2: string | null;
  rateAmount2: string | null;
  // Appendix 1 Schedule 1 — ERM-set Phase 2 monthly-deliverables period.
  phase2DeliverablePeriod?: string | null;
  // Consultant personal block (required at fill-in time)
  primaryPhone: string | null;
  workAuthorizationCategory: string | null;
  // Build W — custom value when workAuthorizationCategory === "Others".
  workAuthorizationOther: string | null;
  residenceAddress: string | null;
  // Build W — structured US billing address.
  addressLine1: string | null;
  addressLine2: string | null;
  addressCity: string | null;
  addressState: string | null;
  addressZip: string | null;
  effectiveDate: string | null; // ISO yyyy-MM-dd
  // Exhibit A
  technologyTrack: string | null;
  customScopeNotes: string | null;
  // Appendix 1 -- employment
  employerPayrollEntity: string | null;
  implementationPartner: string | null;
  endClient: string | null;
  roleTitle: string | null;
  verifiedStartDate: string | null;
  payrollCycle: string | null;
  // Build W/I — work-authorization document (now in Personal Information).
  // Phase 5 (S3): *S3Key is the new pointer; the Cloudinary *PublicId is null
  // for new uploads. Treat (publicId || s3Key) as "uploaded".
  workAuthDocPublicId: string | null;
  workAuthDocS3Key?: string | null;
  workAuthDocContentType: string | null;
  workAuthDocUploadedAt: string | null;
  // Build I — Phase 2 Employment offer-letter upload.
  offerLetterPublicId: string | null;
  offerLetterS3Key?: string | null;
  offerLetterContentType: string | null;
  offerLetterUploadedAt: string | null;
  // Build J — Background Check uploads (DL/State-ID required; SSN optional).
  dlDocPublicId: string | null;
  dlDocS3Key?: string | null;
  dlDocContentType: string | null;
  dlDocUploadedAt: string | null;
  // Build AK — State-ID document (mirrors the DL doc pointers).
  stateIdDocPublicId: string | null;
  stateIdDocS3Key?: string | null;
  stateIdDocContentType: string | null;
  stateIdDocUploadedAt: string | null;
  ssnDocPublicId: string | null;
  ssnDocS3Key?: string | null;
  ssnDocContentType: string | null;
  ssnDocUploadedAt: string | null;
  // Appendix 2 -- ACH (optional)
  achAccountType: string | null;
  achBankName: string | null;
  achAccountHolderName: string | null;
  achRoutingNumber: string | null;
  achAccountNumber: string | null;
  achNoticeEmail: string | null;
  // Build Y — flattened views (comma-joined) of the ERM-filled schedule.
  achDebitDates: string | null;
  achDebitAmounts: string | null;
  achDebitSchedule: string | null; // JSON array of AchDebitRow
  // Appendix 3 -- background check (sensitive PII)
  bgFullLegalName: string | null;
  bgOtherNamesUsed: string | null;
  bgCurrentAddress: string | null;
  // Build J — structured current address + same-as-residence toggle.
  bgCurrentAddressLine1: string | null;
  bgCurrentAddressLine2: string | null;
  bgCurrentAddressCity: string | null;
  bgCurrentAddressState: string | null;
  bgCurrentAddressZip: string | null;
  bgCurrentSameAsResidence: boolean | null;
  bgDateOfBirth: string | null;
  bgFullSsn: string | null;
  bgDriverLicense: string | null;
  // Build AK — State-ID number (alongside the DL number; either or both).
  bgStateId: string | null;
  // Appendix 4 -- portal access
  portalPlatform: string | null;
  portalUsername: string | null;
  // Build J — repeatable platform+username entries (JSON-in-TEXT).
  portalEntries: string | null;
  portalAuthorizedActions: string | null;
  portalEffectiveDate: string | null;
  portalRevocationContact: string | null;
  // Appendix 5 -- security check
  securityCheckCount: string | null;
  securityCheckNumbers: string | null;
  securityCheckBank: string | null;
  securityCheckHolderName: string | null;
  securityCheckAmount: string | null;
  securityCheckDates: string | null;
  // ERM countersignature
  ermName: string | null;
  ermTitle: string | null;
  ermSignatureUrl: string | null;
  signatureDate: string | null;
  // Build W — ERM countersign date (null until the ERM signs).
  ermSignatureDate: string | null;
  // Revision tracking
  currentRevisionRemarks: string | null;
  revisionCount: number | null;
  // Build Y — ERM section-picker revision scope (JSON array of {key,note});
  // non-empty + REVISION_REQUESTED ⇒ the consultant is restricted to these.
  revisionSections: string | null;
  // Build AQ — can the ERM still take back the open change request? Resolved
  // server-side on the detail read (null on list reads and on any row that
  // isn't in REVISION_REQUESTED). When false, revisionRevokeBlockedReason says
  // why in one sentence — most often because revisionConsultantActed is true,
  // which blocks the take-back outright rather than warning about it.
  // revisionRevokeReverts names the ERM's own corrections (ACH schedule, rate
  // card, deliverables period) that went out with the request and would be
  // rolled back with it; empty when the request carried no data fix.
  revisionRevocable?: boolean | null;
  revisionRevokeBlockedReason?: string | null;
  revisionConsultantActed?: boolean | null;
  revisionRevokeReverts?: string[] | null;
  revisionRequestedAt?: string | null;
  // The status the row was revised FROM — where a revoke puts it back.
  revisionPrevStatus?: ConsultantApplicationStatus | null;
  // Build P — Phase 2 reopened-section scope (JSON array of {key}); when
  // phase ≥ 2 and status = SUBMITTED, the consultant fills + signs ONLY
  // these reopened sections, and every completed Phase-1 section (incl.
  // its uploads) is hidden + immutable.
  phase2ReopenedSections?: string | null;
  // Final post-countersignature PDF
  finalPdfUrl: string | null;
  // Phase D — consultant access record (real client IP via X-Forwarded-For).
  // access* captured at OTP verify; signing* at submit. Surfaced to ERM/admin.
  accessIp?: string | null;
  accessAt?: string | null;
  signingIp?: string | null;
  signingAt?: string | null;
  // F-1 — per-section affirmation flags. Eight booleans the wizard
  // toggles per section; the backend's submit-time gate requires all
  // eight to be true alongside every required field + the signature.
  affirmedMainAgreement?: boolean | null;
  affirmedExhibitA?: boolean | null;
  affirmedExhibitB?: boolean | null;
  affirmedAppendix1?: boolean | null;
  affirmedAppendix2?: boolean | null;
  affirmedAppendix3?: boolean | null;
  affirmedAppendix4?: boolean | null;
  affirmedAppendix5?: boolean | null;
  // F-4 — per-agreement requirement flags, set by the ERM at create
  // time. Drive which appendices the wizard treats as required vs
  // optional-skippable for THIS consultant.
  requireAppendix1?: boolean | null;
  requireAppendix2?: boolean | null;
  requireAppendix3?: boolean | null;
  requireAppendix4?: boolean | null;
  requireAppendix5?: boolean | null;
  requireSsn?: boolean | null;
  // F-4 — final (review-step) signature. Drawn separately from the
  // primary signature on the main-agreement step; stamps the closing
  // execution block in the generated PDF.
  finalSignatureImage?: string | null;
  finalSignedAt?: string | null;
  finalSigningIp?: string | null;
  // Build G — Appendix 3 ID type toggle. "DL" | "STATE_ID".
  idType?: string | null;
  // Build M — current phase of the two-phase coaching agreement.
  // 1 = pre-employment (creation default); 2 = post-offer, reached
  // via the ERM "Advance to Phase 2" action on the same document.
  phase?: number | null;
  // Build V — the approved consultant-version number the ERM routed for the
  // CURRENT approval round (the approver reviews this version's snapshot).
  approvalVersionNumber?: number | null;
  // Build G — Appendix 5 security cheque upload. The public_id is
  // persisted; the wizard treats a non-null value as "uploaded".
  chequePublicId?: string | null;
  chequeS3Key?: string | null;   // Phase 5 (S3) — new pointer; publicId null for new uploads
  chequeUploadedAt?: string | null;
  chequeContentType?: string | null;
  // Build T — ERM "Approve consultant version" release state. When
  // consultantCopyReleased is true the consultant can download an
  // OTP-gated PDF of their copy + Certificate of Completion.
  consultantCopyReleased?: boolean | null;
  consultantCopyReleasedAt?: string | null;
  consultantCopyReleasedBy?: string | null;
  consultantPdfPublicId?: string | null;
  documentHash?: string | null;
  // Build T — e-sign consent record captured at the gate before
  // the wizard. consentGivenAt is the wizard-blocking gate signal.
  consentGivenAt?: string | null;
  consentIp?: string | null;
  consentVersion?: string | null;
  // Build U — multi-cheque support. JSON string of ChequeEntry[];
  // ERM detail + wizard parse this client-side.
  cheques?: string | null;
  // Build U — consultant download accounting surfaced to the ERM.
  consultantDownloadCount?: number | null;
  consultantLastDownloadedAt?: string | null;
  // Build O — ERM "All" list approval summary (transient; populated on
  // list rows only, null on detail). managerStatus/accountsStatus carry
  // the latest gate decision per role (PENDING | APPROVED |
  // REVISION_REQUESTED) or null when no gate exists (Phase 1 → no Accounts
  // gate → "N/A"). sentForApprovalAt is the ISO timestamp the agreement
  // was first sent for approval, or null if never sent.
  managerStatus?: string | null;
  accountsStatus?: string | null;
  sentForApprovalAt?: string | null;
  // Build L — timestamp of the last consultant-invite send (create or
  // resend); drives the derived 7-day link-expiry window.
  inviteSentAt?: string | null;
  // Build Q — DERIVED: the consultant access link has lapsed (7 days from
  // inviteSentAt) for an awaiting (SUBMITTED) agreement. The agreement is
  // NOT expired/hidden — it stays in every dashboard; this only drives the
  // "Link expired — resend" indicator (ERM) and the "contact Sage IT to
  // resend" screen (consultant). Populated on list/consultant reads only.
  linkExpired?: boolean | null;
  // Build AP — DERIVED: the server's own resolution of which sections the
  // submit gate applies to, keyed appendix1..appendix5 / ssn /
  // ssnDocRequired. Computed by the SAME call the submit validator gates
  // on, so the wizard can render the server's answer instead of
  // re-deriving its own and risking a section the submit demands but the
  // wizard hides. Populated on consultant reads only.
  effectiveRequirements?: Record<string, boolean> | null;
}

/**
 * Build U — one cheque entry as stored in the cheques JSON column.
 * Backward-compat: when the column is empty but legacy chequePublicId
 * is set, the backend returns it as a single entry at index 0.
 */
export interface ChequeEntry {
  index: number;
  number: string;
  date: string;
  publicId: string;
  s3Key: string;   // Phase 5 (S3) — new pointer; publicId is "" for new uploads
  contentType: string;
  uploadedAt: string;
}

/**
 * Parse the {@code cheques} JSON column into a sorted-by-index list.
 * Tolerates null / malformed JSON by returning an empty array.
 */
export function parseChequeList(raw: string | null | undefined): ChequeEntry[] {
  if (!raw) return [];
  try {
    const arr = JSON.parse(raw) as unknown;
    if (!Array.isArray(arr)) return [];
    return arr
      .map((n) => {
        const o = n as Record<string, unknown>;
        return {
          index: Number(o.index ?? 0),
          number: String(o.number ?? ""),
          date: String(o.date ?? ""),
          publicId: String(o.publicId ?? ""),
          s3Key: String(o.s3Key ?? ""),
          contentType: String(o.contentType ?? ""),
          uploadedAt: String(o.uploadedAt ?? ""),
        } satisfies ChequeEntry;
      })
      .sort((a, b) => a.index - b.index);
  } catch {
    return [];
  }
}

/**
 * Shape of the body accepted by PUT /api/consultant/applications/{appId}/fill.
 * Any subset of these 37 fields; absent keys are left untouched on the
 * entity (idempotent partial save).
 */
export interface ConsultantFillPayload {
  // Build W — structured name (consultant can correct spelling).
  firstName?: string;
  middleName?: string;
  lastName?: string;
  primaryPhone?: string;
  workAuthorizationCategory?: string;
  residenceAddress?: string;
  // Build W — structured US billing address.
  addressLine1?: string;
  addressLine2?: string;
  addressCity?: string;
  addressState?: string;
  addressZip?: string;
  effectiveDate?: string;
  technologyTrack?: string;
  customScopeNotes?: string;
  employerPayrollEntity?: string;
  implementationPartner?: string;
  endClient?: string;
  roleTitle?: string;
  verifiedStartDate?: string;
  payrollCycle?: string;
  achAccountType?: string;
  achBankName?: string;
  achAccountHolderName?: string;
  achRoutingNumber?: string;
  achAccountNumber?: string;
  achNoticeEmail?: string;
  achDebitDates?: string;
  achDebitAmounts?: string;
  bgFullLegalName?: string;
  bgOtherNamesUsed?: string;
  bgCurrentAddress?: string;
  // Build J — structured current address + same-as-residence toggle.
  bgCurrentAddressLine1?: string;
  bgCurrentAddressLine2?: string;
  bgCurrentAddressCity?: string;
  bgCurrentAddressState?: string;
  bgCurrentAddressZip?: string;
  bgCurrentSameAsResidence?: boolean;
  bgDateOfBirth?: string;
  bgFullSsn?: string;
  bgDriverLicense?: string;
  // Build AK — State-ID number.
  bgStateId?: string;
  portalPlatform?: string;
  portalUsername?: string;
  // Build J — repeatable platform+username entries (JSON-in-TEXT).
  portalEntries?: string;
  portalAuthorizedActions?: string;
  portalEffectiveDate?: string;
  portalRevocationContact?: string;
  securityCheckCount?: string;
  securityCheckNumbers?: string;
  securityCheckBank?: string;
  securityCheckHolderName?: string;
  securityCheckAmount?: string;
  securityCheckDates?: string;
  // Build G — Appendix 3 ID type toggle ("DL" | "STATE_ID").
  idType?: string;
  // F-1 affirmation booleans -- the wizard sends these as the
  // consultant ticks each section's "I have read and understood"
  // checkbox. Backend treats null as "not sent" (partial save).
  affirmedMainAgreement?: boolean;
  affirmedExhibitA?: boolean;
  affirmedExhibitB?: boolean;
  affirmedAppendix1?: boolean;
  affirmedAppendix2?: boolean;
  affirmedAppendix3?: boolean;
  affirmedAppendix4?: boolean;
  affirmedAppendix5?: boolean;
}

export interface ConsultantApplicationEvent {
  id: number;
  applicationId: number;
  eventType: string;
  actorType: "ERM" | "CONSULTANT" | "SYSTEM";
  actorUserId: number | null;
  metadata: string | null;
  ipAddress: string | null;
  userAgent: string | null;
  createdAt: string;
}

export interface ConsultantApplicationRevision {
  id: number;
  applicationId: number;
  versionNumber: number;
  payloadSnapshot: string | null;
  createdByRole: "ERM" | "CONSULTANT";
  createdByUserId: number | null;
  createdAt: string;
}

export interface ConsultantApplicationDetailEnvelope {
  application: ConsultantApplication;
  events: ConsultantApplicationEvent[];
  revisions: ConsultantApplicationRevision[];
  // 3B — per-approver gate history (present once Send for Approval fires).
  approvals?: AgreementApproval[];
}

export interface ConsultantApplicationsPage {
  content: ConsultantApplication[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
}

// ── Agreement-ERM auth ──────────────────────────────────────────
//
// Token lives in sessionStorage so a closed tab clears it inside the
// 8h TTL. Stays out of the global apiFetch Authorization header so
// it can't bleed into Sage's user-auth flows.

const AGREEMENT_ERM_TOKEN_KEY = "sage_agreement_erm_token";

export function getAgreementErmToken(): string | null {
  if (typeof window === "undefined") return null;
  try {
    return sessionStorage.getItem(AGREEMENT_ERM_TOKEN_KEY);
  } catch {
    return null;
  }
}

export function clearAgreementErmToken() {
  if (typeof window === "undefined") return;
  try {
    sessionStorage.removeItem(AGREEMENT_ERM_TOKEN_KEY);
  } catch {
    /* storage disabled */
  }
}

export async function agreementErmLogin(email: string, password: string) {
  const res = await fetch(`${BASE_URL}/api/agreement-erm/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password }),
  });
  const body = (await res.json()) as ApiResponse<{ token: string; email: string }>;
  if (!res.ok || !body?.success) {
    throw new Error(body?.message || "Login failed.");
  }
  if (typeof window !== "undefined") {
    try {
      sessionStorage.setItem(AGREEMENT_ERM_TOKEN_KEY, body.data.token);
    } catch {
      /* storage disabled */
    }
  }
  return body.data;
}

async function agreementErmFetch<T>(
  path: string,
  init: RequestInit = {},
): Promise<T> {
  const token = getAgreementErmToken();
  if (!token) {
    throw new Error("Session expired. Please sign in again.");
  }
  const res = await fetch(`${BASE_URL}${path}`, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
      ...(init.headers ?? {}),
    },
  });
  if (res.status === 401 || res.status === 403) {
    clearAgreementErmToken();
    throw new Error("Session expired. Please sign in again.");
  }
  const body = (await res.json()) as ApiResponse<T>;
  if (!res.ok || !body?.success) {
    throw new Error(body?.message || `Request failed (${res.status})`);
  }
  return body.data;
}

// ── Agreement-ERM identity + admin console ──────────────────────
//
// /me lets the frontend render role-aware UI without decoding the JWT
// client-side. The admin endpoints are SUPER_ADMIN-only server-side;
// the console page also guards on the role from /me as a UX layer.

export type AgreementUserRole = "SUPER_ADMIN" | "ERM" | "MANAGER" | "ACCOUNTS";

export interface AgreementMe {
  userId: string | null;
  email: string | null;
  fullName: string | null;
  title: string | null;
  role: AgreementUserRole | null;
}

export interface AgreementUserDto {
  id: string;
  email: string;
  fullName: string;
  title: string;
  role: AgreementUserRole;
  active: boolean;
  createdAt: string | null;
  lastLoginAt: string | null;
}

// Phase C — slim row for the admin "Agreements by ERM" grouped view.
export interface AgreementSummaryDto {
  appId: string;
  consultantName: string | null;
  consultantEmail: string;
  status: ConsultantApplicationStatus;
  ownerErmId: string | null;
  ownerName: string | null;
  createdAt: string | null;
  updatedAt: string | null;
}

/**
 * Error carrying the HTTP status so the admin UI can branch on it
 * (e.g. 409 -> "Email already in use" inline). Unlike
 * {@link agreementErmFetch}, the admin fetch does NOT treat 403 as a
 * dead session -- a 403 here means "not the super-admin", not "token
 * expired", so it must not nuke the session.
 */
export class AdminApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.name = "AdminApiError";
    this.status = status;
  }
}

async function agreementAdminFetch<T>(
  path: string,
  init: RequestInit = {},
): Promise<T> {
  const token = getAgreementErmToken();
  if (!token) {
    throw new AdminApiError(401, "Session expired. Please sign in again.");
  }
  const res = await fetch(`${BASE_URL}${path}`, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
      ...(init.headers ?? {}),
    },
  });
  if (res.status === 401) {
    clearAgreementErmToken();
    throw new AdminApiError(401, "Session expired. Please sign in again.");
  }
  let body: ApiResponse<T> | null = null;
  try {
    body = (await res.json()) as ApiResponse<T>;
  } catch {
    /* non-JSON error body */
  }
  if (!res.ok || !body?.success) {
    throw new AdminApiError(res.status, body?.message || `Request failed (${res.status})`);
  }
  return body.data as T;
}

export async function fetchMe() {
  return agreementErmFetch<AgreementMe>("/api/agreement-erm/me");
}

export async function adminListUsers() {
  return agreementAdminFetch<AgreementUserDto[]>("/api/agreements/admin/users");
}

/** Build AA/AC — operator backfill summary (dry-run reports without changing). */
export interface AdminBackfillSummary {
  dryRun: boolean;
  status: string;
  matched: number;
  processed: number;
  reverted?: number; // revoke-erm-signatures
  regenerated?: number; // regenerate-completed-agreements
  failed: number;
  errors: string[];
}

/**
 * Build AC — revoke the ERM countersignature on every COMPLETED agreement and
 * revert each to VERIFIED for re-approval + re-signature. DESTRUCTIVE +
 * consultant-visible. Pass dryRun=true first to preview the affected count.
 */
export async function adminRevokeErmSignatures(dryRun: boolean) {
  return agreementAdminFetch<AdminBackfillSummary>(
    "/api/agreements/admin/revoke-erm-signatures",
    { method: "POST", body: JSON.stringify({ dryRun }) },
  );
}

/**
 * Build AA — re-render every COMPLETED agreement from the current template and
 * overwrite its stored PDFs + hash. DESTRUCTIVE. dryRun=true previews the count.
 */
export async function adminRegenerateCompletedAgreements(dryRun: boolean) {
  return agreementAdminFetch<AdminBackfillSummary>(
    "/api/agreements/admin/regenerate-completed-agreements",
    { method: "POST", body: JSON.stringify({ dryRun }) },
  );
}

export async function adminCreateUser(body: {
  email: string;
  fullName: string;
  title: string;
  temporaryPassword: string;
  // 3A — "ERM" | "MANAGER" | "ACCOUNTS"; defaults to ERM server-side.
  role?: Exclude<AgreementUserRole, "SUPER_ADMIN">;
}) {
  return agreementAdminFetch<AgreementUserDto>("/api/agreements/admin/users", {
    method: "POST",
    body: JSON.stringify(body),
  });
}

export async function adminSetUserStatus(id: string, active: boolean) {
  return agreementAdminFetch<AgreementUserDto>(
    `/api/agreements/admin/users/${id}/status`,
    { method: "PATCH", body: JSON.stringify({ active }) },
  );
}

export async function adminResetUserPassword(id: string, newPassword: string) {
  return agreementAdminFetch<AgreementUserDto>(
    `/api/agreements/admin/users/${id}/password`,
    { method: "PATCH", body: JSON.stringify({ newPassword }) },
  );
}

/**
 * Update a console user's details — name + title (signature block) and,
 * optionally, the login email (Build AN; blocked for the super-admin, whose
 * account is provisioned from the environment). Omit `email` to leave it
 * unchanged.
 */
export async function adminUpdateUserDetails(
  id: string,
  details: { fullName: string; title: string; email?: string },
) {
  return agreementAdminFetch<AgreementUserDto>(
    `/api/agreements/admin/users/${id}/details`,
    { method: "PATCH", body: JSON.stringify(details) },
  );
}

/**
 * Email a console user their sign-in credentials (email + temporary password)
 * from noreply@sageitco.com. The plaintext password is only known right after
 * create/reset (it's hashed in storage), so the caller passes it through here.
 */
export async function adminSendUserCredentials(id: string, password: string) {
  return agreementAdminFetch<null>(
    `/api/agreements/admin/users/${id}/send-credentials`,
    { method: "POST", body: JSON.stringify({ password }) },
  );
}

// Build K2 — super-admin changes a user's role / deletes a user.
export async function adminChangeUserRole(
  id: string,
  role: Exclude<AgreementUserRole, "SUPER_ADMIN">,
) {
  return agreementAdminFetch<AgreementUserDto>(
    `/api/agreements/admin/users/${id}/role`,
    { method: "PATCH", body: JSON.stringify({ role }) },
  );
}

export async function adminDeleteUser(id: string) {
  return agreementAdminFetch<null>(`/api/agreements/admin/users/${id}`, {
    method: "DELETE",
  });
}

// Build K — per-ERM Manager/Accounts assignments (super-admin only).
export interface ErmAssignments {
  managerIds: string[];
  accountsIds: string[];
}

export async function adminGetErmAssignments(ermId: string) {
  return agreementAdminFetch<ErmAssignments>(
    `/api/agreements/admin/users/${ermId}/assignments`,
  );
}

export async function adminSetErmAssignments(ermId: string, body: ErmAssignments) {
  return agreementAdminFetch<ErmAssignments>(
    `/api/agreements/admin/users/${ermId}/assignments`,
    { method: "PUT", body: JSON.stringify(body) },
  );
}

// Phase C — admin "Agreements by ERM" grouped view (super-admin only).
export async function adminListAgreements() {
  return agreementAdminFetch<AgreementSummaryDto[]>("/api/agreements/admin/applications");
}

/**
 * Build L — super-admin soft-deletes ANY agreement (any status). The
 * backend returns 204; we resolve to void. 401 clears the session;
 * other failures throw AdminApiError carrying the status.
 *
 * (Old name: adminArchiveApplication. Re-exported under that name
 * for backward compat with any caller that hasn't been re-bumped.)
 */
export async function adminDeleteApplication(appId: string) {
  const token = getAgreementErmToken();
  if (!token) {
    throw new AdminApiError(401, "Session expired. Please sign in again.");
  }
  const res = await fetch(
    `${BASE_URL}/api/agreements/admin/applications/${appId}`,
    { method: "DELETE", headers: { Authorization: `Bearer ${token}` } },
  );
  if (res.status === 401) {
    clearAgreementErmToken();
    throw new AdminApiError(401, "Session expired. Please sign in again.");
  }
  if (res.status === 204) return;
  let message = `Request failed (${res.status})`;
  try {
    const body = (await res.json()) as ApiResponse<unknown>;
    if (body?.message) message = body.message;
  } catch {
    /* non-JSON body */
  }
  throw new AdminApiError(res.status, message);
}

/** Legacy alias preserved for any caller that hasn't been renamed yet. */
export const adminArchiveApplication = adminDeleteApplication;

// ── Agreement-ERM operations ────────────────────────────────────

export async function createConsultantApplication(data: {
  consultantEmail: string;
  consultantName?: string;
  // Build W — structured name; server composes consultantName from these.
  firstName?: string;
  middleName?: string;
  lastName?: string;
  consultantPhone?: string;
  // Phase 3: the rate card the ERM seeds at create time. Free-form
  // strings (e.g. period="Months 1-12", amount="$2,400") rendered
  // straight into the Word template's Section 11 cells.
  ratePeriod1?: string;
  rateAmount1?: string;
  ratePeriod2?: string;
  rateAmount2?: string;
  // Appendix 1 Schedule 1 — ERM-set Phase 2 monthly-deliverables period
  // (single merged cell, e.g. "Months 1-12"); read-only to the consultant.
  phase2DeliverablePeriod?: string;
  // F-4: ERM-set visa status (persisted into workAuthCategory) and
  // per-appendix requirement flags. Drive the wizard's effective
  // requirements + the submit-time gate.
  visaStatus?: string;
  // Build W — custom value when visaStatus === "Others".
  visaStatusOther?: string;
  requireAppendix1?: boolean;
  requireAppendix2?: boolean;
  requireAppendix3?: boolean;
  requireAppendix4?: boolean;
  requireAppendix5?: boolean;
  requireSsn?: boolean;
  // Build Y — ERM-filled ACH debit schedule (single free-text fields,
  // e.g. "15th of every month" / "$416.67"); read-only to the consultant.
  achDebitDates?: string;
  achDebitAmounts?: string;
  // Build I — ERM-set Service Track (Exhibit A); Track required, Scope
  // optional; read-only to the consultant.
  technologyTrack?: string;
  customScopeNotes?: string;
  // Build Z — ERM-set Appendix 4 Authorized Actions + Revocation Contact;
  // read-only to the consultant. Authorized Actions carries a default.
  portalAuthorizedActions?: string;
  portalRevocationContact?: string;
  // Build O — optional ERM-authored invitation email pre-text. Blank →
  // the Sage IT Co default copy. Becomes the intro of the consultant's
  // "complete your details" email.
  emailPretext?: string;
  // Legacy JSON-textarea payload. New /agreement-erm/new form does
  // not send it; preserved so the detail-view edit panel and any
  // other in-flight caller keeps compiling.
  payload?: unknown;
}) {
  return agreementErmFetch<ConsultantApplication>(
    "/api/agreement-erm/applications",
    { method: "POST", body: JSON.stringify(data) },
  );
}

export async function listConsultantApplications(
  params: { status?: string; page?: number; size?: number } = {},
) {
  const qs = "?" + new URLSearchParams(
    Object.entries(params)
      .filter(([, v]) => v !== undefined && v !== null && v !== "")
      .map(([k, v]) => [k, String(v)]),
  ).toString();
  return agreementErmFetch<ConsultantApplicationsPage>(
    `/api/agreement-erm/applications${qs}`,
  );
}

/**
 * Every application the caller can see, walking the paged endpoint until it
 * runs out. The server clamps a page at 100 rows, so a single call can never
 * be a complete answer — the super-admin console needs one, because it groups
 * by owning ERM and computes counts, and a capped page silently understates
 * both.
 *
 * Returns the rows plus `complete`, which is false only if the walk hit the
 * page ceiling below. Callers must surface that rather than presenting a
 * partial count as a total.
 */
export async function listAllConsultantApplications(
  params: { status?: string } = {},
  // 50 pages x 100 rows. Far above any real volume, and it stops a server-side
  // paging bug from spinning this into an infinite request loop.
  maxPages = 50,
): Promise<{ rows: ConsultantApplication[]; total: number; complete: boolean }> {
  const rows: ConsultantApplication[] = [];
  let page = 0;
  let total = 0;

  for (; page < maxPages; page++) {
    const result = await listConsultantApplications({ ...params, page, size: 100 });
    rows.push(...result.content);
    total = result.totalElements;
    if (!result.hasNext || result.content.length === 0) {
      return { rows, total, complete: true };
    }
  }

  return { rows, total, complete: false };
}

export async function getConsultantApplication(applicationId: string) {
  return agreementErmFetch<ConsultantApplicationDetailEnvelope>(
    `/api/agreement-erm/applications/${applicationId}`,
  );
}

export async function updateConsultantApplication(
  applicationId: string,
  data: {
    consultantEmail?: string;
    consultantName?: string;
    consultantPhone?: string;
    payload?: unknown;
  },
) {
  return agreementErmFetch<ConsultantApplication>(
    `/api/agreement-erm/applications/${applicationId}`,
    { method: "PUT", body: JSON.stringify(data) },
  );
}

export async function cancelConsultantApplication(applicationId: string) {
  return agreementErmFetch<ConsultantApplication>(
    `/api/agreement-erm/applications/${applicationId}/cancel`,
    { method: "POST" },
  );
}

export async function resendConsultantInvite(applicationId: string) {
  return agreementErmFetch<{ message: string }>(
    `/api/agreement-erm/applications/${applicationId}/resend-invite`,
    { method: "POST" },
  );
}

// Phase C feature 1 — owner ERM (or super-admin) fixes the consultant's
// email/name. Ownership-gated server-side (non-owner → 404).
export async function updateConsultantContact(
  applicationId: string,
  data: { consultantEmail: string; consultantName?: string },
) {
  return agreementErmFetch<ConsultantApplication>(
    `/api/agreement-erm/applications/${applicationId}/consultant-contact`,
    { method: "PATCH", body: JSON.stringify(data) },
  );
}

// ── Phase 6 two-stage workflow actions (Bearer token via
//    agreementErmFetch is auto-attached from sessionStorage) ─────

/** Build Y — one ERM-selected revision section (optional per-section note). */
export interface RevisionSectionSelection {
  key: string;
  note?: string;
}

/** Build Y — parse the persisted revision_sections JSON to selections. */
export function parseRevisionSections(
  json: string | null | undefined,
): RevisionSectionSelection[] {
  if (!json) return [];
  try {
    const arr = JSON.parse(json);
    if (!Array.isArray(arr)) return [];
    return arr
      .filter((r) => r && typeof r.key === "string" && r.key.length > 0)
      .map((r) => ({ key: r.key as string, note: r.note as string | undefined }));
  } catch {
    return [];
  }
}

export async function ermRequestRevision(
  applicationId: string,
  sections: RevisionSectionSelection[],
  ach?: { achDebitDates?: string; achDebitAmounts?: string },
  // Build W — optional Phase-2 Rate Schedule correction (free-text,
  // pre-filled). A changed value auto-scopes the main agreement so the
  // consultant re-reviews + re-signs the corrected rate.
  rate?: {
    ratePeriod1?: string;
    rateAmount1?: string;
    ratePeriod2?: string;
    rateAmount2?: string;
  },
  // Appendix 1 Schedule 1 — optional Phase-2 deliverables-period correction.
  // A changed value auto-scopes Appendix 1 for the consultant's re-review.
  deliverable?: { phase2DeliverablePeriod?: string },
) {
  return agreementErmFetch<ConsultantApplication>(
    `/api/agreement-erm/applications/${applicationId}/request-revision`,
    {
      method: "POST",
      body: JSON.stringify({
        sections,
        ...(ach ?? {}),
        ...(rate ?? {}),
        ...(deliverable ?? {}),
      }),
    },
  );
}

/**
 * Build AJ — ERM "Request signature re-sign": a signature-only revision. Clears
 * the consultant's stored signatures and bounces the agreement back so they
 * re-sign with a proper signature (content untouched). Optional note.
 */
export async function ermRequestSignatureRevision(
  applicationId: string,
  note?: string,
) {
  return agreementErmFetch<ConsultantApplication>(
    `/api/agreement-erm/applications/${applicationId}/request-signature-revision`,
    { method: "POST", body: JSON.stringify({ note: note ?? "" }) },
  );
}

/**
 * Build AK — ERM "Request document re-upload": a per-document revision. Clears
 * the requested uploaded document(s) so the consultant must upload fresh files
 * (their other details stay unchanged) and bounces the agreement back. Doc
 * keys: doc:workauth, doc:offer-letter, doc:dl-doc, doc:ssn-doc, doc:cheque.
 */
export async function ermRequestDocumentRevision(
  applicationId: string,
  docKeys: string[],
  note?: string,
) {
  return agreementErmFetch<ConsultantApplication>(
    `/api/agreement-erm/applications/${applicationId}/request-document-revision`,
    { method: "POST", body: JSON.stringify({ docKeys, note: note ?? "" }) },
  );
}

/**
 * Build AQ — ERM takes back a change request sent by mistake. Restores every
 * field the request cleared (affirmations, signatures, documents) plus any ERM
 * rate/ACH correction that was sent with it, and returns the agreement to the
 * desk it was revised from.
 *
 * Refused by the server once the consultant has entered anything this round —
 * there is no override, because the restore would then be partial. The console
 * hides the action in that case (revisionRevocable false), so a refusal here
 * means the consultant started between the page load and the click.
 */
export async function ermRevokeRevision(applicationId: string) {
  return agreementErmFetch<ConsultantApplication>(
    `/api/agreement-erm/applications/${applicationId}/revoke-revision`,
    { method: "POST" },
  );
}

export async function ermApproveAndSign(
  applicationId: string,
  body: { ermName: string; ermTitle: string; ermSignatureBase64: string },
) {
  return agreementErmFetch<ConsultantApplication>(
    `/api/agreement-erm/applications/${applicationId}/approve-and-sign`,
    { method: "POST", body: JSON.stringify(body) },
  );
}

/**
 * Build M — advances a Phase-1 COMPLETED agreement to Phase 2 on the
 * same document. Optional promotion map flips selected previously-
 * optional sections to required for the Phase-2 fill; an empty body
 * promotes every currently-optional section. Status transitions
 * COMPLETED → SUBMITTED, signatures + affirmations clear, every
 * filled field stays put.
 */
export interface Phase2PromotionPayload {
  appendix1?: boolean;
  appendix2?: boolean;
  appendix3?: boolean;
  appendix4?: boolean;
  appendix5?: boolean;
  ssn?: boolean;
}

export async function ermAdvanceToPhase2(
  applicationId: string,
  promotion?: Phase2PromotionPayload,
) {
  return agreementErmFetch<ConsultantApplication>(
    `/api/agreement-erm/applications/${applicationId}/advance-to-phase-2`,
    { method: "POST", body: JSON.stringify(promotion ?? {}) },
  );
}

/**
 * Forwards the final signed PDF to an arbitrary recipient with an
 * optional note. Backend returns 204 No Content; the helper resolves
 * to void on success.
 */
export async function ermSendPdfToEmail(
  applicationId: string,
  recipientEmail: string,
  note?: string,
) {
  const token = getAgreementErmToken();
  if (!token) {
    throw new Error("Session expired. Please sign in again.");
  }
  const res = await fetch(
    `${BASE_URL}/api/agreement-erm/applications/${applicationId}/send-email`,
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({ recipientEmail, note: note ?? "" }),
    },
  );
  if (res.status === 401 || res.status === 403) {
    clearAgreementErmToken();
    throw new Error("Session expired. Please sign in again.");
  }
  if (res.status === 204) return;
  // Some failures still return JSON ApiResponse with success=false.
  let message = `Request failed (${res.status})`;
  try {
    const body = (await res.json()) as ApiResponse<unknown>;
    if (body?.message) message = body.message;
  } catch {
    /* leave default */
  }
  throw new Error(message);
}

/**
 * Streams the final PDF through the backend so the Cloudinary URL
 * never reaches the client. Returns the raw {@link Response} so the
 * caller can decide between {@code .blob()} (view inline / download
 * via blob URL) and any other binary handling.
 *
 * {@code disposition="inline"} (default) lets the browser render the
 * PDF in-tab; {@code "attachment"} forces a save dialog at the
 * backend layer (the frontend can also do this client-side by
 * triggering an anchor download against the blob URL).
 *
 * Throws when the session is dead; the caller surfaces other 4xx /
 * 5xx via {@link Response#ok}.
 */
export async function fetchAgreementPdfBlob(
  applicationId: string,
  disposition: "inline" | "attachment" = "inline",
): Promise<Response> {
  const token = getAgreementErmToken();
  if (!token) {
    throw new Error("Session expired. Please sign in again.");
  }
  const res = await fetch(
    `${BASE_URL}/api/agreement-erm/applications/${applicationId}/download-pdf?disposition=${disposition}`,
    { headers: { Authorization: `Bearer ${token}` } },
  );
  if (res.status === 401 || res.status === 403) {
    clearAgreementErmToken();
    throw new Error("Session expired. Please sign in again.");
  }
  return res;
}

/**
 * Build G — streams the consultant's Appendix 5 security cheque to the
 * ERM. Mirrors {@link fetchAgreementPdfBlob}'s session handling. The
 * response body is the cheque file (image or PDF) -- the Content-Type
 * header tells the caller which.
 */
export async function fetchAgreementChequeBlob(
  applicationId: string,
  disposition: "inline" | "attachment" = "inline",
): Promise<Response> {
  const token = getAgreementErmToken();
  if (!token) {
    throw new Error("Session expired. Please sign in again.");
  }
  const res = await fetch(
    `${BASE_URL}/api/agreement-erm/applications/${applicationId}/cheque?disposition=${disposition}`,
    { headers: { Authorization: `Bearer ${token}` } },
  );
  if (res.status === 401 || res.status === 403) {
    clearAgreementErmToken();
    throw new Error("Session expired. Please sign in again.");
  }
  return res;
}

/**
 * Build R — bearer-authenticated fetch of ANY ERM agreement document
 * (work-auth, offer-letter, DL, SSN, indexed cheque). The {@code erm*ViewUrl}
 * helpers return a token-less backend URL, so opening them in a new tab — or
 * a plain {@code credentials:"include"} fetch — does NOT attach the
 * sessionStorage bearer, and the JWT/role gate returns 403. Callers fetch the
 * bytes HERE (Authorization header) then open a blob URL. The endpoint stays
 * JWT/role-gated and dual-reads S3 (s3_key) → Cloudinary, streaming the real
 * content-type. {@code docPath} is e.g. "/workauth", "/offer-letter",
 * "/dl-doc", "/ssn-doc", "/cheques/{index}".
 */
export async function fetchAgreementDocBlob(
  applicationId: string,
  docPath: string,
  disposition: "inline" | "attachment" = "inline",
): Promise<Response> {
  const token = getAgreementErmToken();
  if (!token) {
    throw new Error("Session expired. Please sign in again.");
  }
  const res = await fetch(
    `${BASE_URL}/api/agreement-erm/applications/${applicationId}${docPath}?disposition=${disposition}`,
    { headers: { Authorization: `Bearer ${token}` } },
  );
  if (res.status === 401 || res.status === 403) {
    clearAgreementErmToken();
    throw new Error("Session expired. Please sign in again.");
  }
  return res;
}

// ── Consultant portal (email-scoped session) ───────────────────
//
// Token lives in sessionStorage under one global key so it survives
// navigation between dashboard / fill / sign but clears when the tab
// closes (within its ~2h life).
//
// Build V — the portal-auth endpoints are appId-bound and PUBLIC:
//   POST /api/consultant/applications/{appId}/auth/request-otp
//   POST /api/consultant/applications/{appId}/auth/verify-otp
//   GET  /api/consultant/applications/{appId}/auth/email-hint
// The consultant never types an email; the backend resolves the
// ERM-set address from the appId. The session token that verify
// returns is still email-scoped, so the dashboard lists every
// agreement addressed to the verified email.

const CONSULTANT_TOKEN_KEY = "sage_consultant_token";

export function getConsultantToken(): string | null {
  if (typeof window === "undefined") return null;
  try {
    return sessionStorage.getItem(CONSULTANT_TOKEN_KEY);
  } catch {
    return null;
  }
}

export function setConsultantToken(token: string) {
  if (typeof window === "undefined") return;
  try {
    sessionStorage.setItem(CONSULTANT_TOKEN_KEY, token);
  } catch {
    /* storage disabled */
  }
}

export function clearConsultantToken() {
  if (typeof window === "undefined") return;
  try {
    sessionStorage.removeItem(CONSULTANT_TOKEN_KEY);
  } catch {
    /* storage disabled */
  }
}

/**
 * Parse an ApiResponse body defensively. A cold-starting backend or a
 * proxy 502 can return an EMPTY or non-JSON body; calling res.json() on
 * that throws the opaque "Unexpected end of JSON input". Instead we read
 * the text and surface the real HTTP status so the failure is actionable
 * (and retryable) rather than mystifying.
 */
async function readApiResponse<T>(res: Response): Promise<ApiResponse<T>> {
  const text = await res.text();
  if (!text.trim()) {
    throw new Error(
      `The portal didn't respond (HTTP ${res.status || "network"}). It may be waking up — please try again in a moment.`,
    );
  }
  try {
    return JSON.parse(text) as ApiResponse<T>;
  } catch {
    throw new Error(
      `Unexpected response from the portal (HTTP ${res.status}). Please try again in a moment.`,
    );
  }
}

/**
 * Build V — appId-bound portal Step 1. PUBLIC, no token. The backend
 * resolves the consultant email from the agreement row and sends the
 * OTP there. NO email is accepted from the client — the consultant
 * cannot redirect the code to a different address.
 */
export async function requestConsultantPortalOtp(applicationId: string) {
  const res = await fetch(
    `${BASE_URL}/api/consultant/applications/${applicationId}/auth/request-otp`,
    { method: "POST" },
  );
  if (res.status === 429) {
    throw new Error("Too many requests. Try again in a minute.");
  }
  // Build Q — a lapsed access link returns { linkExpired: "true", message }
  // (no code sent); the login page renders the "link expired" state.
  const body = await readApiResponse<{ message: string; linkExpired?: string }>(res);
  if (!res.ok || !body?.success) {
    throw new Error(body?.message || `Request failed (${res.status})`);
  }
  return body.data;
}

/**
 * Build V — appId-bound portal Step 2. PUBLIC, no token. Body is just
 * the 6-digit code; the email is resolved server-side from the row.
 * On success stores the email-scoped session token (the dashboard
 * still lists every agreement addressed to the verified email).
 */
export async function verifyConsultantPortalOtp(applicationId: string, otp: string) {
  const res = await fetch(
    `${BASE_URL}/api/consultant/applications/${applicationId}/auth/verify-otp`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ otp }),
    },
  );
  if (res.status === 429) {
    throw new Error("Too many requests. Try again in a minute.");
  }
  const body = await readApiResponse<{ token: string }>(res);
  if (!res.ok || !body?.success) {
    throw new Error(body?.message || "Invalid or expired code.");
  }
  setConsultantToken(body.data.token);
  return body.data;
}

/**
 * Build V — masked-email hint for the login page. PUBLIC, no token;
 * the backend returns a neutral mask ("—") when the row is missing or
 * cancelled so existence can't be probed.
 */
export async function fetchConsultantEmailHint(applicationId: string) {
  const res = await fetch(
    `${BASE_URL}/api/consultant/applications/${applicationId}/auth/email-hint`,
  );
  if (res.status === 429) {
    throw new Error("Too many requests. Try again in a minute.");
  }
  const body = await readApiResponse<{ maskedEmail: string }>(res);
  if (!res.ok || !body?.success) {
    throw new Error(body?.message || `Request failed (${res.status})`);
  }
  return body.data;
}

// Token-gated consultant calls. Attaches the email-scoped portal token;
// on 401 (missing/expired/wrong-email) it clears the token and bounces
// to /consultant — server-side autosave means progress is preserved.
async function consultantFetch<T>(
  applicationId: string,
  path: string,
  init: RequestInit = {},
): Promise<T> {
  const token = getConsultantToken();
  const res = await fetch(
    `${BASE_URL}/api/consultant/applications/${applicationId}${path}`,
    {
      ...init,
      headers: {
        "Content-Type": "application/json",
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...(init.headers ?? {}),
      },
    },
  );
  if (res.status === 401) {
    clearConsultantToken();
    if (typeof window !== "undefined") {
      // Build V — bounce to the per-agreement login (which resolves the
      // email server-side from the appId), not the bare landing.
      window.location.assign(
        `/consultant/${encodeURIComponent(applicationId)}/login`,
      );
    }
    throw new Error("Verification required.");
  }
  if (res.status === 429) {
    throw new Error("Too many requests. Try again in a minute.");
  }
  const body = await readApiResponse<T>(res);
  if (!res.ok || !body?.success) {
    throw new Error(body?.message || `Request failed (${res.status})`);
  }
  return body.data;
}

/**
 * Build G — multipart POST helper: the standard {@link consultantFetch}
 * forces application/json. This wrapper attaches the consultant token
 * but lets the browser set the multipart Content-Type with boundary.
 */
/**
 * Build AB — downscale a large raster image before upload so phone-camera
 * photos (often 5–12 MB) don't time out or hit upload limits on mobile data.
 * Only touches images that are actually large; PDFs, GIFs, and already-small
 * images pass through untouched. Documents stay legible (max 2400px, JPEG 0.85).
 */
async function downscaleImageFile(file: File): Promise<File> {
  if (typeof document === "undefined") return file;
  if (!file.type.startsWith("image/") || file.type === "image/gif") return file;
  const MAX_DIM = 2400;
  const SIZE_THRESHOLD = 1_500_000; // ~1.5 MB — leave smaller files alone
  try {
    const bitmap = await createImageBitmap(file);
    const scale = Math.min(1, MAX_DIM / Math.max(bitmap.width, bitmap.height));
    if (scale === 1 && file.size <= SIZE_THRESHOLD) {
      bitmap.close?.();
      return file;
    }
    const w = Math.max(1, Math.round(bitmap.width * scale));
    const h = Math.max(1, Math.round(bitmap.height * scale));
    const canvas = document.createElement("canvas");
    canvas.width = w;
    canvas.height = h;
    const ctx = canvas.getContext("2d");
    if (!ctx) {
      bitmap.close?.();
      return file;
    }
    ctx.drawImage(bitmap, 0, 0, w, h);
    bitmap.close?.();
    const blob = await new Promise<Blob | null>((resolve) =>
      canvas.toBlob((b) => resolve(b), "image/jpeg", 0.85),
    );
    if (!blob || blob.size >= file.size) return file; // keep original if not smaller
    const base = file.name.replace(/\.[^.]+$/, "") || "upload";
    return new File([blob], `${base}.jpg`, { type: "image/jpeg" });
  } catch {
    return file; // any decode / canvas failure → upload the original
  }
}

async function consultantMultipartFetch(
  applicationId: string,
  path: string,
  formData: FormData,
): Promise<void> {
  const token = getConsultantToken();
  // Build AB — shrink an oversized image in place before sending (mobile data).
  const original = formData.get("file");
  if (original instanceof File) {
    const slimmed = await downscaleImageFile(original);
    if (slimmed !== original) formData.set("file", slimmed);
  }
  // Build AB — bound the request so a stalled mobile upload can't hang the
  // wizard forever; surface a real reason instead of an endless spinner.
  const controller = new AbortController();
  const timeout =
    typeof window !== "undefined"
      ? window.setTimeout(() => controller.abort(), 90_000)
      : undefined;
  let res: Response;
  try {
    res = await fetch(
      `${BASE_URL}/api/consultant/applications/${applicationId}${path}`,
      {
        method: "POST",
        headers: token ? { Authorization: `Bearer ${token}` } : {},
        body: formData,
        signal: controller.signal,
      },
    );
  } catch (e) {
    if (e instanceof DOMException && e.name === "AbortError") {
      throw new Error("The upload timed out. Check your connection and try again.");
    }
    throw new Error("Couldn't reach the server. Check your connection and try again.");
  } finally {
    if (timeout !== undefined && typeof window !== "undefined") {
      window.clearTimeout(timeout);
    }
  }
  if (res.status === 401) {
    clearConsultantToken();
    if (typeof window !== "undefined") {
      // Build V — bounce to the per-agreement login (which resolves the
      // email server-side from the appId), not the bare landing.
      window.location.assign(
        `/consultant/${encodeURIComponent(applicationId)}/login`,
      );
    }
    throw new Error("Your session expired. Please verify again to continue.");
  }
  if (res.status === 413) {
    throw new Error(
      "That file is too large to upload. Please use a smaller or more compressed photo.",
    );
  }
  if (res.status === 429) {
    throw new Error("Too many requests. Try again in a minute.");
  }
  const body = await readApiResponse<unknown>(res);
  if (!res.ok || !body?.success) {
    throw new Error(body?.message || `Upload failed (${res.status}).`);
  }
}

/**
 * Build G — uploads the Appendix 5 security cheque. The backend stores
 * the bytes in Cloudinary and persists the public_id on the row; the
 * wizard treats {@link ConsultantApplication.chequePublicId} as the
 * "uploaded ✓" signal.
 */
export async function uploadConsultantCheque(
  applicationId: string,
  file: File,
): Promise<void> {
  const form = new FormData();
  form.append("file", file);
  await consultantMultipartFetch(applicationId, "/cheque", form);
}

/**
 * Build U — upload the bytes for cheque #{@code index}. One call per
 * cheque entry; the backend stores each under
 * {@code agreements/{appId}-cheque-{index}}.
 */
export async function uploadConsultantChequeAt(
  applicationId: string,
  index: number,
  file: File,
): Promise<void> {
  const form = new FormData();
  form.append("file", file);
  await consultantMultipartFetch(applicationId, `/cheques/${index}`, form);
}

/**
 * Build W — upload the Appendix 1 work-authorization document. Stored
 * server-side at {@code agreements/{appId}-workauth}.
 */
export async function uploadConsultantWorkAuthDoc(
  applicationId: string,
  file: File,
): Promise<void> {
  const form = new FormData();
  form.append("file", file);
  await consultantMultipartFetch(applicationId, "/workauth", form);
}

/** Build I — upload the Phase 2 Employment offer letter. */
export async function uploadConsultantOfferLetter(
  applicationId: string,
  file: File,
): Promise<void> {
  const form = new FormData();
  form.append("file", file);
  await consultantMultipartFetch(applicationId, "/offer-letter", form);
}

/** Build J — upload the Background Check Driver's-License / State-ID document. */
export async function uploadConsultantDlDoc(
  applicationId: string,
  file: File,
): Promise<void> {
  const form = new FormData();
  form.append("file", file);
  await consultantMultipartFetch(applicationId, "/dl-doc", form);
}

/** Build AK — upload the Background Check State-ID document. */
export async function uploadConsultantStateIdDoc(
  applicationId: string,
  file: File,
): Promise<void> {
  const form = new FormData();
  form.append("file", file);
  await consultantMultipartFetch(applicationId, "/state-id-doc", form);
}

/** Build J — upload the (optional) Background Check SSN document. */
export async function uploadConsultantSsnDoc(
  applicationId: string,
  file: File,
): Promise<void> {
  const form = new FormData();
  form.append("file", file);
  await consultantMultipartFetch(applicationId, "/ssn-doc", form);
}

/** Build J — one repeatable Portal Access entry (platform + username). */
export interface PortalEntry {
  platform: string;
  username: string;
}

/** Build J — parse the portal_entries JSON column into a typed list. */
export function parsePortalEntries(json: string | null | undefined): PortalEntry[] {
  if (!json || !json.trim()) return [];
  try {
    const arr = JSON.parse(json);
    if (!Array.isArray(arr)) return [];
    return arr
      .map((e) => ({
        platform: typeof e?.platform === "string" ? e.platform : "",
        username: typeof e?.username === "string" ? e.username : "",
      }))
      .filter((e) => e.platform.length > 0 || e.username.length > 0);
  } catch {
    return [];
  }
}

/**
 * Build U — patch the metadata (number, date) for cheque #{@code index}
 * without touching the uploaded bytes. Save-on-blur from the wizard.
 */
export async function saveConsultantChequeMetadata(
  applicationId: string,
  index: number,
  body: { number?: string; date?: string },
): Promise<ConsultantApplication> {
  return consultantFetch<ConsultantApplication>(
    applicationId,
    `/cheques/${index}`,
    { method: "PUT", body: JSON.stringify(body) },
  );
}

/**
 * Build I — replaces the Build G PDF preview with a watermarked-images
 * preview. The backend renders the agreement to PDF in-memory,
 * rasterises each page via PDFBox, bakes a watermark
 * (CONFIDENTIAL + viewer email + UTC timestamp) on every page, and
 * returns a JSON envelope of base64 PNGs. No downloadable PDF leaves
 * the server, removing the obvious save path for the consultant view.
 */
export interface ConsultantPreviewImages {
  pages: string[];
  pageCount: number;
  viewerEmail: string;
  /** Build V — present on the approver version-preview (the routed version). */
  versionNumber?: number | null;
}

export async function fetchConsultantPreviewImages(
  applicationId: string,
  primarySignatureBase64: string | null,
): Promise<ConsultantPreviewImages> {
  return consultantFetch<ConsultantPreviewImages>(
    applicationId,
    "/preview-images",
    {
      method: "POST",
      body: JSON.stringify({ primarySignatureBase64 }),
    },
  );
}

/**
 * Build Y — approver inline preview as watermarked PNGs (non-copyable,
 * no saveable PDF). Reuses the {@link ConsultantPreviewImages} shape.
 */
export async function fetchApproverPreviewImages(
  applicationId: string,
  role?: ApproverRole,
): Promise<ConsultantPreviewImages> {
  const qs = role ? `?role=${role}` : "";
  return agreementErmFetch<ConsultantPreviewImages>(
    `/api/agreement-approver/applications/${applicationId}/preview-images${qs}`,
  );
}

/**
 * Build O — approver preview of the FINAL, ERM-signed agreement from the
 * "Approved Documents" record. Same non-copyable PNG shape as
 * {@link fetchApproverPreviewImages}, but the backend renders with the ERM
 * countersignature present. Only valid once the agreement is COMPLETED.
 */
export async function fetchApproverSignedPreviewImages(
  applicationId: string,
  role?: ApproverRole,
): Promise<ConsultantPreviewImages> {
  const qs = role ? `?role=${role}` : "";
  return agreementErmFetch<ConsultantPreviewImages>(
    `/api/agreement-approver/applications/${applicationId}/signed-preview-images${qs}`,
  );
}

/**
 * Build V — approver inline preview of the FROZEN consultant version the ERM
 * routed for this approval round ({@code approvalVersionNumber}). Same
 * non-copyable PNG shape as {@link fetchApproverPreviewImages}; the backend
 * rasterizes the version's immutable snapshot, falling back to a live pre-sign
 * render for legacy rounds with no routed version. The response carries the
 * {@code versionNumber} being reviewed (null when falling back).
 */
export async function fetchApproverVersionPreviewImages(
  applicationId: string,
  role?: ApproverRole,
): Promise<ConsultantPreviewImages> {
  const qs = role ? `?role=${role}` : "";
  return agreementErmFetch<ConsultantPreviewImages>(
    `/api/agreement-approver/applications/${applicationId}/version-preview-images${qs}`,
  );
}

/**
 * Build AJ — approver preview of the LATEST consultant version, for the "All
 * agreements" status list. Same non-copyable PNG shape; the backend rasterizes
 * the newest immutable version snapshot (falling back to a live pre-sign render
 * for legacy rows) and gates on any round the approver was routed.
 */
export async function fetchApproverLatestVersionPreviewImages(
  applicationId: string,
  role?: ApproverRole,
): Promise<ConsultantPreviewImages> {
  const qs = role ? `?role=${role}` : "";
  return agreementErmFetch<ConsultantPreviewImages>(
    `/api/agreement-approver/applications/${applicationId}/latest-version-preview-images${qs}`,
  );
}

/**
 * Build S — Manager preview of the durable PHASE-1 ERM-signed agreement
 * (the snapshot captured at the Phase-1 countersign). Same non-copyable PNG
 * shape; the backend rasterizes the STORED Phase-1 PDF (not a live render) and
 * gates on the snapshot existing + MANAGER role, so it stays previewable
 * independently of Phase 2 (and of the COMPLETED state).
 */
export async function fetchApproverPhase1SignedPreviewImages(
  applicationId: string,
  role?: ApproverRole,
): Promise<ConsultantPreviewImages> {
  const qs = role ? `?role=${role}` : "";
  return agreementErmFetch<ConsultantPreviewImages>(
    `/api/agreement-approver/applications/${applicationId}/phase1-signed-preview-images${qs}`,
  );
}

/** Which document an approver is downloading (the backend's `doc` param). */
export type ApproverDownloadDoc = "final" | "phase1" | "approved";

/**
 * Build AK — the approver's downloadable PDF copy of an agreement they
 * approved. Every other approver document route returns watermarked PNGs; this
 * one returns real PDF bytes so the Manager / Accounts console can offer a
 * Download button once their gate is cleared. `doc` picks the document:
 * "final" (executed, COMPLETED only), "phase1" (Manager's durable Phase-1
 * snapshot) or "approved" (the consultant version they signed off on, before
 * the ERM countersign).
 *
 * Returns the raw Response: the caller checks `ok`, reads the reason off
 * `X-Preview-Error`, and names the saved file itself (Content-Disposition
 * isn't readable cross-origin).
 */
export async function fetchApproverAgreementPdfBlob(
  applicationId: string,
  doc: ApproverDownloadDoc = "final",
  role?: ApproverRole,
): Promise<Response> {
  const token = getAgreementErmToken();
  if (!token) {
    throw new Error("Session expired. Please sign in again.");
  }
  const qs = `?doc=${doc}` + (role ? `&role=${role}` : "");
  const res = await fetch(
    `${BASE_URL}/api/agreement-approver/applications/${applicationId}/download-pdf${qs}`,
    { headers: { Authorization: `Bearer ${token}` } },
  );
  // Unlike the other blob helpers, a 403 here can be the endpoint refusing a
  // document (Phase 1 is Manager-only) rather than a dead session — those
  // carry X-Preview-Error, so only a bare 401/403 signs the approver out.
  if (res.status === 401 || (res.status === 403 && !res.headers.get("X-Preview-Error"))) {
    clearAgreementErmToken();
    throw new Error("Session expired. Please sign in again.");
  }
  return res;
}

/**
 * Build G — ERM inline preview of the consultant-signed agreement
 * before countersigning. Streams the bytes server-side; no Cloudinary
 * round-trip. Same session handling as
 * {@link fetchAgreementPdfBlob}.
 */
export async function fetchErmPreviewPdfBlob(
  applicationId: string,
): Promise<Response> {
  const token = getAgreementErmToken();
  if (!token) {
    throw new Error("Session expired. Please sign in again.");
  }
  const res = await fetch(
    `${BASE_URL}/api/agreement-erm/applications/${applicationId}/preview-pdf`,
    { headers: { Authorization: `Bearer ${token}` } },
  );
  if (res.status === 401 || res.status === 403) {
    clearAgreementErmToken();
    throw new Error("Session expired. Please sign in again.");
  }
  return res;
}

// ── 3B — role-based approval workflow ──────────────────────────

export interface ApproverQueueItem {
  application: ConsultantApplication;
  approvals: AgreementApproval[];
  myRole: ApproverRole;
}

export interface ApproverDetailEnvelope {
  application: ConsultantApplication;
  approvals: AgreementApproval[];
  myRole: ApproverRole;
}

/** Build L — one row in an approver's read-only "Approved Agreements" record. */
export interface ApproverApprovedItem {
  appId: string;
  consultantName: string | null;
  consultantEmail: string | null;
  ermId: string | null;
  ermName: string;
  phase: number | null;
  decidedAt: string | null;
  status: string;
  // Build S — a durable Phase-1 ERM-signed agreement exists to preview
  // (MANAGER only; always false for Accounts). Drives the "Phase 1 signed"
  // preview button independently of the final/COMPLETED state.
  hasPhase1Signed?: boolean;
}

/** Build K — one approver option for the send-for-approval picker. */
export interface ApproverOption {
  id: string;
  name: string;
  email: string;
}

export interface EligibleApprovers {
  phase: number;
  managers: ApproverOption[];
  accounts: ApproverOption[];
}

/** Build K — the approvers this ERM may route the agreement to (drives the pickers). */
export async function ermFetchEligibleApprovers(applicationId: string) {
  return agreementErmFetch<EligibleApprovers>(
    `/api/agreement-erm/applications/${applicationId}/eligible-approvers`,
  );
}

/** Build V — one immutable approved consultant-version snapshot (V1, V2, …). */
export interface AgreementVersion {
  id: number;
  applicationId: number;
  versionNumber: number;
  s3Key?: string | null;
  documentHash?: string | null;
  phase?: number | null;
  approvedAt?: string | null;
}

/**
 * Build V — the approved consultant-version history (V1, V2, …) for the ERM
 * consultant view. Each row is an immutable snapshot created when the ERM
 * approved a consultant version; the ERM previews + selects which to send.
 */
export async function ermFetchAgreementVersions(applicationId: string) {
  return agreementErmFetch<AgreementVersion[]>(
    `/api/agreement-erm/applications/${applicationId}/versions`,
  );
}

/**
 * Build V — ERM view-only preview of a numbered consultant-version snapshot.
 * Streams the stored PDF via an authenticated bearer fetch (same session
 * handling as {@link fetchErmPreviewPdfBlob}); the caller turns the response
 * into a blob URL. The PDF is never presigned to the client.
 */
export async function fetchAgreementVersionPdfBlob(
  applicationId: string,
  versionNumber: number,
): Promise<Response> {
  const token = getAgreementErmToken();
  if (!token) {
    throw new Error("Session expired. Please sign in again.");
  }
  const res = await fetch(
    `${BASE_URL}/api/agreement-erm/applications/${applicationId}/versions/${versionNumber}/pdf`,
    { headers: { Authorization: `Bearer ${token}` } },
  );
  if (res.status === 401 || res.status === 403) {
    clearAgreementErmToken();
    throw new Error("Session expired. Please sign in again.");
  }
  return res;
}

/**
 * ERM routes a consultant-signed agreement to the phase's approvers (also
 * re-send). Build K — the chosen manager (Phase 1+2) + accounts (Phase 2)
 * are required; routing is bound to those specific users. Build V — an
 * optional {@code versionNumber} selects which approved consultant version
 * the approver(s) review (defaults to the latest when omitted).
 */
export async function ermSendForApproval(
  applicationId: string,
  routing?: {
    managerUserId?: string;
    accountsUserId?: string;
    versionNumber?: number;
  },
) {
  return agreementErmFetch<ConsultantApplication>(
    `/api/agreement-erm/applications/${applicationId}/send-for-approval`,
    { method: "POST", body: JSON.stringify(routing ?? {}) },
  );
}

export interface ApprovalBoardItem {
  application: ConsultantApplication;
  approvals: AgreementApproval[];
}

/** ERM status board: agreements currently in the approval gate (+ approvals). */
export async function fetchApprovalBoard() {
  return agreementErmFetch<ApprovalBoardItem[]>(
    "/api/agreement-erm/approval-board",
  );
}

/** Approver's queue — agreements awaiting my gate. */
export async function approverFetchQueue(role?: ApproverRole) {
  const qs = role ? `?role=${role}` : "";
  return agreementErmFetch<ApproverQueueItem[]>(
    `/api/agreement-approver/queue${qs}`,
  );
}

/** Build L — read-only record of agreements I've approved. */
export async function approverFetchApproved(role?: ApproverRole) {
  const qs = role ? `?role=${role}` : "";
  return agreementErmFetch<ApproverApprovedItem[]>(
    `/api/agreement-approver/approved${qs}`,
  );
}

/**
 * Build AI — the approver's "All agreements" status list: every agreement ever
 * routed to me (this gate), across all statuses, carrying the same summary
 * fields the ERM list uses (status, managerStatus / accountsStatus,
 * sentForApprovalAt, ownerName, appendix fields), so the manager dashboard can
 * render the ERM-style status table.
 */
export async function approverFetchApplications(role?: ApproverRole) {
  const qs = role ? `?role=${role}` : "";
  return agreementErmFetch<ConsultantApplication[]>(
    `/api/agreement-approver/applications${qs}`,
  );
}

/** Approver's read-only detail (+ approval history) for one agreement. */
export async function approverFetchDetail(
  applicationId: string,
  role?: ApproverRole,
) {
  const qs = role ? `?role=${role}` : "";
  return agreementErmFetch<ApproverDetailEnvelope>(
    `/api/agreement-approver/applications/${applicationId}${qs}`,
  );
}


export async function approverApprove(
  applicationId: string,
  body?: { note?: string; role?: ApproverRole },
) {
  return agreementErmFetch<ConsultantApplication>(
    `/api/agreement-approver/applications/${applicationId}/approve`,
    { method: "POST", body: JSON.stringify(body ?? {}) },
  );
}

export async function approverRequestRevision(
  applicationId: string,
  body: { note: string; role?: ApproverRole },
) {
  return agreementErmFetch<ConsultantApplication>(
    `/api/agreement-approver/applications/${applicationId}/request-revision`,
    { method: "POST", body: JSON.stringify(body) },
  );
}

// ── Portal dashboard (status-only; no download) ───────────────

// Build K — post-submit experience is status-only. SIGN +
// REVIEW_AND_SIGN open the wizard; NONE means "show the status pill,
// no button". Build L — the consultant download was retired; there is
// no DOWNLOAD action.
export type ConsultantPortalAction =
  | "SIGN"
  | "REVIEW_AND_SIGN"
  | "NONE";

export interface ConsultantAgreementSummary {
  appId: string;
  agreementTitle: string;
  technologyTrack: string | null;
  status: string;
  statusLabel: string;
  action: ConsultantPortalAction;
  createdAt: string | null;
  updatedAt: string | null;
  // Build M — current phase of the two-phase coaching agreement.
  phase: number | null;
  // Build T — ERM "Approve consultant version" release flags. When
  // downloadAvailable is true the dashboard surfaces the OTP-gated
  // download action; the per-app status screen renders the matching
  // download UI without re-checking the flag.
  consultantCopyReleased?: boolean;
  downloadAvailable?: boolean;
  consultantCopyReleasedAt?: string | null;
  // Build Q — the consultant access link has lapsed (7-day TTL) on an
  // awaiting agreement. The agreement is NOT expired/hidden; the row is
  // passive until the ERM resends a fresh link.
  linkExpired?: boolean;
}

/**
 * GET /api/consultant/agreements — email-scoped list of agreements the
 * verified consultant can act on (excludes CANCELLED + EXPIRED). 401
 * bounces back to /consultant per the standard 401 path.
 */
export async function fetchConsultantAgreements(): Promise<
  ConsultantAgreementSummary[]
> {
  const token = getConsultantToken();
  // This GET is idempotent, so a single retry safely rides out a backend
  // cold start / transient 5xx / empty-body proxy blip (the recurring
  // "Unexpected end of JSON input" symptom) instead of surfacing it.
  let res: Response | null = null;
  let lastErr: unknown;
  for (let attempt = 0; attempt < 2; attempt++) {
    if (attempt > 0) await new Promise((r) => setTimeout(r, 1200));
    try {
      res = await fetch(`${BASE_URL}/api/consultant/agreements`, {
        headers: token ? { Authorization: `Bearer ${token}` } : {},
        cache: "no-store",
      });
    } catch (e) {
      lastErr = e; // network error — retry once
      res = null;
      continue;
    }
    if (res.status === 401) {
      clearConsultantToken();
      if (typeof window !== "undefined") {
        window.location.assign("/consultant");
      }
      throw new Error("Verification required.");
    }
    // Retry transient server states; let 2xx/4xx fall through to parse.
    if (res.status >= 500 || res.status === 0) continue;
    break;
  }
  if (!res) {
    throw new Error(
      lastErr instanceof Error
        ? `Couldn't reach the portal (${lastErr.message}). Please try again.`
        : "Couldn't reach the portal. Please try again.",
    );
  }
  const body = await readApiResponse<ConsultantAgreementSummary[]>(res);
  if (!res.ok || !body?.success) {
    throw new Error(body?.message || `Request failed (${res.status})`);
  }
  return body.data;
}

/**
 * GET /api/consultant/agreement-template-pdf — the blank-form preview
 * of the master agreement (underscore placeholders where the
 * consultant's data will eventually land). Used by the wizard's "View
 * full agreement" reference button. Bytes are consultant-independent
 * and cached server-side; subsequent calls are an in-memory return.
 *
 * Returns the raw Response; caller calls .blob() + URL.createObjectURL
 * + revokeObjectURL on close (same leak-proof pattern as the per-app
 * download endpoint).
 */
export async function fetchAgreementTemplatePdfBlob(): Promise<Response> {
  const token = getConsultantToken();
  if (!token) {
    throw new Error("Verification required.");
  }
  return fetch(`${BASE_URL}/api/consultant/agreement-template-pdf`, {
    headers: { Authorization: `Bearer ${token}` },
  });
}

// ── F-3: full inline agreement clauses (parsed from the master template) ──

export interface AgreementSegment {
  kind: "text" | "ph";
  text?: string | null;
  /** Placeholder name when kind === "ph". */
  name?: string | null;
}

export interface AgreementBlock {
  kind: "heading" | "paragraph" | "table";
  level?: number | null;
  segments?: AgreementSegment[] | null;
  /** rows -> cells -> segments (kind === "table"). */
  rows?: AgreementSegment[][][] | null;
}

export interface AgreementContent {
  /** sectionId -> ordered blocks for that wizard section. */
  sections: Record<string, AgreementBlock[]>;
  /** Non-editable placeholder values for THIS app (editable ones are
   *  filled live by the wizard from form state). */
  values: Record<string, string>;
}

/**
 * GET /api/consultant/applications/{appId}/agreement-content — the real
 * clauses partitioned per wizard section (from the binding template) plus
 * this app's non-editable values. Token-gated + email-matched.
 */
export async function fetchAgreementContent(applicationId: string) {
  return consultantFetch<AgreementContent>(applicationId, "/agreement-content");
}

export async function getConsultantApplicationView(applicationId: string) {
  return consultantFetch<ConsultantApplication>(applicationId, "");
}

export async function verifyConsultantDetails(applicationId: string) {
  return consultantFetch<ConsultantApplication>(
    applicationId, "/verify-details",
    { method: "POST" },
  );
}

export async function requestConsultantRevision(
  applicationId: string,
  reason: string,
) {
  return consultantFetch<ConsultantApplication>(
    applicationId, "/request-revision",
    { method: "POST", body: JSON.stringify({ reason }) },
  );
}

/**
 * Phase 3 endpoint: PUT /api/consultant/applications/{appId}/fill.
 * Partial save; the backend stores only the non-null fields supplied
 * here. AbortController signal is forwarded through so the caller can
 * cancel in-flight saves when a fresher payload is ready.
 *
 * 429 surfaces as a "Too many requests" Error -- callers should pause
 * auto-save and back off (see the /fill page's retry logic).
 */
export async function saveConsultantFill(
  applicationId: string,
  patch: ConsultantFillPayload,
  signal?: AbortSignal,
) {
  return consultantFetch<ConsultantApplication>(
    applicationId, "/fill",
    { method: "PUT", body: JSON.stringify(patch), signal },
  );
}

/**
 * Phase 3 + F-4: POST /api/consultant/applications/{appId}/submit.
 *
 * The two-stage flow transitions SUBMITTED|REVISION_REQUESTED ->
 * VERIFIED here; the ERM countersignature later renders the final PDF.
 * F-4 first-and-last signature model demands TWO drawn signatures:
 *   - signatureBase64       primary (drawn on the main-agreement step)
 *   - finalSignatureBase64  final/execution (drawn on the review step)
 * The backend rejects the submission with a 400 +
 * { missingFinalSignature: true } if either is absent.
 */
export async function signConsultantApplication(
  applicationId: string,
  signedLegalName: string,
  signatureBase64: string,
  finalSignatureBase64: string,
) {
  return consultantFetch<ConsultantApplication>(
    applicationId, "/submit",
    {
      method: "POST",
      body: JSON.stringify({
        signedLegalName,
        signatureBase64,
        finalSignatureBase64,
      }),
    },
  );
}

/**
 * TODO: orphaned helper.
 *
 * Backed the legacy single-stage flow's "Email me another copy"
 * button on /consultant/[appId]/done. Removed from the UI when the
 * two-stage workflow started emailing the signed PDF automatically
 * via {@code sendCompletedAgreementToParties} on countersign. No
 * frontend caller remains.
 *
 * Kept around so the backend endpoint
 * {@code POST /api/consultant/applications/{appId}/request-copy}
 * doesn't become "live but unreachable from any client" -- if some
 * future surface wants to ask the consultant-side to re-email the
 * PDF, this is the helper. Safe to delete (along with the backend
 * endpoint + service method + email template) once we've confirmed
 * nothing else depends on it.
 */
export async function requestConsultantCopy(applicationId: string) {
  return consultantFetch<{ message: string }>(
    applicationId, "/request-copy",
    { method: "POST" },
  );
}

/**
 * Build T — POST /api/consultant/applications/{appId}/consent.
 * Records the consultant's e-sign consent at the gate shown BEFORE
 * the wizard. Idempotent — calling twice returns the same persisted
 * timestamp.
 */
export async function recordConsultantConsent(applicationId: string) {
  return consultantFetch<ConsultantApplication>(
    applicationId, "/consent",
    { method: "POST" },
  );
}

// Build L — the consultant download (request-download-otp + /download) was
// retired; no PDF is served to the consultant. The client helpers were
// removed. The internal consultant-version PDF + Certificate are still
// produced for audit via approve-consultant-version (below).

/**
 * Build T — ERM action POST /api/agreement-erm/applications/{appId}/approve-consultant-version.
 * Releases the consultant-version PDF (consultant signatures only,
 * with the appended Certificate of Completion). Distinct from
 * {@link ermApproveAndSign}: this does NOT countersign and does NOT
 * change the main state.
 */
export async function ermApproveConsultantVersion(applicationId: string) {
  return agreementErmFetch<ConsultantApplication>(
    `/api/agreement-erm/applications/${applicationId}/approve-consultant-version`,
    { method: "POST" },
  );
}
