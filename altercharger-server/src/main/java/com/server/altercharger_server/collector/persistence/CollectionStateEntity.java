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
    public SyncResult completeStatusSync(OffsetDateTime windowStart, OffsetDateTime windowEnd) {
        SyncResult rejected = null;
        if (syncBaselineAt == null || lastStatusSyncAt == null) rejected = SyncResult.BASELINE_MISSING;
        else if (windowStart == null || windowEnd == null || syncBaselineAt.isAfter(lastStatusSyncAt)
                || windowStart.isAfter(windowEnd)) rejected = SyncResult.INVALID_SYNC_WINDOW;
        else if (windowStart.isAfter(lastStatusSyncAt)) rejected = SyncResult.SYNC_WINDOW_GAP;
        else if (windowEnd.isBefore(lastStatusSyncAt)) rejected = SyncResult.SYNC_CLOCK_REVERSED;
        if (rejected != null) {
            invalidateStatusBaseline();
            return rejected;
        }
        lastStatusSyncAt = windowEnd;
        statusSyncValid = true;
        return SyncResult.CONTINUOUS_SYNC;
    }

    public enum SyncResult {
        CONTINUOUS_SYNC, BASELINE_MISSING, INVALID_SYNC_WINDOW, SYNC_WINDOW_GAP, SYNC_CLOCK_REVERSED
    }
}
