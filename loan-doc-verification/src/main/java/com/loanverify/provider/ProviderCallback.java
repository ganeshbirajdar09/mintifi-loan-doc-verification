package com.loanverify.provider;

import com.loanverify.domain.ProviderOutcome;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** Callback body sent by the provider. */
public record ProviderCallback(
        @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9_\\-]+$") String eventId,
        @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9_\\-]+$") String externalRef,
        @NotNull ProviderOutcome outcome,
        @Size(max = 500) String reason,
        Instant occurredAt) {
}
