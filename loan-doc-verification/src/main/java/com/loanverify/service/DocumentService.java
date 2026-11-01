package com.loanverify.service;

import com.loanverify.api.ApiException;
import com.loanverify.api.dto.DocumentMetadataRequest;
import com.loanverify.domain.AttemptStatus;
import com.loanverify.domain.Document;
import com.loanverify.domain.DocumentCategory;
import com.loanverify.domain.DocumentStatus;
import com.loanverify.domain.LoanApplication;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class DocumentService {

    private static final Logger LOG = Logger.getLogger(DocumentService.class);

    @Inject
    EntityManager em;

    @Inject
    ApplicationAccess access;

    /** Result of a replace: the current document and whether a new version was created. */
    public record ReplaceResult(Document document, boolean created) {
    }

    /** Adds the first version of a category. Use replace for later versions. */
    @Transactional
    public Document add(String ownerId, UUID applicationId, DocumentMetadataRequest req) {
        if (req.category() == null) {
            throw new ApiException(400, "VALIDATION_FAILED", "category is required");
        }
        LoanApplication app = access.requireOwnedForUpdate(ownerId, applicationId);
        if (findCurrent(app.id, req.category(), false).isPresent()) {
            throw ApiException.conflict("DOCUMENT_EXISTS",
                    "A " + req.category() + " document already exists. Use PUT .../documents/"
                            + req.category() + " to replace it.");
        }
        Instant now = Instant.now();
        Document doc = newVersion(app.id, req.category(), 1, req, now);
        em.persist(doc);
        touch(app, now);
        return doc;
    }

    /**
     * Replaces the current version of a category with a new one.
     * <ul>
     *   <li>Same file reference and content hash as the current version: no-op, returns the current version.
     *       This makes client retries safe.</li>
     *   <li>Otherwise the current version is superseded. Any in-flight verification attempt for it is cancelled,
     *       so a late provider result for the old file can never mark the application ready.</li>
     *   <li>{@code expectedVersion} (from If-Match) guards against overwriting a version the client hasn't seen.</li>
     * </ul>
     */
    @Transactional
    public ReplaceResult replace(String ownerId, UUID applicationId, DocumentCategory category,
                                 DocumentMetadataRequest req, Integer expectedVersion) {
        LoanApplication app = access.requireOwnedForUpdate(ownerId, applicationId);
        Document current = findCurrent(app.id, category, true)
                .orElseThrow(() -> new ApiException(404, "NOT_FOUND",
                        "No " + category + " document to replace. Use POST .../documents to add it."));

        if (expectedVersion != null && expectedVersion != current.version) {
            throw new ApiException(412, "VERSION_MISMATCH", "Current " + category + " version is "
                    + current.version + ", not " + expectedVersion);
        }

        String newHash = req.contentHash().toLowerCase(Locale.ROOT);
        if (current.contentHash.equals(newHash) && current.fileRef.equals(req.fileRef())) {
            return new ReplaceResult(current, false);
        }

        Instant now = Instant.now();
        current.current = false;
        current.supersededAt = now;
        if (current.status == DocumentStatus.PENDING) {
            current.changeStatus(DocumentStatus.CANCELLED, "Replaced by version " + (current.version + 1), now);
        }
        int cancelled = em.createQuery("""
                        update VerificationAttempt a
                        set a.status = :cancelled, a.completedAt = :now,
                            a.resultReason = 'Document replaced', a.rowVersion = a.rowVersion + 1
                        where a.documentId = :doc and a.status in :inFlight""")
                .setParameter("cancelled", AttemptStatus.CANCELLED)
                .setParameter("now", now)
                .setParameter("doc", current.id)
                .setParameter("inFlight", AttemptStatus.IN_FLIGHT)
                .executeUpdate();
        if (cancelled > 0) {
            LOG.infof("Cancelled %d in-flight attempt(s) for superseded document %s", cancelled, current.id);
        }
        // Hibernate flushes inserts before updates. Flush the old row first so the partial unique index
        // "one current version per category" never sees two current rows.
        em.flush();

        Document next = newVersion(app.id, category, current.version + 1, req, now);
        em.persist(next);
        touch(app, now);
        return new ReplaceResult(next, true);
    }

    @Transactional
    public List<Document> list(String ownerId, UUID applicationId, boolean includeHistory) {
        LoanApplication app = access.requireOwned(ownerId, applicationId);
        if (!includeHistory) {
            return access.currentDocuments(app.id);
        }
        return em.createQuery("""
                        select d from Document d where d.applicationId = :app
                        order by d.category, d.version desc""", Document.class)
                .setParameter("app", app.id)
                .getResultList();
    }

    @Transactional
    public Document get(String ownerId, UUID applicationId, UUID documentId) {
        LoanApplication app = access.requireOwned(ownerId, applicationId);
        Document d = em.find(Document.class, documentId);
        if (d == null || !d.applicationId.equals(app.id)) {
            throw ApiException.notFound("Document");
        }
        return d;
    }

    private Optional<Document> findCurrent(UUID applicationId, DocumentCategory category, boolean lock) {
        var query = em.createQuery("""
                        select d from Document d
                        where d.applicationId = :app and d.category = :cat and d.current = true""", Document.class)
                .setParameter("app", applicationId)
                .setParameter("cat", category);
        if (lock) {
            query.setLockMode(LockModeType.PESSIMISTIC_WRITE);
        }
        return query.getResultStream().findFirst();
    }

    private static Document newVersion(UUID applicationId, DocumentCategory category, int version,
                                       DocumentMetadataRequest req, Instant now) {
        Document d = new Document();
        d.id = UUID.randomUUID();
        d.applicationId = applicationId;
        d.category = category;
        d.version = version;
        d.current = true;
        d.fileRef = req.fileRef();
        d.filename = req.filename().strip();
        d.contentHash = req.contentHash().toLowerCase(Locale.ROOT);
        d.contentType = req.contentType();
        d.sizeBytes = req.sizeBytes();
        d.status = DocumentStatus.NOT_REQUESTED;
        d.createdAt = now;
        d.statusChangedAt = now;
        return d;
    }

    private static void touch(LoanApplication app, Instant now) {
        app.updatedAt = now;
    }
}
