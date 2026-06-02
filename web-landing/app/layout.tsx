import type { Metadata, Viewport } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import "./globals.css";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

export const metadata: Metadata = {
  title: "Ultiq — your daily productivity companion",
  description:
    "Ultiq is a calm Android app that helps you sleep deeply, focus clearly, and reflect each day. Sleep tracking, focus sessions, daily checklist, and weekly insights.",
  metadataBase: new URL("https://ultiqapp.com"),
  openGraph: {
    title: "Ultiq — your daily productivity companion",
    description: "Sleep deeply. Focus clearly. Rest. Repeat.",
    url: "https://ultiqapp.com",
    siteName: "Ultiq",
    type: "website",
  },
};

// Next 15+ split: themeColor / colorScheme moved out of `metadata` into
// the dedicated `viewport` export. Indigo here matches the brand value
// in globals.css (--color-ultiq-indigo) and the Android status bar color
// in res/values/themes.xml so the mobile browser chrome matches the rest
// of the app.
export const viewport: Viewport = {
  themeColor: [
    { media: "(prefers-color-scheme: light)", color: "#FFF4E6" },
    { media: "(prefers-color-scheme: dark)", color: "#2A1B6E" },
  ],
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html
      lang="en"
      suppressHydrationWarning
      className={`${geistSans.variable} ${geistMono.variable} h-full antialiased`}
    >
      <head>
        {/* Pre-paint dark-mode application: runs before the page renders so we
            don't flash a light theme on first load. New behaviour (2026-05-23
            landing-redesign): default to DARK when no stored preference,
            regardless of system setting. Productivity apps look ~50% more
            premium in dark by default; the toggle still respects explicit
            user choice in localStorage. */}
        <script
          dangerouslySetInnerHTML={{
            __html: `(function(){try{var s=localStorage.getItem('ultiq_theme');if(s==='light'){return;}document.documentElement.classList.add('dark');}catch(e){document.documentElement.classList.add('dark');}})();`,
          }}
        />
      </head>
      <body className="min-h-full flex flex-col">{children}</body>
    </html>
  );
}
