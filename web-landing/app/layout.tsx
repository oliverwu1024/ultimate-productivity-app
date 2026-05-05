import type { Metadata } from "next";
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

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html
      lang="en"
      className={`${geistSans.variable} ${geistMono.variable} h-full antialiased`}
    >
      <head>
        {/* Pre-paint dark-mode application: runs before the page renders so we
            don't flash a light theme on first load. Reads localStorage first,
            then falls back to system preference. */}
        <script
          dangerouslySetInnerHTML={{
            __html: `(function(){try{var s=localStorage.getItem('ultiq_theme');var d=window.matchMedia('(prefers-color-scheme: dark)').matches;if(s==='dark'||(s==null&&d))document.documentElement.classList.add('dark');}catch(e){}})();`,
          }}
        />
      </head>
      <body className="min-h-full flex flex-col">{children}</body>
    </html>
  );
}
