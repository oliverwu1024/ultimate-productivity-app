//! §13.3 — client-side language selection for the Leptos dashboard.
//!
//! Mirrors `theme.rs`: a reactive `RwSignal<Locale>` in context, applied +
//! persisted through an `Effect`. Text is looked up with `t()` against flat
//! JSON catalogs under `../locales/<tag>.json`, bundled at compile time via
//! `include_str!` (only the active locale plus the English fallback are ever
//! parsed). The BCP-47 tag set and the never-translated glossary terms share
//! the same source of truth as `backend/src/i18n.rs` and the Android picker,
//! so a language chosen on any surface round-trips through the common
//! `preferences.app_language` blob.

use std::cell::RefCell;
use std::collections::HashMap;

use gloo_storage::{LocalStorage, Storage};
use leptos::prelude::*;

const KEY: &str = "ultiq_lang";

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Locale {
    En,
    Es,
    PtBr,
    Fr,
    De,
    Ja,
    ZhHans,
    ZhHant,
    Ko,
    Hi,
    Vi,
    Th,
    Id,
    Ar,
}

impl Locale {
    /// Every locale the dashboard ships, English first. Order drives the picker.
    pub const ALL: [Locale; 14] = [
        Locale::En,
        Locale::Es,
        Locale::PtBr,
        Locale::Fr,
        Locale::De,
        Locale::Ja,
        Locale::ZhHans,
        Locale::ZhHant,
        Locale::Ko,
        Locale::Hi,
        Locale::Vi,
        Locale::Th,
        Locale::Id,
        Locale::Ar,
    ];

    /// BCP-47 tag — matches `backend/src/i18n.rs` and the Android picker.
    pub fn tag(self) -> &'static str {
        match self {
            Locale::En => "en",
            Locale::Es => "es",
            Locale::PtBr => "pt-BR",
            Locale::Fr => "fr",
            Locale::De => "de",
            Locale::Ja => "ja",
            Locale::ZhHans => "zh-Hans",
            Locale::ZhHant => "zh-Hant",
            Locale::Ko => "ko",
            Locale::Hi => "hi",
            Locale::Vi => "vi",
            Locale::Th => "th",
            Locale::Id => "id",
            Locale::Ar => "ar",
        }
    }

    /// The language's own name, shown in the picker (never translated).
    pub fn endonym(self) -> &'static str {
        match self {
            Locale::En => "English",
            Locale::Es => "Español",
            Locale::PtBr => "Português (Brasil)",
            Locale::Fr => "Français",
            Locale::De => "Deutsch",
            Locale::Ja => "日本語",
            Locale::ZhHans => "简体中文",
            Locale::ZhHant => "繁體中文",
            Locale::Ko => "한국어",
            Locale::Hi => "हिन्दी",
            Locale::Vi => "Tiếng Việt",
            Locale::Th => "ไทย",
            Locale::Id => "Bahasa Indonesia",
            Locale::Ar => "العربية",
        }
    }

    pub fn is_rtl(self) -> bool {
        matches!(self, Locale::Ar)
    }

    /// Resolve a stored/synced BCP-47 tag. Unknown/empty/`en` → English.
    /// `pt`/`in` are tolerated as legacy aliases (matches the backend).
    pub fn from_tag(tag: &str) -> Locale {
        match tag.trim() {
            "es" => Locale::Es,
            "pt-BR" | "pt" => Locale::PtBr,
            "fr" => Locale::Fr,
            "de" => Locale::De,
            "ja" => Locale::Ja,
            "zh-Hans" => Locale::ZhHans,
            "zh-Hant" => Locale::ZhHant,
            "ko" => Locale::Ko,
            "hi" => Locale::Hi,
            "vi" => Locale::Vi,
            "th" => Locale::Th,
            "id" | "in" => Locale::Id,
            "ar" => Locale::Ar,
            _ => Locale::En,
        }
    }

    /// Best-effort match of a browser `navigator.language` value (e.g. "es-ES",
    /// "zh-CN", "pt") to a shipped locale, falling back to the primary subtag.
    fn from_browser(lang: &str) -> Locale {
        let lower = lang.trim().to_ascii_lowercase();
        // Chinese needs script disambiguation the plain tag map can't do
        // (browsers report region — zh-CN/zh-TW — not the zh-Hans/zh-Hant script).
        if lower.starts_with("zh") {
            if lower.contains("hant")
                || lower.contains("tw")
                || lower.contains("hk")
                || lower.contains("mo")
            {
                return Locale::ZhHant;
            }
            return Locale::ZhHans;
        }
        if lower.starts_with("pt") {
            return Locale::PtBr;
        }
        let primary = lower.split(['-', '_']).next().unwrap_or("");
        Locale::from_tag(primary)
    }

    fn catalog_json(self) -> &'static str {
        match self {
            Locale::En => include_str!("../locales/en.json"),
            Locale::Es => include_str!("../locales/es.json"),
            Locale::PtBr => include_str!("../locales/pt-BR.json"),
            Locale::Fr => include_str!("../locales/fr.json"),
            Locale::De => include_str!("../locales/de.json"),
            Locale::Ja => include_str!("../locales/ja.json"),
            Locale::ZhHans => include_str!("../locales/zh-Hans.json"),
            Locale::ZhHant => include_str!("../locales/zh-Hant.json"),
            Locale::Ko => include_str!("../locales/ko.json"),
            Locale::Hi => include_str!("../locales/hi.json"),
            Locale::Vi => include_str!("../locales/vi.json"),
            Locale::Th => include_str!("../locales/th.json"),
            Locale::Id => include_str!("../locales/id.json"),
            Locale::Ar => include_str!("../locales/ar.json"),
        }
    }
}

thread_local! {
    /// Parsed catalogs, memoized per locale. WASM is single-threaded, so a
    /// `thread_local` `RefCell` is the simplest safe cache. At most two entries
    /// are ever populated (the active locale plus the English fallback).
    static CATALOGS: RefCell<HashMap<&'static str, HashMap<String, String>>> =
        RefCell::new(HashMap::new());
}

fn lookup(loc: Locale, key: &str) -> Option<String> {
    CATALOGS.with(|c| {
        let mut cache = c.borrow_mut();
        let catalog = cache
            .entry(loc.tag())
            .or_insert_with(|| serde_json::from_str(loc.catalog_json()).unwrap_or_default());
        catalog.get(key).cloned()
    })
}

#[derive(Clone, Copy)]
pub struct I18nContext {
    pub locale: RwSignal<Locale>,
}

/// Read the current locale reactively. Returns English before the context is
/// provided, which never happens in practice — `provide_i18n()` runs first in
/// `App`.
pub fn current_locale() -> Locale {
    use_context::<I18nContext>()
        .map(|c| c.locale.get())
        .unwrap_or(Locale::En)
}

/// Translate a key against the active locale, falling back to English, then to
/// the key itself (so a missing key is visible rather than silently blank).
/// Reactive: call inside a view closure — `{move || t("nav.overview")}` — so it
/// re-runs when the locale changes.
pub fn t(key: &str) -> String {
    let loc = current_locale();
    if let Some(v) = lookup(loc, key) {
        return v;
    }
    if loc != Locale::En {
        if let Some(v) = lookup(Locale::En, key) {
            return v;
        }
    }
    key.to_string()
}

/// Like [`t`], with `{name}`-style placeholder substitution. Part of the i18n
/// foundation; the first callers arrive with the interpolated strings in the
/// 13.3 page sweep (e.g. "{count} left"), so it is unused in the proof slice.
#[allow(dead_code)]
pub fn t_args(key: &str, args: &[(&str, &str)]) -> String {
    let mut s = t(key);
    for (name, value) in args {
        s = s.replace(&format!("{{{name}}}"), value);
    }
    s
}

/// Install the reactive locale context. Initial value: saved choice → browser
/// language → English. A synced server pref (via [`adopt`]) overrides this once
/// `/auth/me` resolves.
pub fn provide_i18n() {
    let locale = RwSignal::new(resolve_initial());
    provide_context(I18nContext { locale });

    Effect::new(move |_| {
        let loc = locale.get();
        apply_document(loc);
        let _ = LocalStorage::set(KEY, loc.tag());
    });
}

pub fn use_i18n() -> I18nContext {
    expect_context::<I18nContext>()
}

/// Apply a server-synced language tag to the given context, if it differs from
/// the current choice. Takes the context by value so it is safe to call from a
/// `spawn_local` future, where `use_context` would run outside the owner.
pub fn adopt(ctx: Option<I18nContext>, tag: Option<&str>) {
    if let (Some(ctx), Some(tag)) = (ctx, tag) {
        let tag = tag.trim();
        if tag.is_empty() {
            return;
        }
        let loc = Locale::from_tag(tag);
        if loc != ctx.locale.get_untracked() {
            ctx.locale.set(loc);
        }
    }
}

fn resolve_initial() -> Locale {
    if let Ok(saved) = LocalStorage::get::<String>(KEY) {
        if !saved.trim().is_empty() {
            return Locale::from_tag(&saved);
        }
    }
    if let Some(lang) = browser_language() {
        return Locale::from_browser(&lang);
    }
    Locale::En
}

fn browser_language() -> Option<String> {
    web_sys::window()
        .and_then(|w| w.navigator().language())
        .filter(|s| !s.is_empty())
}

/// Reflect the locale onto `<html lang dir>` for accessibility + RTL layout.
fn apply_document(loc: Locale) {
    if let Some(el) = web_sys::window()
        .and_then(|w| w.document())
        .and_then(|d| d.document_element())
    {
        let _ = el.set_attribute("lang", loc.tag());
        let _ = el.set_attribute("dir", if loc.is_rtl() { "rtl" } else { "ltr" });
    }
}
