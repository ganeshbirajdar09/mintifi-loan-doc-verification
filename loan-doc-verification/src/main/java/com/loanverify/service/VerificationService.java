package com.loanverify.service;

import com.loanverify.api.dto.VerificationProgressResponse;
import com.loanverify.api.dto.VerificationRequestResponse;
import com.loanverify.api.dto.VerificationRequestResponse.Item;
import com.loanverify.domain.AttemptStatus;
import com.loanverify.domain.Document;
import com.loanverify.domain.DocumentCategory;
import com.loanverify.domain.DocumentStatus;
import com.loanverify.domain.LoanApplication;
import com.loanverify.domain.VerificationAttempt;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class VerificationService {

    /** Statuses from which a fresh attempt can be queued. REJECTED needs a replacement, not a retry. */
    private static final Set<DocumentStatus> REQUESTABLE = EnumSet.of(DocumentStatus.NOT_REQUESTED,
            DocumentStatus.FAILED);

    @Inject
    EntityManager em;

    @Inject
    ApplicationAccess access;

    /**
     * Queues verification for every current document that needs it. Idempotent: documents that are already
     * PENDING or VERIFIED are skipped, so repeated calls never create duplicate provider work.
     * Nothing is sent to the provider inside this transaction. The outbox dispatcher picks the attempts up.
     */
    @Transactional
    public VerificationRequestResponse request(String ownerId, UUID applicationId) {
        LoanApplication app = access.requireOwnedForUpdate(ownerId, applicationId);
        Instant now = Instant.now();

        List<Document> current = em.createQuery("""
                        select d from Document d
                        where d.applicationId = :app and d.current = true
                        order by d.category""", Document.class)
                .setParameter("app", app.id)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultList();

        List<Item> queued = new ArrayList<>();
        List<Item> skipped = new ArrayList<>();
        EnumSet<DocumentCategory> present = EnumSet.noneOf(DocumentCategory.class);

        for (Document doc : current) {
            present.add(doc.category);
            if (!REQUESTABLE.contains(doc.status)) {
                skipped.add(item(doc, switch (doc.status) {
                    case PENDING -> "Verification already in progress";
                    case VERIFIED -> "Already verified";
                    case REJECTED -> "Rejected by provider; replace the document to verify again";
                    default -> "Not eligible in status " + doc.status;
                }));
                continue;
            }
            VerificationAttempt attempt = new VerificationAttempt();
            attempt.id = UUID.randomUUID();
            attempt.documentId = doc.id;
            attempt.externalRef = "vrf_" + UUID.randomUUID().toString().replace("-", "");
            attempt.status = AttemptStatus.QUEUED;
            attempt.submissions = 0;
            attempt.nextAttemptAt = now;
            attempt.createdAt = now;
            em.persist(attempt);

            doc.latestAttemptId = attempt.id;
            doc.changeStatus(DocumentStatus.PENDING, null, now);
            queued.add(item(doc, "Queued for verification"));
        }

        List<DocumentCategory> missing = EnumSet.complementOf(present).stream().toList();
        if (!queued.isEmpty()) {
            app.updatedAt = now;
        }
        return new VerificationRequestResponse(app.id, queued, skipped, missing);
    }

    @Transactional
    public VerificationProgressResponse progress(String ownerId, UUID applicationId) {
        LoanApplication app = access.requireOwned(ownerId, applicationId);
        return Readiness.evaluate(app.id, access.currentDocuments(app.id));
    }

    private static Item item(Document d, String note) {
        return new Item(d.category, d.id, d.version, d.status, note);
    }
}
