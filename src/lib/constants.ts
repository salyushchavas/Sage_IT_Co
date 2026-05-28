export const APP_NAME = "Sage IT";

export const NAV_LINKS = [
  { label: "Courses", href: "/courses" },
  { label: "Categories", href: "/categories" },
  { label: "About", href: "/about" },
  { label: "Support", href: "/support" },
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
