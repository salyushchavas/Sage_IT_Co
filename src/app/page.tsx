import Hero from "@/components/sections/Hero";
import AboutPreview from "@/components/sections/AboutPreview";
import ServicesPreview from "@/components/sections/ServicesPreview";
import SolutionsPreview from "@/components/sections/SolutionsPreview";
import ClientLogos from "@/components/sections/ClientLogos";
import Testimonials from "@/components/sections/Testimonials";
import PortfolioPreview from "@/components/sections/PortfolioPreview";
import CTA from "@/components/sections/CTA";
import Reveal from "@/components/ui/Reveal";

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
