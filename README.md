# Ultiq

> Your daily productivity companion.

A full-stack productivity tracker — sleep, focus sessions, calendar, checklists — built end-to-end as a personal learning project. Android client, Rust backend, Rust + WebAssembly analytics dashboard, fully deployed on AWS.

- 📱 Android: [`ultiqapp.com`](https://ultiqapp.com) (direct APK while Play Store ID verification is pending)
- 🌐 Dashboard: [`app.ultiqapp.com`](https://app.ultiqapp.com)
- 🛬 Landing: [`ultiqapp.com`](https://ultiqapp.com)

## What's in here

| Path | What it is |
| --- | --- |
| `android/` | Native Android app — Kotlin + Jetpack Compose, Room (SQLCipher-encrypted), Retrofit, foreground services for sleep + focus tracking |
| `backend/` | Rust API — Axum, sqlx + Postgres, JWT auth (HS256, `iss`/`aud` pinned, per-user `token_version` revocation), Argon2id passwords, SSE for realtime sync |
| `web-dashboard/` | Analytics dashboard — Leptos + WebAssembly, dark mode, charts, single-use SSE tickets so JWTs never appear in URLs |
| `web-landing/` | Marketing site — Next.js static export, hosts the APK + `version.json` for in-app update checks |
| `play-store/` | Play Store listing assets (feature graphic, icon) |
| `.github/workflows/` | CI/CD — three deploys (backend → ECR + ECS, dashboard → S3 + CloudFront, landing → S3 + CloudFront), all via GitHub OIDC, all action SHAs pinned |

## Stack

**Mobile** — Kotlin, Jetpack Compose, Room (SQLCipher), Retrofit, OkHttp SSE, WorkManager, EncryptedSharedPreferences for the auth token.

**Backend** — Rust, Axum 0.7, sqlx, PostgreSQL, JWT (jsonwebtoken), Argon2id, tower_governor for per-IP rate limiting, tower-http security headers, broadcast channels for SSE fan-out.

**Web dashboard** — Rust, Leptos 0.8 (CSR), Tailwind, charming/leptos-chartistry, gloo-net.

**Web landing** — Next.js (static export), Tailwind, framer-motion.

**Database** — PostgreSQL on AWS RDS (private subnet, encrypted at rest).

**Infrastructure** — All AWS: ECS Fargate + ALB for backend, S3 + CloudFront + ACM + Route 53 for the two static sites, ECR for backend images, Secrets Manager for env, CloudWatch for logs/alarms. CI/CD via GitHub Actions with OIDC role assumption (no long-lived AWS keys in the repo).

## Build status

Phases 0–7 (scaffold → auth → sleep → pomodoro → calendar → dashboard → AWS deploy → web analytics) are **shipped**. Android is at v1.11, dashboard is feature-complete with realtime sync via Server-Sent Events.

What's planned for **v2 and beyond** lives in three follow-up phases:

- **Phase 8 — Alarm & Missions** — Alarmy-style alarms with math/shake/photo dismiss missions.
- **Phase 9 — AI Integration** — backend-mediated Claude calls for weekly insights, natural-language event parsing, an in-app coach, and anomaly detection.
- **Phase 10 — Wearables** — Fitbit + Wear OS companion, sleep stages / HR / HRV, stage-aware wake on Phase 8 alarms.

These aren't started yet — the current working tree shipping for Phase 6 (Play Store launch) is the milestone in flight.

## Security posture

The backend, dashboard, and Android app went through a full security audit before this repo went public. Highlights:

- HTTPS everywhere; HSTS preload + restrictive CSP at CloudFront.
- JWT pinned to HS256 with `iss`/`aud` validation and per-user revocation via a `token_version` column — password change / reset invalidates every device.
- Argon2id (m=64 MiB, t=3) password hashing, with transparent rehash on login for older accounts.
- Per-IP rate limiting (strict on `/auth/*`, generous on the rest).
- Length caps + body-size limits on every input.
- Android: `EncryptedSharedPreferences` for the JWT, SQLCipher for the local Room DB, `allowBackup="false"`, R8 release builds, network-security-config restricting cleartext to dev hosts only.
- Dashboard: SSE auth via single-use 30-second tickets so JWTs never appear in URLs / browser history / access logs.
- All GitHub Actions pinned to commit SHAs; Dependabot manages updates.

## License

[MIT](LICENSE) — © 2026 Che-Yu Wu.

## Why does this exist

It's a personal portfolio project — built to learn full-stack mobile + cloud development from scratch, to use Rust as much as the problem domain allowed, and to run it on a real production AWS stack rather than a hosted-platform escape hatch. The asymmetry between local directory name (`ultimate_productivity_app`) and GitHub repo (`ultimate-productivity-app`) is intentional and predates the renaming pass to "Ultiq".

## Local dev

Each subproject has its own conventions; see the relevant `Cargo.toml` / `build.gradle.kts` / `package.json`. A few quick pointers:

- Backend: `docker-compose up -d` for a local Postgres, then `cargo run` from `backend/` with the standard env vars (`DATABASE_URL`, `JWT_SECRET`, `RESEND_API_KEY`).
- Dashboard: `trunk serve` from `web-dashboard/` after `npm install` (Tailwind comes via npm).
- Landing: `npm run dev` from `web-landing/`.
- Android: open `android/` in Android Studio. CLI builds need `JAVA_HOME=/opt/android-studio/jbr` (system JDK lacks `jlink`).
