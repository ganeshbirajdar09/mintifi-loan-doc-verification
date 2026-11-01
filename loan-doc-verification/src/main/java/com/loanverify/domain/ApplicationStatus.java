package com.loanverify.domain;

/** Derived (never stored) status of a loan application, computed from its current document versions. */
public enum ApplicationStatus {
    /** At least one current document was rejected or failed verification. */
    ACTION_REQUIRED,
    /** One or more required categories have no document yet. */
    AWAITING_DOCUMENTS,
    /** All categories present, at least one current document has not been sent for verification. */
    AWAITING_VERIFICATION_REQUEST,
    /** All categories present, verification in progress. */
    VERIFICATION_IN_PROGRESS,
    /** The latest version of every required document is verified. */
    READY_FOR_REVIEW
}
