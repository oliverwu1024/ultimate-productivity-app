use axum::http::StatusCode;

use crate::error::AppError;

/// Soft cap for short labels (titles, tags, app categories).
pub const MAX_TITLE_CHARS: usize = 256;
/// Cap for free-text descriptions / locations.
pub const MAX_DESCRIPTION_CHARS: usize = 4096;
/// Cap for longer-form notes (sleep notes, session notes).
pub const MAX_NOTES_CHARS: usize = 8192;

/// Reject string fields that exceed their cap. Counts characters (not bytes)
/// so emoji-heavy notes don't get over-counted by the byte length.
pub fn cap_chars(value: &str, max: usize, field: &str) -> Result<(), AppError> {
    if value.chars().count() > max {
        return Err(AppError::new(
            StatusCode::BAD_REQUEST,
            format!("{} too long (max {} characters)", field, max),
        ));
    }
    Ok(())
}

pub fn cap_chars_opt(value: &Option<String>, max: usize, field: &str) -> Result<(), AppError> {
    if let Some(v) = value {
        cap_chars(v, max, field)?;
    }
    Ok(())
}
