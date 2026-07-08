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

use std::cell::{Cell, RefCell};
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

    /// Closest `chrono` locale, for locale-aware month/weekday names via
    /// `format_localized` — so dates don't need hand-translated catalogs.
    pub fn chrono(self) -> chrono::Locale {
        match self {
            Locale::En => chrono::Locale::en_US,
            Locale::Es => chrono::Locale::es_ES,
            Locale::PtBr => chrono::Locale::pt_BR,
            Locale::Fr => chrono::Locale::fr_FR,
            Locale::De => chrono::Locale::de_DE,
            Locale::Ja => chrono::Locale::ja_JP,
            Locale::ZhHans => chrono::Locale::zh_CN,
            Locale::ZhHant => chrono::Locale::zh_TW,
            Locale::Ko => chrono::Locale::ko_KR,
            Locale::Hi => chrono::Locale::hi_IN,
            Locale::Vi => chrono::Locale::vi_VN,
            Locale::Th => chrono::Locale::th_TH,
            Locale::Id => chrono::Locale::id_ID,
            Locale::Ar => chrono::Locale::ar_SA,
        }
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

thread_local! {
    /// Untracked mirror of the active locale, kept in sync by `provide_i18n`'s
    /// Effect. Lets `tu()` / `current_locale_untracked()` resolve the locale
    /// from event handlers and `spawn_local` futures — where `use_context` runs
    /// outside the reactive owner and returns `None` (→ silent English). WASM is
    /// single-threaded, so a `Cell` is safe (same assumption as `CATALOGS`).
    static ACTIVE_LOCALE: Cell<Locale> = Cell::new(Locale::En);
}

fn set_active_locale(loc: Locale) {
    ACTIVE_LOCALE.with(|c| c.set(loc));
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

/// Read the current locale **untracked** (no subscription) — for capturing at
/// render inside a reactive owner (e.g. a dialog component body) without making
/// that owner re-run on a language switch.
pub fn current_locale_untracked() -> Locale {
    ACTIVE_LOCALE.with(|c| c.get())
}

/// Owner-free lookup with the active→English→raw-key fallback chain. Split out
/// so the reactive and untracked entry points share one implementation and it
/// is unit-testable without a Leptos runtime.
fn translate(loc: Locale, key: &str) -> String {
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

/// `{name}`-style placeholder substitution. Ordered replace: a substituted
/// value is not re-scanned, so pass any value that could itself contain a
/// `{token}` (e.g. server error text) in the LAST position.
fn subst(mut s: String, args: &[(&str, &str)]) -> String {
    for (name, value) in args {
        s = s.replace(&format!("{{{name}}}"), value);
    }
    s
}

/// Translate a key against the active locale, falling back to English, then to
/// the key itself (so a missing key is visible rather than silently blank).
/// Reactive: call inside a view closure — `{move || t("nav.overview")}` — so it
/// re-runs when the locale changes.
pub fn t(key: &str) -> String {
    translate(current_locale(), key)
}

/// Like [`t`], with `{name}`-style placeholder substitution.
pub fn t_args(key: &str, args: &[(&str, &str)]) -> String {
    subst(t(key), args)
}

/// Non-reactive translate: reads the active locale from the untracked
/// `thread_local` mirror (not the reactive context), so it resolves correctly
/// from event handlers and `spawn_local` futures — where `use_context` runs
/// outside the owner and would silently fall back to English. Does not
/// subscribe, so a value rendered straight from `tu()` won't re-run on a live
/// language switch (fine for handlers/futures and one-shot render helpers).
pub fn tu(key: &str) -> String {
    translate(current_locale_untracked(), key)
}

/// Install the reactive locale context. Initial value: saved choice → browser
/// language → English. A synced server pref (via [`adopt`]) overrides this once
/// `/auth/me` resolves.
pub fn provide_i18n() {
    let initial = resolve_initial();
    // Seed the untracked mirror eagerly so `tu()` is correct even before this
    // Effect first runs (Effects run on a later microtask).
    set_active_locale(initial);
    let locale = RwSignal::new(initial);
    provide_context(I18nContext { locale });

    Effect::new(move |_| {
        let loc = locale.get();
        set_active_locale(loc);
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

#[cfg(test)]
mod tests {
    //! Pure-logic tests — no Leptos runtime / no browser. Run on the host with
    //! `cargo test` (see the `web-dashboard` CI job). They lock the i18n
    //! contract the compiler and `trunk build` cannot: tag mapping, the
    //! fallback chain, placeholder substitution, and catalog integrity.
    use super::*;

    const ALL: [Locale; 14] = [
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

    #[test]
    fn from_tag_maps_shipped_tags_and_legacy_aliases() {
        assert_eq!(Locale::from_tag("es"), Locale::Es);
        assert_eq!(Locale::from_tag("pt-BR"), Locale::PtBr);
        assert_eq!(Locale::from_tag("pt"), Locale::PtBr); // legacy alias
        assert_eq!(Locale::from_tag("zh-Hans"), Locale::ZhHans);
        assert_eq!(Locale::from_tag("zh-Hant"), Locale::ZhHant);
        assert_eq!(Locale::from_tag("id"), Locale::Id);
        assert_eq!(Locale::from_tag("in"), Locale::Id); // legacy Indonesian code
        assert_eq!(Locale::from_tag("ar"), Locale::Ar);
        assert_eq!(Locale::from_tag("  fr  "), Locale::Fr); // trimmed
        assert_eq!(Locale::from_tag("qq"), Locale::En); // unknown → English
        assert_eq!(Locale::from_tag(""), Locale::En); // empty → English
    }

    #[test]
    fn tag_round_trips_through_from_tag() {
        for loc in ALL {
            assert_eq!(
                Locale::from_tag(loc.tag()),
                loc,
                "tag {:?} did not round-trip",
                loc.tag()
            );
        }
    }

    #[test]
    fn from_browser_resolves_region_and_script() {
        assert_eq!(Locale::from_browser("zh-CN"), Locale::ZhHans);
        assert_eq!(Locale::from_browser("zh"), Locale::ZhHans);
        assert_eq!(Locale::from_browser("zh-TW"), Locale::ZhHant);
        assert_eq!(Locale::from_browser("zh-HK"), Locale::ZhHant);
        assert_eq!(Locale::from_browser("zh-Hant"), Locale::ZhHant);
        assert_eq!(Locale::from_browser("pt-PT"), Locale::PtBr);
        assert_eq!(Locale::from_browser("es-ES"), Locale::Es);
        assert_eq!(Locale::from_browser("en_US"), Locale::En); // underscore + region
        assert_eq!(Locale::from_browser("de"), Locale::De);
        assert_eq!(Locale::from_browser("xx"), Locale::En);
    }

    #[test]
    fn is_rtl_only_for_arabic() {
        for loc in ALL {
            assert_eq!(loc.is_rtl(), loc == Locale::Ar);
        }
    }

    #[test]
    fn chrono_maps_without_panic_for_all() {
        for loc in ALL {
            let _ = loc.chrono();
        }
    }

    #[test]
    fn every_catalog_parses_and_matches_english_keys() {
        // Defense-in-depth beside scripts/i18n-web-check.sh: the include_str!-
        // bundled catalogs must be valid JSON with the exact English key set.
        let en: HashMap<String, String> =
            serde_json::from_str(Locale::En.catalog_json()).expect("en.json must parse");
        assert!(!en.is_empty());
        for loc in ALL {
            let cat: HashMap<String, String> = serde_json::from_str(loc.catalog_json())
                .unwrap_or_else(|e| panic!("{} catalog is invalid JSON: {e}", loc.tag()));
            assert_eq!(
                cat.len(),
                en.len(),
                "{} has {} keys, en has {}",
                loc.tag(),
                cat.len(),
                en.len()
            );
            for k in en.keys() {
                assert!(cat.contains_key(k), "{} missing key {k}", loc.tag());
            }
        }
    }

    #[test]
    fn translate_falls_back_english_then_raw_key() {
        assert_eq!(translate(Locale::En, "common.cancel"), "Cancel");
        assert_eq!(translate(Locale::Es, "common.cancel"), "Cancelar"); // real es value
        // A missing key surfaces the raw key (visible), never blank or a panic.
        assert_eq!(translate(Locale::Es, "no.such.key"), "no.such.key");
    }

    #[test]
    fn subst_replaces_all_and_keeps_unknown_tokens() {
        assert_eq!(subst("hi {name}".into(), &[("name", "Sam")]), "hi Sam");
        // repeated token → every occurrence replaced
        assert_eq!(subst("{x}-{x}".into(), &[("x", "9")]), "9-9");
        // missing arg → token left literal (surfaces the mistake instead of blanking)
        assert_eq!(subst("{count} left".into(), &[("wrong", "3")]), "{count} left");
    }
}
