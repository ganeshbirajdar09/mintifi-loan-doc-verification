package com.loanverify.service;

import com.loanverify.domain.AttemptStatus;
import com.loanverify.domain.CallbackDisposition;
import com.loanverify.domain.Document;
import com.loanverify.domain.ProviderOutcome;
import com.loanverify.domain.VerificationAttempt;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Unit tests for the callback decision table. */
class CallbackDecisionTest {

    private static VerificationAttempt attempt(AttemptStatus s) {
        VerificationAttempt a = new VerificationAttempt();
        a.id = UUID.randomUUID();
        a.status = s;
        return a;
    }

    private static Document doc(boolean current, UUID latest) {
        Document d = new Document();
        d.current = current;
        d.latestAttemptId = latest;
        return d;
    }

    @Test
    void decisionTable() {
        var submitted = attempt(AttemptStatus.SUBMITTED);
        assertEquals(CallbackDisposition.APPLIED,
                CallbackService.decide(ProviderOutcome.VERIFIED, submitted, doc(true, submitted.id)));
        assertEquals(CallbackDisposition.STALE,
                CallbackService.decide(ProviderOutcome.VERIFIED, submitted, doc(false, submitted.id)));
        assertEquals(CallbackDisposition.STALE,
                CallbackService.decide(ProviderOutcome.VERIFIED, submitted, doc(true, UUID.randomUUID())));

        var cancelled = attempt(AttemptStatus.CANCELLED);
        assertEquals(CallbackDisposition.STALE,
                CallbackService.decide(ProviderOutcome.VERIFIED, cancelled, doc(true, cancelled.id)));

        var timedOut = attempt(AttemptStatus.TIMED_OUT);
        assertEquals(CallbackDisposition.LATE_APPLIED,
                CallbackService.decide(ProviderOutcome.REJECTED, timedOut, doc(true, timedOut.id)));

        var requeued = attempt(AttemptStatus.QUEUED);
        assertEquals(CallbackDisposition.APPLIED,
                CallbackService.decide(ProviderOutcome.VERIFIED, requeued, doc(true, requeued.id)));

        var verified = attempt(AttemptStatus.VERIFIED);
        assertEquals(CallbackDisposition.DUPLICATE,
                CallbackService.decide(ProviderOutcome.VERIFIED, verified, doc(true, verified.id)));
        assertEquals(CallbackDisposition.CONFLICTING_OUTCOME,
                CallbackService.decide(ProviderOutcome.REJECTED, verified, doc(true, verified.id)));
    }
}
