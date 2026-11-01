package com.loanverify.api.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateApplicationRequest(
        @NotBlank @Size(max = 120) @Pattern(regexp = "^[^\\p{Cntrl}<>]+$", message = "contains illegal characters")
        String applicantName,

        @NotNull @DecimalMin("1000.00") @DecimalMax("100000000.00") @Digits(integer = 12, fraction = 2)
        BigDecimal requestedAmount,

        @NotNull @Pattern(regexp = "^[A-Z]{3}$", message = "must be an ISO-4217 code such as INR")
        String currency,

        @Size(max = 200) @Pattern(regexp = "^[^\\p{Cntrl}<>]*$", message = "contains illegal characters")
        String purpose) {
}
