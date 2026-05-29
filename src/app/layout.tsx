import type { Metadata, Viewport } from "next";
import localFont from "next/font/local";
import Script from "next/script";
import "./globals.css";
import ChromeWrapper from "@/components/layout/ChromeWrapper";
import { ToastProvider } from "@/components/ui/Toast";
import ClientProviders from "@/components/layout/ClientProviders";
import { AuthProvider } from "@/lib/auth-context";
import { SITE_URL } from "@/lib/seo";

const geistSans = localFont({
  src: "./fonts/GeistVF.woff",
  variable: "--font-geist-sans",
  weight: "100 900",
});
const geistMono = localFont({
  src: "./fonts/GeistMonoVF.woff",
  variable: "--font-geist-mono",
  weight: "100 900",
});

const siteName = "Sage IT";
const defaultTitle = "Sage IT | Engineering Intelligence. Empowering Growth.";
const defaultDescription =
  "SAGEITCO LLC delivers next-generation technology solutions including Cloud, AI, Cybersecurity, Web Development, and Data Analytics for enterprises worldwide.";

export const metadata: Metadata = {
  metadataBase: new URL(SITE_URL),
  title: {
    default: defaultTitle,
    template: "%s | Sage IT",
  },
  description: defaultDescription,
  applicationName: siteName,
  authors: [{ name: "SAGEITCO LLC" }],
  generator: "Next.js",
  keywords: [
    "IT services",
    "cloud solutions",
    "AI",
    "artificial intelligence",
    "cybersecurity",
    "web development",
    "data analytics",
    "enterprise technology",
    "DevOps",
    "managed services",
    "Sage IT",
    "SAGEITCO",
    "Lewisville TX IT",
  ],
  referrer: "origin-when-cross-origin",
  creator: "SAGEITCO LLC",
  publisher: "SAGEITCO LLC",
  category: "Technology",
  formatDetection: {
    email: false,
    address: false,
    telephone: false,
  },
  icons: {
    icon: "/icon.png",
    apple: "/apple-icon.png",
  },
  alternates: {
    canonical: "/",
  },
  openGraph: {
    type: "website",
    locale: "en_US",
    url: "/",
    siteName,
    title: defaultTitle,
    description: defaultDescription,
    images: [
      {
        url: "/sage_logo.png",
        width: 1024,
        height: 1024,
        alt: "Sage IT — Engineering Intelligence. Empowering Growth.",
      },
    ],
  },
  twitter: {
    card: "summary_large_image",
    title: defaultTitle,
    description: defaultDescription,
    images: ["/sage_logo.png"],
  },
  robots: {
    index: true,
    follow: true,
    nocache: false,
    googleBot: {
      index: true,
      follow: true,
      "max-video-preview": -1,
      "max-image-preview": "large",
      "max-snippet": -1,
    },
  },
  verification: {
    google: "googleb1ffc46918a9fd23",
  },
};

export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
  maximumScale: 5,
  themeColor: "#1B2A5C",
};

const organizationJsonLd = {
  "@context": "https://schema.org",
  "@type": "Organization",
  name: "SAGEITCO LLC",
  alternateName: "Sage IT",
  url: SITE_URL,
  logo: `${SITE_URL}/sage_logo.png`,
  description: defaultDescription,
  email: "info@sageitco.com",
  telephone: "+1-469-666-3661",
  address: {
    "@type": "PostalAddress",
    streetAddress: "4400 State Hwy 121, Suite #324",
    addressLocality: "Lewisville",
    addressRegion: "TX",
    postalCode: "75056",
    addressCountry: "US",
  },
  sameAs: [
    "https://github.com/salyushchavas/Sage_IT_Co",
  ],
};

const websiteJsonLd = {
  "@context": "https://schema.org",
  "@type": "WebSite",
  name: siteName,
  url: SITE_URL,
  publisher: { "@type": "Organization", name: "SAGEITCO LLC" },
  potentialAction: {
    "@type": "SearchAction",
    target: `${SITE_URL}/search?q={search_term_string}`,
    "query-input": "required name=search_term_string",
  },
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en" className="dark">
      <body
        className={`${geistSans.variable} ${geistMono.variable} antialiased bg-background text-foreground`}
      >
        {/* Structured data — helps Google build rich results */}
        <Script
          id="ld-organization"
          type="application/ld+json"
          strategy="beforeInteractive"
          dangerouslySetInnerHTML={{ __html: JSON.stringify(organizationJsonLd) }}
        />
        <Script
          id="ld-website"
          type="application/ld+json"
          strategy="beforeInteractive"
          dangerouslySetInnerHTML={{ __html: JSON.stringify(websiteJsonLd) }}
        />
        <AuthProvider>
          <ToastProvider>
            <ClientProviders />
            <ChromeWrapper>{children}</ChromeWrapper>
          </ToastProvider>
        </AuthProvider>
      </body>
    </html>
  );
}
