package com.loanverify;

import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import io.smallrye.jwt.build.Jwt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;

/** Fictional test data and request helpers. */
public final class TestSupport {

    public static final List<String> CATEGORIES = List.of("IDENTITY_PROOF", "ADDRESS_PROOF", "INCOME_PROOF");

    private TestSupport() {
    }

    public static String token(String subject, String... roles) {
        return Jwt.issuer("https://loan-verification.local")
                .audience("loan-verification-api")
                .subject(subject)
                .groups(Set.of(roles))
                .expiresIn(3600)
                .sign();
    }

    public static String newOwner() {
        return "applicant-" + UUID.randomUUID();
    }

    public static RequestSpecification as(String owner) {
        return given().auth().oauth2(token(owner, "applicant")).contentType(ContentType.JSON).accept(ContentType.JSON);
    }

    public static RequestSpecification asAdmin() {
        return given().auth().oauth2(token("operator-1", "admin")).contentType(ContentType.JSON).accept(ContentType.JSON);
    }

    public static Map<String, Object> applicationBody() {
        Map<String, Object> m = new HashMap<>();
        m.put("applicantName", "Asha Fictional");
        m.put("requestedAmount", 250000.00);
        m.put("currency", "INR");
        m.put("purpose", "Home renovation");
        return m;
    }

    public static String createApplication(String owner) {
        return as(owner).body(applicationBody())
                .post("/api/v1/applications")
                .then().statusCode(201)
                .extract().path("id");
    }

    public static String sha256(String seed) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static Map<String, Object> docBody(String category, String filename, String seed) {
        Map<String, Object> m = new HashMap<>();
        if (category != null) {
            m.put("category", category);
        }
        m.put("fileRef", "s3://fictional-loan-docs/" + seed + "/" + filename);
        m.put("filename", filename);
        m.put("contentHash", sha256(seed));
        m.put("contentType", "application/pdf");
        m.put("sizeBytes", 123456);
        return m;
    }

    public static String addDocument(String owner, String appId, String category, String seed) {
        return as(owner).body(docBody(category, category.toLowerCase() + ".pdf", seed))
                .post("/api/v1/applications/{id}/documents", appId)
                .then().statusCode(201)
                .extract().path("id");
    }

    public static Response replaceDocument(String owner, String appId, String category, String seed) {
        return as(owner).body(docBody(null, category.toLowerCase() + "-v2.pdf", seed))
                .put("/api/v1/applications/{id}/documents/{cat}", appId, category);
    }

    public static Map<String, String> addAllDocuments(String owner, String appId) {
        Map<String, String> ids = new HashMap<>();
        for (String c : CATEGORIES) {
            ids.put(c, addDocument(owner, appId, c, c + "-" + UUID.randomUUID()));
        }
        return ids;
    }

    public static void requestVerification(String owner, String appId) {
        as(owner).post("/api/v1/applications/{id}/verification-requests", appId).then().statusCode(202);
    }

    public static Response progress(String owner, String appId) {
        return as(owner).get("/api/v1/applications/{id}/verification", appId).then().statusCode(200).extract().response();
    }
}
