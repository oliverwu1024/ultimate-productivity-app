//! §13.2 — server-side language selection.
//!
//! The user's chosen app language rides the existing `preferences` JSONB blob
//! under the key `app_language`, stored as a BCP-47 tag that mirrors the
//! Android picker in `android/.../ui/settings/LanguageCard.kt`
//! (`"" | en | es | pt-BR | fr | de | ja | zh-Hans | zh-Hant | ko | hi | vi |
//! th | id | ar`). An empty tag means "follow the device" and is treated as
//! English on the server.
//!
//! Two consumers: AI calls get a "respond in {language}" directive so Coach
//! replies, weekly insights, anomaly bodies and sleep ratings come back in the
//! user's language; server-built fixed strings (the anomaly push title) get a
//! localized catalog. Nothing here ever fails its caller — any miss (no row,
//! no pref, DB error, unknown tag) degrades silently to English.

use sqlx::PgPool;
use uuid::Uuid;

/// English name of a stored BCP-47 language tag, used inside AI "respond in X"
/// directives and to key the fixed-string catalogs below. Unknown / empty /
/// `en` → `"English"`. Keep in sync with `LanguageCard.kt`'s `LANGUAGES` list.
/// `pt`/`in` are tolerated as legacy aliases of `pt-BR`/`id`.
pub fn language_name(tag: &str) -> &'static str {
    match tag {
        "es" => "Spanish",
        "pt-BR" | "pt" => "Brazilian Portuguese",
        "fr" => "French",
        "de" => "German",
        "ja" => "Japanese",
        "zh-Hans" => "Simplified Chinese",
        "zh-Hant" => "Traditional Chinese",
        "ko" => "Korean",
        "hi" => "Hindi",
        "vi" => "Vietnamese",
        "th" => "Thai",
        "id" | "in" => "Indonesian",
        "ar" => "Arabic",
        _ => "English",
    }
}

/// Load a user's language name straight from the DB. Any miss degrades to
/// `"English"`; a language lookup must never break the AI / email flow it
/// feeds, so all errors collapse to the default.
pub async fn user_language_name(pool: &PgPool, user_id: Uuid) -> &'static str {
    let tag: Option<String> = sqlx::query_scalar::<_, Option<String>>(
        "SELECT preferences->>'app_language' FROM users WHERE id = $1",
    )
    .bind(user_id)
    .fetch_optional(pool)
    .await
    .ok()
    .flatten() // row present?
    .flatten(); // column non-null?
    language_name(tag.as_deref().unwrap_or("").trim())
}

/// Same as [`user_language_name`] but keyed by email address — the
/// transactional-email flows (verify / reset) only have the recipient's
/// address in hand, not their id. `email` is the unique login identity, so
/// this is a single indexed lookup. Degrades to English on any miss (which is
/// also the reality for a brand-new signup whose language hasn't synced yet).
pub async fn user_language_name_by_email(pool: &PgPool, email: &str) -> &'static str {
    let tag: Option<String> = sqlx::query_scalar::<_, Option<String>>(
        "SELECT preferences->>'app_language' FROM users WHERE email = $1",
    )
    .bind(email)
    .fetch_optional(pool)
    .await
    .ok()
    .flatten()
    .flatten();
    language_name(tag.as_deref().unwrap_or("").trim())
}

/// Trailing system directive that forces the model's output language. Returns
/// `None` for English so English requests keep a byte-identical prompt (full
/// prompt-cache hit, no extra system block). Added *after* the cache
/// breakpoint by callers, so the big cached prefix is shared across all
/// languages. The format-preservation clause keeps strict-output calls (the
/// anomaly JSON verdict, the sleep-rating `RATING|REASONING` line) intact —
/// only the human-readable prose is translated.
pub fn respond_in_directive(language: &str) -> Option<String> {
    if language == "English" {
        return None;
    }
    Some(format!(
        "Write your entire response to the user in {language}, regardless of the language the \
         user writes in. Ultiq feature names (Ultiq, Focus, Streak, Coach, Snore, Cough, \
         Sleep-talk, Nap, Pickup) stay as-is. Any output format, structure, labels or enum \
         values required above still apply exactly — translate only the human-readable prose."
    ))
}

/// Localized title for the anomaly ("we spotted a pattern") push notification.
/// The body is the AI-generated `reason`, already in the user's language via
/// [`respond_in_directive`]. Falls back to English for any unmapped name.
pub fn anomaly_push_title(language: &str) -> &'static str {
    match language {
        "Spanish" => "Atención: Ultiq detectó un patrón",
        "Brazilian Portuguese" => "Atenção: o Ultiq notou um padrão",
        "French" => "Attention : Ultiq a repéré une tendance",
        "German" => "Achtung: Ultiq hat ein Muster erkannt",
        "Japanese" => "お知らせ — Ultiq がパターンを検知しました",
        "Simplified Chinese" => "提醒 — Ultiq 发现了一个规律",
        "Traditional Chinese" => "提醒 — Ultiq 發現了一個規律",
        "Korean" => "알림 — Ultiq가 패턴을 발견했어요",
        "Hindi" => "ध्यान दें — Ultiq ने एक पैटर्न देखा",
        "Vietnamese" => "Lưu ý — Ultiq phát hiện một xu hướng",
        "Thai" => "แจ้งเตือน — Ultiq พบรูปแบบบางอย่าง",
        "Indonesian" => "Perhatian — Ultiq menemukan sebuah pola",
        "Arabic" => "تنبيه — رصد Ultiq نمطًا",
        _ => "Heads up — Ultiq spotted a pattern",
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn known_tags_map_to_names() {
        assert_eq!(language_name("ja"), "Japanese");
        assert_eq!(language_name("zh-Hans"), "Simplified Chinese");
        assert_eq!(language_name("pt-BR"), "Brazilian Portuguese");
        assert_eq!(language_name("id"), "Indonesian");
        assert_eq!(language_name("in"), "Indonesian"); // legacy alias
    }

    #[test]
    fn unknown_empty_and_english_default_to_english() {
        assert_eq!(language_name(""), "English");
        assert_eq!(language_name("en"), "English");
        assert_eq!(language_name("xx-YY"), "English");
    }

    #[test]
    fn english_gets_no_directive_but_others_do() {
        assert!(respond_in_directive("English").is_none());
        let ja = respond_in_directive("Japanese").expect("non-English → directive");
        assert!(ja.contains("Japanese"));
    }

    #[test]
    fn anomaly_title_falls_back_to_english() {
        assert_eq!(
            anomaly_push_title("Klingon"),
            "Heads up — Ultiq spotted a pattern"
        );
        assert!(anomaly_push_title("Japanese").contains("Ultiq"));
    }
}
