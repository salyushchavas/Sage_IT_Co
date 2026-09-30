// Resources — the five learning offerings under the "Resources" menu.
// `id` is the URL slug: /resources/<id>.
export interface Resource {
  id: string;
  title: string;
  description: string;
  icon: string;
  features: string[];
  color: string;
  details: string;
  highlights: { title: string; description: string }[];
  cta: { label: string; href: string };
  secondaryCta: { label: string; href: string };
}

export const resources: Resource[] = [
  {
    id: "technology-courses",
    title: "Technology Courses",
    description:
      "Structured courses across today's most in-demand technology skills, built as step-by-step modules you can follow from beginner to advanced.",
    icon: "💻",
    features: [
      "Video lessons in clear modules",
      "Beginner to advanced levels",
      "Quizzes on key topics",
      "Learn at your own pace",
    ],
    color: "#1B2A5C",
    details:
      "Pick a course, follow it module by module, and build real skill one lesson at a time. Every course in the catalogue is laid out so you always know what comes next and how far you have come.",
    highlights: [
      {
        title: "Modular lessons",
        description: "Each course is split into modules and short video lessons, so there is always a clear next step.",
      },
      {
        title: "A level for everyone",
        description: "Filter the catalogue by beginner, intermediate or advanced and start exactly where you are.",
      },
      {
        title: "Check your understanding",
        description: "Quizzes along the way show what has landed and what deserves another look.",
      },
      {
        title: "Progress you can see",
        description: "Your dashboard remembers where you stopped and how much of each course is done.",
      },
    ],
    cta: { label: "Browse Courses", href: "/courses" },
    secondaryCta: { label: "View Learning Paths", href: "/categories" },
  },
  {
    id: "certification-pathways",
    title: "Certification Pathways",
    description:
      "Turn finished coursework into proof of skill. Complete a course and earn a Sage IT certificate that anyone can verify online.",
    icon: "🎓",
    features: [
      "Certificate of completion",
      "Online verification",
      "Clear completion steps",
      "Ready to share with employers",
    ],
    color: "#C87D5C",
    details:
      "A certificate should mean something. Ours are issued only when the work is done, and each one can be checked online by anyone you share it with.",
    highlights: [
      {
        title: "Earned, not handed out",
        description: "A certificate unlocks once you finish the lessons, pass the quizzes and submit the required assignments.",
      },
      {
        title: "Verifiable online",
        description: "Every certificate has its own ID that an employer can confirm on our verification page.",
      },
      {
        title: "Path by path",
        description: "Follow a learning path to collect certificates across a set of related skills.",
      },
      {
        title: "Yours to share",
        description: "Add it to your resume or professional profile as evidence of what you can do.",
      },
    ],
    cta: { label: "Explore Learning Paths", href: "/categories" },
    secondaryCta: { label: "Browse Courses", href: "/courses" },
  },
  {
    id: "hands-on-labs",
    title: "Hands-On Labs",
    description:
      "Skills stick when you use them. Practical assignments and tasks sit alongside the lessons so you apply each idea as you learn it.",
    icon: "🧪",
    features: [
      "Practical assignments",
      "Guided tasks",
      "Quizzes to test yourself",
      "Learn by doing",
    ],
    color: "#0F1F44",
    details:
      "Watching a lesson is only the start. The practical side of each course asks you to do the work yourself, which is what turns a new idea into a skill you can rely on.",
    highlights: [
      {
        title: "Assignments",
        description: "Put each topic to work with assignments you submit straight from the course page.",
      },
      {
        title: "Guided tasks",
        description: "Step-by-step tasks take you from a concept to a working result.",
      },
      {
        title: "Practice quizzes",
        description: "Short quizzes give you a quick, honest read on how well a topic has settled.",
      },
      {
        title: "Practice that counts",
        description: "Completed assignments and quizzes count towards your course certificate.",
      },
    ],
    cta: { label: "Start Practising", href: "/courses" },
    secondaryCta: { label: "Talk to Us", href: "/contact" },
  },
  {
    id: "virtual-learning",
    title: "Virtual Learning",
    description:
      "Learn from anywhere, on your own schedule. Lessons, progress and next steps live in one online dashboard.",
    icon: "🌐",
    features: [
      "On-demand video lessons",
      "Progress tracking",
      "Learning streaks",
      "One dashboard for everything",
    ],
    color: "#E8A78D",
    details:
      "There is no classroom to travel to and no fixed timetable. Sign in, pick up where you left off, and keep moving at a pace that fits around the rest of your life.",
    highlights: [
      {
        title: "On demand",
        description: "Video lessons are there whenever you are ready, as many times as you need them.",
      },
      {
        title: "Everything in one place",
        description: "Your courses, progress and next steps sit together in a single dashboard.",
      },
      {
        title: "Streaks that build habits",
        description: "Learning streaks make it easy to see, and keep, a steady routine.",
      },
      {
        title: "Your pace",
        description: "Move quickly through what you know and slow down where you need to.",
      },
    ],
    cta: { label: "Get Started", href: "/enroll" },
    secondaryCta: { label: "Sign In", href: "/login" },
  },
  {
    id: "career-growth",
    title: "Career Growth",
    description:
      "Go beyond courses with the Sage program: mentors, structured learning tracks and placement support for the next step in your career.",
    icon: "🚀",
    features: [
      "Dedicated coaches",
      "Resume preparation",
      "Interview training",
      "Placement support",
    ],
    color: "#1B2A5C",
    details:
      "New skills matter most when they lead somewhere. The Sage program surrounds your learning with people and structure, from your first week through to your next role.",
    highlights: [
      {
        title: "Coaches in your corner",
        description: "You are paired with coaches who guide your learning and keep you moving.",
      },
      {
        title: "A resume that works",
        description: "Get help shaping a resume that shows what you can actually do.",
      },
      {
        title: "Interview ready",
        description: "Prepare for interviews with focused training before the real conversations begin.",
      },
      {
        title: "Placement support",
        description: "Regular check-ins and placement support carry you from learning into work.",
      },
    ],
    cta: { label: "Join the Program", href: "/enroll" },
    secondaryCta: { label: "Talk to Us", href: "/contact" },
  },
];

// Navigation links
export const navLinks = [
  { label: "Home", href: "/" },
  { label: "About Us", href: "/about" },
  { label: "Resources", href: "/resources" },
  { label: "Testimonials", href: "/testimonials" },
  { label: "Blogs", href: "/blogs" },
  { label: "Contact Us", href: "/contact" },
];

// Items in the Resources dropdown
export const resourceLinks = resources.map((r) => ({
  label: r.title,
  href: `/resources/${r.id}`,
  desc: r.features[0],
}));

// Testimonials
export interface Testimonial {
  name: string;
  role: string;
  company: string;
  content: string;
  avatar: string;
}

export const testimonials: Testimonial[] = [
  {
    name: "Sarah Chen",
    role: "CTO",
    company: "NovaTech Industries",
    content:
      "Sage IT transformed our cloud infrastructure in 3 months. Their AI-driven approach to migration saved us 40% in operational costs.",
    avatar: "SC",
  },
  {
    name: "Michael Rivera",
    role: "VP of Engineering",
    company: "Quantum Dynamics",
    content:
      "The cybersecurity audit Sage IT performed uncovered critical vulnerabilities we had missed. Their zero-trust implementation is world-class.",
    avatar: "MR",
  },
  {
    name: "Priya Sharma",
    role: "Head of Data",
    company: "FinEdge Capital",
    content:
      "DataNexus revolutionized our analytics pipeline. We went from weeks of manual reporting to real-time dashboards in days.",
    avatar: "PS",
  },
  {
    name: "James Okafor",
    role: "CEO",
    company: "GreenLeaf Solutions",
    content:
      "Working with Sage IT felt like having a senior tech team on demand. Their web development team delivered a platform that exceeded expectations.",
    avatar: "JO",
  },
  {
    name: "Emily Watson",
    role: "Director of Marketing",
    company: "Apex Media Group",
    content:
      "Our digital marketing ROI tripled after partnering with Sage IT. Their data-driven strategies and automation tools are game-changers.",
    avatar: "EW",
  },
];

// Timeline for About page
export interface TimelineEvent {
  year: string;
  title: string;
  description: string;
}

export const timeline: TimelineEvent[] = [
  {
    year: "2018",
    title: "Founded",
    description: "Sage IT was born with a vision to bridge the gap between traditional IT and intelligent automation.",
  },
  {
    year: "2019",
    title: "First Enterprise Client",
    description: "Secured our first Fortune 500 client, delivering a cloud migration project ahead of schedule.",
  },
  {
    year: "2020",
    title: "AI Division Launch",
    description: "Launched our dedicated AI & Machine Learning division, expanding into intelligent automation.",
  },
  {
    year: "2021",
    title: "Global Expansion",
    description: "Expanded operations to 3 countries with offices in the US, UK, and India.",
  },
  {
    year: "2022",
    title: "Product Innovation",
    description: "Released DataNexus and SecureOps Suite, our flagship SaaS products for enterprise clients.",
  },
  {
    year: "2023",
    title: "Industry Recognition",
    description: "Named among the Top 50 Fastest Growing Tech Companies by Deloitte Technology Fast 500.",
  },
  {
    year: "2024",
    title: "AI-First Transformation",
    description: "Pivoted to an AI-first strategy, embedding intelligence into every service and product we deliver.",
  },
];

// Social links
export const socialLinks = [
  { label: "LinkedIn", href: "#", icon: "linkedin" },
  { label: "Twitter", href: "#", icon: "twitter" },
  { label: "GitHub", href: "#", icon: "github" },
  { label: "Instagram", href: "#", icon: "instagram" },
];

// Company stats
export const stats = [
  { value: "200+", label: "Projects Delivered" },
  { value: "50+", label: "Enterprise Clients" },
  { value: "6+", label: "Years of Excellence" },
  { value: "99.9%", label: "Client Satisfaction" },
];
