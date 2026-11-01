package com.loanverify.api.dto;

import com.loanverify.domain.ApplicationStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ApplicationResponse(
        UUID id,
        String applicantName,
        BigDecimal requestedAmount,
        String currency,
        String purpose,
        ApplicationStatus status,
        boolean readyForReview,
        Instant createdAt,
        Instant updatedAt) {
}
