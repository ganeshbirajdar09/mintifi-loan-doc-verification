package com.loanverify.domain;

/** Verification status of a single document version. */
public enum DocumentStatus {
    /** Added or replaced, verification not requested yet. */
    NOT_REQUESTED,
    /** An attempt is queued at, or waiting on, the provider. */
    PENDING,
    VERIFIED,
    /** The provider rejected this version. The applicant must replace it. */
    REJECTED,
    /** The provider never answered or kept failing. Verification can be requested again. */
    FAILED,
    /** The version was replaced while verification was in flight. Only superseded versions have this. */
    CANCELLED
}
