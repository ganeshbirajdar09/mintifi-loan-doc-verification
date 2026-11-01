package com.loanverify.domain;

/** What the service did with a provider callback. Recorded for every callback received. */
public enum CallbackDisposition {
    /** Result applied to the document. */
    APPLIED,
    /** Result applied even though the attempt had already timed out (the document had no newer attempt). */
    LATE_APPLIED,
    /** Same event id seen before, or the attempt already has this outcome. No change. */
    DUPLICATE,
    /** The attempt was already decided with a different outcome. The first decision wins. */
    CONFLICTING_OUTCOME,
    /** The attempt belongs to a superseded document version or was replaced by a newer attempt. No change. */
    STALE,
    /** No attempt with this external reference. No change. */
    UNKNOWN_REFERENCE
}
