package com.loanverify.provider;

/** What we send to the provider: metadata only, plus our correlation id. */
public record ProviderSubmission(String externalRef, String category, String fileRef, String filename,
                                 String contentHash) {
}
