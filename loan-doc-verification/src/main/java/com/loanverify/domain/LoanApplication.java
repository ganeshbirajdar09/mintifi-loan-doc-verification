package com.loanverify.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "loan_application")
public class LoanApplication {

    @Id
    public UUID id;

    /** Subject of the JWT that created the application. Every read and write is scoped to it. */
    @Column(name = "owner_id", nullable = false, updatable = false)
    public String ownerId;

    @Column(name = "applicant_name", nullable = false)
    public String applicantName;

    @Column(name = "requested_amount", nullable = false)
    public BigDecimal requestedAmount;

    @Column(nullable = false, length = 3)
    public String currency;

    public String purpose;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    public long rowVersion;
}
