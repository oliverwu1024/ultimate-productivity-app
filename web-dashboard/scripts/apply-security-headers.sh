#!/usr/bin/env bash
# Attaches a CloudFront ResponseHeadersPolicy to the dashboard + landing
# distributions that emits Strict-Transport-Security, X-Content-Type-Options,
# Referrer-Policy, X-Frame-Options, and a tight Content-Security-Policy.
#
# Idempotent: re-running updates the policy in place. Run after publishing
# the repo and any time the CSP needs to change.
#
# Required env (or arguments): CLOUDFRONT_WEB_DIST_ID, CLOUDFRONT_DIST_ID.
# Reads them from the existing GitHub repo secrets if you pass --gh-secrets.

set -euo pipefail

PROFILE="${AWS_PROFILE:-ultiq}"
POLICY_NAME="ultiq-security-headers"

# CSP — locks scripts/iframes/etc. to same-origin only. The dashboard's
# pre-paint dark-mode script lives in /preinit.js (served from the same
# origin), so script-src is plain 'self' + 'wasm-unsafe-eval' for the WASM
# loader. connect-src allows the API + SSE.
CSP="default-src 'self'; \
script-src 'self' 'wasm-unsafe-eval'; \
style-src 'self' 'unsafe-inline'; \
img-src 'self' data:; \
connect-src 'self' https://api.ultiqapp.com; \
font-src 'self' data:; \
frame-ancestors 'none'; \
base-uri 'self'; \
form-action 'self'"

cat > /tmp/security-headers-policy.json <<JSON
{
  "Name": "$POLICY_NAME",
  "Comment": "Ultiq edge security headers (HSTS, CSP, frame deny, no-referrer)",
  "SecurityHeadersConfig": {
    "StrictTransportSecurity": {
      "Override": true,
      "AccessControlMaxAgeSec": 31536000,
      "IncludeSubdomains": true,
      "Preload": true
    },
    "ContentTypeOptions": { "Override": true },
    "FrameOptions": { "Override": true, "FrameOption": "DENY" },
    "ReferrerPolicy": { "Override": true, "ReferrerPolicy": "strict-origin-when-cross-origin" },
    "ContentSecurityPolicy": {
      "Override": true,
      "ContentSecurityPolicy": "$CSP"
    },
    "XSSProtection": { "Override": true, "Protection": false, "ModeBlock": false }
  }
}
JSON

step() { printf '\n\033[1;36m▶ %s\033[0m\n' "$*"; }

step "1/3  Upsert ResponseHeadersPolicy"
EXISTING=$(aws cloudfront list-response-headers-policies --profile "$PROFILE" \
  --query "ResponseHeadersPolicyList.Items[?ResponseHeadersPolicy.ResponseHeadersPolicyConfig.Name=='$POLICY_NAME'].ResponseHeadersPolicy.Id | [0]" \
  --output text 2>/dev/null || echo "None")
if [[ "$EXISTING" != "None" && -n "$EXISTING" ]]; then
  POLICY_ID="$EXISTING"
  ETAG=$(aws cloudfront get-response-headers-policy --id "$POLICY_ID" \
    --profile "$PROFILE" --query 'ETag' --output text)
  aws cloudfront update-response-headers-policy \
    --id "$POLICY_ID" \
    --if-match "$ETAG" \
    --response-headers-policy-config file:///tmp/security-headers-policy.json \
    --profile "$PROFILE" >/dev/null
  echo "  updated existing policy: $POLICY_ID"
else
  POLICY_ID=$(aws cloudfront create-response-headers-policy \
    --response-headers-policy-config file:///tmp/security-headers-policy.json \
    --profile "$PROFILE" \
    --query 'ResponseHeadersPolicy.Id' --output text)
  echo "  created policy: $POLICY_ID"
fi

attach_policy() {
  local DIST_ID="$1"
  local LABEL="$2"
  step "Attach policy $POLICY_ID to $LABEL ($DIST_ID)"
  aws cloudfront get-distribution-config --id "$DIST_ID" \
    --profile "$PROFILE" > /tmp/dist-cfg.json
  ETAG=$(jq -r '.ETag' /tmp/dist-cfg.json)
  jq --arg pid "$POLICY_ID" \
    '.DistributionConfig.DefaultCacheBehavior.ResponseHeadersPolicyId = $pid | .DistributionConfig' \
    /tmp/dist-cfg.json > /tmp/dist-cfg-new.json
  aws cloudfront update-distribution \
    --id "$DIST_ID" --if-match "$ETAG" \
    --distribution-config file:///tmp/dist-cfg-new.json \
    --profile "$PROFILE" >/dev/null
  echo "  attached"
}

step "2/3  Attach to web dashboard distribution"
WEB_DIST="${CLOUDFRONT_WEB_DIST_ID:-}"
if [[ -z "$WEB_DIST" ]]; then
  echo "Set CLOUDFRONT_WEB_DIST_ID to attach to the dashboard." >&2
else
  attach_policy "$WEB_DIST" "dashboard"
fi

step "3/3  Attach to landing distribution"
LANDING_DIST="${CLOUDFRONT_DIST_ID:-}"
if [[ -z "$LANDING_DIST" ]]; then
  echo "Set CLOUDFRONT_DIST_ID to attach to the landing site." >&2
else
  attach_policy "$LANDING_DIST" "landing"
fi

echo
echo -e "\033[1;32m✓ Done.\033[0m  CloudFront takes ~5 min to roll out per distribution."
