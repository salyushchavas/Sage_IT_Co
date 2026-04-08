// ─── Database-aligned types ─────────────────────────────────────────

export interface User {
  id: string;
  name: string;
  email: string;
  avatar_url: string | null;
  role: "student" | "instructor" | "admin";
  bio: string | null;
  created_at: string;
  updated_at: string;
}

export interface Course {
  id: string;
  title: string;
  slug: string;
  description: string;
  short_description: string;
  thumbnail_url: string | null;
  instructor_id: string;
  instructor?: User;
  category: string;
  level: CourseLevel;
  price: number;
  is_free: boolean;
  duration_hours: number;
  lessons_count: number;
  enrolled_count: number;
  rating: number;
  ratings_count: number;
  tags: string[];
  is_published: boolean;
  created_at: string;
  updated_at: string;
}

export interface Lesson {
  id: string;
  course_id: string;
  title: string;
  description: string | null;
  content_url: string | null;
  content_type: "video" | "article" | "quiz";
  duration_minutes: number;
  order: number;
  is_free_preview: boolean;
  created_at: string;
  updated_at: string;
}

export interface Enrollment {
  id: string;
  user_id: string;
  course_id: string;
  enrolled_at: string;
  completed_at: string | null;
  status: "active" | "completed" | "dropped";
}

export interface Subscription {
  id: string;
  user_id: string;
  plan: SubscriptionPlan;
  status: SubscriptionStatus;
  current_period_start: string;
  current_period_end: string;
  created_at: string;
  updated_at: string;
}

export interface Progress {
  id: string;
  user_id: string;
  lesson_id: string;
  course_id: string;
  status: "not_started" | "in_progress" | "completed";
  progress_percent: number;
  last_accessed_at: string;
  completed_at: string | null;
}

export interface Payment {
  id: string;
  user_id: string;
  subscription_id: string | null;
  course_id: string | null;
  amount: number;
  currency: string;
  status: "pending" | "completed" | "failed" | "refunded";
  payment_method: string;
  transaction_id: string | null;
  created_at: string;
}

export interface Achievement {
  id: string;
  user_id: string;
  badge_id: string;
  badge?: Badge;
  unlocked_at: string;
}

export interface Badge {
  id: string;
  name: string;
  description: string;
  icon_url: string;
  criteria: string;
  level: AchievementLevel;
}

// ─── Frontend-specific types ────────────────────────────────────────

export interface NavLink {
  label: string;
  href: string;
}

export interface PricingTier {
  name: string;
  price: number;
  currency: string;
  interval: "month" | "year";
  description: string;
  features: string[];
  cta: string;
  highlighted: boolean;
}

export type CourseLevel = "Beginner" | "Intermediate" | "Advanced";

export type SubscriptionPlan = "free" | "pro" | "enterprise";

export type SubscriptionStatus =
  | "active"
  | "cancelled"
  | "past_due"
  | "trialing"
  | "expired";

export type AchievementLevel = "Rookie" | "Developer" | "Expert" | "Master";

export interface CourseFilters {
  category?: string;
  level?: CourseLevel;
  search?: string;
  sort?: "popular" | "newest" | "price_asc" | "price_desc" | "rating";
  is_free?: boolean;
}
