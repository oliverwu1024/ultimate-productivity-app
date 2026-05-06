# Release checklist

Solo-user release smoke-test guide. Pick the section(s) that apply to what you touched and run through them after the deploy goes green. Aim is "5-minute confidence check," not exhaustive QA.

For backend changes, **always** confirm `https://api.ultiqapp.com/health` returns `200` and the `ultiq-backend-unhealthy` CloudWatch alarm stays `OK` for 5 minutes after rollout.

## Backend (`/backend`)

- [ ] Login on web dashboard or phone — confirm a fresh JWT works.
- [ ] If you touched `routes/auth.rs`: also test forgot-password (email arrives) and reset (token works once, blocks reuse).
- [ ] If you touched a CRUD route (calendar/sleep/sessions/checklist): create + read + update + delete one record on each of phone and dashboard, confirm SSE pushes the change to the other side within ~1s.
- [ ] If you bumped a Cargo dep with crypto features: log in, then load `/auth/me` — JWT encode + decode in one round-trip is the canary for opaque crypto-backend regressions.
- [ ] If you changed an SQL migration: tail `aws logs tail /ecs/ultiq-backend --since 5m` and grep for `panic|migration` after deploy.

## Android (`/android`)

If you don't ship an APK, skip this section.

- [ ] After build, install the APK on your device by direct download (the in-app banner takes one release to catch up — see the v1.10→v1.11 episode).
- [ ] Cold-start the app (force-stop first) — onboarding gate or login screen renders without crash.
- [ ] Open every bottom-tab once (Dashboard, Sleep, Sessions, Calendar, Checklist) — none crashes, all render existing data.
- [ ] If you touched the **Sessions** screen: tap an old session row → details expand inline; long-running services (focus tracking) still start cleanly.
- [ ] If you touched **Sleep**: start a session, stop it after 30 seconds, confirm the record appears in the list.
- [ ] If you touched **Calendar**: create a future event, then a past event; confirm only the past one shows the mark-done checkbox.
- [ ] If you touched **Checklist**: add an item dated yesterday, reopen the app, confirm the carry-over banner surfaces.
- [ ] If you bumped a release version: confirm `version.json` on S3 advertises it AND the existing-install banner test still works (background → foreground → banner).

## Web dashboard (`/web-dashboard`)

- [ ] After deploy, open `app.ultiqapp.com` in a fresh incognito window — login, hit each page once, no console errors.
- [ ] Light/dark theme toggle works on the page you touched.
- [ ] If you touched a page that subscribes to SSE: open the page in two tabs (or phone + browser), make a change in one, confirm the other updates without a manual refresh.

## Web landing (`/web-landing`)

- [ ] `https://ultiqapp.com/version.json` returns the right `versionCode` + `versionName`.
- [ ] Download button serves the latest APK; the install instructions block shows the matching filename.

## Cross-cutting

- [ ] `gh run list --limit 3` — every relevant deploy workflow finished `success`.
- [ ] No new alerts in the GitHub repo's Security tab after deploy.

## When this list is no longer enough

Once you have ~10 active users, the cost of regressions outpaces the cost of automated tests. At that point: invest in a `cargo test` integration suite for backend routes (auth + calendar are the highest-leverage), install Play Store internal testing, and wire up Crashlytics/Sentry for the Android app. See `MEMORY.md` for the phase-B/C plan.
