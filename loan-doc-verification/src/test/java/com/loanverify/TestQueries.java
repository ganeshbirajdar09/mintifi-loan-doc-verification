package com.loanverify;

import com.loanverify.domain.Document;
import com.loanverify.domain.ProviderCallbackEvent;
import com.loanverify.domain.VerificationAttempt;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.UUID;

/** Read-only DB helpers for assertions. */
@ApplicationScoped
public class TestQueries {

    @Inject
    EntityManager em;

    @Transactional
    public Document document(UUID id) {
        return em.find(Document.class, id);
    }

    @Transactional
    public List<Document> documents(UUID applicationId) {
        return em.createQuery("select d from Document d where d.applicationId = :a order by d.category, d.version",
                Document.class).setParameter("a", applicationId).getResultList();
    }

    @Transactional
    public List<VerificationAttempt> attempts(UUID documentId) {
        return em.createQuery("select a from VerificationAttempt a where a.documentId = :d order by a.createdAt",
                VerificationAttempt.class).setParameter("d", documentId).getResultList();
    }

    @Transactional
    public VerificationAttempt latestAttempt(UUID documentId) {
        List<VerificationAttempt> all = attempts(documentId);
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    @Transactional
    public VerificationAttempt attempt(UUID id) {
        return em.find(VerificationAttempt.class, id);
    }

    @Transactional
    public List<ProviderCallbackEvent> events(String externalRef) {
        return em.createQuery("select e from ProviderCallbackEvent e where e.externalRef = :r order by e.receivedAt",
                ProviderCallbackEvent.class).setParameter("r", externalRef).getResultList();
    }
}
