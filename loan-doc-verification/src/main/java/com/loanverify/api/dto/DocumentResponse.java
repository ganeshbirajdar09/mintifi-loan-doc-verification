package com.loanverify.api.dto;

import com.loanverify.domain.Document;
import com.loanverify.domain.DocumentCategory;
import com.loanverify.domain.DocumentStatus;

import java.time.Instant;
import java.util.UUID;

public record DocumentResponse(
        UUID id,
        UUID applicationId,
        DocumentCategory category,
        int version,
        boolean current,
        String fileRef,
        String filename,
        String contentHash,
        String contentType,
        Long sizeBytes,
        DocumentStatus status,
        String statusReason,
        Instant createdAt,
        Instant statusChangedAt,
        Instant supersededAt) {

    public static DocumentResponse from(Document d) {
        return new DocumentResponse(d.id, d.applicationId, d.category, d.version, d.current, d.fileRef,
                d.filename, d.contentHash, d.contentType, d.sizeBytes, d.status, d.statusReason, d.createdAt,
                d.statusChangedAt, d.supersededAt);
    }
}
