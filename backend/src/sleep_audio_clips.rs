//! §10.x — Sleep-audio Pro-tier clip storage.
//!
//! On-device YAMNet detection has shipped clip-less since v2.11 (labels +
//! timestamps + peak confidence only). Pro users can additionally opt in to
//! storing a short audio clip per detected event. The flow:
//!
//! 1. Phone asks backend for a presigned PUT URL bound to a fresh object key.
//! 2. Phone uploads the encoded AAC clip directly to S3 — backend never
//!    sees the audio bytes.
//! 3. Phone POSTs the object key + duration back to attach the clip to the
//!    sleep_audio_events row.
//! 4. Playback (Android list, web dashboard) asks for a presigned GET URL.
//! 5. S3 lifecycle rule auto-deletes objects after 30 days (the bucket's
//!    `expire-after-30-days` rule on the `u/` prefix).
//! 6. A daily `clip_janitor` (scheduler.rs) then NULLs the row's clip columns
//!    once it's past that window, so the DB never points at a deleted object
//!    (the UI hides the ▶ instead of showing a dead "clip expired" row). The
//!    detection event itself — type, timestamps, confidence — is untouched.
//!
//! Security:
//! - Pro-tier check on every route (currently uses is_admin as the stand-in;
//!   Phase 11 swaps in the billing-backed `is_pro` column).
//! - Presigned URLs are scoped to a single object key, single HTTP method,
//!   single content-type, and a short lifetime (5 min upload, 1 min playback).
//! - Object keys are server-generated (UUID + user prefix); the client can't
//!   write outside its own prefix.
//! - Bucket name is config (`SLEEP_AUDIO_S3_BUCKET`); missing env disables
//!   the routes (503) rather than crashing at startup.

use std::env;
use std::time::Duration;

use aws_sdk_s3 as s3;
use s3::presigning::PresigningConfig;
use uuid::Uuid;

const UPLOAD_TTL_SECS: u64 = 5 * 60; // 5 min — covers slow uploads on flaky LTE.
const PLAYBACK_TTL_SECS: u64 = 60; // 1 min — refreshed on every play tap.

/// Allowed content-type for clip uploads. Mirrors the encoder Android uses
/// (MediaMuxer .m4a / AAC-LC). Locked at presign time so the client can't
/// PUT arbitrary blobs to its key.
/// §10.x-fix — Was `audio/aac` in v2.14.0, which broke web-dashboard
/// playback: HTML5 `<audio>` treated the file as a raw AAC stream (ADTS),
/// couldn't find the MP4 container's duration atom, and rendered 00:00 /
/// failed to play. `audio/mp4` is the correct MIME for AAC-in-MP4 and
/// every browser auto-detects the inner codec.
pub const CLIP_CONTENT_TYPE: &str = "audio/mp4";

/// Max acceptable clip size in bytes. ~10 sec of AAC at 64 kbps ≈ 80 KB;
/// we leave generous headroom for a longer-than-expected event without
/// allowing wildly oversized uploads.
pub const MAX_CLIP_BYTES: i64 = 256 * 1024;

#[derive(Clone)]
pub struct SleepAudioClipStore {
    s3: s3::Client,
    bucket: String,
}

impl SleepAudioClipStore {
    /// Build from ambient AWS credentials (ECS task role in prod, profile
    /// chain in dev). Returns `None` when `SLEEP_AUDIO_S3_BUCKET` is unset
    /// so the rest of the backend keeps starting — Pro-clip routes simply
    /// 503 until the bucket is created and the env wired up.
    pub async fn try_new() -> Option<Self> {
        let bucket = env::var("SLEEP_AUDIO_S3_BUCKET").ok().filter(|s| !s.is_empty())?;
        let config = aws_config::load_from_env().await;
        let s3 = s3::Client::new(&config);
        Some(Self { s3, bucket })
    }

    pub fn bucket(&self) -> &str {
        &self.bucket
    }

    /// Generate the canonical object key for a user's clip. Includes the
    /// user id so the IAM policy can grant per-prefix access if we ever
    /// move to STS-based credentials, and a UUID so two events at the same
    /// millisecond never collide.
    pub fn new_clip_key(&self, user_id: Uuid) -> String {
        format!("u/{}/{}.m4a", user_id, Uuid::new_v4())
    }

    /// Presigned PUT for the phone's direct upload. Pins method + content-type
    /// so the URL can't be re-purposed to upload a different MIME at the
    /// same key.
    pub async fn presign_put(&self, key: &str) -> Result<String, s3::Error> {
        let cfg = PresigningConfig::expires_in(Duration::from_secs(UPLOAD_TTL_SECS))
            .expect("valid presign duration");
        let req = self
            .s3
            .put_object()
            .bucket(&self.bucket)
            .key(key)
            .content_type(CLIP_CONTENT_TYPE)
            .presigned(cfg)
            .await?;
        Ok(req.uri().to_string())
    }

    /// Presigned GET for inline playback in the Android event list or the
    /// web dashboard's audio element. Short TTL because the URL is fetched
    /// fresh on every play tap — caching it would just expand the window
    /// where a leaked URL is replayable.
    ///
    /// §10.x-fix — Always overrides the stored Content-Type via the
    /// response-content-type query param. v2.14.0 uploaded clips with
    /// `audio/aac` (wrong — the file is AAC-in-MP4); the override forces
    /// `audio/mp4` on every GET so legacy objects play correctly without a
    /// re-upload, and new objects (already stored as `audio/mp4`) are
    /// unaffected.
    pub async fn presign_get(&self, key: &str) -> Result<String, s3::Error> {
        let cfg = PresigningConfig::expires_in(Duration::from_secs(PLAYBACK_TTL_SECS))
            .expect("valid presign duration");
        let req = self
            .s3
            .get_object()
            .bucket(&self.bucket)
            .key(key)
            .response_content_type(CLIP_CONTENT_TYPE)
            .presigned(cfg)
            .await?;
        Ok(req.uri().to_string())
    }

    /// Hard-delete a clip object. Used by the per-clip delete UI; the event
    /// row stays (stats are still accurate) but `clip_s3_key` is NULLed in
    /// the same transaction by the route handler so playback can't re-issue
    /// a now-broken GET URL.
    pub async fn delete(&self, key: &str) -> Result<(), s3::Error> {
        self.s3
            .delete_object()
            .bucket(&self.bucket)
            .key(key)
            .send()
            .await
            .map(|_| ())
            .map_err(Into::into)
    }

    /// §10.x-fix (v2.14.4) — Server-side fetch of clip bytes. Used by the
    /// web playback proxy endpoint: the browser <audio> element ended up
    /// fighting cross-origin CORS + Range preflight + presigned-URL signing
    /// in v2.14.0-v2.14.3. Routing playback through the backend (which uses
    /// the ECS task role's IAM directly, no presigning) and serving the
    /// bytes as a normal API response sidesteps all of it — the existing
    /// API CORS already allows app.ultiqapp.com, and the audio element
    /// just sees a same-domain-API URL.
    ///
    /// Clips are < 100 KB so reading the whole body into memory is fine.
    /// Range support would let us stream, but a 100 KB blob arrives in
    /// one TCP roundtrip on any real connection — not worth the code.
    pub async fn get_bytes(&self, key: &str) -> Result<Vec<u8>, String> {
        let resp = self
            .s3
            .get_object()
            .bucket(&self.bucket)
            .key(key)
            .send()
            .await
            .map_err(|e| format!("S3 GetObject failed: {:?}", e))?;
        let aggregated = resp
            .body
            .collect()
            .await
            .map_err(|e| format!("S3 body read failed: {:?}", e))?;
        Ok(aggregated.into_bytes().to_vec())
    }
}
