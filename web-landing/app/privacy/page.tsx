import Link from "next/link";

export const metadata = {
  title: "Privacy policy — Ultiq",
  description: "How Ultiq collects, stores, and handles your data.",
};

const sections = [
  {
    heading: "1. Who we are",
    body: "Ultiq is a personal productivity app built and operated by a single developer. This policy explains what data the app collects when you use it, where that data goes, and what control you have over it.",
  },
  {
    heading: "2. Data you give us",
    body: "When you create an account, we store your email address and a hashed password (we never see or store the password itself). Inside the app, you create records: sleep sessions, focus sessions, calendar events, checklist items, and any targets, tags, or preferences you set. All of this is treated as your data.",
  },
  {
    heading: "3. Data the app observes",
    body: "While a sleep or focus session is running, the app checks whether your phone screen is on or off so it can count phone pickups. It does not read what apps you use, what you type, your location, your contacts, your photos, or any other content on the phone. Phone pickup detection is on-device and only generates a count.",
  },
  {
    heading: "4. Data we do not collect",
    body: "Ultiq does not contain advertising or third-party analytics SDKs. It does not collect device identifiers for tracking, browsing history, location data, or biometric data. There are no in-app purchases, so we collect no payment information.",
  },
  {
    heading: "5. Where data is stored",
    body: "Records sync to a backend hosted on AWS (Sydney region) so the app and the web dashboard show the same view. The database is encrypted at rest and only reachable from our backend service inside a private network. Transport between your phone and the backend is TLS-encrypted. Daily backups are taken; backups are encrypted and rotated.",
  },
  {
    heading: "6. Who else sees your data",
    body: "Nobody. We do not sell, share, or hand over your records to advertisers, data brokers, or analytics providers. The only third party in the loop is our transactional email provider (Resend), which delivers password-reset and account emails — they see only your email address and the message body, never your records.",
  },
  {
    heading: "7. How long we keep it",
    body: "Your records stay until you delete them. You can delete individual records from inside the app, reset all your data from Settings, or delete your account entirely from Settings → Delete account. Deleting an account immediately wipes every record tied to it from our database; backups containing the record roll off after 30 days.",
  },
  {
    heading: "8. Your rights",
    body: "You can sign in to view your data at any time, export or delete individual records, and delete your entire account. If you would like a copy of all data tied to your account in a single export, email support@ultiqapp.com and we will provide it.",
  },
  {
    heading: "9. Children",
    body: "Ultiq is not directed at children under 13. We do not knowingly collect data from children under 13. If you believe a child has created an account, contact us at support@ultiqapp.com and we will remove it.",
  },
  {
    heading: "10. Security",
    body: "Passwords are stored as Argon2 hashes. Authentication uses signed tokens. The backend runs in a private network with a least-privilege IAM model and a managed firewall. We can never recover a lost password — only reset it via the email-driven flow.",
  },
  {
    heading: "11. Changes to this policy",
    body: "If this policy changes, we update the date at the top of the page. Material changes will also be surfaced in the app the next time you sign in.",
  },
  {
    heading: "12. Contact",
    body: "Questions about your data, this policy, or anything else: support@ultiqapp.com.",
  },
];

export default function PrivacyPage() {
  return (
    <div className="flex flex-1 flex-col bg-ultiq-cream text-ultiq-indigo">
      <nav className="border-b border-ultiq-indigo/10 bg-ultiq-cream">
        <div className="mx-auto flex max-w-3xl items-center justify-between px-6 py-4">
          <Link href="/" className="flex items-center gap-2">
            <span className="block size-7">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src="/mascot.svg" alt="Ultiq" />
            </span>
            <span className="font-semibold">Ultiq</span>
          </Link>
          <Link href="/" className="text-sm text-ultiq-indigo/70 hover:text-ultiq-indigo">
            ← Back home
          </Link>
        </div>
      </nav>

      <article className="mx-auto w-full max-w-2xl px-6 py-12 md:py-20">
        <p className="text-xs uppercase tracking-wider text-ultiq-indigo/60">Last updated: May 4, 2026</p>
        <h1 className="mt-2 text-4xl font-bold tracking-tight">Privacy policy</h1>

        <p className="mt-6 text-ultiq-indigo/80">
          Ultiq is a personal productivity app. This page covers what data the app collects, where it lives, and how you can control it.
        </p>

        <div className="mt-10 space-y-8">
          {sections.map((s) => (
            <section key={s.heading}>
              <h2 className="text-lg font-semibold">{s.heading}</h2>
              <p className="mt-2 text-ultiq-indigo/80">{s.body}</p>
            </section>
          ))}
        </div>
      </article>

      <footer className="mt-auto border-t border-ultiq-indigo/10">
        <div className="mx-auto max-w-3xl px-6 py-6 text-sm text-ultiq-indigo/60">
          © {new Date().getFullYear()} Ultiq · <Link href="/terms" className="hover:text-ultiq-indigo">Terms</Link>
        </div>
      </footer>
    </div>
  );
}
