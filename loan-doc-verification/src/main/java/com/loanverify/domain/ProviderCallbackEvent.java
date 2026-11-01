package com.loanverify.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Every authenticated provider callback, with what we did with it. The unique event id makes redeliveries no-ops. */
@Entity
@Table(name = "provider_callback_event")
public class ProviderCallbackEvent {

    @Id
    public UUID id;

    @Column(name = "event_id", nullable = false, updatable = false, unique = true)
    public String eventId;

    @Column(name = "external_ref", nullable = false, updatable = false)
    public String externalRef;

    @Column(name = "attempt_id", updatable = false)
    public UUID attemptId;

    @Column(nullable = false, updatable = false)
    public String outcome;

    @Column(updatable = false)
    public String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    public CallbackDisposition disposition;

    @Column(name = "occurred_at", updatable = false)
    public Instant occurredAt;

    @Column(name = "received_at", nullable = false, updatable = false)
    public Instant receivedAt;
}
