"use client";

import Link from "next/link";
import { useEffect, useState } from "react";

import { useI18n, usePageTitle } from "../i18n";

export default function ResetRedirectPage() {
  const { t } = useI18n();
  usePageTitle("meta.reset");
  const [hasToken, setHasToken] = useState<boolean | null>(null);

  useEffect(() => {
    if (typeof window === "undefined") return;
    const token = new URLSearchParams(window.location.search).get("token");
    if (token) {
      // Forward to the dashboard's reset flow with the token preserved.
      window.location.replace(
        `https://app.ultiqapp.com/reset?token=${encodeURIComponent(token)}`,
      );
    } else {
      setHasToken(false);
    }
  }, []);

  if (hasToken === null) {
    return (
      <div className="flex flex-1 flex-col items-center justify-center bg-ultiq-cream text-ultiq-indigo px-6 text-center">
        <p className="text-sm text-ultiq-indigo/70">{t("reset.redirecting")}</p>
      </div>
    );
  }

  return (
    <div className="flex flex-1 flex-col items-center justify-center bg-ultiq-cream text-ultiq-indigo px-6 text-center">
      <div className="size-32">
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img src="/mascot.svg" alt="Ultiq" />
      </div>
      <h1 className="mt-8 text-3xl font-bold tracking-tight md:text-4xl">
        {t("reset.no_token_title")}
      </h1>
      <p className="mt-4 max-w-md text-ultiq-indigo/70">{t("reset.no_token_body")}</p>
      <Link
        href="https://app.ultiqapp.com/forgot-password"
        className="mt-8 inline-flex items-center gap-2 rounded-full bg-ultiq-indigo px-8 py-3 text-base font-medium text-ultiq-cream shadow-lg shadow-ultiq-indigo/20 transition hover:bg-ultiq-indigo/90"
      >
        {t("reset.request_new")}
      </Link>
      <Link href="/" className="mt-6 text-sm text-ultiq-indigo/70 hover:text-ultiq-indigo">
        ← {t("legal.back_home")}
      </Link>
    </div>
  );
}
