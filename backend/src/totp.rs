//! §16 — TOTP (RFC 6238) helpers for admin two-factor auth.
//!
//! Generates 20-byte random secrets, encodes/decodes to base32 for storage
//! and otpauth:// URIs, and verifies user-supplied 6-digit codes against
//! the current 30-second window (with a ±1 step allowance for clock drift).

use base32::Alphabet;
use rand::Rng;
use totp_rs::{Algorithm, TOTP};

/// Length of a freshly generated TOTP seed. 20 bytes is the SHA-1 / 6-digit
/// default in every authenticator app (Google Authenticator, Authy, 1Password).
const SECRET_LEN: usize = 20;

/// Allowed steps before/after the current 30s window. `1` lets a code from
/// the previous step (up to 30s old) still validate — covers normal phone /
/// server clock drift without making brute-force meaningfully easier.
const SKEW_STEPS: u8 = 1;

const STEP_SECONDS: u64 = 30;
const DIGITS: usize = 6;
const ALPHABET: Alphabet = Alphabet::Rfc4648 { padding: false };

/// Issuer string baked into the provisioning URI so authenticator apps
/// group the entry under "Ultiq" alongside other accounts.
pub const ISSUER: &str = "Ultiq";

/// Generate a fresh 20-byte TOTP seed.
pub fn generate_secret() -> Vec<u8> {
    let mut buf = vec![0u8; SECRET_LEN];
    rand::rng().fill_bytes(&mut buf);
    buf
}

pub fn encode_b32(bytes: &[u8]) -> String {
    base32::encode(ALPHABET, bytes)
}

pub fn decode_b32(s: &str) -> Option<Vec<u8>> {
    base32::decode(ALPHABET, s)
}

fn build_totp(secret: Vec<u8>, account: Option<String>) -> Option<TOTP> {
    TOTP::new(
        Algorithm::SHA1,
        DIGITS,
        SKEW_STEPS,
        STEP_SECONDS,
        secret,
        Some(ISSUER.to_string()),
        account.unwrap_or_default(),
    )
    .ok()
}

/// Build the standard `otpauth://totp/Ultiq:<account>?secret=...` URI.
/// Authenticator apps render the QR for this directly.
pub fn provisioning_uri(secret: &[u8], account: &str) -> Option<String> {
    let totp = build_totp(secret.to_vec(), Some(account.to_string()))?;
    Some(totp.get_url())
}

/// Verify a 6-digit code against the current TOTP window (±1 step).
/// Returns false on invalid code OR on any TOTP-construction error
/// (corrupted secret bytes, etc.) — callers should treat false as "no."
pub fn verify(secret: &[u8], code: &str) -> bool {
    let Some(totp) = build_totp(secret.to_vec(), None) else {
        return false;
    };
    totp.check_current(code).unwrap_or(false)
}
