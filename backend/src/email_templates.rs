//! §13.2 — localized transactional email copy (email verification + password
//! reset). Only the handful of human phrases per email are translated; the
//! HTML skeleton (brand colors, button, code block) is shared and assembled
//! once in [`render`]. "Ultiq" is never translated (glossary rule). Arabic
//! renders right-to-left.
//!
//! Language is the English name produced by [`crate::i18n::language_name`], so
//! an unmapped / English user falls through to the English default arm. Copy
//! is deliberately kept short and link-forward — these are utility emails, not
//! marketing.

/// The translatable phrases for one transactional email. The sign-off
/// (`"— Ultiq"`) is identical across languages, so it lives in [`render`]
/// rather than here.
struct EmailCopy {
    subject: &'static str,
    greeting: &'static str,
    /// The "tap the button to do X, because Y" sentence, ending with a colon.
    body: &'static str,
    button: &'static str,
    /// The "if the button doesn't work, use this link" line, ending with a colon.
    fallback: &'static str,
    /// Expiry window + the "if you didn't ask for this, ignore it" reassurance.
    expiry: &'static str,
}

/// Assemble the `(subject, plain_text, html)` triple an [`crate::email::EmailClient`]
/// send expects. `rtl` flips the HTML container to right-to-left (Arabic).
fn render(copy: &EmailCopy, link: &str, rtl: bool) -> (String, String, String) {
    let text = format!(
        "{greeting}\n\n{body}\n\n{link}\n\n{expiry}\n\n— Ultiq",
        greeting = copy.greeting,
        body = copy.body,
        link = link,
        expiry = copy.expiry,
    );
    let dir = if rtl { "direction:rtl;text-align:right;" } else { "" };
    let html = format!(
        "<div style=\"font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;color:#2A1B6E;line-height:1.55;{dir}\">\
         <p>{greeting}</p>\
         <p>{body}</p>\
         <p style=\"margin:24px 0\"><a href=\"{link}\" style=\"display:inline-block;padding:12px 28px;background:#2A1B6E;color:#FFF4E6;border-radius:24px;text-decoration:none;font-weight:600;\">{button}</a></p>\
         <p style=\"font-size:14px;color:#2A1B6Eaa\">{fallback}<br><code style=\"background:#FFF4E6;padding:4px 8px;border-radius:4px;\">{link}</code></p>\
         <p style=\"font-size:14px;color:#2A1B6Eaa\">{expiry}</p>\
         <p style=\"margin-top:32px;color:#2A1B6E\">— Ultiq</p></div>",
        dir = dir,
        greeting = copy.greeting,
        body = copy.body,
        link = link,
        button = copy.button,
        fallback = copy.fallback,
        expiry = copy.expiry,
    );
    (copy.subject.to_string(), text, html)
}

/// `true` only for the RTL scripts we ship (currently just Arabic).
fn is_rtl(language: &str) -> bool {
    language == "Arabic"
}

/// Localized email-verification message. `link` is the tap-to-verify URL.
pub fn verify_email(language: &str, link: &str) -> (String, String, String) {
    render(&verify_copy(language), link, is_rtl(language))
}

/// Localized password-reset message. `link` is the tap-to-reset URL.
pub fn reset_email(language: &str, link: &str) -> (String, String, String) {
    render(&reset_copy(language), link, is_rtl(language))
}

fn verify_copy(language: &str) -> EmailCopy {
    match language {
        "Spanish" => EmailCopy {
            subject: "Verifica tu correo de Ultiq",
            greeting: "¡Te damos la bienvenida a Ultiq!",
            body: "Toca el botón de abajo para verificar tu dirección de correo electrónico. Esto protege tu cuenta y te permite restablecer tu contraseña si alguna vez la olvidas:",
            button: "Verificar correo",
            fallback: "Si el botón no funciona, pega este enlace en tu navegador:",
            expiry: "Este enlace caduca en 24 horas. Si no te registraste en Ultiq, puedes ignorar este correo de forma segura.",
        },
        "Brazilian Portuguese" => EmailCopy {
            subject: "Verifique seu e-mail da Ultiq",
            greeting: "Boas-vindas à Ultiq!",
            body: "Toque no botão abaixo para verificar seu endereço de e-mail. Isso protege sua conta e permite redefinir sua senha caso você a esqueça:",
            button: "Verificar e-mail",
            fallback: "Se o botão não funcionar, cole este link no seu navegador:",
            expiry: "Este link expira em 24 horas. Se você não se cadastrou na Ultiq, pode ignorar este e-mail com segurança.",
        },
        "French" => EmailCopy {
            subject: "Vérifiez votre e-mail Ultiq",
            greeting: "Bienvenue sur Ultiq !",
            body: "Appuyez sur le bouton ci-dessous pour vérifier votre adresse e-mail. Cela protège votre compte et vous permet de réinitialiser votre mot de passe en cas d'oubli :",
            button: "Vérifier l'e-mail",
            fallback: "Si le bouton ne fonctionne pas, collez ce lien dans votre navigateur :",
            expiry: "Ce lien expire dans 24 heures. Si vous ne vous êtes pas inscrit sur Ultiq, vous pouvez ignorer cet e-mail en toute sécurité.",
        },
        "German" => EmailCopy {
            subject: "Bestätige deine Ultiq-E-Mail",
            greeting: "Willkommen bei Ultiq!",
            body: "Tippe auf die Schaltfläche unten, um deine E-Mail-Adresse zu bestätigen. Das schützt dein Konto und ermöglicht es dir, dein Passwort zurückzusetzen, falls du es einmal vergisst:",
            button: "E-Mail bestätigen",
            fallback: "Wenn die Schaltfläche nicht funktioniert, füge diesen Link in deinen Browser ein:",
            expiry: "Dieser Link läuft in 24 Stunden ab. Wenn du dich nicht bei Ultiq registriert hast, kannst du diese E-Mail ignorieren.",
        },
        "Japanese" => EmailCopy {
            subject: "Ultiq のメールアドレスを確認",
            greeting: "Ultiq へようこそ！",
            body: "下のボタンをタップしてメールアドレスを確認してください。これによりアカウントが保護され、パスワードを忘れた場合にも再設定できるようになります：",
            button: "メールを確認",
            fallback: "ボタンが動作しない場合は、このリンクをブラウザに貼り付けてください：",
            expiry: "このリンクは24時間で失効します。Ultiq に登録した覚えがない場合は、このメールを無視してかまいません。",
        },
        "Simplified Chinese" => EmailCopy {
            subject: "验证你的 Ultiq 邮箱",
            greeting: "欢迎使用 Ultiq！",
            body: "点击下方按钮验证你的电子邮箱地址。这可以保护你的账户，并让你在忘记密码时能够重置密码：",
            button: "验证邮箱",
            fallback: "如果按钮无法使用，请将此链接粘贴到浏览器中：",
            expiry: "此链接将在 24 小时后失效。如果你没有注册 Ultiq，可以放心忽略此邮件。",
        },
        "Traditional Chinese" => EmailCopy {
            subject: "驗證你的 Ultiq 電子郵件",
            greeting: "歡迎使用 Ultiq！",
            body: "點擊下方按鈕驗證你的電子郵件地址。這可以保護你的帳戶，並讓你在忘記密碼時能夠重設密碼：",
            button: "驗證電子郵件",
            fallback: "如果按鈕無法使用，請將此連結貼到瀏覽器中：",
            expiry: "此連結將在 24 小時後失效。如果你沒有註冊 Ultiq，可以放心忽略這封郵件。",
        },
        "Korean" => EmailCopy {
            subject: "Ultiq 이메일 인증",
            greeting: "Ultiq에 오신 것을 환영합니다!",
            body: "아래 버튼을 눌러 이메일 주소를 인증하세요. 이렇게 하면 계정이 보호되고, 비밀번호를 잊어버렸을 때 재설정할 수 있습니다:",
            button: "이메일 인증",
            fallback: "버튼이 작동하지 않으면 이 링크를 브라우저에 붙여넣으세요:",
            expiry: "이 링크는 24시간 후에 만료됩니다. Ultiq에 가입한 적이 없다면 이 이메일을 무시하셔도 됩니다.",
        },
        "Hindi" => EmailCopy {
            subject: "अपना Ultiq ईमेल सत्यापित करें",
            greeting: "Ultiq में आपका स्वागत है!",
            body: "अपना ईमेल पता सत्यापित करने के लिए नीचे दिए गए बटन पर टैप करें। इससे आपका खाता सुरक्षित रहता है और भूल जाने पर आप अपना पासवर्ड रीसेट कर सकते हैं:",
            button: "ईमेल सत्यापित करें",
            fallback: "अगर बटन काम न करे, तो इस लिंक को अपने ब्राउज़र में पेस्ट करें:",
            expiry: "यह लिंक 24 घंटे में समाप्त हो जाएगा। अगर आपने Ultiq के लिए साइन अप नहीं किया, तो आप इस ईमेल को अनदेखा कर सकते हैं।",
        },
        "Vietnamese" => EmailCopy {
            subject: "Xác minh email Ultiq của bạn",
            greeting: "Chào mừng bạn đến với Ultiq!",
            body: "Nhấn vào nút bên dưới để xác minh địa chỉ email của bạn. Điều này bảo vệ tài khoản của bạn và cho phép bạn đặt lại mật khẩu nếu quên:",
            button: "Xác minh email",
            fallback: "Nếu nút không hoạt động, hãy dán liên kết này vào trình duyệt của bạn:",
            expiry: "Liên kết này sẽ hết hạn sau 24 giờ. Nếu bạn không đăng ký Ultiq, bạn có thể bỏ qua email này một cách an toàn.",
        },
        "Thai" => EmailCopy {
            subject: "ยืนยันอีเมล Ultiq ของคุณ",
            greeting: "ยินดีต้อนรับสู่ Ultiq!",
            body: "แตะปุ่มด้านล่างเพื่อยืนยันที่อยู่อีเมลของคุณ การทำเช่นนี้จะช่วยปกป้องบัญชีของคุณ และให้คุณรีเซ็ตรหัสผ่านได้หากลืม:",
            button: "ยืนยันอีเมล",
            fallback: "หากปุ่มใช้งานไม่ได้ ให้วางลิงก์นี้ในเบราว์เซอร์ของคุณ:",
            expiry: "ลิงก์นี้จะหมดอายุใน 24 ชั่วโมง หากคุณไม่ได้สมัคร Ultiq คุณสามารถเพิกเฉยต่ออีเมลนี้ได้อย่างปลอดภัย",
        },
        "Indonesian" => EmailCopy {
            subject: "Verifikasi email Ultiq Anda",
            greeting: "Selamat datang di Ultiq!",
            body: "Ketuk tombol di bawah untuk memverifikasi alamat email Anda. Ini melindungi akun Anda dan memungkinkan Anda menyetel ulang kata sandi jika lupa:",
            button: "Verifikasi email",
            fallback: "Jika tombol tidak berfungsi, tempel tautan ini di browser Anda:",
            expiry: "Tautan ini kedaluwarsa dalam 24 jam. Jika Anda tidak mendaftar di Ultiq, Anda dapat mengabaikan email ini dengan aman.",
        },
        "Arabic" => EmailCopy {
            subject: "أكّد بريدك الإلكتروني في Ultiq",
            greeting: "مرحبًا بك في Ultiq!",
            body: "انقر على الزر أدناه لتأكيد عنوان بريدك الإلكتروني. هذا يحمي حسابك ويتيح لك إعادة تعيين كلمة المرور إذا نسيتها:",
            button: "تأكيد البريد الإلكتروني",
            fallback: "إذا لم يعمل الزر، فالصق هذا الرابط في متصفحك:",
            expiry: "تنتهي صلاحية هذا الرابط خلال 24 ساعة. إذا لم تكن قد سجّلت في Ultiq، يمكنك تجاهل هذه الرسالة بأمان.",
        },
        // English (base) + any unmapped language.
        _ => EmailCopy {
            subject: "Verify your Ultiq email",
            greeting: "Welcome to Ultiq!",
            body: "Tap the button below to verify your email address. This protects your account and lets you reset your password if you ever forget it:",
            button: "Verify email",
            fallback: "If the button doesn't work, paste this link into your browser:",
            expiry: "This link expires in 24 hours. If you didn't sign up for Ultiq, you can safely ignore this email.",
        },
    }
}

fn reset_copy(language: &str) -> EmailCopy {
    match language {
        "Spanish" => EmailCopy {
            subject: "Restablece tu contraseña de Ultiq",
            greeting: "Hola:",
            body: "Recibimos una solicitud para restablecer tu contraseña de Ultiq. Toca el botón de abajo para elegir una nueva contraseña:",
            button: "Restablecer contraseña",
            fallback: "Si el botón no abre la aplicación, copia este enlace en Ultiq:",
            expiry: "Este enlace caduca en 1 hora. Si no solicitaste un restablecimiento, puedes ignorar este correo de forma segura.",
        },
        "Brazilian Portuguese" => EmailCopy {
            subject: "Redefina sua senha da Ultiq",
            greeting: "Olá,",
            body: "Recebemos uma solicitação para redefinir sua senha da Ultiq. Toque no botão abaixo para escolher uma nova senha:",
            button: "Redefinir senha",
            fallback: "Se o botão não abrir o app, copie este link para a Ultiq:",
            expiry: "Este link expira em 1 hora. Se você não solicitou uma redefinição, pode ignorar este e-mail com segurança.",
        },
        "French" => EmailCopy {
            subject: "Réinitialisez votre mot de passe Ultiq",
            greeting: "Bonjour,",
            body: "Nous avons reçu une demande de réinitialisation de votre mot de passe Ultiq. Appuyez sur le bouton ci-dessous pour choisir un nouveau mot de passe :",
            button: "Réinitialiser le mot de passe",
            fallback: "Si le bouton n'ouvre pas l'application, copiez ce lien dans Ultiq :",
            expiry: "Ce lien expire dans 1 heure. Si vous n'avez pas demandé de réinitialisation, vous pouvez ignorer cet e-mail en toute sécurité.",
        },
        "German" => EmailCopy {
            subject: "Setze dein Ultiq-Passwort zurück",
            greeting: "Hallo,",
            body: "Wir haben eine Anfrage zum Zurücksetzen deines Ultiq-Passworts erhalten. Tippe auf die Schaltfläche unten, um ein neues Passwort zu wählen:",
            button: "Passwort zurücksetzen",
            fallback: "Wenn die Schaltfläche die App nicht öffnet, kopiere diesen Link in Ultiq:",
            expiry: "Dieser Link läuft in 1 Stunde ab. Wenn du keine Zurücksetzung angefordert hast, kannst du diese E-Mail ignorieren.",
        },
        "Japanese" => EmailCopy {
            subject: "Ultiq のパスワードを再設定",
            greeting: "こんにちは、",
            body: "Ultiq のパスワード再設定のリクエストを受け付けました。下のボタンをタップして新しいパスワードを設定してください：",
            button: "パスワードを再設定",
            fallback: "ボタンでアプリが開かない場合は、このリンクを Ultiq にコピーしてください：",
            expiry: "このリンクは1時間で失効します。再設定をリクエストしていない場合は、このメールを無視してかまいません。",
        },
        "Simplified Chinese" => EmailCopy {
            subject: "重置你的 Ultiq 密码",
            greeting: "你好，",
            body: "我们收到了重置你 Ultiq 密码的请求。点击下方按钮设置新密码：",
            button: "重置密码",
            fallback: "如果按钮无法打开应用，请将此链接复制到 Ultiq 中：",
            expiry: "此链接将在 1 小时后失效。如果你没有请求重置，可以放心忽略此邮件。",
        },
        "Traditional Chinese" => EmailCopy {
            subject: "重設你的 Ultiq 密碼",
            greeting: "你好，",
            body: "我們收到了重設你 Ultiq 密碼的請求。點擊下方按鈕設定新密碼：",
            button: "重設密碼",
            fallback: "如果按鈕無法開啟應用程式，請將此連結複製到 Ultiq 中：",
            expiry: "此連結將在 1 小時後失效。如果你沒有要求重設，可以放心忽略這封郵件。",
        },
        "Korean" => EmailCopy {
            subject: "Ultiq 비밀번호 재설정",
            greeting: "안녕하세요,",
            body: "Ultiq 비밀번호 재설정 요청을 받았습니다. 아래 버튼을 눌러 새 비밀번호를 설정하세요:",
            button: "비밀번호 재설정",
            fallback: "버튼으로 앱이 열리지 않으면 이 링크를 Ultiq에 복사하세요:",
            expiry: "이 링크는 1시간 후에 만료됩니다. 재설정을 요청하지 않으셨다면 이 이메일을 무시하셔도 됩니다.",
        },
        "Hindi" => EmailCopy {
            subject: "अपना Ultiq पासवर्ड रीसेट करें",
            greeting: "नमस्ते,",
            body: "हमें आपका Ultiq पासवर्ड रीसेट करने का अनुरोध मिला। नया पासवर्ड चुनने के लिए नीचे दिए गए बटन पर टैप करें:",
            button: "पासवर्ड रीसेट करें",
            fallback: "अगर बटन ऐप न खोले, तो इस लिंक को Ultiq में कॉपी करें:",
            expiry: "यह लिंक 1 घंटे में समाप्त हो जाएगा। अगर आपने रीसेट का अनुरोध नहीं किया, तो आप इस ईमेल को अनदेखा कर सकते हैं।",
        },
        "Vietnamese" => EmailCopy {
            subject: "Đặt lại mật khẩu Ultiq của bạn",
            greeting: "Xin chào,",
            body: "Chúng tôi đã nhận được yêu cầu đặt lại mật khẩu Ultiq của bạn. Nhấn vào nút bên dưới để chọn mật khẩu mới:",
            button: "Đặt lại mật khẩu",
            fallback: "Nếu nút không mở ứng dụng, hãy sao chép liên kết này vào Ultiq:",
            expiry: "Liên kết này sẽ hết hạn sau 1 giờ. Nếu bạn không yêu cầu đặt lại, bạn có thể bỏ qua email này một cách an toàn.",
        },
        "Thai" => EmailCopy {
            subject: "รีเซ็ตรหัสผ่าน Ultiq ของคุณ",
            greeting: "สวัสดี",
            body: "เราได้รับคำขอรีเซ็ตรหัสผ่าน Ultiq ของคุณ แตะปุ่มด้านล่างเพื่อตั้งรหัสผ่านใหม่:",
            button: "รีเซ็ตรหัสผ่าน",
            fallback: "หากปุ่มไม่เปิดแอป ให้คัดลอกลิงก์นี้ไปยัง Ultiq:",
            expiry: "ลิงก์นี้จะหมดอายุใน 1 ชั่วโมง หากคุณไม่ได้ขอรีเซ็ต คุณสามารถเพิกเฉยต่ออีเมลนี้ได้อย่างปลอดภัย",
        },
        "Indonesian" => EmailCopy {
            subject: "Setel ulang kata sandi Ultiq Anda",
            greeting: "Halo,",
            body: "Kami menerima permintaan untuk menyetel ulang kata sandi Ultiq Anda. Ketuk tombol di bawah untuk memilih kata sandi baru:",
            button: "Setel ulang kata sandi",
            fallback: "Jika tombol tidak membuka aplikasi, salin tautan ini ke Ultiq:",
            expiry: "Tautan ini kedaluwarsa dalam 1 jam. Jika Anda tidak meminta penyetelan ulang, Anda dapat mengabaikan email ini dengan aman.",
        },
        "Arabic" => EmailCopy {
            subject: "أعد تعيين كلمة مرور Ultiq",
            greeting: "مرحبًا،",
            body: "تلقّينا طلبًا لإعادة تعيين كلمة مرور Ultiq الخاصة بك. انقر على الزر أدناه لاختيار كلمة مرور جديدة:",
            button: "إعادة تعيين كلمة المرور",
            fallback: "إذا لم يفتح الزر التطبيق، فانسخ هذا الرابط إلى Ultiq:",
            expiry: "تنتهي صلاحية هذا الرابط خلال ساعة واحدة. إذا لم تطلب إعادة التعيين، يمكنك تجاهل هذه الرسالة بأمان.",
        },
        // English (base) + any unmapped language.
        _ => EmailCopy {
            subject: "Reset your Ultiq password",
            greeting: "Hi,",
            body: "We received a request to reset your Ultiq password. Tap the button below to choose a new password:",
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
        let (subject, text, html) = verify_email("English", "https://x/y?token=abc");
        assert_eq!(subject, "Verify your Ultiq email");
        assert!(text.contains("Welcome to Ultiq!"));
        assert!(text.contains("https://x/y?token=abc"));
        assert!(html.contains(">Verify email</a>"));
        // Sign-off is shared, not per-language.
        assert!(text.trim_end().ends_with("— Ultiq"));
    }

    #[test]
    fn unmapped_language_falls_back_to_english() {
        let (subject, _, _) = reset_email("Klingon", "https://x");
        assert_eq!(subject, "Reset your Ultiq password");
    }

    #[test]
    fn localized_arabic_is_rtl_and_keeps_brand_name() {
        let (subject, _text, html) = reset_email("Arabic", "https://x/y");
        assert!(subject.contains("Ultiq")); // brand never translated
        assert!(html.contains("direction:rtl")); // RTL container
        assert!(html.contains("https://x/y"));
    }

    #[test]
    fn localized_language_translates_subject() {
        let (subject, _, _) = verify_email("Japanese", "https://x");
        assert!(subject.contains("Ultiq"));
        assert!(subject.contains("確認"));
    }

    #[test]
    fn link_is_present_in_both_bodies_for_every_language() {
        for lang in [
            "Spanish",
            "Brazilian Portuguese",
            "French",
            "German",
            "Japanese",
            "Simplified Chinese",
            "Traditional Chinese",
            "Korean",
            "Hindi",
            "Vietnamese",
            "Thai",
            "Indonesian",
            "Arabic",
            "English",
        ] {
            for (_s, text, html) in [verify_email(lang, "LINKMARK"), reset_email(lang, "LINKMARK")] {
                assert!(text.contains("LINKMARK"), "text missing link for {lang}");
                assert!(html.contains("LINKMARK"), "html missing link for {lang}");
            }
        }
    }
}
