package com.loanverify;

import com.loanverify.provider.SimulatedVerificationProvider;
import com.loanverify.service.OutboxDispatcher;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static com.loanverify.TestSupport.addDocument;
import static com.loanverify.TestSupport.as;
import static com.loanverify.TestSupport.asAdmin;
import static com.loanverify.TestSupport.createApplication;
import static com.loanverify.TestSupport.newOwner;
import static com.loanverify.TestSupport.requestVerification;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class SimulatorAdminTest {

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
    void simulatorRequiresAdminRole() {
        as(newOwner()).get("/simulator/settings").then().statusCode(403);
        asAdmin().get("/simulator/settings").then().statusCode(200).body("mode", equalTo("MANUAL"));
    }

    @Test
    void settingsCanBeChangedAndAreValidated() {
        asAdmin().body(Map.of("mode", "AUTO", "minDelayMs", 10, "maxDelayMs", 50, "submitFailureRate", 0.2,
                        "dropRate", 0.1, "duplicateRate", 0.5, "rejectRate", 0.0))
                .put("/simulator/settings").then().statusCode(200).body("duplicateRate", equalTo(0.5f));
        asAdmin().body(Map.of("mode", "AUTO", "minDelayMs", 10, "maxDelayMs", 50, "submitFailureRate", 2.0,
                        "dropRate", 0, "duplicateRate", 0, "rejectRate", 0))
                .put("/simulator/settings").then().statusCode(400);
        asAdmin().delete("/simulator/settings").then().statusCode(200).body("mode", equalTo("MANUAL"));
    }

    @Test
    void manualCallbackThroughAdminApi() {
        String owner = newOwner();
        String app = createApplication(owner);
        UUID doc = UUID.fromString(addDocument(owner, app, "IDENTITY_PROOF", "admin-" + UUID.randomUUID()));
        requestVerification(owner, app);
        dispatcher.dispatchDue(Instant.now());
        String ref = db.latestAttempt(doc).externalRef;

        asAdmin().get("/simulator/jobs/{ref}", ref).then().statusCode(200).body("state", equalTo("AWAITING_MANUAL"));
        asAdmin().body(Map.of("outcome", "VERIFIED", "times", 2))
                .post("/simulator/jobs/{ref}/callbacks", ref)
                .then().statusCode(200).body("statuses", org.hamcrest.Matchers.contains(200, 200));
        asAdmin().get("/simulator/jobs/{ref}", ref).then().body("state", equalTo("DELIVERED"));
        as(owner).get("/api/v1/applications/{id}/documents/by-id/{d}", app, doc.toString())
                .then().body("status", equalTo("VERIFIED"));
    }
}
