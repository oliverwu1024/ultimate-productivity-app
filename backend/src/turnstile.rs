use serde::Deserialize;

/// Cloudflare Turnstile siteverify endpoint. POST `secret` + `response`
/// (the token the widget produced client-side) + optional `remoteip`.
const SITEVERIFY_URL: &str = "https://challenges.cloudflare.com/turnstile/v0/siteverify";

#[derive(Deserialize)]
struct SiteverifyResponse {
    success: bool,
    #[serde(default)]
    #[serde(rename = "error-codes")]
    error_codes: Vec<String>,
}

/// Verify a Turnstile token with Cloudflare. Returns Ok(true) on a
/// successful captcha challenge, Ok(false) on a real "this token is
/// bad / expired / replayed" rejection, Err only on network/serde
/// failure talking to Cloudflare. Callers should treat Err as
/// "captcha service down" (probably reject with 503, not assume the
/// user is a bot).
///
/// Tokens are single-use and have a ~5min TTL on Cloudflare's side, so
/// we don't need to cache verification results locally — re-validating
/// the same token would fail anyway.
pub async fn verify(
    secret: &str,
    token: &str,
    remote_ip: Option<&str>,
) -> Result<bool, reqwest::Error> {
    let client = reqwest::Client::new();
    let mut form: Vec<(&str, &str)> = vec![("secret", secret), ("response", token)];
    if let Some(ip) = remote_ip {
        form.push(("remoteip", ip));
    }
    let resp = client
        .post(SITEVERIFY_URL)
        .form(&form)
        .send()
        .await?
        .error_for_status()?;
    let parsed: SiteverifyResponse = resp.json().await?;
    if !parsed.success {
        tracing::warn!(
            "Turnstile verification failed: error_codes={:?}",
            parsed.error_codes
        );
    }
    Ok(parsed.success)
}
