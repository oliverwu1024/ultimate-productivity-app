"use client";

import { motion } from "framer-motion";
import { Moon, Sun, Timer, ListChecks, Sparkles, Download, MonitorCog } from "lucide-react";
import Link from "next/link";
import { useEffect, useState } from "react";

type Theme = "light" | "dark" | "system";

function readStoredTheme(): Theme {
  if (typeof window === "undefined") return "system";
  const v = window.localStorage.getItem("ultiq_theme");
  return v === "light" || v === "dark" ? v : "system";
}

function systemPrefersDark(): boolean {
  if (typeof window === "undefined") return false;
  return window.matchMedia("(prefers-color-scheme: dark)").matches;
}

function applyTheme(t: Theme) {
  const dark = t === "dark" || (t === "system" && systemPrefersDark());
  document.documentElement.classList.toggle("dark", dark);
}

function ThemeToggle() {
  const [theme, setTheme] = useState<Theme>("system");

  useEffect(() => {
    setTheme(readStoredTheme());
  }, []);

  const cycle = () => {
    const next: Theme = theme === "light" ? "dark" : theme === "dark" ? "system" : "light";
    setTheme(next);
    applyTheme(next);
    if (next === "system") {
      window.localStorage.removeItem("ultiq_theme");
    } else {
      window.localStorage.setItem("ultiq_theme", next);
    }
  };

  const Icon = theme === "light" ? Sun : theme === "dark" ? Moon : MonitorCog;
  const label = theme === "light" ? "Light" : theme === "dark" ? "Dark" : "System";

  return (
    <button
      onClick={cycle}
      title="Cycle theme"
      aria-label="Toggle theme"
      className="rounded-full p-2 text-ultiq-indigo/70 transition hover:bg-ultiq-indigo/5 hover:text-ultiq-indigo"
    >
      <Icon size={18} aria-hidden="true" />
      <span className="sr-only">{label}</span>
    </button>
  );
}

const features = [
  {
    icon: Moon,
    title: "Sleep tracking",
    body: "Tap once to start a night. Wake up to a calm summary, not a quiz.",
    bg: "bg-ultiq-blue/40",
    iconColor: "text-ultiq-indigo",
  },
  {
    icon: Timer,
    title: "Focus sessions",
    body: "Pomodoro timer that quietly notices each phone pickup, no shaming.",
    bg: "bg-ultiq-red/15",
    iconColor: "text-ultiq-red",
  },
  {
    icon: ListChecks,
    title: "Daily checklist",
    body: "A short list of intentions for the day — drop into a focus session straight from a task.",
    bg: "bg-ultiq-yellow/30",
    iconColor: "text-ultiq-indigo",
  },
  {
    icon: Sparkles,
    title: "Weekly insight",
    body: "On Sundays, a gentle reflection on how the week landed — sleep, focus, follow-through.",
    bg: "bg-ultiq-indigo/10",
    iconColor: "text-ultiq-indigo",
  },
];

const screenshots = [
  {
    src: "/screenshots/dashboard.png",
    title: "Dashboard",
    body: "Last night, today's plan, and the day's focus at a glance.",
  },
  {
    src: "/screenshots/checklist.png",
    title: "Checklist",
    body: "Plan tomorrow or carry today forward — one short list, no nags.",
  },
  {
    src: "/screenshots/sleep.png",
    title: "Sleep",
    body: "Start a night with one tap. Duration, quality, debt — at a glance.",
  },
  {
    src: "/screenshots/focus.png",
    title: "Focus",
    body: "A Pomodoro timer that pulls from today's checklist.",
  },
  {
    src: "/screenshots/calendar.png",
    title: "Calendar",
    body: "Sessions, sleep, and events on a single monthly view.",
  },
];

export default function Home() {
  return (
    <div className="flex flex-1 flex-col bg-ultiq-cream text-ultiq-indigo">
      {/* Nav */}
      <nav className="sticky top-0 z-30 backdrop-blur-md bg-ultiq-cream/80 border-b border-ultiq-indigo/10">
        <div className="mx-auto flex max-w-6xl items-center justify-between px-6 py-4">
          <Link href="/" className="flex items-center gap-2">
            <span className="block size-8">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src="/mascot.svg" alt="Ultiq" />
            </span>
            <span className="text-xl font-semibold tracking-tight">Ultiq</span>
          </Link>
          <div className="flex items-center gap-2 sm:gap-4">
            <ThemeToggle />
            <a
              href="https://app.ultiqapp.com"
              className="text-sm font-medium text-ultiq-indigo/80 transition hover:text-ultiq-indigo"
            >
              Sign in
            </a>
            <a
              href="#get-the-app"
              className="rounded-full bg-ultiq-indigo px-4 py-2 text-sm font-medium text-ultiq-cream transition hover:bg-ultiq-indigo/90"
            >
              Get the app
            </a>
          </div>
        </div>
      </nav>

      {/* Hero */}
      <section className="relative overflow-hidden">
        <div className="mx-auto flex max-w-5xl flex-col items-center px-6 pt-16 pb-24 text-center md:pt-28 md:pb-32">
          <motion.div
            initial={{ opacity: 0, y: 12, rotate: -6 }}
            animate={{ opacity: 1, y: 0, rotate: 0 }}
            transition={{ duration: 0.7, ease: "easeOut" }}
            className="mb-8 size-44 md:size-56"
          >
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src="/mascot.svg" alt="" className="drop-shadow-md" />
          </motion.div>

          <motion.h1
            initial={{ opacity: 0, y: 12 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: 0.15, duration: 0.6 }}
            className="text-4xl font-bold tracking-tight md:text-6xl"
          >
            Your daily productivity companion.
          </motion.h1>

          <motion.p
            initial={{ opacity: 0, y: 12 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: 0.3, duration: 0.6 }}
            className="mt-6 max-w-xl text-lg text-ultiq-indigo/75 md:text-xl"
          >
            Sleep deeply. Focus clearly. Rest. Repeat.
            <br />A calm Android app that quietly looks after the rhythm of your day.
          </motion.p>

          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            transition={{ delay: 0.5, duration: 0.6 }}
            className="mt-10 flex flex-col items-center gap-3"
          >
            <a
              href="#get-the-app"
              className="rounded-full bg-ultiq-indigo px-8 py-3 text-base font-medium text-ultiq-cream shadow-lg shadow-ultiq-indigo/15 transition hover:translate-y-[-1px] hover:bg-ultiq-indigo/90"
            >
              Get the app
            </a>
            <p className="text-sm text-ultiq-indigo/60">Free · Android · Coming soon to Google Play</p>
          </motion.div>
        </div>

        {/* Decorative blobs */}
        <div aria-hidden className="pointer-events-none absolute -top-24 -left-24 size-72 rounded-full bg-ultiq-yellow/30 blur-3xl" />
        <div aria-hidden className="pointer-events-none absolute -bottom-32 -right-24 size-96 rounded-full bg-ultiq-red/15 blur-3xl" />
      </section>

      {/* Features */}
      <section className="mx-auto w-full max-w-6xl px-6 py-20">
        <h2 className="text-center text-3xl font-bold tracking-tight md:text-4xl">
          Four small things, woven into your day.
        </h2>
        <p className="mx-auto mt-4 max-w-2xl text-center text-ultiq-indigo/70">
          Ultiq doesn&apos;t demand attention. It notices, encourages, and gets out of the way.
        </p>

        <div className="mt-12 grid gap-5 sm:grid-cols-2 lg:grid-cols-4">
          {features.map((f, i) => (
            <motion.div
              key={f.title}
              initial={{ opacity: 0, y: 18 }}
              whileInView={{ opacity: 1, y: 0 }}
              viewport={{ once: true, margin: "-40px" }}
              transition={{ delay: i * 0.08, duration: 0.45 }}
              className={`rounded-3xl border border-ultiq-indigo/10 ${f.bg} p-6`}
            >
              <div className={`mb-5 inline-flex size-11 items-center justify-center rounded-2xl bg-ultiq-cream ${f.iconColor}`}>
                <f.icon size={22} strokeWidth={2} />
              </div>
              <h3 className="text-lg font-semibold tracking-tight">{f.title}</h3>
              <p className="mt-2 text-sm leading-relaxed text-ultiq-indigo/70">{f.body}</p>
            </motion.div>
          ))}
        </div>
      </section>

      {/* Screenshots */}
      <section className="bg-ultiq-cream/60 border-y border-ultiq-indigo/10">
        <div className="mx-auto w-full max-w-6xl px-6 py-20">
          <h2 className="text-center text-3xl font-bold tracking-tight md:text-4xl">
            See it on your phone.
          </h2>
          <p className="mx-auto mt-4 max-w-2xl text-center text-ultiq-indigo/70">
            A quick tour of the screens you&apos;ll actually live in.
          </p>

          <div className="mt-12 -mx-6 overflow-x-auto px-6 pb-4 md:mx-0 md:px-0 md:overflow-visible">
            <ul className="flex snap-x snap-mandatory gap-5 md:grid md:grid-cols-5 md:gap-6">
              {screenshots.map((s, i) => (
                <motion.li
                  key={s.title}
                  initial={{ opacity: 0, y: 18 }}
                  whileInView={{ opacity: 1, y: 0 }}
                  viewport={{ once: true, margin: "-40px" }}
                  transition={{ delay: i * 0.06, duration: 0.45 }}
                  className="snap-center shrink-0 basis-[78%] sm:basis-[44%] md:basis-auto"
                >
                  <div className="rounded-[2rem] border border-ultiq-indigo/10 bg-ultiq-indigo/95 p-2 shadow-xl shadow-ultiq-indigo/15">
                    {/* eslint-disable-next-line @next/next/no-img-element */}
                    <img
                      src={s.src}
                      alt={`${s.title} screen`}
                      loading="lazy"
                      className="block w-full rounded-[1.6rem]"
                    />
                  </div>
                  <h3 className="mt-4 text-base font-semibold tracking-tight">{s.title}</h3>
                  <p className="mt-1 text-sm leading-relaxed text-ultiq-indigo/65">{s.body}</p>
                </motion.li>
              ))}
            </ul>
          </div>
        </div>
      </section>

      {/* Mascot moment */}
      <section className="bg-ultiq-indigo text-ultiq-cream">
        <div className="mx-auto flex max-w-5xl flex-col items-center px-6 py-24 text-center md:py-32">
          <motion.div
            initial={{ opacity: 0, scale: 0.9 }}
            whileInView={{ opacity: 1, scale: 1 }}
            viewport={{ once: true }}
            transition={{ duration: 0.6 }}
            className="mb-8 size-40 md:size-52"
          >
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src="/mascot.svg" alt="" />
          </motion.div>
          <motion.blockquote
            initial={{ opacity: 0, y: 16 }}
            whileInView={{ opacity: 1, y: 0 }}
            viewport={{ once: true }}
            transition={{ delay: 0.2, duration: 0.6 }}
            className="font-serif text-3xl italic md:text-5xl"
          >
            &ldquo;Sleep deeply, focus clearly, <br className="hidden md:inline" />
            rest, repeat.&rdquo;
          </motion.blockquote>
          <p className="mt-6 text-ultiq-cream/60">— a sleeping book&apos;s daily wish for you</p>
        </div>
      </section>

      {/* Get the app CTA */}
      <section id="get-the-app" className="mx-auto w-full max-w-4xl px-6 py-20 text-center">
        <h2 className="text-3xl font-bold tracking-tight md:text-4xl">Ready when you are.</h2>
        <p className="mx-auto mt-4 max-w-xl text-ultiq-indigo/70">
          Direct APK download for Android — sideload it onto your phone today, no Play Store required.
        </p>

        <div className="mt-10 flex flex-col items-center gap-4">
          <a
            href="/ultiq.2.9.0.apk"
            download
            className="inline-flex items-center gap-3 rounded-full bg-ultiq-indigo px-8 py-4 text-base font-medium text-ultiq-cream shadow-lg shadow-ultiq-indigo/20 transition hover:translate-y-[-1px] hover:bg-ultiq-indigo/90"
          >
            <Download size={20} strokeWidth={2.2} />
            Download APK · v2.9.0 · 17 MB
          </a>
          <span className="text-xs text-ultiq-indigo/50">Android 8.0+ (API 26)</span>
        </div>

        <details className="mx-auto mt-10 max-w-lg rounded-2xl border border-ultiq-indigo/15 bg-white/40 p-5 text-left text-sm text-ultiq-indigo/80">
          <summary className="cursor-pointer font-medium text-ultiq-indigo">First time installing?</summary>
          <ol className="mt-3 list-decimal space-y-2 pl-5 text-ultiq-indigo/70">
            <li>Tap the downloaded <code className="rounded bg-ultiq-indigo/5 px-1.5 py-0.5">ultiq.2.9.0.apk</code> in your Files or Downloads app.</li>
            <li>If Android asks <em>&ldquo;allow installs from this source?&rdquo;</em>, tap <strong>Settings</strong> → toggle on <strong>Allow from this source</strong>, then go back.</li>
            <li>Tap <strong>Install</strong>. The icon appears on your home screen / app drawer when it&apos;s done.</li>
          </ol>
          <p className="mt-3 text-xs text-ultiq-indigo/50">
            That &ldquo;unknown source&rdquo; prompt is Android&apos;s standard one-time check for any APK installed outside the Play Store. Same APK as the Play Store build, just hosted here.
          </p>
        </details>

        <div className="mx-auto mt-10 flex max-w-lg items-center justify-center gap-3 text-sm text-ultiq-indigo/60">
          <span className="rounded-full bg-ultiq-yellow/30 px-2.5 py-1 text-xs font-semibold uppercase tracking-wider text-ultiq-indigo">
            Coming soon
          </span>
          <span>also on Google Play —</span>
          <a href="mailto:support@ultiqapp.com" className="text-ultiq-red underline-offset-4 hover:underline">
            support@ultiqapp.com
          </a>
        </div>
      </section>

      {/* Footer */}
      <footer className="mt-auto border-t border-ultiq-indigo/10 bg-ultiq-cream">
        <div className="mx-auto flex max-w-6xl flex-col items-center justify-between gap-4 px-6 py-8 text-sm text-ultiq-indigo/70 md:flex-row">
          <div className="flex items-center gap-3">
            <span className="block size-6">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src="/mascot.svg" alt="" />
            </span>
            <span>© {new Date().getFullYear()} Ultiq</span>
          </div>
          <div className="flex items-center gap-6">
            <a href="https://app.ultiqapp.com" className="hover:text-ultiq-indigo">Dashboard</a>
            <Link href="/privacy" className="hover:text-ultiq-indigo">Privacy</Link>
            <Link href="/terms" className="hover:text-ultiq-indigo">Terms</Link>
            <a href="mailto:support@ultiqapp.com" className="hover:text-ultiq-indigo">Support</a>
          </div>
        </div>
      </footer>
    </div>
  );
}
