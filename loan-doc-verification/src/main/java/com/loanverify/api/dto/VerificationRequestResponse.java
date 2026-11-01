package com.loanverify.api.dto;

import com.loanverify.domain.DocumentCategory;
import com.loanverify.domain.DocumentStatus;

import java.util.List;
import java.util.UUID;

public record VerificationRequestResponse(
        UUID applicationId,
        List<Item> queued,
        List<Item> skipped,
        List<DocumentCategory> missingCategories) {

    public record Item(DocumentCategory category, UUID documentId, int version, DocumentStatus status, String note) {
    }
}
