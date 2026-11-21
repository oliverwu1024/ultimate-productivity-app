//! §13.2 — localized transactional email copy (email verification + password
//! reset). Only the handful of human phrases per email are translated; the
//! HTML skeleton (brand colors, button, code block) is shared and assembled
//! once in [`render`]. "Ultiq" is never translated (glossary rule).
//!
//! Language is a [`Language`] enum value; every catalog matches on it
//! exhaustively (English is the base variant), so a new language can't be added
//! without giving it copy here. Copy is deliberately kept short and
//! link-forward — these are utility emails, not marketing.

use crate::i18n::Language;

/// The translatable phrases for one transactional email. The sign-off
/// (`"— Ultiq"`) is identical across languages, so it lives in [`render`]
/// rather than here.
struct EmailCopy {
    subject: &'static str,
    greeting: &'static str,
    /// The "tap the link to do X, because Y" sentence, ending with a colon.
    /// Says "link" (not "button") so it reads correctly in the plaintext part,
    /// which has no button — only the raw URL.
    body: &'static str,
    button: &'static str,
    /// The "if the button doesn't work, use this link" line, ending with a colon.
    fallback: &'static str,
    /// Expiry window + the "if you didn't ask for this, ignore it" reassurance.
    expiry: &'static str,
}

/// Assemble the `(subject, plain_text, html)` triple an [`crate::email::EmailClient`]
/// send expects.
fn render(copy: &EmailCopy, link: &str) -> (String, String, String) {
    let text = format!(
        "{greeting}\n\n{body}\n\n{link}\n\n{expiry}\n\n— Ultiq",
        greeting = copy.greeting,
        body = copy.body,
        link = link,
        expiry = copy.expiry,
    );
    let html = format!(
        "<div style=\"font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;color:#2A1B6E;line-height:1.55;\">\
         <p>{greeting}</p>\
         <p>{body}</p>\
         <p style=\"margin:24px 0\"><a href=\"{link}\" style=\"display:inline-block;padding:12px 28px;background:#2A1B6E;color:#FFF4E6;border-radius:24px;text-decoration:none;font-weight:600;\">{button}</a></p>\
         <p style=\"font-size:14px;color:#2A1B6Eaa\">{fallback}<br><code style=\"background:#FFF4E6;padding:4px 8px;border-radius:4px;\">{link}</code></p>\
         <p style=\"font-size:14px;color:#2A1B6Eaa\">{expiry}</p>\
         <p style=\"margin-top:32px;color:#2A1B6E\">— Ultiq</p></div>",
        greeting = copy.greeting,
        body = copy.body,
        link = link,
        button = copy.button,
        fallback = copy.fallback,
        expiry = copy.expiry,
    );
    (copy.subject.to_string(), text, html)
}

/// Localized email-verification message. `link` is the tap-to-verify URL.
pub fn verify_email(language: Language, link: &str) -> (String, String, String) {
    render(&verify_copy(language), link)
}

/// Localized password-reset message. `link` is the tap-to-reset URL.
pub fn reset_email(language: Language, link: &str) -> (String, String, String) {
    render(&reset_copy(language), link)
}

fn verify_copy(language: Language) -> EmailCopy {
    match language {
        Language::SimplifiedChinese => EmailCopy {
            subject: "验证你的 Ultiq 邮箱",
            greeting: "欢迎使用 Ultiq！",
            body: "点击下方链接验证你的电子邮箱地址。这可以保护你的账户，并让你在忘记密码时能够重置密码：",
            button: "验证邮箱",
            fallback: "如果按钮无法使用，请将此链接粘贴到浏览器中：",
            expiry: "此链接将在 24 小时后失效。如果你没有注册 Ultiq，可以放心忽略此邮件。",
        },
        Language::TraditionalChinese => EmailCopy {
            subject: "驗證你的 Ultiq 電子郵件",
            greeting: "歡迎使用 Ultiq！",
            body: "點擊下方連結驗證你的電子郵件地址。這可以保護你的帳戶，並讓你在忘記密碼時能夠重設密碼：",
            button: "驗證電子郵件",
            fallback: "如果按鈕無法使用，請將此連結貼到瀏覽器中：",
            expiry: "此連結將在 24 小時後失效。如果你沒有註冊 Ultiq，可以放心忽略這封郵件。",
        },
        Language::English => EmailCopy {
            subject: "Verify your Ultiq email",
            greeting: "Welcome to Ultiq!",
            body: "Tap the link below to verify your email address. This protects your account and lets you reset your password if you ever forget it:",
            button: "Verify email",
            fallback: "If the button doesn't work, paste this link into your browser:",
            expiry: "This link expires in 24 hours. If you didn't sign up for Ultiq, you can safely ignore this email.",
        },
    }
}

fn reset_copy(language: Language) -> EmailCopy {
    match language {
        Language::SimplifiedChinese => EmailCopy {
            subject: "重置你的 Ultiq 密码",
            greeting: "你好，",
            body: "我们收到了重置你 Ultiq 密码的请求。点击下方链接设置新密码：",
            button: "重置密码",
            fallback: "如果按钮无法打开应用，请将此链接复制到 Ultiq 中：",
            expiry: "此链接将在 1 小时后失效。如果你没有请求重置，可以放心忽略此邮件。",
        },
        Language::TraditionalChinese => EmailCopy {
            subject: "重設你的 Ultiq 密碼",
            greeting: "你好，",
            body: "我們收到了重設你 Ultiq 密碼的請求。點擊下方連結設定新密碼：",
            button: "重設密碼",
            fallback: "如果按鈕無法開啟應用程式，請將此連結複製到 Ultiq 中：",
            expiry: "此連結將在 1 小時後失效。如果你沒有要求重設，可以放心忽略這封郵件。",
        },
        Language::English => EmailCopy {
            subject: "Reset your Ultiq password",
            greeting: "Hi,",
            body: "We received a request to reset your Ultiq password. Tap the link below to choose a new password:",
            button: "Reset password",
            fallback: "If the button doesn't open the app, copy this link into Ultiq:",
            expiry: "This link expires in 1 hour. If you didn't request a reset, you can safely ignore this email.",
        },
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn english_default_matches_the_original_copy() {
        let (subject, text, html) = verify_email(Language::English, "https://x/y?token=abc");
        assert_eq!(subject, "Verify your Ultiq email");
        assert!(text.contains("Welcome to Ultiq!"));
        assert!(text.contains("https://x/y?token=abc"));
        assert!(html.contains(">Verify email</a>"));
        // Sign-off is shared, not per-language.
        assert!(text.trim_end().ends_with("— Ultiq"));
    }

    #[test]
    fn unknown_tag_falls_back_to_english() {
        // An unrecognised tag resolves to Language::English via from_tag.
        let (subject, _, _) = reset_email(Language::from_tag("klingon"), "https://x");
        assert_eq!(subject, "Reset your Ultiq password");
    }

    #[test]
    fn localized_chinese_keeps_brand_name() {
        let (subject, _text, html) = reset_email(Language::TraditionalChinese, "https://x/y");
        assert!(subject.contains("Ultiq")); // brand never translated
        assert!(html.contains("https://x/y"));
    }

    #[test]
    fn localized_language_translates_subject() {
        let (subject, _, _) = verify_email(Language::SimplifiedChinese, "https://x");
        assert!(subject.contains("Ultiq"));
        assert_ne!(subject, "Verify your Ultiq email");
    }

    #[test]
    fn link_is_present_in_both_bodies_for_every_language() {
        for lang in Language::ALL {
            for (_s, text, html) in [verify_email(lang, "LINKMARK"), reset_email(lang, "LINKMARK")] {
                assert!(text.contains("LINKMARK"), "text missing link for {lang:?}");
                assert!(html.contains("LINKMARK"), "html missing link for {lang:?}");
            }
        }
    }

    /// Content drift guard: every non-English variant must localize BOTH
    /// subjects. (Exhaustiveness is compiler-enforced now; this catches a
    /// subject accidentally left as the English string.)
    #[test]
    fn every_language_localizes_both_subjects() {
        let en_verify = verify_email(Language::English, "x").0;
        let en_reset = reset_email(Language::English, "x").0;
        for lang in Language::ALL {
            if lang == Language::English {
                continue;
            }
            assert_ne!(
                verify_email(lang, "x").0,
                en_verify,
                "{lang:?}: verify subject not localized"
            );
            assert_ne!(
                reset_email(lang, "x").0,
                en_reset,
                "{lang:?}: reset subject not localized"
            );
        }
    }
}
