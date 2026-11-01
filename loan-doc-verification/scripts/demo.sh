#!/usr/bin/env bash
# End-to-end walkthrough against a running service (docker compose up).
# Requires: curl, jq.  Usage: ./scripts/demo.sh [base-url]
set -euo pipefail
BASE=${1:-http://localhost:8080}
J='Content-Type: application/json'

say() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
hash() { printf '%s' "$1" | sha256sum | cut -d' ' -f1; }

say "Get a dev token for a fictional applicant"
TOKEN=$(curl -sf -X POST "$BASE/dev/token" -H "$J" \
  -d '{"subject":"demo-applicant","roles":["applicant"]}' | jq -r .accessToken)
AUTH="Authorization: Bearer $TOKEN"

say "Create a loan application"
APP=$(curl -sf -X POST "$BASE/api/v1/applications" -H "$J" -H "$AUTH" -H "Idempotency-Key: demo-$(date +%s)" \
  -d '{"applicantName":"Asha Fictional","requestedAmount":250000,"currency":"INR","purpose":"Home renovation"}')
echo "$APP" | jq .
APP_ID=$(echo "$APP" | jq -r .id)

for CAT in IDENTITY_PROOF ADDRESS_PROOF INCOME_PROOF; do
  say "Add $CAT"
  curl -sf -X POST "$BASE/api/v1/applications/$APP_ID/documents" -H "$J" -H "$AUTH" -d "{
    \"category\":\"$CAT\",
    \"fileRef\":\"s3://fictional-loan-docs/$APP_ID/${CAT,,}.pdf\",
    \"filename\":\"${CAT,,}.pdf\",
    \"contentHash\":\"$(hash "$APP_ID-$CAT-v1")\",
    \"contentType\":\"application/pdf\",
    \"sizeBytes\":120000}" | jq '{category, version, status}'
done

say "Request verification"
curl -sf -X POST "$BASE/api/v1/applications/$APP_ID/verification-requests" -H "$AUTH" | jq '{queued: [.queued[].category]}'

say "Replace ADDRESS_PROOF while it is being verified"
curl -sf -X PUT "$BASE/api/v1/applications/$APP_ID/documents/ADDRESS_PROOF" -H "$J" -H "$AUTH" -H 'If-Match: "1"' -d "{
  \"fileRef\":\"s3://fictional-loan-docs/$APP_ID/address_proof-v2.pdf\",
  \"filename\":\"address_proof-v2.pdf\",
  \"contentHash\":\"$(hash "$APP_ID-ADDRESS-v2")\"}" | jq '{category, version, status}'
curl -sf -X POST "$BASE/api/v1/applications/$APP_ID/verification-requests" -H "$AUTH" | jq '{queued: [.queued[].category]}'

say "Poll progress (the simulator answers asynchronously, unreliably)"
for i in $(seq 1 40); do
  P=$(curl -sf "$BASE/api/v1/applications/$APP_ID/verification" -H "$AUTH")
  echo "$P" | jq -c '{status, verifiedCount, docs: [.documents[] | {category, version, status}]}'
  STATUS=$(echo "$P" | jq -r .status)
  [ "$STATUS" = "READY_FOR_REVIEW" ] && break
  if [ "$STATUS" = "ACTION_REQUIRED" ]; then
    echo "-> a document failed verification (provider timeout/outage); requesting again"
    curl -sf -X POST "$BASE/api/v1/applications/$APP_ID/verification-requests" -H "$AUTH" > /dev/null
  fi
  sleep 3
done
