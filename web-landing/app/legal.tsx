"use client";

// Shared chrome for the three legal/info pages (privacy, terms, delete-account).
// Each page.tsx stays a server component (so its English `metadata` export is
// preserved for SEO + link previews) and renders this client component, which
// pulls all visible copy from the active locale catalog. Keys follow the
// `<ns>.title`, `<ns>.date`, `<ns>.intro`, `<ns>.s<n>.h`, `<ns>.s<n>.b` scheme.

import Link from "next/link";

import { useI18n, usePageTitle } from "./i18n";

type FooterLink = { href: string; key: string };

export function LegalChrome({
  ns,
  sectionCount,
  titleKey,
  footerLinks = [],
}: {
  ns: "privacy" | "terms" | "del";
  sectionCount: number;
  titleKey: string;
  footerLinks?: FooterLink[];
}) {
  const { t, ta, locale } = useI18n();
  usePageTitle(titleKey);

  const sections = Array.from({ length: sectionCount }, (_, i) => i + 1);

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
            ← {t("legal.back_home")}
          </Link>
        </div>
      </nav>

      <article className="mx-auto w-full max-w-2xl px-6 py-12 md:py-20">
        <p className="text-xs uppercase tracking-wider text-ultiq-indigo/60">
          {ta("legal.last_updated", { date: t(`${ns}.date`) })}
        </p>
        <h1 className="mt-2 text-4xl font-bold tracking-tight">{t(`${ns}.title`)}</h1>

        <p className="mt-6 text-ultiq-indigo/80">{t(`${ns}.intro`)}</p>

        {locale !== "en" && (
          <p className="mt-4 rounded-xl border border-ultiq-indigo/15 bg-ultiq-yellow/20 px-4 py-3 text-sm text-ultiq-indigo/70">
            {t("legal.governs")}
          </p>
        )}

        <div className="mt-10 space-y-8">
          {sections.map((n) => (
            <section key={n}>
              <h2 className="text-lg font-semibold">{t(`${ns}.s${n}.h`)}</h2>
              <p className="mt-2 text-ultiq-indigo/80">{t(`${ns}.s${n}.b`)}</p>
            </section>
          ))}
        </div>
      </article>

      <footer className="mt-auto border-t border-ultiq-indigo/10">
        <div className="mx-auto max-w-3xl px-6 py-6 text-sm text-ultiq-indigo/60">
          © {new Date().getFullYear()} Ultiq
          {footerLinks.map((l) => (
            <span key={l.key}>
              {" · "}
              <Link href={l.href} className="hover:text-ultiq-indigo">
                {t(l.key)}
              </Link>
            </span>
          ))}
        </div>
      </footer>
    </div>
  );
}
