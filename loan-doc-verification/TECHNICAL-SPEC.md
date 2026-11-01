# Technical Specification

## 1. Goal

Track the documents of a loan application through verification by an external provider that is
**asynchronous and unreliable**: slow, sometimes silent, sometimes answering more than once. Applicants
can also replace a document while its verification is still running.

The one invariant everything else serves:

> An application is **ready for review** if and only if, for each of `IDENTITY_PROOF`, `ADDRESS_PROOF`
> and `INCOME_PROOF`, the **current (latest) version** of the document is `VERIFIED`. A provider
> result only ever counts for the document version it was requested for.

## 2. Architecture

A single Quarkus service on PostgreSQL. The provider sits behind a port (`VerificationProvider`) with
one implementation, the in-process simulator.

```
 applicant ──JWT──▶ REST API ──▶ services ──▶ PostgreSQL
                                     │            ▲
                    (outbox rows) ───┘            │
 OutboxDispatcher (scheduled) ──submit──▶ Provider (simulator)
                                                  │ async, maybe late / twice / never
 POST /provider/callbacks ◀──HMAC-signed HTTP─────┘
        │
        └─▶ CallbackService ──▶ PostgreSQL
```

Three properties shape the design:

1. **Document versions are immutable.** Replacing a document inserts version *n+1* and marks *n* as no
   longer current. Nothing is overwritten, so a result can always be traced to exactly the file it was for.
2. **Every verification request is an attempt bound to one version** (`verification_attempt`), with a
   random correlation id (`external_ref`) that the provider echoes back. A document accepts a result only
   from the attempt in `document.latest_attempt_id`, and only while it is the current version.
3. **Readiness is derived, never stored.** It is computed from the current versions on every read
   (`Readiness.evaluate`), so no "ready" flag can drift out of sync.

## 3. Data model

```
loan_application (id, owner_id, applicant_name, requested_amount, currency, purpose, …, row_version)
      1
      │
      * document (id, application_id, category, version, is_current,
      │           file_ref, filename, content_hash, content_type, size_bytes,
      │           status, status_reason, latest_attempt_id, superseded_at, row_version)
      │      UNIQUE (application_id, category, version)
      │      UNIQUE (application_id, category) WHERE is_current      ← one current version
      1
      │
      * verification_attempt (id, document_id, external_ref UNIQUE, status, submissions,
                              next_attempt_at, submitted_at, completed_at, last_error, …)
             UNIQUE (document_id) WHERE status IN ('QUEUED','SUBMITTED') ← one in-flight attempt

provider_callback_event (event_id UNIQUE, external_ref, attempt_id, outcome, disposition, received_at)
idempotency_record      (owner_id, scope, idempotency_key) PK, request_hash, response
```

Only metadata is stored. `file_ref` is a storage URI (`s3://`, `gs://`, `azblob://`, `docstore://`),
and `content_hash` is a SHA-256 hex digest.

The partial unique indexes are backstops. The primary concurrency control is row locking (section 6).
If a bug ever slips past the locking, the database still refuses two current versions or two in-flight attempts.

## 4. State machines

**Document (per version)**

```
NOT_REQUESTED ──request──▶ PENDING ──VERIFIED──▶ VERIFIED
      ▲                     │  │ └──REJECTED──▶ REJECTED     (must replace the document)
      │                     │  └──timeout / provider down──▶ FAILED ──request──▶ PENDING
      │                     └──replaced while pending──▶ CANCELLED   (superseded versions only)
 (replace creates a new NOT_REQUESTED version; the old version keeps its status, is_current=false)
```

**Attempt (outbox row)**

```
QUEUED ──claim──▶ SUBMITTED ──callback──▶ VERIFIED | REJECTED
  ▲  ▲               │  │
  │  └─submit failed─┘  └─no callback in timeout─┐
  │    (backoff; after max → FAILED)             │
  └──────────── re-submit same external_ref ─────┘ (after max → TIMED_OUT)
any in-flight state ──document replaced──▶ CANCELLED
```

**Application (derived)**, first match wins:
`ACTION_REQUIRED` (a current doc is REJECTED/FAILED) → `AWAITING_DOCUMENTS` → `AWAITING_VERIFICATION_REQUEST`
→ `READY_FOR_REVIEW` (all current docs VERIFIED) → `VERIFICATION_IN_PROGRESS`.

## 5. Talking to the provider

### Outbox, not in-transaction calls

`POST /verification-requests` only writes `QUEUED` attempts and returns 202. The `OutboxDispatcher`
(every 1s) does the rest:

1. **Claim**: in a short transaction, `SELECT … FOR UPDATE SKIP LOCKED` up to 50 due attempts, set them
   `SUBMITTED`, `submissions++`, commit. `SKIP LOCKED` lets several instances share the queue safely.
2. **Submit** outside any transaction, so a slow provider never holds DB locks or connections.
3. **On failure**, back to `QUEUED` with exponential backoff and jitter (`2s · 2^(n-1)`, cap 1m, +≤25%).
   After `max-submissions` the attempt goes to `FAILED` and the document to `FAILED`.

There is no dual-write problem: if the process dies after commit but before submit, the attempt is
`SUBMITTED` with no answer, and the timeout sweep re-submits it.

### Timeouts

Each tick also sweeps `SUBMITTED` attempts older than `verification.timeout`. Below `max-submissions`
they go back to `QUEUED` and are **re-submitted with the same `external_ref`**, so whichever answer
arrives first wins and any later ones are duplicates. After the maximum the attempt is `TIMED_OUT` and
the document `FAILED`. The applicant sees `ACTION_REQUIRED` and can request again.

### Callbacks

`POST /provider/callbacks` is authenticated by HMAC (section 7), then `CallbackService` handles it in
one transaction using this decision table:

| Situation | Disposition | Effect |
|---|---|---|
| `event_id` already stored | `DUPLICATE` | none |
| unknown `external_ref` | `UNKNOWN_REFERENCE` | none (recorded) |
| attempt already VERIFIED/REJECTED, same outcome | `DUPLICATE` | none |
| attempt already decided, different outcome | `CONFLICTING_OUTCOME` | none, first decision wins, logged |
| attempt CANCELLED, or document no longer current, or a newer attempt exists | `STALE` | none |
| attempt TIMED_OUT/FAILED but still the latest for the current doc | `LATE_APPLIED` | attempt + document updated |
| otherwise (SUBMITTED, or re-QUEUED after timeout) | `APPLIED` | attempt + document updated |

Every authenticated, well-formed callback gets **200 with its disposition**, including duplicates and
stale ones, so the provider stops retrying. All callbacks are kept in `provider_callback_event` for audit.

Why accept late results? A provider that took 3 minutes still verified *that exact file*. If nothing
newer exists (no replacement, no new attempt), throwing the answer away would force a pointless retry.
Once a newer attempt exists, the old answer is stale by definition.

## 6. Concurrency

The races the brief calls out, and how each is closed:

| Race | Mechanism |
|---|---|
| Replace vs. callback for the old version | Both lock the **document row**. Replace takes `application → document`, cancels in-flight attempts and clears `is_current`. A callback that gets the lock afterwards sees `CANCELLED` / not current → `STALE`. A callback that gets the lock first applies to v*n*, which replace then supersedes. Either order leaves v*n+1* `NOT_REQUESTED`, so the application is not ready. |
| Two concurrent replaces / adds of one category | Both lock the **application row** first, so they serialise. Versions stay contiguous (tested with 10 threads). The partial unique index is the backstop. |
| Same callback delivered concurrently | `event_id` UNIQUE. The loser hits 23505 and is reported as `DUPLICATE` (tested with 8 threads). |
| Callback vs. timeout sweep / submit failure | Both lock `document → attempt`, then re-check the attempt state. The sweep only acts if it is still `SUBMITTED` with the same submission count. |
| Two dispatcher instances | `FOR UPDATE SKIP LOCKED` |
| Double request-verification | Application lock, plus the status check (PENDING/VERIFIED skipped), plus the partial unique index on in-flight attempts |
| Duplicate create (client retry) | `Idempotency-Key`: the key row is inserted in the same transaction as the create, so a concurrent twin blocks on the PK and then replays the stored response. Same key with a different body → 422. |

**Lock order is always application → document → attempt.** Paths that start from an attempt (callback,
sweep, submit failure) look up its document id without a lock and then lock document → attempt.
Because the order is fixed, these paths can't deadlock.

`@Version` columns add optimistic checks on top. A concurrent-modification failure maps to 409 and
asks the client to retry.

Replace and add are naturally idempotent, so they don't need idempotency keys: replacing with the same
`fileRef` and `contentHash` returns the current version with 200, and adding an existing category is 409.
`If-Match: "<version>"` gives conditional replace (412 on mismatch).

## 7. Security

- **Authentication**: JWT (RS256) via SmallRye JWT, with issuer and audience checked. Roles come from
  `groups`: `applicant` for the API, `admin` for the simulator. `quarkus.security.jaxrs.deny-unannotated-endpoints=true`
  means any new endpoint is closed until it is explicitly annotated.
- **Authorisation / IDOR**: every application access goes through `ApplicationAccess`, which matches
  `owner_id` against the token `sub`. Someone else's application returns **404, not 403**, so ids can't
  be probed. UUIDv4 ids are unguessable as well.
- **Provider callbacks**: `X-Provider-Signature: sha256=HMAC(secret, timestamp + "." + rawBody)`, checked
  with a constant-time compare over the raw bytes. `X-Provider-Timestamp` must be within ±5 min, which
  bounds replay, and the event-id uniqueness makes replays inside the window harmless. Results only bind
  to unguessable `external_ref`s. The secret must be ≥ 32 chars, and prod startup fails without it.
- **Input validation**: Bean Validation on every body. `fileRef` uses a scheme allowlist with no `..`
  segments. `filename` has no path separators or control characters. The hash must be 64 hex chars.
  Sizes and amounts are bounded, and unknown JSON fields are rejected (mass-assignment guard).
  Request bodies are capped at 64 KB.
- **Errors** never contain stack traces, SQL or parser internals. Unexpected errors get a reference id
  that is logged server-side.
- **Data**: only metadata is stored and all data is fictional. Logs carry ids, never names or hashes.
  Responses set `Cache-Control: no-store` and `X-Content-Type-Options: nosniff`.
- **Container**: runs as a non-root user. Compose binds ports to 127.0.0.1.
- **Dev conveniences**: `/dev/token` and the bundled RSA keys are off unless explicitly enabled, and the
  `prod` profile requires `JWT_PUBLIC_KEY_LOCATION`. The simulator admin API requires `admin` and
  returns 404 when the simulator is disabled.

Not done (would be in a real deployment): rate limiting per client, mTLS or IP allowlisting for the
provider, secret rotation (accepting two secrets during a rollover), audit log shipping, and encryption of
`applicant_name` at rest.

## 8. The simulator

`SimulatedVerificationProvider` implements the provider port in-process. It is configurable at runtime
for latency range, submit-failure rate, drop rate, duplicate rate and reject rate, and can run in `AUTO`
or `MANUAL` mode. Callbacks are signed and sent over real HTTP to the public callback endpoint, so the
simulator exercises authentication, parsing and idempotency end to end. It also retries its own webhook
deliveries on 5xx and network errors, like a real webhook sender.

`MANUAL` mode plus `deliverNow(ref, outcome, times)` is what makes the race tests deterministic. Tests
also drive the dispatcher with an explicit clock (`dispatchDue(now)`, `sweepTimeouts(now)`), and the
background scheduler is disabled in the test profile.

## 9. API summary

See README. Status codes: 201 for creates and new versions (`Location` and `ETag: "<version>"`), 200 for
a no-op replace, 202 for verification requests, 400 validation, 401 unauthenticated, 403 wrong role, 404
not found or not yours, 409 conflict/duplicate category, 412 `If-Match` mismatch, 422 idempotency key reused.

## 10. Trade-offs and alternatives considered

- **In-process scheduler vs. a message broker.** A DB-backed outbox needs no extra infrastructure, is
  transactional with the business write, and scales horizontally with `SKIP LOCKED`. At high volume
  you'd move submissions to Kafka/SQS. The attempt table already makes that change local.
- **Derived readiness vs. a stored flag.** Deriving costs one indexed query per read (≤ 3 current rows)
  and can't go stale. If reviewers needed to query "all ready applications", I'd add a column that is
  maintained in the same transactions that change document status.
- **Rejected documents aren't auto-retried.** A rejection is a statement about the file, so the applicant
  has to replace it. `FAILED` (we never got an answer) can be re-requested.
- **First decision wins on conflicting outcomes.** The alternative, "last write wins", would let a delayed
  duplicate flip a decision. Conflicts are logged for investigation.
- **Pessimistic row locks** on hot paths instead of retry loops on optimistic failures. Contention is per
  application (tiny), and locks give exactly-once state changes with simple code.
- **Simulator in-process** (the brief allows it) rather than a separate container. The callback still
  travels over HTTP, so swapping in a real provider client is a new `VerificationProvider` implementation
  plus configuration.

## 11. Testing strategy

- Integration tests run against real PostgreSQL (Dev Services/Testcontainers), because locking, partial
  indexes and `SKIP LOCKED` are exactly what's under test.
- Scenario tests (MANUAL simulator, controllable clock) cover each failure mode in the brief: duplicates,
  slow answers, silence, replacing during verification, and provider outages.
- Concurrency tests use parallel threads (replace ×10, add ×8, duplicate callback ×8, idempotent create ×8).
- A chaos test runs AUTO mode with random latency, failures, drops and duplicates, plus mid-flight
  replacements, and asserts that every application converges with exactly its latest versions verified.
- Pure unit tests cover the readiness rules and the callback decision table.
