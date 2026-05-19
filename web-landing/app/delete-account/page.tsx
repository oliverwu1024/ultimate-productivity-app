import Link from "next/link";

export const metadata = {
  title: "Delete your account — Ultiq",
  description: "How to delete your Ultiq account and the data tied to it.",
};

const sections = [
  {
    heading: "1. From inside the app",
    body: "Open Ultiq → Settings → Delete account. Confirm the prompt. Your account and every record tied to it are wiped from our database immediately. This is the fastest and most reliable path.",
  },
  {
    heading: "2. By email",
    body: "If you no longer have the app installed, can't sign in, or would rather not use the in-app flow, email support@ultiqapp.com from the address tied to your Ultiq account and ask us to delete it. We'll confirm the request, delete the account, and reply when it's done — usually within a few business days.",
  },
  {
    heading: "3. What gets deleted",
    body: "Everything tied to your account: your email and hashed password, every sleep session, every focus session, every calendar event, every checklist item, every preference, and every tag. Once deleted, none of it is recoverable.",
  },
  {
    heading: "4. Backups",
    body: "Our database is backed up daily. Backups containing your records expire and are deleted after 30 days. After that window, no copy of your data remains on our servers.",
  },
  {
    heading: "5. What we keep",
    body: "Nothing tied to you personally. We don't retain analytics, ad identifiers, or device IDs because we never collect them in the first place.",
  },
  {
    heading: "6. Questions",
    body: "If you have any questions about deletion, what we store, or anything else, email support@ultiqapp.com.",
  },
];

export default function DeleteAccountPage() {
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
        <p className="text-xs uppercase tracking-wider text-ultiq-indigo/60">Last updated: May 19, 2026</p>
        <h1 className="mt-2 text-4xl font-bold tracking-tight">Delete your account</h1>

        <p className="mt-6 text-ultiq-indigo/80">
          You can delete your Ultiq account and all the data tied to it at any time. There are two ways to do it: from inside the app, or by emailing us.
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
          © {new Date().getFullYear()} Ultiq · <Link href="/privacy" className="hover:text-ultiq-indigo">Privacy</Link> · <Link href="/terms" className="hover:text-ultiq-indigo">Terms</Link>
        </div>
      </footer>
    </div>
  );
}
