package com.loanverify.service;

import com.loanverify.domain.AttemptStatus;
import com.loanverify.domain.CallbackDisposition;
import com.loanverify.domain.Document;
import com.loanverify.domain.DocumentStatus;
import com.loanverify.domain.ProviderCallbackEvent;
import com.loanverify.domain.ProviderOutcome;
import com.loanverify.domain.VerificationAttempt;
import com.loanverify.provider.ProviderCallback;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.UUID;

/**
 * Applies provider callbacks. Each callback is handled in its own transaction and gets one of the
 * {@link CallbackDisposition}s. The rules, in order:
 * <ol>
 *   <li>An event id seen before is a redelivery: no-op.</li>
 *   <li>Unknown external reference: recorded, no-op.</li>
 *   <li>Attempt already decided: same outcome is a duplicate, a different outcome is a conflict.
 *       The first decision wins either way.</li>
 *   <li>Attempt cancelled, document superseded, or a newer attempt exists for the document: stale, no-op.</li>
 *   <li>Otherwise the outcome is applied to the attempt and the document. A result that arrives after we
 *       timed the attempt out still applies (LATE_APPLIED) as long as nothing newer replaced it.</li>
 * </ol>
 * Concurrency: the document row is locked before the attempt row, the same order as the replace path
 * (application, then document, then attempt). Two callbacks for the same attempt, or a callback racing a
 * replace, therefore serialise instead of interleaving.
 */
@ApplicationScoped
public class CallbackService {

    private static final Logger LOG = Logger.getLogger(CallbackService.class);

    @Inject
    EntityManager em;

    public CallbackDisposition handle(ProviderCallback cb) {
        try {
            return QuarkusTransaction.requiringNew().call(() -> apply(cb, Instant.now()));
        } catch (RuntimeException e) {
            if (DbErrors.isUniqueViolation(e)) {
                // A concurrent delivery of the same event committed first.
                return CallbackDisposition.DUPLICATE;
            }
            throw e;
        }
    }

    CallbackDisposition apply(ProviderCallback cb, Instant now) {
        Long seen = em.createQuery("select count(e) from ProviderCallbackEvent e where e.eventId = :id", Long.class)
                .setParameter("id", cb.eventId())
                .getSingleResult();
        if (seen > 0) {
            return CallbackDisposition.DUPLICATE;
        }

        VerificationAttempt attempt = em.createQuery(
                        "select a from VerificationAttempt a where a.externalRef = :ref", VerificationAttempt.class)
                .setParameter("ref", cb.externalRef())
                .getResultStream().findFirst().orElse(null);
        if (attempt == null) {
            LOG.warnf("Callback for unknown reference (event %s)", cb.eventId());
            return record(cb, null, CallbackDisposition.UNKNOWN_REFERENCE, now);
        }

        Document doc = em.find(Document.class, attempt.documentId, LockModeType.PESSIMISTIC_WRITE);
        em.refresh(attempt, LockModeType.PESSIMISTIC_WRITE);

        CallbackDisposition disposition = decide(cb.outcome(), attempt, doc);
        switch (disposition) {
            case APPLIED, LATE_APPLIED -> {
                attempt.status = cb.outcome() == ProviderOutcome.VERIFIED ? AttemptStatus.VERIFIED : AttemptStatus.REJECTED;
                attempt.completedAt = now;
                attempt.resultReason = cb.reason();
                doc.changeStatus(cb.outcome() == ProviderOutcome.VERIFIED ? DocumentStatus.VERIFIED : DocumentStatus.REJECTED,
                        cb.reason(), now);
            }
            case CONFLICTING_OUTCOME -> LOG.warnf(
                    "Conflicting provider outcome %s for attempt %s already %s; keeping first decision",
                    cb.outcome(), attempt.id, attempt.status);
            default -> {
                // DUPLICATE / STALE: nothing to change
            }
        }
        LOG.debugf("Callback %s for attempt %s -> %s", cb.eventId(), attempt.id, disposition);
        return record(cb, attempt.id, disposition, now);
    }

    static CallbackDisposition decide(ProviderOutcome outcome, VerificationAttempt attempt, Document doc) {
        if (AttemptStatus.DECIDED.contains(attempt.status)) {
            boolean same = (attempt.status == AttemptStatus.VERIFIED) == (outcome == ProviderOutcome.VERIFIED);
            return same ? CallbackDisposition.DUPLICATE : CallbackDisposition.CONFLICTING_OUTCOME;
        }
        boolean latestForCurrentDocument = doc.current && attempt.id.equals(doc.latestAttemptId);
        if (attempt.status == AttemptStatus.CANCELLED || !latestForCurrentDocument) {
            return CallbackDisposition.STALE;
        }
        // QUEUED here means we re-queued after a timeout and the original answer arrived late. Still valid.
        return attempt.status == AttemptStatus.TIMED_OUT || attempt.status == AttemptStatus.FAILED
                ? CallbackDisposition.LATE_APPLIED
                : CallbackDisposition.APPLIED;
    }

    private CallbackDisposition record(ProviderCallback cb, UUID attemptId, CallbackDisposition d, Instant now) {
        ProviderCallbackEvent e = new ProviderCallbackEvent();
        e.id = UUID.randomUUID();
        e.eventId = cb.eventId();
        e.externalRef = cb.externalRef();
        e.attemptId = attemptId;
        e.outcome = cb.outcome().name();
        e.reason = cb.reason();
        e.disposition = d;
        e.occurredAt = cb.occurredAt();
        e.receivedAt = now;
        em.persist(e);
        em.flush();
        return d;
    }
}
