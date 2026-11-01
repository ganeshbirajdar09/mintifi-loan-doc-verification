package com.loanverify.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "idempotency_record")
public class IdempotencyRecord {

    @EmbeddedId
    public Key key;

    @Column(name = "request_hash", nullable = false, length = 64)
    public String requestHash;

    @Column(name = "response_status")
    public Integer responseStatus;

    @Column(name = "response_body")
    public String responseBody;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Embeddable
    public static class Key implements Serializable {
        @Column(name = "owner_id")
        public String ownerId;

        @Column(name = "scope")
        public String scope;

        @Column(name = "idempotency_key")
        public String idempotencyKey;

        public Key() {
        }

        public Key(String ownerId, String scope, String idempotencyKey) {
            this.ownerId = ownerId;
            this.scope = scope;
            this.idempotencyKey = idempotencyKey;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key other)) return false;
            return Objects.equals(ownerId, other.ownerId)
                    && Objects.equals(scope, other.scope)
                    && Objects.equals(idempotencyKey, other.idempotencyKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(ownerId, scope, idempotencyKey);
        }
    }
}
