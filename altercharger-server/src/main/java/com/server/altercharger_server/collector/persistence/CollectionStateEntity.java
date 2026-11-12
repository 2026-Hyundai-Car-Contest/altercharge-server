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

    @Column(name = "sync_baseline_at")
    private OffsetDateTime syncBaselineAt;

    @Column(name = "last_status_sync_at")
    private OffsetDateTime lastStatusSyncAt;

    @Column(name = "status_sync_valid", nullable = false)
    private boolean statusSyncValid;

    public CollectionStateEntity(String task) {
        this.task = task;
    }

    public static String regionTask(String zcode) {
        return "region:" + zcode;
    }

    public void succeed(OffsetDateTime observedAt) {
        succeededAt = observedAt;
        retryAfter = null;
        consecutiveFailures = 0;
    }

    public void beginStatusSync() {
        statusSyncValid = false;
    }

    public void establishStatusBaseline(OffsetDateTime at) {
        syncBaselineAt = at;
        lastStatusSyncAt = at;
        statusSyncValid = true;
    }

    public void invalidateStatusBaseline() {
        syncBaselineAt = null;
        statusSyncValid = false;
    }

    /** A successful overlapping change window can repair a failed attempt, but never a coverage gap. */
    public void completeStatusSync(OffsetDateTime windowStart, OffsetDateTime windowEnd) {
        if (syncBaselineAt == null || lastStatusSyncAt == null || windowStart == null || windowEnd == null
                || syncBaselineAt.isAfter(lastStatusSyncAt) || windowStart.isAfter(windowEnd)
                || windowStart.isAfter(lastStatusSyncAt) || windowEnd.isBefore(lastStatusSyncAt)) {
            invalidateStatusBaseline();
            return;
        }
        lastStatusSyncAt = windowEnd;
        statusSyncValid = true;
    }
}
