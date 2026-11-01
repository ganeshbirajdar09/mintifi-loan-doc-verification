-- Loan application document verification schema.
-- Design notes are in TECHNICAL-SPEC.md. The short version:
--   * documents are immutable versions; exactly one current version per (application, category)
--   * every verification request is an attempt bound to ONE document version
--   * a document only accepts a provider result from its latest attempt
--   * provider callbacks are recorded by event id for idempotency and audit

CREATE TABLE loan_application (
    id               UUID PRIMARY KEY,
    owner_id         VARCHAR(128)   NOT NULL,
    applicant_name   VARCHAR(120)   NOT NULL,
    requested_amount NUMERIC(14, 2) NOT NULL CHECK (requested_amount > 0),
    currency         CHAR(3)        NOT NULL,
    purpose          VARCHAR(200),
    created_at       TIMESTAMPTZ    NOT NULL,
    updated_at       TIMESTAMPTZ    NOT NULL,
    row_version      BIGINT         NOT NULL DEFAULT 0
);

CREATE INDEX ix_loan_application_owner ON loan_application (owner_id, created_at DESC);

CREATE TABLE document (
    id                UUID PRIMARY KEY,
    application_id    UUID         NOT NULL REFERENCES loan_application (id),
    category          VARCHAR(32)  NOT NULL
        CHECK (category IN ('IDENTITY_PROOF', 'ADDRESS_PROOF', 'INCOME_PROOF')),
    version           INT          NOT NULL CHECK (version >= 1),
    is_current        BOOLEAN      NOT NULL,
    file_ref          VARCHAR(512) NOT NULL,
    filename          VARCHAR(255) NOT NULL,
    content_hash      CHAR(64)     NOT NULL,
    content_type      VARCHAR(100),
    size_bytes        BIGINT CHECK (size_bytes IS NULL OR size_bytes >= 0),
    status            VARCHAR(32)  NOT NULL
        CHECK (status IN ('NOT_REQUESTED', 'PENDING', 'VERIFIED', 'REJECTED', 'FAILED', 'CANCELLED')),
    status_reason     VARCHAR(500),
    latest_attempt_id UUID,
    created_at        TIMESTAMPTZ  NOT NULL,
    status_changed_at TIMESTAMPTZ  NOT NULL,
    superseded_at     TIMESTAMPTZ,
    row_version       BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_document_version UNIQUE (application_id, category, version)
);

-- Backstop for concurrent add/replace: at most one current version per category.
CREATE UNIQUE INDEX uq_document_current
    ON document (application_id, category)
    WHERE is_current;

CREATE TABLE verification_attempt (
    id              UUID PRIMARY KEY,
    document_id     UUID         NOT NULL REFERENCES document (id),
    external_ref    VARCHAR(64)  NOT NULL UNIQUE,
    status          VARCHAR(32)  NOT NULL
        CHECK (status IN ('QUEUED', 'SUBMITTED', 'VERIFIED', 'REJECTED', 'TIMED_OUT', 'FAILED', 'CANCELLED')),
    submissions     INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL,
    submitted_at    TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    result_reason   VARCHAR(500),
    last_error      VARCHAR(500),
    created_at      TIMESTAMPTZ  NOT NULL,
    row_version     BIGINT       NOT NULL DEFAULT 0
);

ALTER TABLE document
    ADD CONSTRAINT fk_document_latest_attempt
        FOREIGN KEY (latest_attempt_id) REFERENCES verification_attempt (id)
        DEFERRABLE INITIALLY DEFERRED;

-- Backstop: never two in-flight attempts for the same document version.
CREATE UNIQUE INDEX uq_attempt_in_flight
    ON verification_attempt (document_id)
    WHERE status IN ('QUEUED', 'SUBMITTED');

-- Outbox polling and timeout sweeping.
CREATE INDEX ix_attempt_queued ON verification_attempt (next_attempt_at) WHERE status = 'QUEUED';
CREATE INDEX ix_attempt_submitted ON verification_attempt (submitted_at) WHERE status = 'SUBMITTED';
CREATE INDEX ix_attempt_document ON verification_attempt (document_id);

CREATE TABLE provider_callback_event (
    id           UUID PRIMARY KEY,
    event_id     VARCHAR(64)  NOT NULL UNIQUE,
    external_ref VARCHAR(64)  NOT NULL,
    attempt_id   UUID REFERENCES verification_attempt (id),
    outcome      VARCHAR(32)  NOT NULL,
    reason       VARCHAR(500),
    disposition  VARCHAR(32)  NOT NULL
        CHECK (disposition IN ('APPLIED', 'LATE_APPLIED', 'DUPLICATE', 'CONFLICTING_OUTCOME', 'STALE', 'UNKNOWN_REFERENCE')),
    occurred_at  TIMESTAMPTZ,
    received_at  TIMESTAMPTZ  NOT NULL
);

CREATE INDEX ix_callback_event_ref ON provider_callback_event (external_ref);

CREATE TABLE idempotency_record (
    owner_id        VARCHAR(128) NOT NULL,
    scope           VARCHAR(64)  NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash    CHAR(64)     NOT NULL,
    response_status INT,
    response_body   TEXT,
    created_at      TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (owner_id, scope, idempotency_key)
);
