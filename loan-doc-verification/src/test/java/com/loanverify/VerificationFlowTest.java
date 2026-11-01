package com.loanverify;

import com.loanverify.domain.AttemptStatus;
import com.loanverify.domain.CallbackDisposition;
import com.loanverify.domain.DocumentStatus;
import com.loanverify.domain.ProviderCallbackEvent;
import com.loanverify.domain.ProviderOutcome;
import com.loanverify.domain.VerificationAttempt;
import com.loanverify.provider.SimulatedVerificationProvider;
import com.loanverify.provider.SimulatedVerificationProvider.DeliveryReport;
import com.loanverify.service.OutboxDispatcher;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.loanverify.TestSupport.addAllDocuments;
import static com.loanverify.TestSupport.addDocument;
import static com.loanverify.TestSupport.as;
import static com.loanverify.TestSupport.createApplication;
import static com.loanverify.TestSupport.newOwner;
import static com.loanverify.TestSupport.progress;
import static com.loanverify.TestSupport.replaceDocument;
import static com.loanverify.TestSupport.requestVerification;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verification against the simulator in MANUAL mode: the test decides exactly when, how often and in what order
 * the provider answers, so each unreliable-provider scenario is reproducible.
 */
@QuarkusTest
class VerificationFlowTest {

    @Inject
    OutboxDispatcher dispatcher;

    @Inject
    SimulatedVerificationProvider simulator;

    @Inject
    TestQueries db;

    @BeforeEach
    @AfterEach
    void resetSimulator() {
        simulator.reset(); // MANUAL mode, no failures (test profile)
    }

    // ------------------------------------------------------------------ happy path

    @Test
    void applicationBecomesReadyWhenAllLatestDocumentsAreVerified() {
        String owner = newOwner();
        String app = createApplication(owner);
        Map<String, String> docs = addAllDocuments(owner, app);

        progress(owner, app).then().body("status", equalTo("AWAITING_VERIFICATION_REQUEST"));

        as(owner).post("/api/v1/applications/{id}/verification-requests", app)
                .then().statusCode(202)
                .body("queued", hasSize(3))
                .body("skipped", hasSize(0));

        progress(owner, app).then()
                .body("status", equalTo("VERIFICATION_IN_PROGRESS"))
                .body("readyForReview", equalTo(false))
                .body("verifiedCount", equalTo(0));

        dispatcher.dispatchDue(Instant.now());
        for (String docId : docs.values()) {
            VerificationAttempt a = db.latestAttempt(UUID.fromString(docId));
            assertEquals(AttemptStatus.SUBMITTED, a.status);
            assertOk(simulator.deliverNow(a.externalRef, ProviderOutcome.VERIFIED, 1, null));
        }

        progress(owner, app).then()
                .body("status", equalTo("READY_FOR_REVIEW"))
                .body("readyForReview", equalTo(true))
                .body("verifiedCount", equalTo(3))
                .body("missingCategories", hasSize(0));
        as(owner).get("/api/v1/applications/{id}", app).then().body("readyForReview", equalTo(true));
    }

    @Test
    void requestingVerificationTwiceDoesNotDuplicateProviderWork() {
        String owner = newOwner();
        String app = createApplication(owner);
        Map<String, String> docs = addAllDocuments(owner, app);
        requestVerification(owner, app);

        as(owner).post("/api/v1/applications/{id}/verification-requests", app)
                .then().statusCode(202).body("queued", hasSize(0)).body("skipped", hasSize(3));

        for (String docId : docs.values()) {
            assertEquals(1, db.attempts(UUID.fromString(docId)).size());
        }
    }

    @Test
    void missingDocumentsAreReported() {
        String owner = newOwner();
        String app = createApplication(owner);
        addDocument(owner, app, "IDENTITY_PROOF", "only-" + UUID.randomUUID());
        as(owner).post("/api/v1/applications/{id}/verification-requests", app)
                .then().statusCode(202)
                .body("queued", hasSize(1))
                .body("missingCategories", hasItems("ADDRESS_PROOF", "INCOME_PROOF"));
        progress(owner, app).then().body("status", equalTo("AWAITING_DOCUMENTS"));
    }

    // ------------------------------------------------------------------ duplicates

    @Test
    void duplicateCallbacksAreAppliedOnce() {
        String owner = newOwner();
        String app = createApplication(owner);
        String docId = addDocument(owner, app, "IDENTITY_PROOF", "dup-" + UUID.randomUUID());
        requestVerification(owner, app);
        dispatcher.dispatchDue(Instant.now());
        VerificationAttempt a = db.latestAttempt(UUID.fromString(docId));

        // same event delivered three times, then a second event with the same outcome
        assertOk(simulator.deliverNow(a.externalRef, ProviderOutcome.VERIFIED, 3, null));
        assertOk(simulator.deliverNow(a.externalRef, ProviderOutcome.VERIFIED, 1, null));

        List<ProviderCallbackEvent> events = db.events(a.externalRef);
        assertEquals(2, events.size(), "redeliveries of one event id are stored once");
        assertEquals(CallbackDisposition.APPLIED, events.get(0).disposition);
        assertEquals(CallbackDisposition.DUPLICATE, events.get(1).disposition);
        assertEquals(DocumentStatus.VERIFIED, db.document(UUID.fromString(docId)).status);
    }

    @Test
    void conflictingOutcomesKeepTheFirstDecision() {
        String owner = newOwner();
        String app = createApplication(owner);
        String docId = addDocument(owner, app, "INCOME_PROOF", "conflict-" + UUID.randomUUID());
        requestVerification(owner, app);
        dispatcher.dispatchDue(Instant.now());
        VerificationAttempt a = db.latestAttempt(UUID.fromString(docId));

        assertOk(simulator.deliverNow(a.externalRef, ProviderOutcome.REJECTED, 1, "Blurry scan"));
        assertOk(simulator.deliverNow(a.externalRef, ProviderOutcome.VERIFIED, 1, null));

        List<ProviderCallbackEvent> events = db.events(a.externalRef);
        assertEquals(CallbackDisposition.APPLIED, events.get(0).disposition);
        assertEquals(CallbackDisposition.CONFLICTING_OUTCOME, events.get(1).disposition);
        assertEquals(DocumentStatus.REJECTED, db.document(UUID.fromString(docId)).status);
        progress(owner, app).then().body("status", equalTo("ACTION_REQUIRED"));

        // a rejected document is not re-sent; it must be replaced
        as(owner).post("/api/v1/applications/{id}/verification-requests", app)
                .then().body("queued", hasSize(0)).body("skipped[0].status", equalTo("REJECTED"));
    }

    // ------------------------------------------------------------------ replace during verification

    @Test
    void lateResultForAReplacedDocumentIsIgnored() {
        String owner = newOwner();
        String app = createApplication(owner);
        Map<String, String> docs = addAllDocuments(owner, app);
        requestVerification(owner, app);
        dispatcher.dispatchDue(Instant.now());

        // verify address + income
        for (String c : List.of("ADDRESS_PROOF", "INCOME_PROOF")) {
            VerificationAttempt a = db.latestAttempt(UUID.fromString(docs.get(c)));
            assertOk(simulator.deliverNow(a.externalRef, ProviderOutcome.VERIFIED, 1, null));
        }

        // applicant replaces identity proof while its verification is still in flight
        UUID oldIdentity = UUID.fromString(docs.get("IDENTITY_PROOF"));
        VerificationAttempt oldAttempt = db.latestAttempt(oldIdentity);
        String newIdentity = replaceDocument(owner, app, "IDENTITY_PROOF", "id-v2-" + UUID.randomUUID())
                .then().statusCode(201).extract().path("id");

        assertEquals(AttemptStatus.CANCELLED, db.attempt(oldAttempt.id).status);
        assertEquals(DocumentStatus.CANCELLED, db.document(oldIdentity).status);

        // provider now answers for the OLD file
        assertOk(simulator.deliverNow(oldAttempt.externalRef, ProviderOutcome.VERIFIED, 1, null));
        assertEquals(CallbackDisposition.STALE, db.events(oldAttempt.externalRef).get(0).disposition);

        progress(owner, app).then()
                .body("readyForReview", equalTo(false))
                .body("verifiedCount", equalTo(2))
                .body("status", equalTo("AWAITING_VERIFICATION_REQUEST"));

        // verify the new version: now ready
        as(owner).post("/api/v1/applications/{id}/verification-requests", app)
                .then().body("queued", hasSize(1)).body("queued[0].documentId", equalTo(newIdentity));
        dispatcher.dispatchDue(Instant.now());
        VerificationAttempt fresh = db.latestAttempt(UUID.fromString(newIdentity));
        assertOk(simulator.deliverNow(fresh.externalRef, ProviderOutcome.VERIFIED, 1, null));
        progress(owner, app).then().body("readyForReview", equalTo(true));
    }

    @Test
    void replacingAVerifiedDocumentRevokesReadiness() {
        String owner = newOwner();
        String app = createApplication(owner);
        Map<String, String> docs = addAllDocuments(owner, app);
        requestVerification(owner, app);
        dispatcher.dispatchDue(Instant.now());
        for (String docId : docs.values()) {
            assertOk(simulator.deliverNow(db.latestAttempt(UUID.fromString(docId)).externalRef,
                    ProviderOutcome.VERIFIED, 1, null));
        }
        progress(owner, app).then().body("readyForReview", equalTo(true));

        replaceDocument(owner, app, "ADDRESS_PROOF", "moved-house-" + UUID.randomUUID()).then().statusCode(201);

        progress(owner, app).then()
                .body("readyForReview", equalTo(false))
                .body("verifiedCount", equalTo(2));
    }

    // ------------------------------------------------------------------ slow / silent provider

    @Test
    void timedOutAttemptIsResubmittedThenFailsAndALateResultStillApplies() {
        String owner = newOwner();
        String app = createApplication(owner);
        UUID docId = UUID.fromString(addDocument(owner, app, "ADDRESS_PROOF", "slow-" + UUID.randomUUID()));
        requestVerification(owner, app);

        Instant t0 = Instant.now();
        dispatcher.dispatchDue(t0);
        VerificationAttempt a = db.latestAttempt(docId);
        assertEquals(1, a.submissions);

        // no callback within the timeout (1m in tests): re-queued and re-submitted with the same reference
        Instant t1 = t0.plus(Duration.ofMinutes(2));
        dispatcher.sweepTimeouts(t1);
        assertEquals(AttemptStatus.QUEUED, db.attempt(a.id).status);
        dispatcher.dispatchDue(t1);
        assertEquals(2, db.attempt(a.id).submissions);
        assertEquals(2, simulator.job(a.externalRef).orElseThrow().timesSubmitted(),
                "provider received the same reference again");

        // still nothing: max submissions (2 in tests) reached -> TIMED_OUT, document FAILED
        dispatcher.sweepTimeouts(t1.plus(Duration.ofMinutes(2)));
        assertEquals(AttemptStatus.TIMED_OUT, db.attempt(a.id).status);
        assertEquals(DocumentStatus.FAILED, db.document(docId).status);
        progress(owner, app).then().body("status", equalTo("ACTION_REQUIRED"));

        // the provider finally answers; nothing newer exists, so the result is accepted
        assertOk(simulator.deliverNow(a.externalRef, ProviderOutcome.VERIFIED, 1, null));
        assertEquals(CallbackDisposition.LATE_APPLIED, db.events(a.externalRef).get(0).disposition);
        assertEquals(DocumentStatus.VERIFIED, db.document(docId).status);
    }

    @Test
    void lateResultOfASupersededAttemptIsIgnored() {
        String owner = newOwner();
        String app = createApplication(owner);
        UUID docId = UUID.fromString(addDocument(owner, app, "INCOME_PROOF", "retry-" + UUID.randomUUID()));
        requestVerification(owner, app);
        Instant t0 = Instant.now();
        dispatcher.dispatchDue(t0);
        VerificationAttempt first = db.latestAttempt(docId);
        dispatcher.sweepTimeouts(t0.plus(Duration.ofMinutes(2)));
        dispatcher.dispatchDue(t0.plus(Duration.ofMinutes(2)));
        dispatcher.sweepTimeouts(t0.plus(Duration.ofMinutes(5)));
        assertEquals(DocumentStatus.FAILED, db.document(docId).status);

        // applicant retries -> a new attempt becomes the document's latest
        as(owner).post("/api/v1/applications/{id}/verification-requests", app).then().body("queued", hasSize(1));
        VerificationAttempt second = db.latestAttempt(docId);
        assertNotEquals(first.id, second.id);

        // the first attempt's answer turns up now: it must not decide the document
        assertOk(simulator.deliverNow(first.externalRef, ProviderOutcome.REJECTED, 1, "late"));
        assertEquals(CallbackDisposition.STALE, db.events(first.externalRef).get(0).disposition);
        assertEquals(DocumentStatus.PENDING, db.document(docId).status);
    }

    // ------------------------------------------------------------------ provider refusing submissions

    @Test
    void submitFailuresAreRetriedWithBackoffThenFail() {
        var s = simulator.settings();
        simulator.updateSettings(new SimulatedVerificationProvider.Settings(s.mode(), s.minDelayMs(), s.maxDelayMs(),
                1.0, 0, 0, 0));

        String owner = newOwner();
        String app = createApplication(owner);
        UUID docId = UUID.fromString(addDocument(owner, app, "IDENTITY_PROOF", "flaky-" + UUID.randomUUID()));
        requestVerification(owner, app);

        Instant t0 = Instant.now();
        dispatcher.dispatchDue(t0);
        VerificationAttempt a = db.latestAttempt(docId);
        assertEquals(AttemptStatus.QUEUED, a.status, "failed submission goes back to the queue");
        assertTrue(a.nextAttemptAt.isAfter(t0), "with backoff");
        assertNotNull(a.lastError);

        dispatcher.dispatchDue(t0.plus(Duration.ofHours(1)));
        a = db.attempt(a.id);
        assertEquals(AttemptStatus.FAILED, a.status);
        assertEquals(2, a.submissions);
        assertEquals(DocumentStatus.FAILED, db.document(docId).status);

        // provider recovers; the applicant can request again
        simulator.reset();
        as(owner).post("/api/v1/applications/{id}/verification-requests", app).then().body("queued", hasSize(1));
        dispatcher.dispatchDue(Instant.now());
        assertOk(simulator.deliverNow(db.latestAttempt(docId).externalRef, ProviderOutcome.VERIFIED, 1, null));
        assertEquals(DocumentStatus.VERIFIED, db.document(docId).status);
    }

    @Test
    void callbackForUnknownReferenceIsAcknowledgedButIgnored() {
        String ref = "vrf_unknown_" + UUID.randomUUID().toString().replace("-", "");
        DeliveryReport r = simulator.deliverNow(ref, ProviderOutcome.VERIFIED, 1, null);
        assertOk(r);
        assertEquals(CallbackDisposition.UNKNOWN_REFERENCE, db.events(ref).get(0).disposition);
    }

    private static void assertOk(DeliveryReport report) {
        assertFalse(report.statuses().isEmpty());
        for (int status : report.statuses()) {
            assertEquals(200, status, "callback HTTP status for " + report.externalRef());
        }
    }
}
