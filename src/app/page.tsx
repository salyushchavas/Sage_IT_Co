import Hero from "@/components/sections/Hero";
import AboutPreview from "@/components/sections/AboutPreview";
import ServicesPreview from "@/components/sections/ServicesPreview";
import SolutionsPreview from "@/components/sections/SolutionsPreview";
import ClientLogos from "@/components/sections/ClientLogos";
import Testimonials from "@/components/sections/Testimonials";
import PortfolioPreview from "@/components/sections/PortfolioPreview";
import CTA from "@/components/sections/CTA";
import Reveal from "@/components/ui/Reveal";
import type { Metadata } from "next";

// Home keeps the absolute brand title from the root default. We override
// just the description for a stronger homepage snippet, plus an explicit
// canonical so search engines collapse "/" and "" together.
export const metadata: Metadata = {
  title: { absolute: "Sage IT | Engineering Intelligence. Empowering Growth." },
  description:
    "SAGEITCO LLC delivers next-generation technology solutions — Cloud, AI, Cybersecurity, Web Development, and Data Analytics — for enterprises worldwide.",
  alternates: { canonical: "/" },
  openGraph: {
    type: "website",
    url: "/",
    title: "Sage IT | Engineering Intelligence. Empowering Growth.",
    description:
      "SAGEITCO LLC delivers next-generation technology solutions — Cloud, AI, Cybersecurity, Web Development, and Data Analytics — for enterprises worldwide.",
  },
  twitter: {
    title: "Sage IT | Engineering Intelligence. Empowering Growth.",
    description:
      "SAGEITCO LLC delivers next-generation technology solutions — Cloud, AI, Cybersecurity, Web Development, and Data Analytics — for enterprises worldwide.",
  },
};

export default function Home() {
  return (
    <>
      <Hero />
      <Reveal direction="up"><AboutPreview /></Reveal>
      <Reveal direction="up"><ServicesPreview /></Reveal>
      <Reveal direction="up"><SolutionsPreview /></Reveal>
      <Reveal direction="fade"><ClientLogos /></Reveal>
      <Reveal direction="up"><Testimonials /></Reveal>
      <Reveal direction="up"><PortfolioPreview /></Reveal>
      <Reveal direction="scale"><CTA /></Reveal>
    </>
  );
}
