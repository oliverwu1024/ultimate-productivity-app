#!/usr/bin/env bash
# Provisions AWS hosting for the Ultiq web dashboard at app.ultiqapp.com.
#
# Idempotent-ish: re-running picks up where it left off. Safe to abort and resume.
# Run from anywhere; uses absolute resource IDs.
#
# Pre-existing (not created here):
#   - Hosted zone Z09841952ZPTQK6QY9LR4 (ultiqapp.com)
#   - ACM cert arn:aws:acm:us-east-1:...:certificate/d959b863-be7f-430a-b1fa-a0c4314d0ade
#     (created via earlier `aws acm request-certificate` call)
#   - S3 bucket ultiq-web (already created in ap-southeast-2)
#   - IAM role github-actions-deploy (extended below for the new bucket + distribution)

set -euo pipefail

PROFILE="ultiq"
REGION="ap-southeast-2"
US_EAST_1="us-east-1"
ACCOUNT="[aws-account]"
ZONE_ID="Z09841952ZPTQK6QY9LR4"
CERT_ARN="arn:aws:acm:us-east-1:[aws-account]:certificate/d959b863-be7f-430a-b1fa-a0c4314d0ade"
BUCKET="ultiq-web"
DOMAIN="app.ultiqapp.com"

step() { printf '\n\033[1;36m▶ %s\033[0m\n' "$*"; }

step "1/9  Block public access on $BUCKET"
aws s3api put-public-access-block \
  --bucket "$BUCKET" \
  --public-access-block-configuration \
    "BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true" \
  --profile "$PROFILE"

step "2/9  Enable AES256 encryption on $BUCKET"
aws s3api put-bucket-encryption --bucket "$BUCKET" \
  --server-side-encryption-configuration \
    '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}' \
  --profile "$PROFILE"

step "3/9  Upsert ACM validation CNAME in Route 53"
VALIDATION_NAME=$(aws acm describe-certificate \
  --certificate-arn "$CERT_ARN" --profile "$PROFILE" --region "$US_EAST_1" \
  --query 'Certificate.DomainValidationOptions[0].ResourceRecord.Name' --output text)
VALIDATION_VALUE=$(aws acm describe-certificate \
  --certificate-arn "$CERT_ARN" --profile "$PROFILE" --region "$US_EAST_1" \
  --query 'Certificate.DomainValidationOptions[0].ResourceRecord.Value' --output text)
echo "  CNAME: $VALIDATION_NAME -> $VALIDATION_VALUE"
cat > /tmp/cert-validation.json <<JSON
{
  "Changes": [
    {
      "Action": "UPSERT",
      "ResourceRecordSet": {
        "Name": "$VALIDATION_NAME",
        "Type": "CNAME",
        "TTL": 300,
        "ResourceRecords": [{"Value": "$VALIDATION_VALUE"}]
      }
    }
  ]
}
JSON
aws route53 change-resource-record-sets \
  --hosted-zone-id "$ZONE_ID" \
  --change-batch file:///tmp/cert-validation.json \
  --profile "$PROFILE" >/dev/null
echo "  validation record submitted"

step "4/9  Create CloudFront Origin Access Control (OAC) for $BUCKET"
EXISTING_OAC=$(aws cloudfront list-origin-access-controls \
  --profile "$PROFILE" \
  --query "OriginAccessControlList.Items[?Name=='ultiq-web-oac'].Id | [0]" \
  --output text 2>/dev/null || echo "None")
if [[ "$EXISTING_OAC" != "None" && -n "$EXISTING_OAC" ]]; then
  OAC_ID="$EXISTING_OAC"
  echo "  reusing existing OAC: $OAC_ID"
else
  OAC_ID=$(aws cloudfront create-origin-access-control \
    --origin-access-control-config \
      'Name=ultiq-web-oac,Description=OAC for ultiq-web,SigningProtocol=sigv4,SigningBehavior=always,OriginAccessControlOriginType=s3' \
    --profile "$PROFILE" \
    --query 'OriginAccessControl.Id' --output text)
  echo "  created OAC: $OAC_ID"
fi

step "5/9  Wait for ACM cert to be ISSUED"
for i in {1..30}; do
  STATUS=$(aws acm describe-certificate --certificate-arn "$CERT_ARN" \
    --profile "$PROFILE" --region "$US_EAST_1" \
    --query 'Certificate.Status' --output text)
  echo "  attempt $i: status=$STATUS"
  if [[ "$STATUS" == "ISSUED" ]]; then break; fi
  sleep 10
done
if [[ "$STATUS" != "ISSUED" ]]; then
  echo "  cert still not issued after 5 min; aborting" >&2
  exit 1
fi

step "6/9  Create CloudFront distribution"
EXISTING_DIST=$(aws cloudfront list-distributions --profile "$PROFILE" \
  --query "DistributionList.Items[?Aliases.Items && contains(Aliases.Items, '$DOMAIN')].Id | [0]" \
  --output text 2>/dev/null || echo "None")
if [[ "$EXISTING_DIST" != "None" && -n "$EXISTING_DIST" ]]; then
  DIST_ID="$EXISTING_DIST"
  echo "  reusing existing distribution: $DIST_ID"
else
  CALLER_REF="ultiq-web-$(date +%s)"
  cat > /tmp/cf-config.json <<JSON
{
  "CallerReference": "$CALLER_REF",
  "Comment": "Ultiq web dashboard",
  "Enabled": true,
  "DefaultRootObject": "index.html",
  "Aliases": { "Quantity": 1, "Items": ["$DOMAIN"] },
  "Origins": {
    "Quantity": 1,
    "Items": [
      {
        "Id": "ultiq-web-s3",
        "DomainName": "$BUCKET.s3.$REGION.amazonaws.com",
        "OriginPath": "",
        "S3OriginConfig": { "OriginAccessIdentity": "" },
        "OriginAccessControlId": "$OAC_ID",
        "CustomHeaders": { "Quantity": 0 },
        "ConnectionAttempts": 3,
        "ConnectionTimeout": 10
      }
    ]
  },
  "DefaultCacheBehavior": {
    "TargetOriginId": "ultiq-web-s3",
    "ViewerProtocolPolicy": "redirect-to-https",
    "AllowedMethods": {
      "Quantity": 2,
      "Items": ["GET", "HEAD"],
      "CachedMethods": { "Quantity": 2, "Items": ["GET", "HEAD"] }
    },
    "Compress": true,
    "CachePolicyId": "658327ea-f89d-4fab-a63d-7e88639e58f6",
    "FunctionAssociations": { "Quantity": 0 },
    "LambdaFunctionAssociations": { "Quantity": 0 },
    "FieldLevelEncryptionId": "",
    "SmoothStreaming": false,
    "TrustedKeyGroups": { "Enabled": false, "Quantity": 0 },
    "TrustedSigners": { "Enabled": false, "Quantity": 0 }
  },
  "CustomErrorResponses": {
    "Quantity": 2,
    "Items": [
      { "ErrorCode": 403, "ResponsePagePath": "/index.html", "ResponseCode": "200", "ErrorCachingMinTTL": 10 },
      { "ErrorCode": 404, "ResponsePagePath": "/index.html", "ResponseCode": "200", "ErrorCachingMinTTL": 10 }
    ]
  },
  "PriceClass": "PriceClass_100",
  "ViewerCertificate": {
    "ACMCertificateArn": "$CERT_ARN",
    "SSLSupportMethod": "sni-only",
    "MinimumProtocolVersion": "TLSv1.2_2021",
    "Certificate": "$CERT_ARN",
    "CertificateSource": "acm"
  },
  "HttpVersion": "http2and3",
  "IsIPV6Enabled": true,
  "Restrictions": { "GeoRestriction": { "RestrictionType": "none", "Quantity": 0 } },
  "WebACLId": "",
  "Staging": false
}
JSON
  DIST_ID=$(aws cloudfront create-distribution \
    --distribution-config file:///tmp/cf-config.json \
    --profile "$PROFILE" \
    --query 'Distribution.Id' --output text)
  echo "  created distribution: $DIST_ID"
fi

DIST_DOMAIN=$(aws cloudfront get-distribution \
  --id "$DIST_ID" --profile "$PROFILE" \
  --query 'Distribution.DomainName' --output text)
DIST_HOSTED_ZONE_ID="Z2FDTNDATAQYW2"  # CloudFront's fixed hosted zone ID for aliases
echo "  CloudFront domain: $DIST_DOMAIN"

step "7/9  Update bucket policy to allow CloudFront OAC reads"
cat > /tmp/bucket-policy.json <<JSON
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "AllowCloudFrontServicePrincipal",
      "Effect": "Allow",
      "Principal": { "Service": "cloudfront.amazonaws.com" },
      "Action": "s3:GetObject",
      "Resource": "arn:aws:s3:::$BUCKET/*",
      "Condition": {
        "StringEquals": {
          "AWS:SourceArn": "arn:aws:cloudfront::$ACCOUNT:distribution/$DIST_ID"
        }
      }
    }
  ]
}
JSON
aws s3api put-bucket-policy --bucket "$BUCKET" \
  --policy file:///tmp/bucket-policy.json --profile "$PROFILE"

step "8/9  Create Route 53 A/ALIAS for $DOMAIN -> CloudFront"
cat > /tmp/alias.json <<JSON
{
  "Changes": [
    {
      "Action": "UPSERT",
      "ResourceRecordSet": {
        "Name": "$DOMAIN.",
        "Type": "A",
        "AliasTarget": {
          "HostedZoneId": "$DIST_HOSTED_ZONE_ID",
          "DNSName": "$DIST_DOMAIN.",
          "EvaluateTargetHealth": false
        }
      }
    }
  ]
}
JSON
aws route53 change-resource-record-sets \
  --hosted-zone-id "$ZONE_ID" \
  --change-batch file:///tmp/alias.json \
  --profile "$PROFILE" >/dev/null
echo "  alias record submitted"

step "9/9  Extend github-actions-deploy IAM role with web-dashboard perms"
cat > /tmp/web-deploy-policy.json <<JSON
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:PutObject", "s3:DeleteObject", "s3:GetObject", "s3:ListBucket"],
      "Resource": [
        "arn:aws:s3:::$BUCKET",
        "arn:aws:s3:::$BUCKET/*"
      ]
    },
    {
      "Effect": "Allow",
      "Action": ["cloudfront:CreateInvalidation", "cloudfront:GetInvalidation"],
      "Resource": "arn:aws:cloudfront::$ACCOUNT:distribution/$DIST_ID"
    }
  ]
}
JSON
aws iam put-role-policy \
  --role-name github-actions-deploy \
  --policy-name ultiq-web-deploy \
  --policy-document file:///tmp/web-deploy-policy.json \
  --profile "$PROFILE"

cat <<SUMMARY

\033[1;32m✓ Provisioning complete.\033[0m

  S3 bucket:           $BUCKET (private, AES256)
  CloudFront dist ID:  $DIST_ID
  CloudFront domain:   $DIST_DOMAIN
  ACM cert:            $CERT_ARN
  Public URL:          https://$DOMAIN

Next:
  1. Add GitHub repo secret:  CLOUDFRONT_WEB_DIST_ID = $DIST_ID
     (Settings → Secrets and variables → Actions → New repository secret)

  2. The deploy workflow will be created at .github/workflows/deploy-web.yml
     by the next step in the Claude session — push to main with
     web-dashboard/** changes triggers it.

  3. CloudFront distribution status takes ~5-15 min to fully deploy. Until then,
     https://$DOMAIN may serve 503s. Check with:
     aws cloudfront get-distribution --id $DIST_ID --profile $PROFILE \\
       --query 'Distribution.Status' --output text
SUMMARY
