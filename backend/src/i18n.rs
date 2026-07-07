//! §13.2 — server-side language selection.
//!
//! The user's chosen app language rides the existing `preferences` JSONB blob
//! under the key `app_language`, stored as a BCP-47 tag that mirrors the
//! Android picker in `android/.../ui/settings/LanguageCard.kt`
//! (`"" | en | es | pt-BR | fr | de | ja | zh-Hans | zh-Hant | ko | hi | vi |
//! th | id | ar`). An empty tag means "follow the device" and is treated as
//! English on the server.
//!
//! [`Language`] is the single source of truth for which languages we localize
//! into. Every catalog (the AI "respond in X" directive, the anomaly push
//! title, the transactional emails) matches on it **exhaustively**, so adding
//! or renaming a language is a compile error until every catalog is updated —
//! there is no stringly-typed name that can silently drift to an English
//! fallback. Nothing here ever fails its caller: any miss (no row, no pref, DB
//! error, unknown tag) resolves to [`Language::English`].

use sqlx::PgPool;
use uuid::Uuid;

/// Every language Ultiq localizes into (English is the base). The variant set
/// is the single source of truth; downstream catalogs match on it exhaustively.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Language {
    English,
    Spanish,
    BrazilianPortuguese,
    French,
    German,
    Japanese,
    SimplifiedChinese,
    TraditionalChinese,
    Korean,
    Hindi,
    Vietnamese,
    Thai,
    Indonesian,
    Arabic,
}

impl Language {
    /// All variants (English first), for exhaustive iteration in tests. Gated
    /// to `test` since nothing at runtime enumerates languages yet — drop the
    /// gate the day a "supported languages" surface needs it.
    #[cfg(test)]
    pub const ALL: [Language; 14] = [
        Language::English,
        Language::Spanish,
        Language::BrazilianPortuguese,
        Language::French,
        Language::German,
        Language::Japanese,
        Language::SimplifiedChinese,
        Language::TraditionalChinese,
        Language::Korean,
        Language::Hindi,
        Language::Vietnamese,
        Language::Thai,
        Language::Indonesian,
        Language::Arabic,
    ];

    /// Resolve a stored BCP-47 tag (from the Android picker) to a language.
    /// Unknown / empty / `en` → [`Language::English`]. `pt`/`in` are tolerated
    /// as legacy aliases of `pt-BR`/`id`. Keep in sync with `LanguageCard.kt`.
    pub fn from_tag(tag: &str) -> Language {
        match tag.trim() {
            "es" => Language::Spanish,
            "pt-BR" | "pt" => Language::BrazilianPortuguese,
            "fr" => Language::French,
            "de" => Language::German,
            "ja" => Language::Japanese,
            "zh-Hans" => Language::SimplifiedChinese,
            "zh-Hant" => Language::TraditionalChinese,
            "ko" => Language::Korean,
            "hi" => Language::Hindi,
            "vi" => Language::Vietnamese,
            "th" => Language::Thai,
            "id" | "in" => Language::Indonesian,
            "ar" => Language::Arabic,
            _ => Language::English,
        }
    }

    /// English name of the language, used inside AI "respond in X" directives.
    pub fn english_name(self) -> &'static str {
        match self {
            Language::English => "English",
            Language::Spanish => "Spanish",
            Language::BrazilianPortuguese => "Brazilian Portuguese",
            Language::French => "French",
            Language::German => "German",
            Language::Japanese => "Japanese",
            Language::SimplifiedChinese => "Simplified Chinese",
            Language::TraditionalChinese => "Traditional Chinese",
            Language::Korean => "Korean",
            Language::Hindi => "Hindi",
            Language::Vietnamese => "Vietnamese",
            Language::Thai => "Thai",
            Language::Indonesian => "Indonesian",
            Language::Arabic => "Arabic",
        }
    }
}

/// Load a user's language straight from the DB. Any miss degrades to
/// [`Language::English`]; a language lookup must never break the AI / email
/// flow it feeds, so all errors collapse to the default (with a debug
/// breadcrumb so a DB failure isn't indistinguishable from "picked English").
pub async fn user_language(pool: &PgPool, user_id: Uuid) -> Language {
    let tag: Option<String> = match sqlx::query_scalar::<_, Option<String>>(
        "SELECT preferences->>'app_language' FROM users WHERE id = $1",
    )
    .bind(user_id)
    .fetch_optional(pool)
    .await
    {
        Ok(row) => row.flatten(),
        Err(e) => {
            tracing::debug!(target: "i18n", "app_language lookup by id failed ({e}); defaulting to English");
            None
        }
    };
    Language::from_tag(tag.as_deref().unwrap_or(""))
}

/// Same as [`user_language`] but keyed by email address — the transactional-
/// email flows (verify / reset) only have the recipient's address in hand, not
/// their id. `email` is the unique login identity, so this is a single indexed
/// lookup. Degrades to English on any miss (which is also the reality for a
/// brand-new signup whose language hasn't synced yet).
pub async fn user_language_by_email(pool: &PgPool, email: &str) -> Language {
    let tag: Option<String> = match sqlx::query_scalar::<_, Option<String>>(
        "SELECT preferences->>'app_language' FROM users WHERE email = $1",
    )
    .bind(email)
    .fetch_optional(pool)
    .await
    {
        Ok(row) => row.flatten(),
        Err(e) => {
            tracing::debug!(target: "i18n", "app_language lookup by email failed ({e}); defaulting to English");
            None
        }
    };
    Language::from_tag(tag.as_deref().unwrap_or(""))
}

/// Trailing system directive that forces the model's output language. Returns
/// `None` for English so English requests keep a byte-identical prompt (full
/// prompt-cache hit, no extra system block). Added *after* the cache breakpoint
/// by callers, so the big cached prefix is shared across all languages. The
/// format-preservation clause keeps strict-output calls (the anomaly JSON
/// verdict, the sleep-rating `RATING|REASONING` line) intact — only the
/// human-readable prose is translated.
pub fn respond_in_directive(language: Language) -> Option<String> {
    if language == Language::English {
        return None;
    }
    let name = language.english_name();
    Some(format!(
        "Write your entire response to the user in {name}, regardless of the language the \
         user writes in. Ultiq feature names (Ultiq, Focus, Streak, Coach, Snore, Cough, \
         Sleep-talk, Nap, Pickup) stay as-is. Any output format, structure, labels or enum \
         values required above still apply exactly — translate only the human-readable prose."
    ))
}

/// Localized title for the anomaly ("we spotted a pattern") push notification.
/// The body is the AI-generated `reason`, already in the user's language via
/// [`respond_in_directive`]. Exhaustive match — a new [`Language`] variant
/// won't compile until it is given a title here.
pub fn anomaly_push_title(language: Language) -> &'static str {
    match language {
        Language::English => "Heads up — Ultiq spotted a pattern",
        Language::Spanish => "Atención: Ultiq detectó un patrón",
        Language::BrazilianPortuguese => "Atenção: o Ultiq notou um padrão",
        Language::French => "Attention : Ultiq a repéré une tendance",
        Language::German => "Achtung: Ultiq hat ein Muster erkannt",
        Language::Japanese => "お知らせ — Ultiq がパターンを検知しました",
        Language::SimplifiedChinese => "提醒 — Ultiq 发现了一个规律",
        Language::TraditionalChinese => "提醒 — Ultiq 發現了一個規律",
        Language::Korean => "알림 — Ultiq가 패턴을 발견했어요",
        Language::Hindi => "ध्यान दें — Ultiq ने एक पैटर्न देखा",
        Language::Vietnamese => "Lưu ý — Ultiq phát hiện một xu hướng",
        Language::Thai => "แจ้งเตือน — Ultiq พบรูปแบบบางอย่าง",
        Language::Indonesian => "Perhatian — Ultiq menemukan sebuah pola",
        Language::Arabic => "تنبيه — رصد Ultiq نمطًا",
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn known_tags_map_to_variants() {
        assert_eq!(Language::from_tag("ja"), Language::Japanese);
        assert_eq!(Language::from_tag("zh-Hans"), Language::SimplifiedChinese);
        assert_eq!(Language::from_tag("pt-BR"), Language::BrazilianPortuguese);
        assert_eq!(Language::from_tag("id"), Language::Indonesian);
        assert_eq!(Language::from_tag("in"), Language::Indonesian); // legacy alias
        assert_eq!(Language::from_tag("  ja  "), Language::Japanese); // trimmed
    }

    #[test]
    fn unknown_empty_and_english_default_to_english() {
        assert_eq!(Language::from_tag(""), Language::English);
        assert_eq!(Language::from_tag("en"), Language::English);
        assert_eq!(Language::from_tag("xx-YY"), Language::English);
    }

    #[test]
    fn english_gets_no_directive_but_others_do() {
        assert!(respond_in_directive(Language::English).is_none());
        let ja = respond_in_directive(Language::Japanese).expect("non-English → directive");
        assert!(ja.contains("Japanese"));
    }

    /// Content drift guard: every non-English variant must have a distinct,
    /// non-English push title AND a respond-in directive. (Exhaustiveness is
    /// already enforced by the compiler — this catches a wrong/duplicated
    /// value, e.g. a title accidentally left as the English string.)
    #[test]
    fn all_non_english_variants_are_localized() {
        let english_title = anomaly_push_title(Language::English);
        for lang in Language::ALL {
            if lang == Language::English {
                continue;
            }
            assert_ne!(
                anomaly_push_title(lang),
                english_title,
                "{lang:?}: push title not localized"
            );
            assert!(
                respond_in_directive(lang).is_some(),
                "{lang:?}: no respond-in directive"
            );
        }
    }
}
