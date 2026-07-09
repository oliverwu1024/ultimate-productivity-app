"use client";

// Client-side i18n for the landing page. Mirrors the web-dashboard's
// conventions (src/i18n.rs) so a language chosen on either surface round-trips
// through the shared `ultiq_lang` localStorage key: same 14 BCP-47 tags, same
// endonyms, same {name} placeholder syntax, RTL for Arabic only, and the same
// resolve order (stored → navigator.language → en). Catalogs are flat JSON maps
// of dotted keys; a missing key falls back to English, then to the raw key so
// typos surface instead of rendering blank.
//
// Unlike the dashboard, the landing is a static export with no per-locale
// routing (deliberate — see the 13.4 decision): the exported HTML is English,
// and the selected locale is applied on the client after hydration. First
// render therefore always uses "en" to match the static markup and avoid a
// hydration mismatch; the resolved locale is applied in an effect.

import {
  createContext,
  Fragment,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import { Check, Globe } from "lucide-react";

import en from "../locales/en.json";
import es from "../locales/es.json";
import ptBR from "../locales/pt-BR.json";
import fr from "../locales/fr.json";
import de from "../locales/de.json";
import ja from "../locales/ja.json";
import zhHans from "../locales/zh-Hans.json";
import zhHant from "../locales/zh-Hant.json";
import ko from "../locales/ko.json";
import hi from "../locales/hi.json";
import vi from "../locales/vi.json";
import th from "../locales/th.json";
import id from "../locales/id.json";
import ar from "../locales/ar.json";

export type Catalog = Record<string, string>;

export type LocaleTag =
  | "en"
  | "es"
  | "pt-BR"
  | "fr"
  | "de"
  | "ja"
  | "zh-Hans"
  | "zh-Hant"
  | "ko"
  | "hi"
  | "vi"
  | "th"
  | "id"
  | "ar";

type LocaleDef = { tag: LocaleTag; endonym: string; catalog: Catalog };

// Order matches the dashboard's language picker.
export const LOCALES: LocaleDef[] = [
  { tag: "en", endonym: "English", catalog: en },
  { tag: "es", endonym: "Español", catalog: es },
  { tag: "pt-BR", endonym: "Português (Brasil)", catalog: ptBR },
  { tag: "fr", endonym: "Français", catalog: fr },
  { tag: "de", endonym: "Deutsch", catalog: de },
  { tag: "ja", endonym: "日本語", catalog: ja },
  { tag: "zh-Hans", endonym: "简体中文", catalog: zhHans },
  { tag: "zh-Hant", endonym: "繁體中文", catalog: zhHant },
  { tag: "ko", endonym: "한국어", catalog: ko },
  { tag: "hi", endonym: "हिन्दी", catalog: hi },
  { tag: "vi", endonym: "Tiếng Việt", catalog: vi },
  { tag: "th", endonym: "ไทย", catalog: th },
  { tag: "id", endonym: "Bahasa Indonesia", catalog: id },
  { tag: "ar", endonym: "العربية", catalog: ar },
];

const STORAGE_KEY = "ultiq_lang";
const RTL_TAGS: LocaleTag[] = ["ar"];
export const isRtl = (tag: LocaleTag): boolean => RTL_TAGS.includes(tag);

const BY_TAG = new Map<LocaleTag, LocaleDef>(LOCALES.map((l) => [l.tag, l]));
const EN_CATALOG = BY_TAG.get("en")!.catalog;

/** Normalise any BCP-47-ish string to one of our supported tags, matching the
 *  dashboard's `from_tag`/`from_browser` logic (Chinese script disambiguation,
 *  pt→pt-BR, legacy `in`→id, primary-subtag fallback). */
function normalizeTag(raw: string): LocaleTag {
  const s = raw.trim();
  if (!s) return "en";
  const lower = s.toLowerCase();

  const exact = LOCALES.find((l) => l.tag.toLowerCase() === lower);
  if (exact) return exact.tag;

  const [primary, region = ""] = lower.split("-");
  if (primary === "zh") {
    return ["tw", "hk", "mo", "hant"].includes(region) ? "zh-Hant" : "zh-Hans";
  }
  if (primary === "pt") return "pt-BR";
  if (primary === "in") return "id"; // legacy ISO code for Indonesian

  const byPrimary = LOCALES.find((l) => l.tag.toLowerCase().split("-")[0] === primary);
  return byPrimary ? byPrimary.tag : "en";
}

function resolveInitial(): LocaleTag {
  if (typeof window === "undefined") return "en";
  try {
    const saved = window.localStorage.getItem(STORAGE_KEY);
    if (saved && saved.trim()) return normalizeTag(saved);
  } catch {
    /* localStorage unavailable (private mode / blocked) */
  }
  if (typeof navigator !== "undefined" && navigator.language) {
    return normalizeTag(navigator.language);
  }
  return "en";
}

type I18nValue = {
  locale: LocaleTag;
  setLocale: (tag: LocaleTag) => void;
  /** Look up a key in the active catalog (English fallback, then raw key). */
  t: (key: string) => string;
  /** Look up + substitute {name} placeholders. */
  ta: (key: string, args: Record<string, string | number>) => string;
};

const I18nContext = createContext<I18nValue | null>(null);

export function LocaleProvider({ children }: { children: ReactNode }) {
  // Start at "en" to match the statically-exported English HTML on first paint,
  // then switch to the resolved locale after mount (client-only).
  const [locale, setLocaleState] = useState<LocaleTag>("en");

  useEffect(() => {
    setLocaleState(resolveInitial());
  }, []);

  useEffect(() => {
    const el = document.documentElement;
    el.setAttribute("lang", locale);
    el.setAttribute("dir", isRtl(locale) ? "rtl" : "ltr");
  }, [locale]);

  const setLocale = (tag: LocaleTag) => {
    setLocaleState(tag);
    try {
      window.localStorage.setItem(STORAGE_KEY, tag);
    } catch {
      /* ignore */
    }
  };

  const value = useMemo<I18nValue>(() => {
    const catalog = (BY_TAG.get(locale) ?? BY_TAG.get("en")!).catalog;
    const t = (key: string): string => {
      const v = catalog[key];
      if (v !== undefined) return v;
      const fb = EN_CATALOG[key];
      return fb !== undefined ? fb : key;
    };
    const ta = (key: string, args: Record<string, string | number>): string => {
      let out = t(key);
      for (const [name, val] of Object.entries(args)) {
        out = out.split(`{${name}}`).join(String(val));
      }
      return out;
    };
    return { locale, setLocale, t, ta };
  }, [locale]);

  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

export function useI18n(): I18nValue {
  const ctx = useContext(I18nContext);
  if (!ctx) throw new Error("useI18n must be used within a LocaleProvider");
  return ctx;
}

/** Sets document.title to a translated key, keeping the browser tab in sync
 *  with the selected locale (the static <title> in metadata stays English). */
export function usePageTitle(key: string): void {
  const { t } = useI18n();
  useEffect(() => {
    document.title = t(key);
  }, [t, key]);
}

/** Renders a template string with {name} placeholders, substituting React
 *  nodes (e.g. an inline <code>) rather than plain text — keeps translation
 *  word-order free while allowing embedded markup. */
export function TemplateText({
  template,
  values,
}: {
  template: string;
  values: Record<string, ReactNode>;
}) {
  const parts = template.split(/(\{\w+\})/g);
  return (
    <>
      {parts.map((part, i) => {
        const m = /^\{(\w+)\}$/.exec(part);
        const node = m && m[1] in values ? values[m[1]] : part;
        return <Fragment key={i}>{node}</Fragment>;
      })}
    </>
  );
}

/** Globe dropdown listing every locale by its endonym. Persists to
 *  `ultiq_lang` and applies <html lang/dir> via the provider effect. */
export function LanguageSwitcher() {
  const { locale, setLocale, t } = useI18n();
  const [open, setOpen] = useState(false);
  const current = BY_TAG.get(locale) ?? LOCALES[0];

  return (
    <div className="relative">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-label={t("lang.label")}
        className="inline-flex items-center gap-1.5 rounded-full px-2.5 py-2 text-sm font-medium text-ultiq-indigo/70 transition hover:bg-ultiq-indigo/5 hover:text-ultiq-indigo"
      >
        <Globe size={18} aria-hidden="true" />
        <span className="hidden sm:inline">{current.endonym}</span>
      </button>

      {open && (
        <>
          {/* Click-away backdrop */}
          <button
            type="button"
            aria-hidden="true"
            tabIndex={-1}
            onClick={() => setOpen(false)}
            className="fixed inset-0 z-40 cursor-default"
          />
          <ul
            role="listbox"
            aria-label={t("lang.label")}
            className="absolute right-0 z-50 mt-2 max-h-80 w-48 overflow-auto rounded-2xl border border-ultiq-indigo/10 bg-ultiq-cream p-1.5 shadow-xl shadow-ultiq-indigo/10 dark:border-white/10 dark:bg-ultiq-night-800"
          >
            {LOCALES.map((l) => {
              const active = l.tag === locale;
              return (
                <li key={l.tag}>
                  <button
                    type="button"
                    role="option"
                    aria-selected={active}
                    onClick={() => {
                      setLocale(l.tag);
                      setOpen(false);
                    }}
                    className={`flex w-full items-center justify-between gap-2 rounded-xl px-3 py-2 text-left text-sm transition hover:bg-ultiq-indigo/5 ${
                      active ? "font-semibold text-ultiq-indigo dark:text-ultiq-cream" : "text-ultiq-indigo/75 dark:text-ultiq-cream/75"
                    }`}
                  >
                    <span dir="auto">{l.endonym}</span>
                    {active && <Check size={15} aria-hidden="true" />}
                  </button>
                </li>
              );
            })}
          </ul>
        </>
      )}
    </div>
  );
}
