import Hero from "@/components/sections/Hero";
import AboutPreview from "@/components/sections/AboutPreview";
import ServicesPreview from "@/components/sections/ServicesPreview";
import SolutionsPreview from "@/components/sections/SolutionsPreview";
import ClientLogos from "@/components/sections/ClientLogos";
import Testimonials from "@/components/sections/Testimonials";
import PortfolioPreview from "@/components/sections/PortfolioPreview";
import CTA from "@/components/sections/CTA";

export default function Home() {
  return (
    <>
      <Hero />
      <AboutPreview />
      <ServicesPreview />
      <SolutionsPreview />
      <ClientLogos />
      <Testimonials />
      <PortfolioPreview />
      <CTA />
    </>
  );
}
