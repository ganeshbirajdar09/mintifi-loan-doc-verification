package com.loanverify.provider;

/** The provider could not accept the submission right now (timeout, 5xx, connection refused). Retryable. */
public class ProviderException extends Exception {

    public ProviderException(String message) {
        super(message);
    }

    public ProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
