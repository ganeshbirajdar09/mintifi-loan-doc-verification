package com.loanverify.service;

import com.loanverify.api.dto.VerificationProgressResponse;
import com.loanverify.domain.ApplicationStatus;
import com.loanverify.domain.Document;
import com.loanverify.domain.DocumentCategory;
import com.loanverify.domain.DocumentStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plain unit tests for the readiness rules (no Quarkus, no DB). */
class ReadinessTest {

    private static final UUID APP = UUID.randomUUID();

    private static Document doc(DocumentCategory c, int version, boolean current, DocumentStatus s) {
        Document d = new Document();
        d.id = UUID.randomUUID();
        d.applicationId = APP;
        d.category = c;
        d.version = version;
        d.current = current;
        d.status = s;
        d.statusChangedAt = Instant.now();
        return d;
    }

    @Test
    void readyOnlyWhenAllCurrentVersionsVerified() {
        var p = Readiness.evaluate(APP, List.of(
                doc(DocumentCategory.IDENTITY_PROOF, 1, true, DocumentStatus.VERIFIED),
                doc(DocumentCategory.ADDRESS_PROOF, 1, true, DocumentStatus.VERIFIED),
                doc(DocumentCategory.INCOME_PROOF, 1, true, DocumentStatus.VERIFIED)));
        assertTrue(p.readyForReview());
        assertEquals(3, p.verifiedCount());
    }

    @Test
    void verifiedOldVersionDoesNotCount() {
        VerificationProgressResponse p = Readiness.evaluate(APP, List.of(
                doc(DocumentCategory.IDENTITY_PROOF, 1, false, DocumentStatus.VERIFIED),
                doc(DocumentCategory.IDENTITY_PROOF, 2, true, DocumentStatus.PENDING),
                doc(DocumentCategory.ADDRESS_PROOF, 1, true, DocumentStatus.VERIFIED),
                doc(DocumentCategory.INCOME_PROOF, 1, true, DocumentStatus.VERIFIED)));
        assertFalse(p.readyForReview());
        assertEquals(2, p.verifiedCount());
        assertEquals(ApplicationStatus.VERIFICATION_IN_PROGRESS, p.status());
    }

    @Test
    void statusPriorities() {
        assertEquals(ApplicationStatus.AWAITING_DOCUMENTS, Readiness.evaluate(APP, List.of()).status());
        assertEquals(ApplicationStatus.ACTION_REQUIRED, Readiness.evaluate(APP, List.of(
                doc(DocumentCategory.IDENTITY_PROOF, 1, true, DocumentStatus.REJECTED))).status());
        assertEquals(ApplicationStatus.AWAITING_VERIFICATION_REQUEST, Readiness.evaluate(APP, List.of(
                doc(DocumentCategory.IDENTITY_PROOF, 1, true, DocumentStatus.VERIFIED),
                doc(DocumentCategory.ADDRESS_PROOF, 1, true, DocumentStatus.NOT_REQUESTED),
                doc(DocumentCategory.INCOME_PROOF, 1, true, DocumentStatus.PENDING))).status());
        assertEquals(List.of(DocumentCategory.ADDRESS_PROOF, DocumentCategory.INCOME_PROOF),
                Readiness.evaluate(APP, List.of(
                        doc(DocumentCategory.IDENTITY_PROOF, 1, true, DocumentStatus.VERIFIED))).missingCategories());
    }
}
