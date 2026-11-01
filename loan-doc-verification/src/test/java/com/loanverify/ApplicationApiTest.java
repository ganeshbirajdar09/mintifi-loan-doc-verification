package com.loanverify;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.loanverify.TestSupport.applicationBody;
import static com.loanverify.TestSupport.as;
import static com.loanverify.TestSupport.createApplication;
import static com.loanverify.TestSupport.newOwner;
import static com.loanverify.TestSupport.token;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class ApplicationApiTest {

    @Test
    void createAndFetchApplication() {
        String owner = newOwner();
        String id = as(owner).body(applicationBody())
                .post("/api/v1/applications")
                .then().statusCode(201)
                .header("Location", containsString("/api/v1/applications/"))
                .body("id", notNullValue())
                .body("status", equalTo("AWAITING_DOCUMENTS"))
                .body("readyForReview", equalTo(false))
                .extract().path("id");

        as(owner).get("/api/v1/applications/{id}", id)
                .then().statusCode(200)
                .body("applicantName", equalTo("Asha Fictional"))
                .body("currency", equalTo("INR"));

        as(owner).get("/api/v1/applications").then().statusCode(200).body("id", hasItem(id));
    }

    @Test
    void requiresAuthentication() {
        given().contentType(ContentType.JSON).body(applicationBody())
                .post("/api/v1/applications").then().statusCode(401);
        given().get("/api/v1/applications").then().statusCode(401);
        given().auth().oauth2("not-a-jwt").get("/api/v1/applications").then().statusCode(401);
    }

    @Test
    void requiresApplicantRole() {
        given().auth().oauth2(token("operator", "admin")).get("/api/v1/applications").then().statusCode(403);
    }

    @Test
    void applicationsAreIsolatedBetweenOwners() {
        String alice = newOwner();
        String bob = newOwner();
        String id = createApplication(alice);

        // 404, not 403: other people's ids are indistinguishable from non-existent ones
        as(bob).get("/api/v1/applications/{id}", id).then().statusCode(404);
        as(bob).get("/api/v1/applications/{id}/documents", id).then().statusCode(404);
        as(bob).get("/api/v1/applications/{id}/verification", id).then().statusCode(404);
        as(bob).post("/api/v1/applications/{id}/verification-requests", id).then().statusCode(404);
        as(bob).body(TestSupport.docBody("IDENTITY_PROOF", "id.pdf", "x"))
                .post("/api/v1/applications/{id}/documents", id).then().statusCode(404);
        as(bob).get("/api/v1/applications").then().statusCode(200).body("$", hasSize(0));
    }

    @Test
    void rejectsInvalidInput() {
        String owner = newOwner();
        Map<String, Object> body = applicationBody();
        body.put("currency", "rupees");
        body.put("requestedAmount", -5);
        as(owner).body(body).post("/api/v1/applications")
                .then().statusCode(400)
                .body("code", equalTo("VALIDATION_FAILED"))
                .body("fieldErrors.field", hasItem("currency"))
                .body("fieldErrors.field", hasItem("requestedAmount"));

        Map<String, Object> unknown = applicationBody();
        unknown.put("ownerId", "someone-else");
        as(owner).body(unknown).post("/api/v1/applications").then().statusCode(400);

        as(owner).body("{not json").post("/api/v1/applications").then().statusCode(400);
        as(owner).get("/api/v1/applications/not-a-uuid").then().statusCode(404);
    }

    @Test
    void idempotencyKeyReplaysTheOriginalResponse() {
        String owner = newOwner();
        String key = "create-" + System.nanoTime();
        String first = as(owner).header("Idempotency-Key", key).body(applicationBody())
                .post("/api/v1/applications").then().statusCode(201)
                .header("Idempotent-Replayed", "false").extract().path("id");
        String second = as(owner).header("Idempotency-Key", key).body(applicationBody())
                .post("/api/v1/applications").then().statusCode(201)
                .header("Idempotent-Replayed", "true").extract().path("id");
        assertEquals(first, second);

        Map<String, Object> different = applicationBody();
        different.put("requestedAmount", 999999);
        as(owner).header("Idempotency-Key", key).body(different)
                .post("/api/v1/applications").then().statusCode(422)
                .body("code", equalTo("IDEMPOTENCY_KEY_REUSED"));

        // keys are scoped per owner
        String other = as(newOwner()).header("Idempotency-Key", key).body(applicationBody())
                .post("/api/v1/applications").then().statusCode(201).extract().path("id");
        org.junit.jupiter.api.Assertions.assertNotEquals(first, other);
    }

    @Test
    void concurrentRequestsWithSameIdempotencyKeyCreateOneApplication() throws Exception {
        String owner = newOwner();
        String key = "race-" + System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                tasks.add(() -> as(owner).header("Idempotency-Key", key).body(applicationBody())
                        .post("/api/v1/applications").then().extract().path("id"));
            }
            Set<String> ids = new HashSet<>();
            for (Future<String> f : pool.invokeAll(tasks)) {
                ids.add(f.get());
            }
            assertEquals(1, ids.size(), "all concurrent requests must resolve to one application: " + ids);
            as(owner).get("/api/v1/applications").then().body("$", hasSize(1));
        } finally {
            pool.shutdownNow();
        }
    }
}
