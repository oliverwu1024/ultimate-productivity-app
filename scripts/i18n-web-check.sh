#!/usr/bin/env bash
# Web i18n catalog integrity check.
# (Android's values-* catalogs are covered natively by Android Lint:
#  MissingTranslation / ExtraTranslation / StringFormatMatches.)
#
# Assumes FLAT JSON catalogs, base locale = en.json, one file per locale.
# Per catalog dir it asserts:
#   (1) every base key exists in every other locale — no missing, no extra keys, and
#   (2) placeholder tokens ({x}, {{x}}, %s / %d / %1$s) match per shared key.
#
# It NO-OPS when a base catalog is absent, so it can live in CI now and only
# starts enforcing once 13.3 (dashboard) / 13.4 (landing) create real catalogs.
# When those land, point DIRS at the i18n library's actual catalog root.
set -euo pipefail

DIRS=(
  "web-landing/messages"
  "web-dashboard/locales"
)
BASE="en"

# Emit the sorted multiset of placeholder tokens found in a string.
# `grep` exits 1 on a placeholder-free string (the common case); the `|| true`
# keeps that from tripping `set -o pipefail`/`set -e` and aborting the whole
# check on the first plain string it sees.
placeholders() {
  { grep -oE '\{\{[^}]+\}\}|\{[^}]+\}|%[0-9]*\$?[sd]' <<<"${1:-}" || true; } | sort | tr '\n' ' '
}

rc=0
checked_any=0

for dir in "${DIRS[@]}"; do
  base_file="$dir/$BASE.json"
  if [[ ! -f "$base_file" ]]; then
    echo "i18n-web: no base catalog at $base_file — skipping"
    continue
  fi
  checked_any=1
  mapfile -t base_keys < <(jq -r 'keys[]' "$base_file")

  for f in "$dir"/*.json; do
    loc="$(basename "$f" .json)"
    [[ "$loc" == "$BASE" ]] && continue

    # LC_ALL=C so `sort` and `comm` share byte-order collation — otherwise a
    # UTF-8 locale can make `sort`'s output look "unsorted" to `comm` (noisy
    # warnings, and risks an unreliable diff on collation-ambiguous keys).
    missing="$(LC_ALL=C comm -23 <(printf '%s\n' "${base_keys[@]}" | LC_ALL=C sort) <(jq -r 'keys[]' "$f" | LC_ALL=C sort) || true)"
    extra="$(LC_ALL=C comm -13 <(printf '%s\n' "${base_keys[@]}" | LC_ALL=C sort) <(jq -r 'keys[]' "$f" | LC_ALL=C sort) || true)"
    if [[ -n "$missing" ]]; then echo "::error::[$dir/$loc] missing keys:"; echo "$missing"; rc=1; fi
    if [[ -n "$extra"   ]]; then echo "::error::[$dir/$loc] extra keys:";   echo "$extra";   rc=1; fi

    for k in "${base_keys[@]}"; do
      jq -e --arg k "$k" 'has($k)' "$f" >/dev/null || continue
      bt="$(placeholders "$(jq -r --arg k "$k" '.[$k]' "$base_file")")"
      lt="$(placeholders "$(jq -r --arg k "$k" '.[$k]' "$f")")"
      if [[ "$bt" != "$lt" ]]; then
        echo "::error::[$dir/$loc] placeholder mismatch for '$k': base=[$bt] loc=[$lt]"
        rc=1
      fi
    done
  done
done

if [[ "$checked_any" == "0" ]]; then
  echo "i18n-web: no catalogs present yet — nothing to check (expected until 13.3 / 13.4)."
fi
exit "$rc"
