# Loan Application Document Verification Service

Backend service that manages the documents of a (fictional) loan application and tracks whether
they've been verified by an external, unreliable verification provider. An application is
**ready for review only when the latest version of each required document is verified.**

Design and the reasoning behind it: [TECHNICAL-SPEC.md](TECHNICAL-SPEC.md).

## Versions

| Component | Version |
|---|---|
| Java | 21 |
| Quarkus | 3.20.0 (LTS) |
| PostgreSQL | 16 (`postgres:16-alpine`) |
| Maven | 3.9+ |
| Flyway | managed by Quarkus BOM |
| Docker / Compose | Docker 24+ with Compose v2 |

## Run it

```bash
docker compose up --build
```

- API: http://localhost:8080
- OpenAPI / Swagger UI: http://localhost:8080/q/swagger-ui
- Health: http://localhost:8080/q/health

Compose starts PostgreSQL and the service (`prod` profile). Flyway migrates the schema on startup.
The provider simulator runs inside the service in `AUTO` mode.

Walk through the whole flow (needs `curl` and `jq`):

```bash
./scripts/demo.sh
```

### Dev mode (hot reload)

```bash
mvn quarkus:dev
```

Uses Quarkus Dev Services, which starts a throwaway PostgreSQL in Docker.

## Run the tests

```bash
mvn verify
```

The tests are `@QuarkusTest`s against a real PostgreSQL that Dev Services starts automatically, so
**Docker must be running**. No mocks for the database.

If you'd rather point the tests at the compose database:

```bash
docker compose up -d db
mvn verify -Dquarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/loans_test \
           -Dquarkus.datasource.username=loans -Dquarkus.datasource.password=loans
```

What the suite covers:

| Test | What it proves |
|---|---|
| `ApplicationApiTest` | auth required, role checks, owner isolation (404 for other people's ids), validation, Idempotency-Key replay incl. concurrent requests |
| `DocumentLifecycleTest` | add, replace → new version + history, no-op replace, `If-Match` conflicts, 10 concurrent replaces → exactly one current version, concurrent adds → one wins |
| `VerificationFlowTest` | happy path, repeated requests, duplicate callbacks, conflicting outcomes, **replace while verifying (stale result ignored)**, replace after verified revokes readiness, timeout → resubmit → timeout → late result, late result of a superseded attempt, provider refusing submissions with backoff |
| `CallbackSecurityTest` | unsigned / wrong signature / tampered body / expired timestamp rejected, concurrent redeliveries stored once |
| `SimulatorAdminTest` | simulator admin API and role |
| `ChaosEndToEndTest` | 6 applications against a random, lossy, duplicating provider with mid-flight replacements all converge to READY_FOR_REVIEW |
| `ReadinessTest`, `CallbackDecisionTest` | pure unit tests for the readiness rules and the callback decision table |

## Authentication

The API expects a JWT bearer token (RS256) with:
- `sub`: the applicant id. Every application is scoped to it.
- `groups`: `applicant` for the API, `admin` for the simulator controls.

There is no identity provider in this exercise. For local use the service can mint tokens at
`POST /dev/token`. That endpoint is enabled only in dev/test and in docker-compose
(`APP_DEV_AUTH_ENABLED=true`). The bundled RSA keys in `src/main/resources/dev-keys` are for local use only.

```bash
TOKEN=$(curl -s -X POST localhost:8080/dev/token -H 'Content-Type: application/json' \
  -d '{"subject":"applicant-42","roles":["applicant"]}' | jq -r .accessToken)
ADMIN=$(curl -s -X POST localhost:8080/dev/token -H 'Content-Type: application/json' \
  -d '{"subject":"ops-1","roles":["admin"]}' | jq -r .accessToken)
```

## API

All under `/api/v1/applications`, JSON in and out, `Authorization: Bearer $TOKEN`.

| Method & path | Purpose |
|---|---|
| `POST /` | Create an application. Optional `Idempotency-Key` header. |
| `GET /` · `GET /{id}` | List / get your applications, including derived `status` and `readyForReview` |
| `POST /{id}/documents` | Add the first version of a document category |
| `PUT /{id}/documents/{category}` | Replace with a new version. Optional `If-Match: "<version>"`. |
| `GET /{id}/documents[?includeHistory=true]` | Current documents (or every version) |
| `GET /{id}/documents/by-id/{documentId}` | One document version |
| `POST /{id}/verification-requests` | Queue verification of every current document that needs it (202) |
| `GET /{id}/verification` | Progress per category, and whether the application is ready for review |
| `POST /provider/callbacks` | Provider webhook (HMAC-signed, no JWT) |
| `/simulator/**` | Simulator controls (admin role) |

Categories: `IDENTITY_PROOF`, `ADDRESS_PROOF`, `INCOME_PROOF`.

### Sample calls

```bash
H='Content-Type: application/json'; A="Authorization: Bearer $TOKEN"

# create
APP_ID=$(curl -s -X POST localhost:8080/api/v1/applications -H "$H" -H "$A" -H 'Idempotency-Key: k-001' \
  -d '{"applicantName":"Asha Fictional","requestedAmount":250000,"currency":"INR","purpose":"Home renovation"}' | jq -r .id)

# add a document (metadata only)
curl -s -X POST localhost:8080/api/v1/applications/$APP_ID/documents -H "$H" -H "$A" -d '{
  "category": "IDENTITY_PROOF",
  "fileRef": "s3://fictional-loan-docs/asha/passport.pdf",
  "filename": "passport.pdf",
  "contentHash": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
  "contentType": "application/pdf",
  "sizeBytes": 183422
}'

# replace it, but only if version 1 is still current
curl -s -X PUT localhost:8080/api/v1/applications/$APP_ID/documents/IDENTITY_PROOF -H "$H" -H "$A" \
  -H 'If-Match: "1"' -d '{
  "fileRef": "s3://fictional-loan-docs/asha/passport-rescan.pdf",
  "filename": "passport-rescan.pdf",
  "contentHash": "60303ae22b998861bce3b28f33eec1be758a213c86c93c076dbe9f558c11c752"
}'

# request verification, then watch progress
curl -s -X POST localhost:8080/api/v1/applications/$APP_ID/verification-requests -H "$A" | jq
curl -s localhost:8080/api/v1/applications/$APP_ID/verification -H "$A" | jq
```

Progress response (abridged):

```json
{
  "applicationId": "…",
  "status": "VERIFICATION_IN_PROGRESS",
  "readyForReview": false,
  "requiredCount": 3,
  "verifiedCount": 2,
  "documents": [
    {"category": "IDENTITY_PROOF", "present": true, "version": 2, "status": "PENDING"},
    {"category": "ADDRESS_PROOF",  "present": true, "version": 1, "status": "VERIFIED"},
    {"category": "INCOME_PROOF",   "present": true, "version": 1, "status": "VERIFIED"}
  ],
  "missingCategories": []
}
```

Application `status` is one of `AWAITING_DOCUMENTS`, `AWAITING_VERIFICATION_REQUEST`,
`VERIFICATION_IN_PROGRESS`, `ACTION_REQUIRED` (a document was rejected or verification failed) and `READY_FOR_REVIEW`.

Errors use one shape: `{"status":409,"code":"DOCUMENT_EXISTS","message":"…","fieldErrors":[]}`.

## The provider simulator

The simulator lives in the service (`SimulatedVerificationProvider`) and behaves like a flaky
third party. It receives submissions from the outbox and answers by POSTing **HMAC-signed callbacks over
real HTTP** to `/provider/callbacks`, so the production callback path is the one that gets exercised.

| Setting | Effect |
|---|---|
| `mode` | `AUTO` answers by itself. `MANUAL` waits for you to trigger callbacks. |
| `minDelayMs` / `maxDelayMs` | random latency per answer, so results arrive out of order |
| `submitFailureRate` | share of submissions refused with a simulated 503. The service retries with backoff. |
| `dropRate` | share of submissions never answered. The service times out and re-submits. |
| `duplicateRate` | share of answers delivered twice (same event id) |
| `rejectRate` | share of documents rejected |

**Deterministic hints** (AUTO mode): a filename containing `reject` is rejected, `drop` is never
answered, `dup` is delivered three times, and `slow` uses the max delay.

Defaults come from `app.simulator.*` in `application.properties` (env vars `APP_SIMULATOR_*`).
Change them at runtime:

```bash
AH="Authorization: Bearer $ADMIN"

curl -s localhost:8080/simulator/settings -H "$AH" | jq

# make the provider nasty
curl -s -X PUT localhost:8080/simulator/settings -H "$AH" -H 'Content-Type: application/json' -d '{
  "mode":"AUTO","minDelayMs":200,"maxDelayMs":8000,
  "submitFailureRate":0.3,"dropRate":0.2,"duplicateRate":0.5,"rejectRate":0.1}'

# or drive it by hand to reproduce a race
curl -s -X PUT localhost:8080/simulator/settings -H "$AH" -H 'Content-Type: application/json' -d '{
  "mode":"MANUAL","minDelayMs":0,"maxDelayMs":0,
  "submitFailureRate":0,"dropRate":0,"duplicateRate":0,"rejectRate":0}'
curl -s "localhost:8080/simulator/jobs?state=AWAITING_MANUAL" -H "$AH" | jq
curl -s -X POST localhost:8080/simulator/jobs/<externalRef>/callbacks -H "$AH" -H 'Content-Type: application/json' \
  -d '{"outcome":"VERIFIED","times":3}' | jq     # same event delivered 3 times

# back to configured defaults
curl -s -X DELETE localhost:8080/simulator/settings -H "$AH"
```

A typical manual race: request verification → replace the document → deliver `VERIFIED` for the
old job's `externalRef`. The callback is acknowledged with disposition `STALE`, and the application stays not ready.

## Configuration

| Property (env var) | Default | Notes |
|---|---|---|
| `app.provider.callback-secret` (`APP_PROVIDER_CALLBACK_SECRET`) | none in prod | ≥ 32 chars. Startup fails without it. |
| `app.provider.signature-tolerance` | `5m` | allowed clock skew / replay window for callbacks |
| `app.verification.timeout` | `2m` | wait for a callback before re-submitting |
| `app.verification.max-submissions` | `3` | after this, attempt → TIMED_OUT/FAILED and document → FAILED |
| `app.verification.backoff-base` / `backoff-max` | `2s` / `1m` | exponential backoff with jitter for refused submissions |
| `app.outbox.poll-interval` / `batch-size` | `1s` / `50` | outbox dispatcher |
| `app.dev-auth.enabled` (`APP_DEV_AUTH_ENABLED`) | `false` | `/dev/token` |
| `app.simulator.*` | see file | simulator defaults |

## Project layout

```
src/main/java/com/loanverify
├── api/            REST resources, exception mapping, DTOs
├── service/        application, documents, verification, callbacks, outbox, readiness, idempotency
├── domain/         JPA entities and enums
├── provider/       provider port, HMAC signer, simulator
├── security/       roles, current user
└── config/         typed configuration
src/main/resources/db/migration   Flyway migrations
```

## Time spent

_Fill in before submitting._
