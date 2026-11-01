package com.loanverify.api.dto;

import com.loanverify.domain.ApplicationStatus;
import com.loanverify.domain.DocumentCategory;
import com.loanverify.domain.DocumentStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record VerificationProgressResponse(
        UUID applicationId,
        ApplicationStatus status,
        boolean readyForReview,
        int requiredCount,
        int verifiedCount,
        List<CategoryProgress> documents,
        List<DocumentCategory> missingCategories) {

    public record CategoryProgress(
            DocumentCategory category,
            boolean present,
            UUID documentId,
            Integer version,
            DocumentStatus status,
            String statusReason,
            Instant statusChangedAt) {
    }
}
