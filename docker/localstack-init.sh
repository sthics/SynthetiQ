#!/bin/bash
# ═══════════════════════════════════════════════════════════════════
# LocalStack initialization script
# Creates AWS resources that mirror the production environment.
# Runs automatically when LocalStack container starts.
# ═══════════════════════════════════════════════════════════════════

set -euo pipefail

echo "Initializing LocalStack resources..."

# ── Dead Letter Queue (must be created first) ─────────────────────
awslocal sqs create-queue \
  --queue-name synthetiq-review-dlq \
  --attributes '{
    "MessageRetentionPeriod": "1209600"
  }'

DLQ_ARN=$(awslocal sqs get-queue-attributes \
  --queue-url http://localhost:4566/000000000000/synthetiq-review-dlq \
  --attribute-names QueueArn \
  --query 'Attributes.QueueArn' --output text)

# ── Main Review Queue ─────────────────────────────────────────────
awslocal sqs create-queue \
  --queue-name synthetiq-review-tasks \
  --attributes "{
    \"VisibilityTimeout\": \"120\",
    \"MessageRetentionPeriod\": \"86400\",
    \"RedrivePolicy\": \"{\\\"deadLetterTargetArn\\\":\\\"${DLQ_ARN}\\\",\\\"maxReceiveCount\\\":\\\"3\\\"}\"
  }"

# ── S3 Bucket for artifacts ──────────────────────────────────────
awslocal s3 mb s3://synthetiq-artifacts

# ── SNS Topic for webhook fanout (future use) ────────────────────
awslocal sns create-topic --name synthetiq-events

echo "LocalStack initialization complete!"
echo "  SQS Queue: synthetiq-review-tasks"
echo "  SQS DLQ:   synthetiq-review-dlq"
echo "  S3 Bucket: synthetiq-artifacts"
echo "  SNS Topic: synthetiq-events"
