package com.loanverify;

import com.loanverify.provider.SimulatedVerificationProvider;
import com.loanverify.provider.SimulatedVerificationProvider.Mode;
import com.loanverify.provider.SimulatedVerificationProvider.Settings;
import com.loanverify.service.OutboxDispatcher;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static com.loanverify.TestSupport.addAllDocuments;
import static com.loanverify.TestSupport.as;
import static com.loanverify.TestSupport.createApplication;
import static com.loanverify.TestSupport.newOwner;
import static com.loanverify.TestSupport.progress;
import static com.loanverify.TestSupport.replaceDocument;
import static com.loanverify.TestSupport.requestVerification;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End to end against an unreliable provider in AUTO mode: random latency (so results arrive out of order),
 * refused submissions, dropped requests, duplicate deliveries, plus applicants replacing documents mid-flight.
 * The client behaves like a real one: it re-requests verification when told action is required.
 * Every application must converge to READY_FOR_REVIEW, with exactly its latest versions verified.
 */
@QuarkusTest
class ChaosEndToEndTest {

    @Inject
    SimulatedVerificationProvider simulator;

    @Inject
    OutboxDispatcher dispatcher;

    @Inject
    TestQueries db;

    @AfterEach
    void reset() {
        simulator.reset();
    }

    @Test
    void convergesUnderAnUnreliableProvider() {
        simulator.updateSettings(new Settings(Mode.AUTO, 5, 250, 0.3, 0.15, 0.5, 0.0));

        record App(String owner, String id) {
        }
        List<App> apps = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String owner = newOwner();
            String id = createApplication(owner);
            Map<String, String> docs = addAllDocuments(owner, id);
            requestVerification(owner, id);
            apps.add(new App(owner, id));
        }
        // half of the applicants replace a document while verification is in flight
        dispatcher.dispatchDue(Instant.now());
        for (int i = 0; i < apps.size(); i += 2) {
            App a = apps.get(i);
            replaceDocument(a.owner(), a.id(), "INCOME_PROOF", "new-payslip-" + i + "-" + System.nanoTime())
                    .then().statusCode(201);
            requestVerification(a.owner(), a.id());
        }

        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(300)).until(() -> {
            // Drive the outbox with a clock pushed forward, so backoff and timeouts elapse instantly.
            Instant future = Instant.now().plus(Duration.ofMinutes(10));
            dispatcher.dispatchDue(future);
            if (ThreadLocalRandom.current().nextInt(4) == 0) {
                dispatcher.sweepTimeouts(Instant.now().plus(Duration.ofMinutes(2)));
            }
            boolean allReady = true;
            for (App a : apps) {
                String status = progress(a.owner(), a.id()).path("status");
                if ("ACTION_REQUIRED".equals(status)) {
                    requestVerification(a.owner(), a.id()); // a FAILED doc gets a fresh attempt
                }
                allReady &= "READY_FOR_REVIEW".equals(status);
            }
            return allReady;
        });

        for (App a : apps) {
            var docs = db.documents(java.util.UUID.fromString(a.id()));
            long currentVerified = docs.stream()
                    .filter(d -> d.current && d.status == com.loanverify.domain.DocumentStatus.VERIFIED).count();
            assertEquals(3, currentVerified);
            as(a.owner()).get("/api/v1/applications/{id}", a.id()).then().statusCode(200);
        }
    }
}
