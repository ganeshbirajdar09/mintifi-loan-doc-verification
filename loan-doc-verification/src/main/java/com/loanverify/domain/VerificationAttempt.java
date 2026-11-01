package com.loanverify.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * A request to verify one specific document version. It doubles as an outbox row: the dispatcher submits
 * QUEUED attempts to the provider outside the transaction that created them.
 */
@Entity
@Table(name = "verification_attempt")
public class VerificationAttempt {

    @Id
    public UUID id;

    @Column(name = "document_id", nullable = false, updatable = false)
    public UUID documentId;

    /** Correlation id sent to the provider and echoed back on callbacks. Random, never guessable from ids. */
    @Column(name = "external_ref", nullable = false, updatable = false, unique = true)
    public String externalRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public AttemptStatus status;

    @Column(nullable = false)
    public int submissions;

    @Column(name = "next_attempt_at", nullable = false)
    public Instant nextAttemptAt;

    @Column(name = "submitted_at")
    public Instant submittedAt;

    @Column(name = "completed_at")
    public Instant completedAt;

    @Column(name = "result_reason")
    public String resultReason;

    @Column(name = "last_error")
    public String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @Version
    @Column(name = "row_version", nullable = false)
    public long rowVersion;
}
