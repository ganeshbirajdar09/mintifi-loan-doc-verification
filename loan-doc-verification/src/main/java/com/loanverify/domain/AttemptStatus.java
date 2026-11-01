package com.loanverify.domain;

import java.util.EnumSet;
import java.util.Set;

public enum AttemptStatus {
    /** Waiting in the outbox to be (re)submitted to the provider. */
    QUEUED,
    /** Handed to the provider, waiting for a callback. */
    SUBMITTED,
    VERIFIED,
    REJECTED,
    /** No callback within the timeout after the maximum number of submissions. A late callback can still apply. */
    TIMED_OUT,
    /** Could not be submitted after the maximum number of tries. */
    FAILED,
    /** The document version was replaced while this attempt was in flight. */
    CANCELLED;

    public static final Set<AttemptStatus> IN_FLIGHT = EnumSet.of(QUEUED, SUBMITTED);
    public static final Set<AttemptStatus> DECIDED = EnumSet.of(VERIFIED, REJECTED);
}
