package com.server.altercharger_server.collector.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "collection_state")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CollectionStateEntity {
    @Id
    @Column(length = 20)
    private String task;

    @Column(name = "succeeded_at")
    private OffsetDateTime succeededAt;

    @Column(name = "retry_after")
    private OffsetDateTime retryAfter;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    public void succeed(OffsetDateTime observedAt) {
        succeededAt = observedAt;
        retryAfter = null;
        consecutiveFailures = 0;
    }
}
