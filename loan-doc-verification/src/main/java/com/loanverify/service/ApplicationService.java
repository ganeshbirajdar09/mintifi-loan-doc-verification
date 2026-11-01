package com.loanverify.service;

import com.loanverify.api.dto.ApplicationResponse;
import com.loanverify.api.dto.CreateApplicationRequest;
import com.loanverify.api.dto.VerificationProgressResponse;
import com.loanverify.domain.Document;
import com.loanverify.domain.LoanApplication;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class ApplicationService {

    @Inject
    EntityManager em;

    @Inject
    ApplicationAccess access;

    @Transactional
    public ApplicationResponse create(String ownerId, CreateApplicationRequest req) {
        Instant now = Instant.now();
        LoanApplication app = new LoanApplication();
        app.id = UUID.randomUUID();
        app.ownerId = ownerId;
        app.applicantName = req.applicantName().strip();
        app.requestedAmount = req.requestedAmount();
        app.currency = req.currency();
        app.purpose = req.purpose() == null || req.purpose().isBlank() ? null : req.purpose().strip();
        app.createdAt = now;
        app.updatedAt = now;
        em.persist(app);
        return toResponse(app, Readiness.evaluate(app.id, List.of()));
    }

    @Transactional
    public ApplicationResponse get(String ownerId, UUID id) {
        LoanApplication app = access.requireOwned(ownerId, id);
        return toResponse(app, Readiness.evaluate(app.id, access.currentDocuments(app.id)));
    }

    @Transactional
    public List<ApplicationResponse> list(String ownerId, int limit) {
        List<LoanApplication> apps = em.createQuery("""
                        select a from LoanApplication a where a.ownerId = :owner order by a.createdAt desc""",
                        LoanApplication.class)
                .setParameter("owner", ownerId)
                .setMaxResults(limit)
                .getResultList();
        if (apps.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<Document>> docs = em.createQuery("""
                        select d from Document d where d.applicationId in :ids and d.current = true""",
                        Document.class)
                .setParameter("ids", apps.stream().map(a -> a.id).toList())
                .getResultStream()
                .collect(Collectors.groupingBy(d -> d.applicationId));
        return apps.stream()
                .map(a -> toResponse(a, Readiness.evaluate(a.id, docs.getOrDefault(a.id, List.of()))))
                .toList();
    }

    static ApplicationResponse toResponse(LoanApplication a, VerificationProgressResponse progress) {
        return new ApplicationResponse(a.id, a.applicantName, a.requestedAmount, a.currency, a.purpose,
                progress.status(), progress.readyForReview(), a.createdAt, a.updatedAt);
    }
}
