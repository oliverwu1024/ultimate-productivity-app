#!/bin/sh
# Nightly logical backup of the Ultiq production database.
# Reads DATABASE_URL from ECS-injected env (sourced from Secrets Manager) and
# uploads a gzipped pg_dump to S3 with a UTC-timestamped filename. S3 lifecycle
# policy on the bucket handles retention, so this script doesn't manage rotation.

set -eu

: "${DATABASE_URL:?DATABASE_URL not set}"
: "${BACKUP_BUCKET:?BACKUP_BUCKET not set}"

DATE=$(date -u +%Y%m%d-%H%M%S)
KEY="ultiq-${DATE}.sql.gz"
LOCAL="/tmp/${KEY}"

echo "[${DATE}] pg_dump → ${LOCAL}"
pg_dump "$DATABASE_URL" --no-owner --no-acl --format=plain | gzip > "$LOCAL"
SIZE=$(du -h "$LOCAL" | cut -f1)
echo "[${DATE}] dump size: ${SIZE}"

echo "[${DATE}] uploading s3://${BACKUP_BUCKET}/${KEY}"
aws s3 cp "$LOCAL" "s3://${BACKUP_BUCKET}/${KEY}" \
  --content-type application/gzip

rm -f "$LOCAL"
echo "[${DATE}] done."
