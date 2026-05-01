import Link from "next/link";

export const metadata = {
  title: "Terms & conditions — Ultiq",
  description: "The terms under which you use Ultiq.",
};

const sections = [
  {
    heading: "1. What you give us",
    body: "We collect the records you create in the app: sleep sessions, focus sessions, calendar events, checklist items, and any targets or preferences you set. We also store basic account info — your email address and a hashed password.",
  },
  {
    heading: "2. What we do with it",
    body: "Your data is yours. We store it on our backend so it syncs between your phone and the web dashboard. We do not sell, share, or analyze your data for advertising. The only things we use it for are the features inside the app.",
  },
  {
    heading: "3. Phone activity",
    body: "Sleep and focus sessions check whether your screen is on or off so we can count phone pickups. We don't read what apps you use, what you type, or anything else you do on the phone.",
  },
  {
    heading: "4. Your account",
    body: "You can sign out, reset all your data, or delete your account anytime from Settings. Deleting an account wipes every record tied to it from our servers.",
  },
  {
    heading: "5. As-is",
    body: 'Ultiq is provided "as is", without warranty. We try our best, but the developer isn\'t liable for data loss, missed reminders, or anything else that may happen while using the app. Don\'t rely on it for anything safety-critical.',
  },
  {
    heading: "6. Contact",
    body: "Questions, bugs, or feedback? Reach us at support@ultiqapp.com.",
  },
  {
    heading: "7. Changes",
    body: "These terms may change as the app evolves. We'll update the date at the top when they do.",
  },
];

export default function TermsPage() {
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
        <p className="text-xs uppercase tracking-wider text-ultiq-indigo/60">Last updated: April 30, 2026</p>
        <h1 className="mt-2 text-4xl font-bold tracking-tight">Terms &amp; conditions</h1>

        <p className="mt-6 text-ultiq-indigo/80">
          Ultiq is a personal productivity app built by a single developer. By using this app, you agree to the terms below.
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
          © {new Date().getFullYear()} Ultiq
        </div>
      </footer>
    </div>
  );
}
