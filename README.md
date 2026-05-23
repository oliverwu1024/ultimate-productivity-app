![Ultiq](web-landing/public/mascot.svg)

# Ultiq

> Your daily productivity companion.

Ultiq tracks the parts of your day that actually move the needle — sleep, focus
sessions, calendar, checklists — and uses on-device + AI analysis to surface what
your numbers are telling you. Sleep is session-based with phone-pickup detection
and on-device snore + cough monitoring. Focus sessions run an Alarmy-style wake
flow (math, shake, or photo mission to dismiss). An in-app coach can read your
data and propose calendar/checklist writes you confirm with one tap. Daily AI
anomaly detection pings you when slow-burn patterns appear.

- 📱 **Android** — install from [`ultiqapp.com`](https://ultiqapp.com) (direct APK; Play Store production track in progress)
- 🌐 **Web dashboard** — [`app.ultiqapp.com`](https://app.ultiqapp.com)
- 🛬 **Landing page** — [`ultiqapp.com`](https://ultiqapp.com)

---

## Features

**Sleep tracking.** Start / End Sleep buttons start a session; phone pickups
during the session are tracked automatically. Optional on-device YAMNet model
detects snoring + coughing episodes — audio never leaves the phone. End-of-night
dialog shows the per-pickup + snore + cough timeline, with a one-tap AI quality
rating (1-5 + a one-line reason). Tap any past record to see the full timeline.

**Focus sessions** with phone-lockout overlay, overtime detection, wall-clock
anchored timer (background-throttle-proof), and an optional 1-line debrief after
each session that an AI auto-tags into deep_work / meetings / admin / other.

**Calendar + checklists** with recurrence, schedule modes (Today / repeat
days-of-week / by due date), unfinished-from-yesterday carry-over banner,
and an in-app natural-language create dialog ("lunch with Sarah tomorrow at 1pm").

**Wake alarms** Alarmy-style. Math, shake, or photograph-a-fixed-scene mission
to silence — so you actually get out of bed. Configurable mission difficulty
and snooze limits.

**Coach chat** in-app. Tool-using assistant that can read your sleep / focus
patterns, log past sleep, set alarms, and propose calendar + checklist events
you confirm before save. Quota + per-IP rate-limited.

**Weekly insight + anomaly detection.** AI generates a weekly summary card on
the Dashboard (24h cached server-side). A daily anomaly scan flags slow-burn
patterns (5+ short nights, focus collapse, sleep-window phone spirals) and
pushes them via FCM.

**Web analytics dashboard.** Cross-feature analytics, dark mode, mobile
responsive, realtime sync via Server-Sent Events.

**One-tap in-app updates.** Sideload installs receive update banners that
hand the APK to PackageInstaller — no Files-app digging.

---

## Stack

| Layer | Choice |
|---|---|
| Mobile | Kotlin + Jetpack Compose; Room (SQLCipher-encrypted); Retrofit + OkHttp SSE; WorkManager; EncryptedSharedPreferences for tokens; MediaPipe Tasks Audio (YAMNet) for on-device snore + cough; CameraX for the photo dismiss mission; Firebase Cloud Messaging for anomaly push |
| Backend | Rust + Axum 0.7; sqlx + PostgreSQL; jsonwebtoken HS256 with `iss`/`aud` + per-user `token_version` revocation; Argon2id passwords (m=64 MiB, t=3); tower_governor per-IP rate limit; tower-http security headers; broadcast channels for SSE fan-out; AWS Bedrock (Claude Sonnet + Haiku via AU inference profiles) for AI features |
| Web dashboard | Rust + Leptos 0.8 (WebAssembly, CSR); Tailwind; charming + leptos-chartistry; gloo-net |
| Web landing | Next.js (static export); Tailwind; framer-motion |
| Database | PostgreSQL on AWS RDS (private subnet, encrypted at rest) |
| Infra | All AWS — ECS Fargate + ALB; S3 + CloudFront + ACM + Route 53; ECR; Secrets Manager; CloudWatch (logs + Bedrock spend alarm); SNS for alert email |
| CI/CD | GitHub Actions via OIDC role assumption (no long-lived AWS keys); deploy workflows for backend, dashboard, and landing — each with `concurrency:` to serialize per-branch and avoid ECS circuit-breaker races; all action SHAs pinned; Dependabot manages updates |

---

## Repo layout

| Path | What it is |
|---|---|
| `android/` | Native Android app — Kotlin + Compose + Room + foreground services for sleep + focus tracking + alarm pipeline |
| `backend/` | REST API — Axum, sqlx, JWT, SSE, Bedrock-mediated AI routes |
| `web-dashboard/` | Analytics dashboard — Leptos + WASM, single-use SSE tickets so JWTs never appear in URLs |
| `web-landing/` | Marketing site — Next.js static export, hosts the APK + `version.json` for in-app update checks |
| `play-store/` | Play Store listing assets (feature graphic, icon) |
| `.github/workflows/` | CI/CD — three deploys, OIDC-only, SHAs pinned |

---

## Security posture

The backend, dashboard, and Android app went through a full security audit
before this repo went public. Highlights:

- HTTPS everywhere; HSTS preload + restrictive CSP at CloudFront.
- JWT pinned to HS256 with `iss`/`aud` validation and per-user revocation via
  a `token_version` column — password change / reset invalidates every device.
- Argon2id (m=64 MiB, t=3) password hashing, with transparent rehash on login
  for older accounts.
- Per-IP rate limiting (strict on `/auth/*`; tighter on `/ai/*` because every
  request fans out into a Bedrock call; generous on the rest).
- Length caps + body-size limits on every input.
- AI routes never see raw audio. Sleep audio analysis is on-device only — only
  labels + timestamps + confidence values cross the wire.
- Android: `EncryptedSharedPreferences` for the JWT, SQLCipher for the local
  Room DB, `allowBackup="false"`, R8 release builds, network-security-config
  restricting cleartext to dev hosts only.
- Dashboard: SSE auth via single-use 30-second tickets so JWTs never appear in
  URLs / browser history / access logs.
- Bedrock IAM scoped to AU inference-profile ARNs only, with a CloudWatch
  spend alarm wired to SNS email.
- All GitHub Actions pinned to commit SHAs; Dependabot manages updates.

---

## Local dev

- **Backend** — `docker-compose -f docker-compose.dev.yml up -d` brings up
  Postgres on port 5433. Then `cargo run` from `backend/` with `.env` set
  (`DATABASE_URL`, `JWT_SECRET`, `RESEND_API_KEY`; optional `AWS_PROFILE` +
  `AWS_REGION` for local Bedrock).
- **Web dashboard** — `trunk serve` from `web-dashboard/` after `npm install`
  (Tailwind comes via npm).
- **Landing page** — `npm run dev` from `web-landing/`.
- **Android** — open `android/` in Android Studio. CLI builds need
  `JAVA_HOME=/opt/android-studio/jbr` (system JDK lacks `jlink`). The YAMNet
  model file (`yamnet.tflite`, ~4 MB) ships at `android/app/src/main/assets/`.

---

## License

© 2026 Che-Yu Wu. **All rights reserved.** This repository is source-available
for reading only — see [LICENSE](LICENSE). No grant of use, redistribution, or
commercial exploitation. The Android app itself is published separately on
[ultiqapp.com](https://ultiqapp.com); this repo is the implementation, not a
redistributable artifact.
