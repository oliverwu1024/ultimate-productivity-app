# Ultiq

> Your daily productivity companion — sleep tracking with on-device snore + cough detection, focus sessions with Alarmy-style wake missions, calendar + checklists with natural-language create, and an AI coach that reads your data and proposes writes you confirm with one tap. Android + Web dashboard, fully on AWS.

Live at **[ultiqapp.com](https://ultiqapp.com)** · Dashboard at **[app.ultiqapp.com](https://app.ultiqapp.com)**

![Android](https://img.shields.io/badge/Android-Kotlin%202.2-3DDC84?logo=android&logoColor=white)
![Compose](https://img.shields.io/badge/Jetpack%20Compose-BOM%202024.10-4285F4?logo=jetpackcompose&logoColor=white)
![Rust](https://img.shields.io/badge/Backend-Axum%200.7-DEA584?logo=rust&logoColor=white)
![Leptos](https://img.shields.io/badge/Web-Leptos%200.8-EF3939)
![Next.js](https://img.shields.io/badge/Landing-Next.js%2016-000000?logo=nextdotjs&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-336791?logo=postgresql&logoColor=white)
![Bedrock](https://img.shields.io/badge/AI-AWS%20Bedrock%20%C2%B7%20Claude-FF9900?logo=amazon&logoColor=white)
![YAMNet](https://img.shields.io/badge/On--device%20ML-YAMNet%20%C2%B7%20TFLite-FF6F00?logo=tensorflow&logoColor=white)
![AWS](https://img.shields.io/badge/Cloud-AWS%20ECS%20%C2%B7%20RDS%20%C2%B7%20CloudFront-232F3E?logo=amazonaws&logoColor=white)
![License](https://img.shields.io/badge/License-All%20Rights%20Reserved-lightgrey)
![CI](https://img.shields.io/badge/CI-passing-brightgreen)

---

## Table of Contents

- [What it does](#what-it-does)
- [Engineering highlights](#engineering-highlights)
- [Tech stack](#tech-stack)
- [Project structure](#project-structure)
- [Getting started](#getting-started)
- [Security posture](#security-posture)
- [License](#license)

---

## What it does

Ultiq is a productivity companion for Android, with a Web dashboard for cross-feature analytics. It tracks the parts of your day that move the needle — sleep, focus sessions, calendar, checklists — and uses on-device + AI analysis to surface what your numbers are telling you.

**Sleep tracking** is session-based. Start / End Sleep buttons begin a session; phone pickups during the session are auto-detected. Optional on-device YAMNet inference detects snoring + coughing episodes — audio never leaves the phone; only labels + timestamps + confidence persist. The End Sleep dialog shows the per-pickup + snore + cough timeline and offers a one-tap AI sleep quality rating (1-5 + a one-line reason). Tap any past sleep record on the Sleep tab to expand the full timeline.

**Focus sessions** run a phone-lockout overlay with prominent overtime detection. A wall-clock anchored timer survives background throttling (early Android versions undercounted by 5× during long sessions; the fix anchors against `System.currentTimeMillis()`). Optional 1-line debrief after each session is auto-tagged by AI into `deep_work` / `meetings` / `admin` / `other` for the weekly insight.

**Wake alarms** Alarmy-style. Math, shake, or photograph-a-fixed-scene mission to silence — so you actually get out of bed. Configurable mission difficulty + snooze limits. Alarms full-screen takeover when the phone is in active use (Android 14+ demotes the full-screen-intent to a heads-up notification otherwise).

**Coach chat** in-app. Tool-using assistant that can read your sleep / focus patterns, log past sleep, set alarms, and propose calendar + checklist events you confirm before save. Quota + per-IP rate-limited.

**Weekly insight + anomaly detection.** AI generates a weekly summary card on the Dashboard (24h cached server-side). A daily anomaly scan flags slow-burn patterns — 5+ short nights, focus collapse, sleep-window phone spirals — and pushes them via FCM.

**Calendar + checklists** with recurrence, schedule modes (Today / repeat days-of-week / by due date), unfinished-from-yesterday carry-over banner, and natural-language create ("lunch with Sarah tomorrow at 1pm").

**Web analytics dashboard** mirrors the mobile data with cross-feature correlations, dark mode, mobile responsive, realtime sync via Server-Sent Events.

**One-tap in-app updates** — sideload installs receive update banners that hand the APK to `PackageInstaller` so the user gets the system "Install?" prompt directly, no Files-app digging.

## Engineering highlights

- **On-device sleep audio with MediaPipe + YAMNet.** Detects snore + cough at ~4 Hz during sleep sessions. Audio never reaches the backend; only debounced labels + timestamps + peak confidence persist. `audioClassifier.createAudioRecord()` configures the recorder for `ENCODING_PCM_FLOAT` (one of two MediaPipe-API gotchas — `ClassificationResult.timestampMs()` returning `Optional<Long>` is the other).
- **Backend-mediated Claude** via AWS Bedrock — Sonnet 4.6 for weekly insights + NL parse, Haiku 4.5 for session debrief + sleep rating + anomaly scan. AU inference profiles, IAM-scoped to the inference-profile ARNs only. Per-user daily quota tracked server-side; CloudWatch spend alarm wired to SNS.
- **Anti-hallucination numeric validator** on Sonnet's weekly insight reply — extracts every numeral and warns when one isn't present in the input data card. Catches invented stats before they reach users.
- **Coach tool-loop with hybrid context** — Sonnet exposed 8 tools (read sleep / focus / calendar / checklist, log sleep, create alarm, propose calendar event, propose checklist item). User confirms every write inline. `AI_CHAT_TOOLS_ENABLED` env flag gates the tool path so the chat surface degrades to read-only if Bedrock has an outage.
- **JWT pinned to HS256** with `iss` / `aud` validation and per-user `token_version` revocation — password change / reset invalidates every device.
- **Argon2id passwords** (m=64 MiB, t=3) with transparent rehash on login for older accounts.
- **SSE realtime sync** with single-use 30-second tickets so JWTs never appear in URLs / browser history / access logs.
- **Per-IP rate limiting** at three tiers — strict on `/auth/*` (brute-force + email-quota drain), tight on `/ai/*` (every request fans out into a Bedrock call), generous on the rest.
- **R8 / ProGuard rules** tuned for the long tail — Gson DTOs, Room entities, MediaPipe Tasks Audio reflection callbacks, AutoValue + javapoet compile-only deps all explicitly kept or `-dontwarn`'d.
- **CI/CD via GitHub OIDC** — no long-lived AWS keys in the repo. Three deploys (backend → ECR + ECS, dashboard → S3 + CloudFront, landing → S3 + CloudFront), all with `concurrency:` blocks to serialize per-branch and avoid ECS circuit-breaker races. All action SHAs pinned; Dependabot manages updates.

## Tech stack

| Layer | Choice |
|---|---|
| Mobile | Kotlin 2.2 · Jetpack Compose · Room (SQLCipher-encrypted) · Retrofit + OkHttp SSE · WorkManager · EncryptedSharedPreferences · MediaPipe Tasks Audio (YAMNet TFLite) · CameraX · Firebase Cloud Messaging |
| Backend | Rust · Axum 0.7 · sqlx · jsonwebtoken HS256 · Argon2id · tower_governor · tower-http · AWS Bedrock (Claude Sonnet + Haiku via AU inference profiles) |
| Web dashboard | Rust · Leptos 0.8 (WebAssembly, CSR) · Tailwind · charming + leptos-chartistry · gloo-net |
| Web landing | Next.js 16 (static export) · Tailwind · framer-motion |
| Database | PostgreSQL 16 on AWS RDS (private subnet, encrypted at rest) |
| Infrastructure | AWS only — ECS Fargate + ALB · S3 + CloudFront + ACM + Route 53 · ECR · Secrets Manager · CloudWatch · SNS |
| CI/CD | GitHub Actions via OIDC role assumption |

## Project structure

| Path | What it is |
|---|---|
| `android/` | Native Android app — Compose UI, foreground services for sleep + focus + alarm, on-device ML |
| `backend/` | REST API — Axum routes, sqlx queries, JWT middleware, SSE event bus, Bedrock-mediated AI routes |
| `web-dashboard/` | Analytics dashboard — Leptos + WASM, single-use SSE tickets, dark mode |
| `web-landing/` | Marketing site — Next.js static export, hosts the APK + `version.json` for in-app update checks |
| `play-store/` | Play Store listing assets (feature graphic, icon) |
| `.github/workflows/` | CI/CD — three deploys, OIDC-only, SHAs pinned |

## Getting started

**Backend**

```bash
docker-compose -f docker-compose.dev.yml up -d   # Postgres on :5433
cd backend
cargo run                                        # reads .env
```

`.env` needs `DATABASE_URL`, `JWT_SECRET`, `RESEND_API_KEY`. Optional: `AWS_PROFILE` + `AWS_REGION` for local Bedrock calls (the production task role provides these via the EC2 metadata chain on ECS).

**Web dashboard**

```bash
cd web-dashboard
npm install
trunk serve
```

**Landing page**

```bash
cd web-landing
npm install
npm run dev
```

**Android**

```bash
JAVA_HOME=/opt/android-studio/jbr ./gradlew :app:assembleDebug
```

The system JDK lacks `jlink` on Linux — the bundled JBR ships with it. The YAMNet model (`yamnet.tflite`, ~4 MB) is checked into `android/app/src/main/assets/` so a fresh clone builds + runs without any extra fetch step.

## Security posture

The backend, dashboard, and Android app went through a full security audit before this repo went public.

- HTTPS everywhere; HSTS preload + restrictive CSP at CloudFront.
- JWT pinned to HS256 with `iss` / `aud` validation and per-user revocation via `token_version` — password change / reset invalidates every device.
- Argon2id (m=64 MiB, t=3) password hashing, transparent rehash on login for older accounts.
- Per-IP rate limiting at three tiers (strict on `/auth/*`, tight on `/ai/*`, generous on the rest).
- Length caps + body-size limits on every input; `cap_chars_opt` validator for free-text fields.
- Sleep audio analysis is on-device only — raw PCM never leaves the phone; only labels + timestamps + confidence cross the wire.
- Android: `EncryptedSharedPreferences` for the JWT, SQLCipher for the local Room DB, `allowBackup="false"`, R8 release builds, network-security-config restricting cleartext to dev hosts only.
- Dashboard SSE auth via single-use 30-second tickets so JWTs never appear in URLs / browser history / access logs.
- Bedrock IAM scoped to AU inference-profile ARNs only; CloudWatch spend alarm wired to SNS email.
- All GitHub Actions pinned to commit SHAs; Dependabot manages updates.

## License

© 2026 Che-Yu Wu. **All rights reserved.** This repository is source-available for reading only — see [LICENSE](LICENSE). No grant of use, redistribution, or commercial exploitation. The Android app is published separately on [ultiqapp.com](https://ultiqapp.com); this repo is the implementation, not a redistributable artifact.
