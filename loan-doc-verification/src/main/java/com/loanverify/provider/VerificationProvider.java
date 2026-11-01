package com.loanverify.provider;

/**
 * Port to the external verification provider. Submission is fire-and-forget: the provider answers later,
 * possibly more than once, by calling {@code POST /provider/callbacks}.
 * Implementations must be safe to call again with the same external reference (we re-submit after timeouts).
 */
public interface VerificationProvider {

    void submit(ProviderSubmission submission) throws ProviderException;
}
