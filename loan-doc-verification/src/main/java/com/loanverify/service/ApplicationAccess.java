package com.loanverify.service;

import com.loanverify.api.ApiException;
import com.loanverify.domain.Document;
import com.loanverify.domain.LoanApplication;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.UUID;

/**
 * The single place that loads an application for a caller. Applications owned by someone else are reported
 * as 404, not 403, so ids can't be probed for existence.
 */
@ApplicationScoped
public class ApplicationAccess {

    @Inject
    EntityManager em;

    public LoanApplication requireOwned(String ownerId, UUID applicationId) {
        LoanApplication app = em.find(LoanApplication.class, applicationId);
        return checkOwner(app, ownerId);
    }

    /**
     * Loads and row-locks the application. Every document mutation for an application takes this lock first,
     * which serialises add/replace/request-verification per application and gives a fixed lock order
     * (application -> document -> attempt) that the callback path also follows from "document" onward.
     */
    public LoanApplication requireOwnedForUpdate(String ownerId, UUID applicationId) {
        LoanApplication app = em.find(LoanApplication.class, applicationId, LockModeType.PESSIMISTIC_WRITE);
        return checkOwner(app, ownerId);
    }

    public List<Document> currentDocuments(UUID applicationId) {
        return em.createQuery("""
                        select d from Document d
                        where d.applicationId = :app and d.current = true
                        order by d.category""", Document.class)
                .setParameter("app", applicationId)
                .getResultList();
    }

    private static LoanApplication checkOwner(LoanApplication app, String ownerId) {
        if (app == null || !app.ownerId.equals(ownerId)) {
            throw ApiException.notFound("Application");
        }
        return app;
    }
}
