# web-landing

The marketing / direct-APK-download site for [Ultiq](https://ultiqapp.com).

Next.js (static export). Hosts:
- The download button + sideload instructions on the home page.
- The latest APK at `public/ultiq.<version>.apk`.
- `public/version.json` — the manifest the Android app polls for in-app update prompts.

Deployed to S3 + CloudFront via the `Deploy landing` GitHub Actions workflow on every push to `main` that touches `web-landing/**`. **Not** deployed to Vercel — the AWS pipeline does the static export, S3 sync, and CloudFront invalidation.

See the [top-level README](../README.md) for the full stack overview and project context.

## Local dev

```bash
npm install
npm run dev          # http://localhost:3000
npm run build        # static export to `out/`
```

The APK files in `public/` are gitignored and uploaded to S3 manually as part of each release; the workflow excludes `*.apk` from the `out/` sync to avoid clobbering older versions.
