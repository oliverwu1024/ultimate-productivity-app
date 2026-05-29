#!/usr/bin/env bash
# Attaches a CloudFront ResponseHeadersPolicy to the dashboard + landing
# distributions. Two policies because the two sites have different script
# requirements:
#   - Dashboard (Leptos/WASM, no inline scripts): tight CSP, scripts only
#     from same origin + 'wasm-unsafe-eval' for the WASM loader.
#   - Landing (Next.js static export with hydration): needs 'unsafe-inline'
#     because Next emits inline bootstrap scripts. Loosens script-src but
#     keeps everything else (HSTS, frame deny, no-referrer, restricted
#     connect/img/font sources) identical.
#
# Idempotent: re-running updates each policy in place. Run after publishing
# the repo and any time the CSP needs to change.
#
# Required env: CLOUDFRONT_WEB_DIST_ID, CLOUDFRONT_DIST_ID.

set -euo pipefail

PROFILE="${AWS_PROFILE:-ultiq}"
DASHBOARD_POLICY_NAME="ultiq-security-headers-dashboard"
LANDING_POLICY_NAME="ultiq-security-headers-landing"
LEGACY_POLICY_NAME="ultiq-security-headers"

# Trunk emits an inline `<script type="module">` to bootstrap the WASM on
# every build (it references hash-versioned filenames so pinning a SHA in
# CSP would require regenerating the policy on every deploy). Accept
# 'unsafe-inline' for scripts as the practical Trunk-build trade-off; all
# other directives stay locked down.
DASHBOARD_CSP="default-src 'self'; \
script-src 'self' 'unsafe-inline' 'wasm-unsafe-eval'; \
style-src 'self' 'unsafe-inline'; \
img-src 'self' data:; \
connect-src 'self' https://api.ultiqapp.com; \
font-src 'self' data:; \
media-src 'self' blob:; \
frame-ancestors 'none'; \
base-uri 'self'; \
form-action 'self'"

# Next.js hydration uses inline <script> tags emitted by the framework. They
# can't be hashed (vary per build) so we accept 'unsafe-inline' on the landing
# site only. Everything else stays locked down.
LANDING_CSP="default-src 'self'; \
script-src 'self' 'unsafe-inline'; \
style-src 'self' 'unsafe-inline'; \
img-src 'self' data:; \
connect-src 'self' https://api.ultiqapp.com; \
font-src 'self' data:; \
frame-ancestors 'none'; \
base-uri 'self'; \
form-action 'self'"

write_policy_json() {
  local name="$1"
  local csp="$2"
  cat > /tmp/security-headers-policy.json <<JSON
{
  "Name": "$name",
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
      "ContentSecurityPolicy": "$csp"
    },
    "XSSProtection": { "Override": true, "Protection": false, "ModeBlock": false }
  }
}
JSON
}

upsert_policy() {
  local name="$1"
  local csp="$2"
  write_policy_json "$name" "$csp"
  local existing
  existing=$(aws cloudfront list-response-headers-policies --profile "$PROFILE" \
    --query "ResponseHeadersPolicyList.Items[?ResponseHeadersPolicy.ResponseHeadersPolicyConfig.Name=='$name'].ResponseHeadersPolicy.Id | [0]" \
    --output text 2>/dev/null || echo "None")
  if [[ "$existing" != "None" && -n "$existing" ]]; then
    local etag
    etag=$(aws cloudfront get-response-headers-policy --id "$existing" \
      --profile "$PROFILE" --query 'ETag' --output text)
    aws cloudfront update-response-headers-policy \
      --id "$existing" \
      --if-match "$etag" \
      --response-headers-policy-config file:///tmp/security-headers-policy.json \
      --profile "$PROFILE" >/dev/null
    echo "  updated existing policy: $existing ($name)"
    echo "$existing"
  else
    local new_id
    new_id=$(aws cloudfront create-response-headers-policy \
      --response-headers-policy-config file:///tmp/security-headers-policy.json \
      --profile "$PROFILE" \
      --query 'ResponseHeadersPolicy.Id' --output text)
    echo "  created policy: $new_id ($name)"
    echo "$new_id"
  fi
}

step() { printf '\n\033[1;36m▶ %s\033[0m\n' "$*"; }

step "1/3  Upsert dashboard ResponseHeadersPolicy"
DASH_POLICY_ID=$(upsert_policy "$DASHBOARD_POLICY_NAME" "$DASHBOARD_CSP" | tail -1)

step "    Upsert landing ResponseHeadersPolicy"
LAND_POLICY_ID=$(upsert_policy "$LANDING_POLICY_NAME" "$LANDING_CSP" | tail -1)

attach_policy() {
  local DIST_ID="$1"
  local LABEL="$2"
  local POLICY_ID="$3"
  step "Attach policy $POLICY_ID to $LABEL ($DIST_ID)"
  aws cloudfront get-distribution-config --id "$DIST_ID" \
    --profile "$PROFILE" > /tmp/dist-cfg.json
  local ETAG
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

step "2/3  Attach dashboard policy to web dashboard distribution"
WEB_DIST="${CLOUDFRONT_WEB_DIST_ID:-}"
if [[ -z "$WEB_DIST" ]]; then
  echo "Set CLOUDFRONT_WEB_DIST_ID to attach to the dashboard." >&2
else
  attach_policy "$WEB_DIST" "dashboard" "$DASH_POLICY_ID"
fi

step "3/3  Attach landing policy to landing distribution"
LANDING_DIST="${CLOUDFRONT_DIST_ID:-}"
if [[ -z "$LANDING_DIST" ]]; then
  echo "Set CLOUDFRONT_DIST_ID to attach to the landing site." >&2
else
  attach_policy "$LANDING_DIST" "landing" "$LAND_POLICY_ID"
fi

echo
echo -e "\033[1;32m✓ Done.\033[0m  CloudFront takes ~5 min to roll out per distribution."
