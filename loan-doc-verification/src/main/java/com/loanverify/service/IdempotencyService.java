package com.loanverify.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loanverify.api.ApiException;
import com.loanverify.domain.IdempotencyRecord;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.function.Supplier;

/**
 * Idempotency-Key support for non-idempotent creates.
 * <p>
 * The key row is inserted in the same transaction as the work. A concurrent request with the same key blocks
 * on the primary key until the first commits, then fails with a unique violation and replays the stored
 * response. Same key with a different body gets 422.
 */
@ApplicationScoped
public class IdempotencyService {

    @Inject
    EntityManager em;

    @Inject
    ObjectMapper mapper;

    public record Outcome<T>(T body, int status, boolean replayed) {
    }

    public <T> Outcome<T> execute(String ownerId, String scope, String key, Object request, int successStatus,
                                  Class<T> type, Supplier<T> action) {
        if (key == null || key.isBlank()) {
            return new Outcome<>(QuarkusTransaction.requiringNew().call(action::get), successStatus, false);
        }
        if (key.length() > 128 || !key.matches("^[A-Za-z0-9_\\-:.]+$")) {
            throw ApiException.badRequest("INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key must be 1-128 characters of [A-Za-z0-9_-:.]");
        }
        String requestHash = sha256(toJson(request));
        IdempotencyRecord.Key id = new IdempotencyRecord.Key(ownerId, scope, key);
        try {
            return QuarkusTransaction.requiringNew().call(() -> {
                IdempotencyRecord rec = new IdempotencyRecord();
                rec.key = id;
                rec.requestHash = requestHash;
                rec.createdAt = Instant.now();
                em.persist(rec);
                em.flush(); // claim the key now; blocks behind a concurrent holder of the same key

                T body = action.get();
                rec.responseStatus = successStatus;
                rec.responseBody = toJson(body);
                return new Outcome<>(body, successStatus, false);
            });
        } catch (RuntimeException e) {
            if (!DbErrors.isUniqueViolation(e)) {
                throw e;
            }
            return replay(id, requestHash, type);
        }
    }

    private <T> Outcome<T> replay(IdempotencyRecord.Key id, String requestHash, Class<T> type) {
        IdempotencyRecord rec = QuarkusTransaction.requiringNew().call(() -> em.find(IdempotencyRecord.class, id));
        if (rec == null || rec.responseStatus == null) {
            throw ApiException.conflict("IDEMPOTENCY_IN_PROGRESS", "A request with this Idempotency-Key is in progress");
        }
        if (!rec.requestHash.equals(requestHash)) {
            throw new ApiException(422, "IDEMPOTENCY_KEY_REUSED",
                    "Idempotency-Key was already used with a different request body");
        }
        try {
            return new Outcome<>(mapper.readValue(rec.responseBody, type), rec.responseStatus, true);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored idempotent response is unreadable", e);
        }
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
