package com.loanverify;

import com.loanverify.domain.Document;
import com.loanverify.domain.DocumentCategory;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.loanverify.TestSupport.addDocument;
import static com.loanverify.TestSupport.as;
import static com.loanverify.TestSupport.createApplication;
import static com.loanverify.TestSupport.docBody;
import static com.loanverify.TestSupport.newOwner;
import static com.loanverify.TestSupport.replaceDocument;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class DocumentLifecycleTest {

    @Inject
    TestQueries db;

    @Test
    void addDocumentStoresMetadataOnly() {
        String owner = newOwner();
        String app = createApplication(owner);
        as(owner).body(docBody("IDENTITY_PROOF", "passport.pdf", "seed-1"))
                .post("/api/v1/applications/{id}/documents", app)
                .then().statusCode(201)
                .header("ETag", "\"1\"")
                .body("category", equalTo("IDENTITY_PROOF"))
                .body("version", equalTo(1))
                .body("current", equalTo(true))
                .body("status", equalTo("NOT_REQUESTED"))
                .body("filename", equalTo("passport.pdf"));

        as(owner).body(docBody("IDENTITY_PROOF", "other.pdf", "seed-2"))
                .post("/api/v1/applications/{id}/documents", app)
                .then().statusCode(409).body("code", equalTo("DOCUMENT_EXISTS"));
    }

    @Test
    void validatesDocumentMetadata() {
        String owner = newOwner();
        String app = createApplication(owner);

        Map<String, Object> badHash = docBody("INCOME_PROOF", "salary.pdf", "s");
        badHash.put("contentHash", "abc");
        as(owner).body(badHash).post("/api/v1/applications/{id}/documents", app)
                .then().statusCode(400).body("fieldErrors.field", hasItem("contentHash"));

        Map<String, Object> traversal = docBody("INCOME_PROOF", "salary.pdf", "s");
        traversal.put("filename", "../../etc/passwd");
        traversal.put("fileRef", "file:///etc/passwd");
        as(owner).body(traversal).post("/api/v1/applications/{id}/documents", app)
                .then().statusCode(400)
                .body("fieldErrors.field", hasItem("filename"))
                .body("fieldErrors.field", hasItem("fileRef"));

        Map<String, Object> badCategory = docBody("PAYSLIP", "salary.pdf", "s");
        as(owner).body(badCategory).post("/api/v1/applications/{id}/documents", app).then().statusCode(400);

        Map<String, Object> noCategory = docBody(null, "salary.pdf", "s");
        as(owner).body(noCategory).post("/api/v1/applications/{id}/documents", app).then().statusCode(400);
    }

    @Test
    void replaceCreatesNewVersionAndKeepsHistory() {
        String owner = newOwner();
        String app = createApplication(owner);
        String v1 = addDocument(owner, app, "ADDRESS_PROOF", "addr-1");

        String v2 = replaceDocument(owner, app, "ADDRESS_PROOF", "addr-2")
                .then().statusCode(201)
                .header("ETag", "\"2\"")
                .body("version", equalTo(2))
                .body("current", equalTo(true))
                .extract().path("id");

        as(owner).get("/api/v1/applications/{id}/documents", app)
                .then().statusCode(200).body("$", hasSize(1)).body("[0].id", equalTo(v2));

        as(owner).queryParam("includeHistory", true).get("/api/v1/applications/{id}/documents", app)
                .then().statusCode(200).body("$", hasSize(2));

        as(owner).get("/api/v1/applications/{id}/documents/by-id/{doc}", app, v1)
                .then().statusCode(200).body("current", equalTo(false)).body("supersededAt", org.hamcrest.Matchers.notNullValue());
    }

    @Test
    void replacingWithIdenticalContentIsANoOp() {
        String owner = newOwner();
        String app = createApplication(owner);
        addDocument(owner, app, "INCOME_PROOF", "same");
        // addDocument uses "<category>.pdf"; replace with the same ref/hash
        String id = as(owner).body(docBody(null, "income_proof.pdf", "same"))
                .put("/api/v1/applications/{id}/documents/INCOME_PROOF", app)
                .then().statusCode(200).body("version", equalTo(1)).extract().path("id");
        assertEquals(1, db.documents(UUID.fromString(app)).size());
        assertEquals(id, db.documents(UUID.fromString(app)).get(0).id.toString());
    }

    @Test
    void conditionalReplaceWithIfMatch() {
        String owner = newOwner();
        String app = createApplication(owner);
        addDocument(owner, app, "IDENTITY_PROOF", "id-1");
        replaceDocument(owner, app, "IDENTITY_PROOF", "id-2").then().statusCode(201);

        as(owner).header("If-Match", "\"1\"").body(docBody(null, "id-3.pdf", "id-3"))
                .put("/api/v1/applications/{id}/documents/IDENTITY_PROOF", app)
                .then().statusCode(412).body("code", equalTo("VERSION_MISMATCH"));

        as(owner).header("If-Match", "\"2\"").body(docBody(null, "id-3.pdf", "id-3"))
                .put("/api/v1/applications/{id}/documents/identity-proof", app)
                .then().statusCode(201).body("version", equalTo(3));
    }

    @Test
    void replacingAMissingCategoryIs404() {
        String owner = newOwner();
        String app = createApplication(owner);
        replaceDocument(owner, app, "INCOME_PROOF", "x").then().statusCode(404);
        as(owner).body(docBody(null, "x.pdf", "x"))
                .put("/api/v1/applications/{id}/documents/NOT_A_CATEGORY", app).then().statusCode(404);
    }

    @Test
    void concurrentReplacesKeepExactlyOneCurrentVersion() throws Exception {
        String owner = newOwner();
        String app = createApplication(owner);
        addDocument(owner, app, "IDENTITY_PROOF", "base");

        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String seed = "concurrent-" + i;
                tasks.add(() -> replaceDocument(owner, app, "IDENTITY_PROOF", seed).statusCode());
            }
            for (Future<Integer> f : pool.invokeAll(tasks)) {
                assertEquals(201, f.get());
            }
        } finally {
            pool.shutdownNow();
        }

        List<Document> docs = db.documents(UUID.fromString(app)).stream()
                .filter(d -> d.category == DocumentCategory.IDENTITY_PROOF).toList();
        assertEquals(threads + 1, docs.size());
        assertEquals(1, docs.stream().filter(d -> d.current).count());
        // versions are contiguous 1..n and the current one is the highest
        for (int i = 0; i < docs.size(); i++) {
            assertEquals(i + 1, docs.get(i).version);
        }
        assertEquals(threads + 1, docs.stream().filter(d -> d.current).findFirst().orElseThrow().version);
    }

    @Test
    void concurrentAddsOfTheSameCategoryLetExactlyOneWin() throws Exception {
        String owner = newOwner();
        String app = createApplication(owner);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String seed = "add-" + i;
                tasks.add(() -> as(owner).body(docBody("ADDRESS_PROOF", "a.pdf", seed))
                        .post("/api/v1/applications/{id}/documents", app).statusCode());
            }
            int created = 0;
            int conflicts = 0;
            for (Future<Integer> f : pool.invokeAll(tasks)) {
                int s = f.get();
                if (s == 201) created++;
                if (s == 409) conflicts++;
            }
            assertEquals(1, created);
            assertEquals(threads - 1, conflicts);
        } finally {
            pool.shutdownNow();
        }
    }
}
