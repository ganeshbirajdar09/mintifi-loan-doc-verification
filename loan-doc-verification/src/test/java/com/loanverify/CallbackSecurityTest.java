package com.loanverify;

import com.loanverify.provider.CallbackSigner;
import com.loanverify.provider.SimulatedVerificationProvider;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The callback endpoint is public, so the HMAC check is all that stands between it and forged results. */
@QuarkusTest
class CallbackSecurityTest {

    @Inject
    SimulatedVerificationProvider simulator;

    @Inject
    CallbackSigner signer;

    @Inject
    TestQueries db;

    private static String body(String eventId, String ref, String outcome) {
        return "{\"eventId\":\"" + eventId + "\",\"externalRef\":\"" + ref + "\",\"outcome\":\"" + outcome + "\"}";
    }

    @Test
    void rejectsUnsignedCallbacks() {
        String b = body("evt_" + UUID.randomUUID().toString().replace("-", ""), "vrf_x", "VERIFIED");
        assertEquals(401, simulator.postRaw(b, null, null));
        assertEquals(401, simulator.postRaw(b, Long.toString(Instant.now().getEpochSecond()), null));
    }

    @Test
    void rejectsWrongSignature() {
        String b = body("evt_" + UUID.randomUUID().toString().replace("-", ""), "vrf_x", "VERIFIED");
        long ts = Instant.now().getEpochSecond();
        assertEquals(401, simulator.postRaw(b, Long.toString(ts), "sha256=" + "00".repeat(32)));
        assertEquals(401, simulator.postRaw(b, Long.toString(ts), "sha256=not-hex"));
    }

    @Test
    void rejectsTamperedBody() {
        long ts = Instant.now().getEpochSecond();
        String signed = body("evt_a" + System.nanoTime(), "vrf_x", "REJECTED");
        String sig = signer.sign(ts, signed.getBytes(StandardCharsets.UTF_8));
        String tampered = signed.replace("REJECTED", "VERIFIED");
        assertEquals(401, simulator.postRaw(tampered, Long.toString(ts), sig));
    }

    @Test
    void rejectsReplayOutsideTimestampWindow() {
        long old = Instant.now().minusSeconds(3600).getEpochSecond();
        String b = body("evt_old" + System.nanoTime(), "vrf_x", "VERIFIED");
        assertEquals(401, simulator.postRaw(b, Long.toString(old), signer.sign(old, b.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void rejectsSignedButMalformedPayload() {
        long ts = Instant.now().getEpochSecond();
        String b = "{\"eventId\":\"e1\",\"externalRef\":\"r1\",\"outcome\":\"MAYBE\"}";
        assertEquals(400, simulator.postRaw(b, Long.toString(ts), signer.sign(ts, b.getBytes(StandardCharsets.UTF_8))));
        String injection = body("e2'; drop table document;--", "r1", "VERIFIED");
        assertEquals(400, simulator.postRaw(injection, Long.toString(ts),
                signer.sign(ts, injection.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void acceptsValidSignature() {
        long ts = Instant.now().getEpochSecond();
        String b = body("evt_ok" + System.nanoTime(), "vrf_nobody", "VERIFIED");
        assertEquals(200, simulator.postRaw(b, Long.toString(ts), signer.sign(ts, b.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void concurrentRedeliveriesOfOneEventAreStoredOnce() throws Exception {
        String ref = "vrf_race" + System.nanoTime();
        String b = body("evt_race" + System.nanoTime(), ref, "VERIFIED");
        long ts = Instant.now().getEpochSecond();
        String sig = signer.sign(ts, b.getBytes(StandardCharsets.UTF_8));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                tasks.add(() -> simulator.postRaw(b, Long.toString(ts), sig));
            }
            for (Future<Integer> f : pool.invokeAll(tasks)) {
                assertEquals(200, f.get());
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, db.events(ref).size());
    }
}
