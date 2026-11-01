package com.loanverify.service;

import com.loanverify.config.AppConfig;
import com.loanverify.domain.AttemptStatus;
import com.loanverify.domain.Document;
import com.loanverify.domain.DocumentStatus;
import com.loanverify.domain.VerificationAttempt;
import com.loanverify.provider.ProviderException;
import com.loanverify.provider.ProviderSubmission;
import com.loanverify.provider.VerificationProvider;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Moves verification attempts through the provider:
 * <ul>
 *   <li><b>dispatch</b>: claims QUEUED attempts with {@code FOR UPDATE SKIP LOCKED} (safe with several app
 *       instances), marks them SUBMITTED, commits, then calls the provider outside any transaction.
 *       A failed call puts the attempt back in the queue with exponential backoff and jitter.</li>
 *   <li><b>sweep</b>: SUBMITTED attempts with no callback after the timeout are re-submitted (same external ref)
 *       until {@code max-submissions}, then TIMED_OUT, and the document goes to FAILED so the applicant can
 *       request verification again.</li>
 * </ul>
 * Both take an explicit {@code now} so tests can drive time.
 */
@ApplicationScoped
public class OutboxDispatcher {

    private static final Logger LOG = Logger.getLogger(OutboxDispatcher.class);
    /** Upper bound on batches per call, so one tick can't run forever. */
    private static final int MAX_ROUNDS = 20;

    @Inject
    EntityManager em;

    @Inject
    VerificationProvider provider;

    @Inject
    AppConfig config;

    record Claimed(UUID attemptId, int attemptNo, ProviderSubmission payload) {
    }

    @Scheduled(every = "${app.outbox.poll-interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            delayed = "2s")
    void tick() {
        Instant now = Instant.now();
        try {
            dispatchDue(now);
            sweepTimeouts(now);
        } catch (RuntimeException e) {
            LOG.error("Outbox tick failed", e);
        }
    }

    /** Claims and submits due attempts, batch by batch, until none are due. @return successful submissions */
    public int dispatchDue(Instant now) {
        int ok = 0;
        for (int round = 0; round < MAX_ROUNDS; round++) {
            List<Claimed> claimed = QuarkusTransaction.requiringNew().call(() -> claim(now));
            for (Claimed c : claimed) {
                try {
                    provider.submit(c.payload());
                    ok++;
                } catch (ProviderException | RuntimeException e) {
                    LOG.infof("Provider submission failed for attempt %s (try %d): %s", c.attemptId(),
                            c.attemptNo(), e.getMessage());
                    QuarkusTransaction.requiringNew().run(() -> onSubmitFailure(c.attemptId(), c.attemptNo(),
                            e.getMessage(), now));
                }
            }
            if (claimed.size() < config.outbox().batchSize()) {
                break;
            }
        }
        return ok;
    }

    /** Re-queues or times out overdue attempts, batch by batch. @return attempts changed */
    public int sweepTimeouts(Instant now) {
        Instant cutoff = now.minus(config.verification().timeout());
        int handled = 0;
        Instant after = Instant.EPOCH;
        for (int round = 0; round < MAX_ROUNDS; round++) {
            final Instant from = after;
            List<Object[]> overdue = QuarkusTransaction.requiringNew().call(() -> em.createQuery("""
                            select a.id, a.submittedAt from VerificationAttempt a
                            where a.status = :submitted and a.submittedAt < :cutoff and a.submittedAt >= :from
                            order by a.submittedAt""", Object[].class)
                    .setParameter("submitted", AttemptStatus.SUBMITTED)
                    .setParameter("cutoff", cutoff)
                    .setParameter("from", from)
                    .setMaxResults(config.outbox().batchSize())
                    .getResultList());
            for (Object[] row : overdue) {
                UUID id = (UUID) row[0];
                Boolean changed = QuarkusTransaction.requiringNew().call(() -> timeOut(id, cutoff, now));
                if (Boolean.TRUE.equals(changed)) {
                    handled++;
                }
                after = (Instant) row[1];
            }
            if (overdue.size() < config.outbox().batchSize()) {
                break;
            }
        }
        return handled;
    }

    // ------------------------------------------------------------------ transactional steps

    List<Claimed> claim(Instant now) {
        @SuppressWarnings("unchecked")
        List<String> ids = em.createNativeQuery("""
                        select cast(id as varchar) from verification_attempt
                        where status = 'QUEUED' and next_attempt_at <= :now
                        order by next_attempt_at
                        limit :limit
                        for update skip locked""")
                .setParameter("now", now)
                .setParameter("limit", config.outbox().batchSize())
                .getResultList();

        List<Claimed> out = new ArrayList<>();
        for (String id : ids) {
            VerificationAttempt a = em.find(VerificationAttempt.class, UUID.fromString(id));
            Document d = em.find(Document.class, a.documentId);
            a.status = AttemptStatus.SUBMITTED;
            a.submissions++;
            a.submittedAt = now;
            a.lastError = null;
            out.add(new Claimed(a.id, a.submissions,
                    new ProviderSubmission(a.externalRef, d.category.name(), d.fileRef, d.filename, d.contentHash)));
        }
        return out;
    }

    void onSubmitFailure(UUID attemptId, int submission, String error, Instant now) {
        Locked l = lockDocumentThenAttempt(attemptId);
        VerificationAttempt a = l.attempt();
        if (a.status != AttemptStatus.SUBMITTED || a.submissions != submission) {
            return; // cancelled or answered meanwhile
        }
        a.lastError = truncate(error);
        if (a.submissions >= config.verification().maxSubmissions()) {
            a.status = AttemptStatus.FAILED;
            a.completedAt = now;
            failDocumentIfLatest(l.document(), a, "Verification provider unavailable; request verification again", now);
        } else {
            a.status = AttemptStatus.QUEUED;
            a.nextAttemptAt = now.plus(backoff(a.submissions));
        }
    }

    boolean timeOut(UUID attemptId, Instant cutoff, Instant now) {
        Locked l = lockDocumentThenAttempt(attemptId);
        VerificationAttempt a = l.attempt();
        if (a.status != AttemptStatus.SUBMITTED || a.submittedAt == null || !a.submittedAt.isBefore(cutoff)) {
            return false; // answered or re-submitted meanwhile
        }
        if (a.submissions < config.verification().maxSubmissions()) {
            a.status = AttemptStatus.QUEUED;
            a.nextAttemptAt = now;
            a.lastError = "No provider callback within " + config.verification().timeout();
        } else {
            a.status = AttemptStatus.TIMED_OUT;
            a.completedAt = now;
            a.lastError = "No provider callback after " + a.submissions + " submissions";
            failDocumentIfLatest(l.document(), a, "Verification provider did not respond; request verification again", now);
        }
        return true;
    }

    // ------------------------------------------------------------------ helpers

    record Locked(Document document, VerificationAttempt attempt) {
    }

    /** Same lock order as the callback and replace paths: document, then attempt. */
    private Locked lockDocumentThenAttempt(UUID attemptId) {
        UUID documentId = em.createQuery("select a.documentId from VerificationAttempt a where a.id = :id", UUID.class)
                .setParameter("id", attemptId)
                .getSingleResult();
        Document d = em.find(Document.class, documentId, LockModeType.PESSIMISTIC_WRITE);
        VerificationAttempt a = em.find(VerificationAttempt.class, attemptId, LockModeType.PESSIMISTIC_WRITE);
        return new Locked(d, a);
    }

    private static void failDocumentIfLatest(Document d, VerificationAttempt a, String reason, Instant now) {
        if (d.current && a.id.equals(d.latestAttemptId) && d.status == DocumentStatus.PENDING) {
            d.changeStatus(DocumentStatus.FAILED, reason, now);
        }
    }

    Duration backoff(int submissions) {
        long base = config.verification().backoffBase().toMillis();
        long max = config.verification().backoffMax().toMillis();
        long exp = Math.min(max, base * (1L << Math.min(20, Math.max(0, submissions - 1))));
        long jitter = ThreadLocalRandom.current().nextLong(0, Math.max(1, exp / 4) + 1);
        return Duration.ofMillis(Math.min(max, exp + jitter));
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= 500 ? s : s.substring(0, 500);
    }
}
