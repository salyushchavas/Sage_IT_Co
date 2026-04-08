export const APP_NAME = "Sage IT";

export const NAV_LINKS = [
  { label: "Courses", href: "/courses" },
  { label: "Categories", href: "/categories" },
  { label: "Pricing", href: "/pricing" },
  { label: "About", href: "/about" },
  { label: "Support", href: "/support" },
] as const;

export const PRICING_TIERS = [
  {
    name: "Free",
    price: 0,
    currency: "INR",
    interval: "month" as const,
    description: "Get started with the basics",
    features: [
      "Access to free courses",
      "Community forum access",
      "Basic progress tracking",
      "Course bookmarks",
    ],
    cta: "Get Started",
    highlighted: false,
  },
  {
    name: "Pro",
    price: 499,
    currency: "INR",
    interval: "month" as const,
    description: "Unlock your full potential",
    features: [
      "Everything in Free",
      "Unlimited course access",
      "Certificates of completion",
      "Priority support",
      "Offline downloads",
      "Project reviews",
    ],
    cta: "Upgrade to Pro",
    highlighted: true,
  },
  {
    name: "Enterprise",
    price: 999,
    currency: "INR",
    interval: "month" as const,
    description: "For teams and organizations",
    features: [
      "Everything in Pro",
      "Team management dashboard",
      "Custom learning paths",
      "Dedicated account manager",
      "API access",
      "SSO integration",
      "Analytics & reporting",
    ],
    cta: "Contact Sales",
    highlighted: false,
  },
] as const;

export const LEVELS = ["Beginner", "Intermediate", "Advanced"] as const;

export const ACHIEVEMENT_LEVELS = [
  "Rookie",
  "Developer",
  "Expert",
  "Master",
] as const;

export const ITEMS_PER_PAGE = 12;

export const MAX_FILE_SIZE = 5 * 1024 * 1024;
