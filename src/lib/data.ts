// Navigation links
export const navLinks = [
  { label: "Home", href: "/" },
  { label: "About", href: "/about" },
  { label: "Services", href: "/services" },
  { label: "Solutions", href: "/solutions" },
  { label: "Portfolio", href: "/portfolio" },
  { label: "Careers", href: "/careers" },
  { label: "Contact", href: "/contact" },
];

// Services
export interface Service {
  id: string;
  title: string;
  description: string;
  icon: string;
  features: string[];
  color: string;
}

export const services: Service[] = [
  {
    id: "cloud",
    title: "Cloud Solutions",
    description:
      "Scalable cloud infrastructure designed for performance, security, and seamless migration. We architect solutions on AWS, Azure, and GCP.",
    icon: "☁️",
    features: [
      "Cloud Migration & Strategy",
      "Multi-Cloud Architecture",
      "DevOps & CI/CD Pipelines",
      "Serverless Computing",
    ],
    color: "#0F5132",
  },
  {
    id: "cybersecurity",
    title: "Cybersecurity",
    description:
      "Enterprise-grade security solutions to protect your digital assets. From threat detection to compliance, we keep your business secure.",
    icon: "🛡️",
    features: [
      "Threat Detection & Response",
      "Security Audits & Compliance",
      "Zero Trust Architecture",
      "Penetration Testing",
    ],
    color: "#D4A017",
  },
  {
    id: "webdev",
    title: "Web Development",
    description:
      "High-performance web applications built with modern frameworks. From startups to enterprise, we deliver pixel-perfect experiences.",
    icon: "🌐",
    features: [
      "Full-Stack Development",
      "Progressive Web Apps",
      "E-Commerce Platforms",
      "API Development",
    ],
    color: "#0A3D26",
  },
  {
    id: "ai",
    title: "AI Solutions",
    description:
      "Harness the power of artificial intelligence to automate processes, gain insights, and transform your business operations.",
    icon: "🧠",
    features: [
      "Machine Learning Models",
      "Natural Language Processing",
      "Computer Vision",
      "AI-Powered Automation",
    ],
    color: "#E8B82E",
  },
  {
    id: "marketing",
    title: "Digital Marketing",
    description:
      "Data-driven marketing strategies that amplify your brand presence and drive measurable results across all digital channels.",
    icon: "📈",
    features: [
      "SEO & Content Strategy",
      "Social Media Marketing",
      "PPC & Performance Ads",
      "Marketing Automation",
    ],
    color: "#0F5132",
  },
  {
    id: "data",
    title: "Data & Analytics",
    description:
      "Transform raw data into actionable intelligence. Our analytics solutions help you make smarter decisions, faster.",
    icon: "📊",
    features: [
      "Business Intelligence",
      "Data Warehousing",
      "Real-Time Analytics",
      "Predictive Modeling",
    ],
    color: "#D4A017",
  },
];

// Solutions
export interface Solution {
  id: string;
  title: string;
  description: string;
  category: string;
  features: string[];
}

export const solutions: Solution[] = [
  {
    id: "enterprise-ai",
    title: "Enterprise AI Platform",
    description:
      "End-to-end AI platform for enterprise automation, from data ingestion to model deployment and monitoring.",
    category: "AI Platforms",
    features: ["AutoML Pipeline", "Model Registry", "Real-Time Inference", "Drift Detection"],
  },
  {
    id: "cloud-hub",
    title: "CloudHub 360",
    description:
      "Unified multi-cloud management platform with cost optimization, security monitoring, and automated scaling.",
    category: "Enterprise Solutions",
    features: ["Multi-Cloud Dashboard", "Cost Optimizer", "Security Scanner", "Auto-Scaling"],
  },
  {
    id: "data-nexus",
    title: "DataNexus",
    description:
      "Modern data platform that unifies your data sources, enabling real-time analytics and AI-ready data pipelines.",
    category: "SaaS Tools",
    features: ["Data Integration", "Stream Processing", "Visual Analytics", "Data Governance"],
  },
  {
    id: "secureops",
    title: "SecureOps Suite",
    description:
      "Comprehensive cybersecurity operations platform with AI-driven threat detection and automated incident response.",
    category: "Enterprise Solutions",
    features: ["SIEM Integration", "Threat Intelligence", "Incident Playbooks", "Compliance Reports"],
  },
  {
    id: "devflow",
    title: "DevFlow Pro",
    description:
      "Accelerate your development lifecycle with our integrated DevOps platform featuring CI/CD, testing, and deployment.",
    category: "SaaS Tools",
    features: ["Pipeline Builder", "Test Automation", "Container Orchestration", "Release Management"],
  },
  {
    id: "insight-engine",
    title: "InsightEngine",
    description:
      "AI-powered business intelligence tool that automatically discovers patterns and generates actionable insights.",
    category: "AI Platforms",
    features: ["Auto-Discovery", "Natural Language Queries", "Predictive Forecasting", "Custom Dashboards"],
  },
];

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

// Client logos (placeholder names)
export const clients = [
  "TechCorp Global",
  "NovaTech Industries",
  "Quantum Dynamics",
  "FinEdge Capital",
  "GreenLeaf Solutions",
  "Apex Media Group",
  "SkyBridge Systems",
  "CoreLogic AI",
  "Vertex Labs",
  "PulseNet Inc.",
];

// Portfolio
export interface PortfolioItem {
  id: string;
  title: string;
  description: string;
  category: string;
  tags: string[];
  result: string;
}

export const portfolio: PortfolioItem[] = [
  {
    id: "novatech-migration",
    title: "NovaTech Cloud Migration",
    description:
      "Migrated a legacy on-premise infrastructure to a multi-cloud environment supporting 10M+ daily transactions.",
    category: "Cloud",
    tags: ["AWS", "Azure", "Kubernetes", "Terraform"],
    result: "40% cost reduction, 99.99% uptime",
  },
  {
    id: "finedge-analytics",
    title: "FinEdge Real-Time Analytics",
    description:
      "Built a real-time analytics platform processing 50TB of financial data daily with sub-second query performance.",
    category: "Data",
    tags: ["Apache Kafka", "ClickHouse", "React", "Python"],
    result: "10x faster insights, $2M annual savings",
  },
  {
    id: "quantum-security",
    title: "Quantum Dynamics Security Overhaul",
    description:
      "Implemented zero-trust architecture and AI-driven threat detection across 200+ microservices.",
    category: "Security",
    tags: ["Zero Trust", "ML", "SIEM", "DevSecOps"],
    result: "95% threat detection rate, SOC2 compliance",
  },
  {
    id: "greenleaf-platform",
    title: "GreenLeaf Digital Platform",
    description:
      "Designed and developed a full-stack sustainability tracking platform with IoT integration and AI insights.",
    category: "Web",
    tags: ["Next.js", "Node.js", "PostgreSQL", "IoT"],
    result: "500K+ users, 3x engagement increase",
  },
  {
    id: "apex-marketing",
    title: "Apex Marketing Automation",
    description:
      "Built an AI-powered marketing automation suite with predictive audience targeting and campaign optimization.",
    category: "AI",
    tags: ["Python", "TensorFlow", "React", "AWS"],
    result: "3x ROI, 60% reduction in ad spend waste",
  },
  {
    id: "skybridge-saas",
    title: "SkyBridge SaaS Platform",
    description:
      "Architected a multi-tenant SaaS platform serving 1000+ enterprise clients with custom workflow automation.",
    category: "Cloud",
    tags: ["Microservices", "React", "Go", "GCP"],
    result: "1000+ enterprise clients, $5M ARR",
  },
];

// Job listings
export interface Job {
  id: string;
  title: string;
  department: string;
  location: string;
  type: string;
  description: string;
  requirements: string[];
}

export const jobs: Job[] = [
  {
    id: "senior-ai-engineer",
    title: "Senior AI Engineer",
    department: "AI & Machine Learning",
    location: "Remote / Hyderabad",
    type: "Full-time",
    description:
      "Lead the development of cutting-edge AI solutions for enterprise clients. Design and deploy ML models at scale.",
    requirements: [
      "5+ years ML/AI experience",
      "Python, TensorFlow/PyTorch",
      "MLOps & model deployment",
      "Strong system design skills",
    ],
  },
  {
    id: "cloud-architect",
    title: "Cloud Solutions Architect",
    department: "Cloud Engineering",
    location: "Remote / Bangalore",
    type: "Full-time",
    description:
      "Design and implement scalable cloud architectures on AWS, Azure, and GCP for enterprise clients.",
    requirements: [
      "7+ years cloud experience",
      "AWS/Azure/GCP certified",
      "Kubernetes & Terraform",
      "Enterprise architecture background",
    ],
  },
  {
    id: "fullstack-developer",
    title: "Full-Stack Developer",
    department: "Web Development",
    location: "Remote / Pune",
    type: "Full-time",
    description:
      "Build high-performance web applications using React, Next.js, and Node.js for diverse client projects.",
    requirements: [
      "3+ years full-stack experience",
      "React/Next.js, Node.js",
      "TypeScript, PostgreSQL",
      "REST & GraphQL APIs",
    ],
  },
  {
    id: "security-analyst",
    title: "Cybersecurity Analyst",
    department: "Security Operations",
    location: "Hyderabad",
    type: "Full-time",
    description:
      "Monitor, detect, and respond to security threats. Conduct vulnerability assessments and security audits.",
    requirements: [
      "3+ years cybersecurity experience",
      "SIEM tools (Splunk, QRadar)",
      "CEH or CISSP certification",
      "Incident response expertise",
    ],
  },
  {
    id: "data-engineer",
    title: "Data Engineer",
    department: "Data & Analytics",
    location: "Remote / Chennai",
    type: "Full-time",
    description:
      "Design and maintain scalable data pipelines and infrastructure supporting real-time analytics platforms.",
    requirements: [
      "4+ years data engineering",
      "Apache Spark, Kafka",
      "Python/Scala",
      "Data warehouse design",
    ],
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
