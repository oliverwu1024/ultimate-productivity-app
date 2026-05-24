"use client";

import { AnimatePresence, motion } from "framer-motion";
import {
  Moon,
  Sun,
  Timer,
  ListChecks,
  Sparkles,
  Download,
  MonitorCog,
  ArrowRight,
  AlarmClock,
  CalendarDays,
  MessagesSquare,
} from "lucide-react";
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
  // §landing-redesign — "system" no longer follows the OS; it defaults to
  // dark to match the pre-paint script. Explicit light is the only opt-out.
  const dark = t === "dark" || t === "system";
  document.documentElement.classList.toggle("dark", dark);
  void systemPrefersDark; // retained for future use; reference avoids unused-import lint
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
  const label = theme === "light" ? "Light" : theme === "dark" ? "Dark" : "Auto";

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
    body: "Start a night with one tap. Phone-pickup detection, sleep debt + extra rest, and optional on-device snore + cough monitoring — audio stays on your phone.",
    bg: "bg-ultiq-blue/40",
    iconColor: "text-ultiq-indigo",
  },
  {
    icon: AlarmClock,
    title: "Wake missions",
    body: "Alarmy-style alarms with math, shake, or photograph-a-fixed-scene missions to dismiss. So you actually get out of bed.",
    bg: "bg-ultiq-yellow/30",
    iconColor: "text-ultiq-indigo",
  },
  {
    icon: Timer,
    title: "Focus sessions",
    body: "Pomodoro with phone-lockout overlay and prominent overtime indicator. Quietly notices each pickup, no shaming. AI auto-tags what you worked on.",
    bg: "bg-ultiq-red/15",
    iconColor: "text-ultiq-red",
  },
  {
    icon: ListChecks,
    title: "Daily checklist",
    body: "A short list of intentions for today. Recurrence by days-of-week or due date, carry-over for what didn't get done, drop straight into a focus session from any task.",
    bg: "bg-ultiq-indigo/10",
    iconColor: "text-ultiq-indigo",
  },
  {
    icon: CalendarDays,
    title: "Calendar",
    body: "Events, sleep, and focus sessions on a single monthly view. Natural-language create — type 'lunch with Sarah tomorrow at 1pm'; AI parses, you confirm.",
    bg: "bg-ultiq-blue/40",
    iconColor: "text-ultiq-indigo",
  },
  {
    icon: MessagesSquare,
    title: "AI coach",
    body: "Tool-using assistant reads your sleep + focus + checklist data. Proposes calendar + checklist writes you confirm with a tap. Logs sleep + sets alarms via chat.",
    bg: "bg-ultiq-indigo/10",
    iconColor: "text-ultiq-indigo",
  },
  {
    icon: Sparkles,
    title: "AI insight + sleep rating",
    body: "Weekly reflection on the past 7 days. Daily anomaly nudges if slow-burn patterns appear (short nights, focus collapse, sleep-window phone spirals). One-tap AI sleep quality rating after each night.",
    bg: "bg-ultiq-yellow/30",
    iconColor: "text-ultiq-indigo",
  },
];

type Screenshot = { src: string; title: string; body: string };

const screenshots: Screenshot[] = [
  { src: "/screenshots/dashboard.png", title: "Dashboard", body: "Last night, today's plan, and the day's focus at a glance." },
  { src: "/screenshots/checklist.png", title: "Checklist", body: "Plan tomorrow or carry today forward — one short list, no nags." },
  { src: "/screenshots/sleep.png", title: "Sleep", body: "Start a night with one tap. Duration, quality, debt — at a glance." },
  { src: "/screenshots/focus.png", title: "Focus", body: "A Pomodoro timer that pulls from today's checklist." },
  { src: "/screenshots/calendar.png", title: "Calendar", body: "Sessions, sleep, and events on a single monthly view." },
  { src: "/screenshots/coach.png", title: "Coach", body: "Tool-using AI coach reads your data and proposes writes you confirm with a tap." },
  { src: "/screenshots/insight.png", title: "AI insight", body: "Weekly reflection + daily anomaly nudges. Quiet by default, useful when it speaks." },
  { src: "/screenshots/alarm.png", title: "Alarm", body: "Alarmy-style wake missions — solve math, shake the phone, or photograph a fixed scene to silence." },
];

/** §landing-redesign — Three themed groups of screens for the tour. Phone
 *  mockup in the hero still cycles through the full screenshots array. */
type TourSection = {
  eyebrow: string;
  title: string;
  subtitle: string;
  keys: string[];
  emoji: string;
  /** Drives the per-emoji motion: float = gentle vertical bob (home);
   *  spin = slow continuous rotation (sun); sway = side-to-side rock (moon). */
  motion: "float" | "spin" | "sway";
};

const tourSections: TourSection[] = [
  {
    eyebrow: "Home page",
    title: "A calm overview, with an AI coach beside it.",
    subtitle: "Dashboard shows last night, today's plan, and the day's focus at a glance. The week's AI reflection appears when there's something to say. Coach reads your data and proposes calendar + checklist writes you confirm with one tap.",
    keys: ["Dashboard", "AI insight", "Coach"],
    emoji: "🏠",
    motion: "float",
  },
  {
    eyebrow: "Your day",
    title: "Plan, focus, schedule.",
    subtitle: "A short checklist, a quiet Pomodoro, and a single monthly view — built for getting through the day without noise.",
    keys: ["Checklist", "Focus", "Calendar"],
    emoji: "☀️",
    motion: "spin",
  },
  {
    eyebrow: "Your night",
    title: "Sleep deeply. Wake on your terms.",
    subtitle: "Start a night with one tap. Snore + cough detection runs on-device. Alarmy-style wake missions — math, shake, or a photo of a fixed scene — so you actually get out of bed.",
    keys: ["Sleep", "Alarm"],
    emoji: "🌙",
    motion: "sway",
  },
];

/** Per-section emoji animation. Subtle "this part of the day is happening"
 *  signal without being a Slack-emoji-rave. */
const sectionEmojiVariants: Record<
  TourSection["motion"],
  { animate: Record<string, number[]>; transition: { duration: number; repeat: number; ease: "easeInOut" | "linear" } }
> = {
  float: {
    animate: { y: [0, -8, 0] },
    transition: { duration: 3.2, repeat: Infinity, ease: "easeInOut" },
  },
  spin: {
    animate: { rotate: [0, 360] },
    transition: { duration: 22, repeat: Infinity, ease: "linear" },
  },
  sway: {
    animate: { rotate: [-12, 12, -12], y: [0, -3, 0] },
    transition: { duration: 5, repeat: Infinity, ease: "easeInOut" },
  },
};

const screenshotByTitle = (t: string): Screenshot =>
  screenshots.find((s) => s.title === t) ?? screenshots[0];

/** §landing-redesign — Three slow-drifting gradient blobs behind the hero.
 *  Provides the "炫炮" backdrop motion without burning the GPU. */
function AnimatedGradientMesh() {
  return (
    <div aria-hidden className="pointer-events-none absolute inset-0 -z-10 overflow-hidden">
      <div
        className="animate-drift absolute -top-32 -left-32 size-[28rem] rounded-full bg-ultiq-yellow/30 blur-3xl"
        style={{ animationDelay: "0s" }}
      />
      <div
        className="animate-drift absolute top-1/4 right-0 size-[32rem] rounded-full bg-ultiq-red/25 blur-3xl"
        style={{ animationDelay: "-7s" }}
      />
      <div
        className="animate-drift absolute -bottom-40 left-1/3 size-[34rem] rounded-full bg-ultiq-blue/30 blur-3xl"
        style={{ animationDelay: "-14s" }}
      />
    </div>
  );
}

/** §landing-redesign — Floating phone mockup that cycles through real Ultiq
 *  screenshots. The single highest-impact element on the page; takes the
 *  hero from "nice landing" to "I want to install this right now". */
function PhoneMockup() {
  const [index, setIndex] = useState(0);

  useEffect(() => {
    const t = setInterval(() => setIndex((i) => (i + 1) % screenshots.length), 3500);
    return () => clearInterval(t);
  }, []);

  const current = screenshots[index];

  return (
    <div className="relative mx-auto w-[260px] sm:w-[300px] md:w-[330px]">
      {/* Halo glow behind the phone */}
      <div
        aria-hidden
        className="absolute -inset-8 rounded-[3rem] bg-gradient-to-br from-ultiq-red/30 via-ultiq-indigo/25 to-ultiq-blue/30 blur-3xl"
      />

      <div className="animate-float relative">
        <div className="ultiq-glow rounded-[2.6rem] border border-ultiq-indigo/15 bg-ultiq-night-900 p-2.5 dark:border-white/10">
          <div className="relative aspect-[9/19] overflow-hidden rounded-[2rem] bg-black">
            <AnimatePresence mode="wait">
              <motion.img
                key={current.src}
                src={current.src}
                alt={`${current.title} screen`}
                initial={{ opacity: 0, scale: 1.03 }}
                animate={{ opacity: 1, scale: 1 }}
                exit={{ opacity: 0, scale: 0.97 }}
                transition={{ duration: 0.6, ease: "easeInOut" }}
                className="absolute inset-0 h-full w-full object-cover"
              />
            </AnimatePresence>

            {/* Notch */}
            <div
              aria-hidden
              className="absolute left-1/2 top-2 h-5 w-20 -translate-x-1/2 rounded-full bg-black/80"
            />
          </div>
        </div>

        {/* Floating label of the current screen */}
        <AnimatePresence mode="wait">
          <motion.div
            key={current.title}
            initial={{ opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -8 }}
            transition={{ duration: 0.35 }}
            className="absolute -bottom-12 left-1/2 -translate-x-1/2 whitespace-nowrap rounded-full border border-ultiq-indigo/15 bg-ultiq-cream/80 px-4 py-1.5 text-sm font-medium text-ultiq-indigo backdrop-blur dark:border-white/10 dark:bg-ultiq-night-800/80 dark:text-ultiq-cream"
          >
            {current.title}
          </motion.div>
        </AnimatePresence>
      </div>

      {/* Dot indicators */}
      <div className="absolute -bottom-24 left-1/2 flex -translate-x-1/2 gap-1.5">
        {screenshots.map((s, i) => (
          <button
            key={s.title}
            onClick={() => setIndex(i)}
            aria-label={`Show ${s.title}`}
            className={`h-1.5 rounded-full transition-all ${
              i === index ? "w-6 bg-ultiq-indigo dark:bg-ultiq-cream" : "w-1.5 bg-ultiq-indigo/30 dark:bg-ultiq-cream/30"
            }`}
          />
        ))}
      </div>
    </div>
  );
}

export default function Home() {
  return (
    <div className="relative flex flex-1 flex-col bg-ultiq-cream text-ultiq-indigo">
      {/* Page-level subtle dotted grid (sits below the gradient meshes) */}
      <div aria-hidden className="bg-ultiq-grid pointer-events-none absolute inset-0 -z-20" />

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

      {/* Hero — 2-column desktop, stacked mobile, animated mesh behind */}
      <section className="relative overflow-hidden">
        <AnimatedGradientMesh />

        <div className="mx-auto grid max-w-6xl items-center gap-16 px-6 pt-16 pb-32 md:grid-cols-[1.05fr_0.95fr] md:gap-10 md:pt-28 md:pb-40">
          {/* Left column — mascot + headline + CTA */}
          <div className="text-center md:text-left">
            <motion.div
              initial={{ opacity: 0, y: 10 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: 0.6, ease: "easeOut" }}
              className="animate-breathe mx-auto mb-6 size-24 md:mx-0 md:mb-8 md:size-32"
            >
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src="/mascot.svg" alt="" className="drop-shadow-md" />
            </motion.div>

            <motion.h1
              initial={{ opacity: 0, y: 14 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ delay: 0.1, duration: 0.6 }}
              className="text-5xl font-bold leading-[1.05] tracking-tight md:text-7xl"
            >
              <span className="text-gradient-hero block">Sleep deeply.</span>
              <span className="text-gradient-hero block">Focus clearly.</span>
              <span className="block text-ultiq-indigo/85">Rest, repeat.</span>
            </motion.h1>

            <motion.p
              initial={{ opacity: 0, y: 14 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ delay: 0.25, duration: 0.6 }}
              className="mt-8 max-w-xl text-lg leading-relaxed text-ultiq-indigo/75 md:mx-0 md:text-xl"
            >
              Ultiq is a calm productivity companion for Android. Sleep tracking with on-device snore + cough detection, focus sessions, daily checklist, and an AI coach that proposes writes you confirm with one tap.
            </motion.p>

            <motion.div
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              transition={{ delay: 0.45, duration: 0.6 }}
              className="mt-10 flex flex-col items-center gap-4 md:flex-row md:items-center"
            >
              <a
                href="#get-the-app"
                className="ultiq-glow group inline-flex items-center gap-2 rounded-full bg-ultiq-indigo px-7 py-3.5 text-base font-medium text-ultiq-cream transition hover:translate-y-[-1px] hover:bg-ultiq-indigo/90"
              >
                Get the app
                <ArrowRight size={18} strokeWidth={2.2} className="transition group-hover:translate-x-0.5" />
              </a>
              <p className="text-sm text-ultiq-indigo/60">Free · Android 8.0+ · v2.13.6</p>
            </motion.div>
          </div>

          {/* Right column — floating phone */}
          <motion.div
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: 0.35, duration: 0.8, ease: "easeOut" }}
            className="relative flex items-center justify-center md:justify-end"
          >
            <PhoneMockup />
          </motion.div>
        </div>
      </section>

      {/* Features */}
      <section className="relative mx-auto w-full max-w-6xl px-6 py-24">
        <motion.div
          initial={{ opacity: 0, y: 14 }}
          whileInView={{ opacity: 1, y: 0 }}
          viewport={{ once: true, margin: "-80px" }}
          transition={{ duration: 0.6 }}
          className="text-center"
        >
          <p className="mb-3 text-sm font-medium uppercase tracking-[0.18em] text-ultiq-indigo/55">
            What it does
          </p>
          <h2 className="text-3xl font-bold tracking-tight md:text-5xl">
            Seven small things, woven into your day.
          </h2>
          <p className="mx-auto mt-5 max-w-2xl text-lg text-ultiq-indigo/70">
            Ultiq doesn&apos;t demand attention. It notices, encourages, and gets out of the way.
          </p>
        </motion.div>

        {/* §landing-redesign — 7-card layout shaped as an inverted-pyramid: 4
         *  cards on top row + 3 cards centered on the bottom row. Uses an 8-col
         *  grid at lg+; each card spans 2 cols, and the 5th card (index 4)
         *  starts at col 2 so the bottom row's 3 cards (cols 2-3, 4-5, 6-7)
         *  sit centered between 1-col margins. */}
        <div className="mt-14 grid gap-5 sm:grid-cols-2 lg:grid-cols-8">
          {features.map((f, i) => (
            <motion.div
              key={f.title}
              initial={{ opacity: 0, y: 22 }}
              whileInView={{ opacity: 1, y: 0 }}
              viewport={{ once: true, margin: "-40px" }}
              transition={{ delay: i * 0.08, duration: 0.5 }}
              whileHover={{ y: -4 }}
              className={`group relative rounded-3xl border border-ultiq-indigo/10 ${f.bg} p-6 transition lg:col-span-2 ${i === 4 ? "lg:col-start-2" : ""}`}
            >
              {/* Hover-glow ring */}
              <div className="ultiq-glow pointer-events-none absolute inset-0 rounded-3xl opacity-0 transition group-hover:opacity-100" />

              <div className="relative">
                <div className={`mb-5 inline-flex size-12 items-center justify-center rounded-2xl bg-ultiq-cream ${f.iconColor} shadow-sm`}>
                  <f.icon size={22} strokeWidth={2} />
                </div>
                <h3 className="text-lg font-semibold tracking-tight">{f.title}</h3>
                <p className="mt-2 text-sm leading-relaxed text-ultiq-indigo/70">{f.body}</p>
              </div>
            </motion.div>
          ))}
        </div>
      </section>

      {/* Three-section tour with zig-zag layout (text left / phones right,
       *  alternating each section) and compact phone previews. The hero's
       *  floating phone is the WOW visual; these are supporting context. */}
      {tourSections.map((sect, sectIdx) => {
        const items = sect.keys.map(screenshotByTitle);
        const isReverse = sectIdx % 2 === 1; // 0,2 = text-left; 1 = text-right
        const altBg = sectIdx % 2 === 0
          ? "bg-ultiq-cream/60 border-y border-ultiq-indigo/10"
          : "";
        return (
          <section key={sect.eyebrow} className={altBg}>
            <div className="mx-auto w-full max-w-6xl px-6 py-20 md:py-24">
              <div className="grid items-center gap-12 md:grid-cols-2 md:gap-16">
                {/* Text column */}
                <motion.div
                  initial={{ opacity: 0, x: isReverse ? 30 : -30 }}
                  whileInView={{ opacity: 1, x: 0 }}
                  viewport={{ once: true, margin: "-80px" }}
                  transition={{ duration: 0.6 }}
                  className={isReverse ? "md:order-2" : "md:order-1"}
                >
                  <motion.div
                    aria-hidden
                    className="mb-4 inline-block text-5xl md:text-6xl select-none"
                    style={{ transformOrigin: "50% 60%" }}
                    {...sectionEmojiVariants[sect.motion]}
                  >
                    {sect.emoji}
                  </motion.div>
                  <p className="mb-3 text-sm font-medium uppercase tracking-[0.18em] text-ultiq-indigo/55">
                    {sect.eyebrow}
                  </p>
                  <h2 className="text-3xl font-bold tracking-tight md:text-5xl">
                    {sect.title}
                  </h2>
                  <p className="mt-5 max-w-xl text-lg leading-relaxed text-ultiq-indigo/70">
                    {sect.subtitle}
                  </p>
                </motion.div>

                {/* Phones column */}
                <div className={isReverse ? "md:order-1" : "md:order-2"}>
                  <ul className="flex flex-wrap items-start justify-center gap-4 md:gap-5">
                    {items.map((s, i) => (
                      <motion.li
                        key={s.title}
                        initial={{ opacity: 0, y: 24 }}
                        whileInView={{ opacity: 1, y: 0 }}
                        viewport={{ once: true, margin: "-40px" }}
                        transition={{ delay: i * 0.1, duration: 0.5 }}
                        whileHover={{ y: -4 }}
                        className={`flex-shrink-0 ${
                          items.length === 3
                            ? "w-[120px] sm:w-[130px] md:w-[140px]"
                            : "w-[150px] sm:w-[170px] md:w-[180px]"
                        }`}
                      >
                        <div className="ultiq-glow group rounded-[1.6rem] border border-ultiq-indigo/10 bg-ultiq-night-900 p-1.5 transition">
                          {/* eslint-disable-next-line @next/next/no-img-element */}
                          <img
                            src={s.src}
                            alt={`${s.title} screen`}
                            loading="lazy"
                            className="block w-full rounded-[1.2rem] transition group-hover:scale-[1.01]"
                          />
                        </div>
                        <h3 className="mt-3 text-center text-xs font-semibold tracking-tight md:text-sm">
                          {s.title}
                        </h3>
                      </motion.li>
                    ))}
                  </ul>
                </div>
              </div>
            </div>
          </section>
        );
      })}

      {/* Mascot moment */}
      <section className="relative overflow-hidden bg-ultiq-indigo text-ultiq-cream">
        {/* Subtle gradient sheen */}
        <div
          aria-hidden
          className="animate-drift pointer-events-none absolute inset-0 opacity-40"
          style={{
            background:
              "radial-gradient(circle at 20% 30%, rgba(217,71,76,0.35), transparent 50%), radial-gradient(circle at 80% 70%, rgba(168,197,232,0.35), transparent 50%)",
          }}
        />

        <div className="relative mx-auto flex max-w-5xl flex-col items-center px-6 py-28 text-center md:py-36">
          <motion.div
            initial={{ opacity: 0, scale: 0.92 }}
            whileInView={{ opacity: 1, scale: 1 }}
            viewport={{ once: true }}
            transition={{ duration: 0.7 }}
            className="animate-breathe mb-10 size-40 md:size-52"
          >
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src="/mascot.svg" alt="" />
          </motion.div>
          <motion.blockquote
            initial={{ opacity: 0, y: 18 }}
            whileInView={{ opacity: 1, y: 0 }}
            viewport={{ once: true }}
            transition={{ delay: 0.2, duration: 0.7 }}
            className="font-serif text-3xl italic leading-[1.2] md:text-6xl"
          >
            &ldquo;Sleep deeply, focus clearly, <br className="hidden md:inline" />
            rest, repeat.&rdquo;
          </motion.blockquote>
          <p className="mt-6 text-ultiq-cream/60">— a sleeping book&apos;s daily wish for you</p>
        </div>
      </section>

      {/* Get the app CTA */}
      <section id="get-the-app" className="relative overflow-hidden">
        <AnimatedGradientMesh />
        <div className="mx-auto w-full max-w-4xl px-6 py-28 text-center">
          <motion.h2
            initial={{ opacity: 0, y: 14 }}
            whileInView={{ opacity: 1, y: 0 }}
            viewport={{ once: true }}
            transition={{ duration: 0.6 }}
            className="text-3xl font-bold tracking-tight md:text-5xl"
          >
            Ready when you are.
          </motion.h2>
          <motion.p
            initial={{ opacity: 0, y: 14 }}
            whileInView={{ opacity: 1, y: 0 }}
            viewport={{ once: true }}
            transition={{ delay: 0.1, duration: 0.6 }}
            className="mx-auto mt-5 max-w-xl text-lg text-ultiq-indigo/75"
          >
            Direct APK download for Android — sideload it onto your phone today, no Play Store required.
          </motion.p>

          <motion.div
            initial={{ opacity: 0, y: 14 }}
            whileInView={{ opacity: 1, y: 0 }}
            viewport={{ once: true }}
            transition={{ delay: 0.2, duration: 0.6 }}
            className="mt-12 flex flex-col items-center gap-4"
          >
            <a
              href="/ultiq.2.13.6.apk"
              download
              className="ultiq-glow group inline-flex items-center gap-3 rounded-full bg-ultiq-indigo px-9 py-4 text-base font-medium text-ultiq-cream transition hover:translate-y-[-1px] hover:bg-ultiq-indigo/90"
            >
              <Download size={20} strokeWidth={2.2} />
              Download APK · v2.13.6 · 42 MB
              <ArrowRight size={18} strokeWidth={2.2} className="transition group-hover:translate-x-0.5" />
            </a>
            <span className="text-xs text-ultiq-indigo/55">Android 8.0+ (API 26)</span>
          </motion.div>

          <details className="mx-auto mt-12 max-w-lg rounded-2xl border border-ultiq-indigo/15 bg-white/40 p-5 text-left text-sm text-ultiq-indigo/80 backdrop-blur dark:bg-ultiq-night-800/40">
            <summary className="cursor-pointer font-medium text-ultiq-indigo">First time installing?</summary>
            <ol className="mt-3 list-decimal space-y-2 pl-5 text-ultiq-indigo/70">
              <li>Tap the downloaded <code className="rounded bg-ultiq-indigo/5 px-1.5 py-0.5">ultiq.2.13.6.apk</code> in your Files or Downloads app.</li>
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
