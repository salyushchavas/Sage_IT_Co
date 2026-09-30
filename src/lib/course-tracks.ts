import {
  BrainCircuit, Braces, Bug, Cloud, Code2, Coffee, Database, LayoutDashboard,
  Layers, ShieldCheck, Workflow, type LucideIcon,
} from "lucide-react";

/**
 * The courses a visitor can apply for (roadmap step 1: "select technology /
 * skillset"). The names are the same ones the profile and program steps
 * use, so what someone applies for carries through their roadmap.
 */
export interface CourseTrack {
  name: string;
  description: string;
  icon: LucideIcon;
}

export const courseTracks: CourseTrack[] = [
  { name: "Java Full Stack",          description: "Java, Spring Boot, REST APIs, databases and a modern front end.", icon: Coffee },
  { name: "Python Full Stack",        description: "Python, web frameworks, APIs, databases and front-end basics.", icon: Code2 },
  { name: ".NET Full Stack",          description: "C#, ASP.NET Core, SQL Server and front-end development.", icon: Braces },
  { name: "Data Engineering",         description: "SQL, data pipelines, warehousing and big-data tools.", icon: Database },
  { name: "Cloud & DevOps",           description: "Cloud platforms, containers, CI/CD and infrastructure as code.", icon: Cloud },
  { name: "React / Angular Frontend", description: "Modern JavaScript and TypeScript, components, state and testing.", icon: LayoutDashboard },
  { name: "QA / Testing",             description: "Manual and automated testing, API tests and test planning.", icon: Bug },
  { name: "Data Science & AI",        description: "Python, statistics, machine learning and working with models.", icon: BrainCircuit },
  { name: "Salesforce",               description: "Administration, Apex, Lightning components and integrations.", icon: Layers },
  { name: "ServiceNow",               description: "Platform administration, ITSM, scripting and workflows.", icon: Workflow },
  { name: "Cybersecurity",            description: "Security fundamentals, threat detection, networks and compliance.", icon: ShieldCheck },
];

/** What the application form offers: every course, plus "Other". */
export const COURSE_OPTIONS: string[] = [...courseTracks.map((c) => c.name), "Other"];

/** The application page with a course already chosen. */
export function applyHref(course?: string): string {
  return course ? `/enroll?course=${encodeURIComponent(course)}` : "/enroll";
}
