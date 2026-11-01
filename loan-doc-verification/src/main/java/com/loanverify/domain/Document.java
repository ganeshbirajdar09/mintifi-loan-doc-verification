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
 * One immutable version of a document's metadata. Replacing a document inserts a new row and marks this one
 * as no longer current. Only the verification fields change after insert.
 */
@Entity
@Table(name = "document")
public class Document {

    @Id
    public UUID id;

    @Column(name = "application_id", nullable = false, updatable = false)
    public UUID applicationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    public DocumentCategory category;

    @Column(nullable = false, updatable = false)
    public int version;

    @Column(name = "is_current", nullable = false)
    public boolean current;

    @Column(name = "file_ref", nullable = false, updatable = false)
    public String fileRef;

    @Column(nullable = false, updatable = false)
    public String filename;

    @Column(name = "content_hash", nullable = false, updatable = false, length = 64)
    public String contentHash;

    @Column(name = "content_type", updatable = false)
    public String contentType;

    @Column(name = "size_bytes", updatable = false)
    public Long sizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public DocumentStatus status;

    @Column(name = "status_reason")
    public String statusReason;

    /** The only attempt whose result may change this document's status. */
    @Column(name = "latest_attempt_id")
    public UUID latestAttemptId;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "status_changed_at", nullable = false)
    public Instant statusChangedAt;

    @Column(name = "superseded_at")
    public Instant supersededAt;

    @Version
    @Column(name = "row_version", nullable = false)
    public long rowVersion;

    public void changeStatus(DocumentStatus newStatus, String reason, Instant at) {
        this.status = newStatus;
        this.statusReason = reason;
        this.statusChangedAt = at;
    }
}
