package com.loanverify.service;

import com.loanverify.api.dto.VerificationProgressResponse;
import com.loanverify.api.dto.VerificationProgressResponse.CategoryProgress;
import com.loanverify.domain.ApplicationStatus;
import com.loanverify.domain.Document;
import com.loanverify.domain.DocumentCategory;
import com.loanverify.domain.DocumentStatus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pure readiness rules. An application is ready for review only when the CURRENT (latest) version of every
 * required category is VERIFIED. Superseded versions never count, whatever their status.
 */
public final class Readiness {

    private Readiness() {
    }

    public static VerificationProgressResponse evaluate(UUID applicationId, Collection<Document> documents) {
        Map<DocumentCategory, Document> current = new EnumMap<>(DocumentCategory.class);
        for (Document d : documents) {
            if (d.current) {
                current.put(d.category, d);
            }
        }

        List<CategoryProgress> progress = new ArrayList<>();
        List<DocumentCategory> missing = new ArrayList<>();
        int verified = 0;
        for (DocumentCategory category : DocumentCategory.values()) {
            Document d = current.get(category);
            if (d == null) {
                missing.add(category);
                progress.add(new CategoryProgress(category, false, null, null, null, null, null));
            } else {
                if (d.status == DocumentStatus.VERIFIED) {
                    verified++;
                }
                progress.add(new CategoryProgress(category, true, d.id, d.version, d.status, d.statusReason,
                        d.statusChangedAt));
            }
        }

        ApplicationStatus status = status(current, missing);
        return new VerificationProgressResponse(applicationId, status, status == ApplicationStatus.READY_FOR_REVIEW,
                DocumentCategory.values().length, verified, progress, missing);
    }

    static ApplicationStatus status(Map<DocumentCategory, Document> current, List<DocumentCategory> missing) {
        boolean anyNeedsAction = current.values().stream()
                .anyMatch(d -> d.status == DocumentStatus.REJECTED || d.status == DocumentStatus.FAILED);
        if (anyNeedsAction) {
            return ApplicationStatus.ACTION_REQUIRED;
        }
        if (!missing.isEmpty()) {
            return ApplicationStatus.AWAITING_DOCUMENTS;
        }
        if (current.values().stream().anyMatch(d -> d.status == DocumentStatus.NOT_REQUESTED)) {
            return ApplicationStatus.AWAITING_VERIFICATION_REQUEST;
        }
        if (current.values().stream().allMatch(d -> d.status == DocumentStatus.VERIFIED)) {
            return ApplicationStatus.READY_FOR_REVIEW;
        }
        return ApplicationStatus.VERIFICATION_IN_PROGRESS;
    }
}
