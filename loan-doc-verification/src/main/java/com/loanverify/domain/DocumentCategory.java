package com.loanverify.domain;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Locale;

/** The three documents every loan application needs before it can go to review. */
public enum DocumentCategory {
    IDENTITY_PROOF,
    ADDRESS_PROOF,
    INCOME_PROOF;

    /**
     * Lenient parsing for path params and JSON: accepts "IDENTITY_PROOF", "identity_proof" and "identity-proof".
     * Unknown values throw IllegalArgumentException, which surfaces as 400/404.
     */
    @JsonCreator
    public static DocumentCategory fromString(String value) {
        if (value == null) {
            throw new IllegalArgumentException("category is required");
        }
        return DocumentCategory.valueOf(value.trim().replace('-', '_').toUpperCase(Locale.ROOT));
    }
}
