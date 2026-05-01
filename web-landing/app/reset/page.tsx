import Link from "next/link";

export const metadata = {
  title: "Reset password — Ultiq",
  description: "Open Ultiq on your phone to finish resetting your password.",
};

export default function ResetFallbackPage() {
  return (
    <div className="flex flex-1 flex-col items-center justify-center bg-ultiq-cream text-ultiq-indigo px-6 text-center">
      <div className="size-32">
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img src="/mascot.svg" alt="Ultiq" />
      </div>
      <h1 className="mt-8 text-3xl font-bold tracking-tight md:text-4xl">
        Open Ultiq to finish resetting your password
      </h1>
      <p className="mt-4 max-w-md text-ultiq-indigo/70">
        This link is meant to open the Ultiq app directly. If you&apos;re seeing this page,
        you probably haven&apos;t installed the app yet.
      </p>
      <Link
        href="/ultiq-latest.apk"
        className="mt-8 inline-flex items-center gap-2 rounded-full bg-ultiq-indigo px-8 py-3 text-base font-medium text-ultiq-cream shadow-lg shadow-ultiq-indigo/20 transition hover:bg-ultiq-indigo/90"
      >
        Download Ultiq for Android
      </Link>
      <Link href="/" className="mt-6 text-sm text-ultiq-indigo/70 hover:text-ultiq-indigo">
        ← Back home
      </Link>
    </div>
  );
}
