package com.server.altercharger_server.collector;

import com.server.altercharger_server.collector.CollectionException.Reason;
import com.server.altercharger_server.collector.persistence.CollectorLedger;
import com.server.altercharger_server.collector.persistence.CollectorLedger.State;
import com.server.altercharger_server.collector.persistence.CollectorLedger.Usage;
import com.server.altercharger_server.station.StationStore;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.collector.enabled", havingValue = "true", matchIfMissing = true)
public class StationCollector {
    private static final Logger log = LoggerFactory.getLogger(StationCollector.class);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final long LOCK_ID = 462932114;
    private final DataSource dataSource;
    private final CollectorLedger ledger;
    private final StationStore store;
    private final EvChargerClient client;
    private final String serviceKey;
    private final List<String> zcodes;
    private final int rows;
    private final int dailyBudget;
    private final int metadataBudget;
    private final Duration statusRefresh;
    private final Duration infoRefresh;

    public StationCollector(DataSource dataSource, CollectorLedger ledger, StationStore store, EvChargerClient client,
                            @Value("${app.collector.service-key}") String serviceKey,
                            @Value("${app.collector.zcodes}") String zcodes,
                            @Value("${app.collector.rows}") int rows,
                            @Value("${app.collector.daily-budget}") int dailyBudget,
                            @Value("${app.collector.metadata-budget}") int metadataBudget,
                            @Value("${app.collector.status-refresh-seconds}") long statusRefreshSeconds,
                            @Value("${app.collector.info-refresh-seconds}") long infoRefreshSeconds) {
        this.dataSource = dataSource;
        this.ledger = ledger;
        this.store = store;
        this.client = client;
        this.serviceKey = serviceKey.strip();
        if (this.serviceKey.isBlank() || Set.of("needed", "your_service_key_here", "your_service_key", "change_me", "changeme")
                .contains(this.serviceKey.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("An actual service key is required when the collector is enabled");
        }
        this.zcodes = Arrays.stream(zcodes.split(",")).map(String::strip).filter(value -> !value.isBlank()).distinct().toList();
        if (this.zcodes.isEmpty() || this.zcodes.stream().anyMatch(code -> !code.matches("[0-9]{2}"))) {
            throw new IllegalArgumentException("Collector zcodes must be comma-separated two-digit regional codes");
        }
        if (rows < 10 || rows > 9999 || dailyBudget < 1 || dailyBudget > 900
                || metadataBudget < 1 || metadataBudget > dailyBudget) {
            throw new IllegalArgumentException("Collector rows or call budgets are outside their supported limits");
        }
        this.rows = rows;
        this.dailyBudget = dailyBudget;
        this.metadataBudget = metadataBudget;
        if (statusRefreshSeconds < 300 || infoRefreshSeconds < 86400) {
            throw new IllegalArgumentException("Collector status/info refresh must be at least 300/86400 seconds");
        }
        this.statusRefresh = Duration.ofSeconds(statusRefreshSeconds);
        this.infoRefresh = Duration.ofSeconds(infoRefreshSeconds);
    }

    @Scheduled(initialDelay = 1000, fixedDelay = 60000)
    public void collectIfDue() {
        // Session advisory locks MUST be acquired and released on the same physical connection.
        // This dedicated connection is not a long-running transaction; quota reservations commit separately.
        try (Connection lockConnection = dataSource.getConnection()) {
            if (!lock(lockConnection, true)) return;
            try {
                runIfDue("metadata", infoRefresh);
                runIfDue("status", statusRefresh);
            } finally {
                try {
                    lock(lockConnection, false);
                } catch (SQLException ex) {
                    // A pooled session must never retain an unreleased advisory lock.
                    lockConnection.abort(Runnable::run);
                    throw ex;
                }
            }
        } catch (Exception ex) {
            log.warn("Collector database coordination failed ({})", ex.getClass().getSimpleName());
        }
    }

    private void runIfDue(String task, Duration interval) {
        if (task.equals("status") && !store.hasCollectedData()) return;
        OffsetDateTime now = now();
        State state = ledger.state(task);
        if (state == null || state.retryAfter() != null && now.isBefore(state.retryAfter())
                || state.succeededAt() != null && now.isBefore(state.succeededAt().plus(interval))) return;
        Progress progress = new Progress();
        try (var snapshot = new CollectedSnapshot()) {
            ensureBudget(task, zcodes.size());
            for (int regionIndex = 0; regionIndex < zcodes.size(); regionIndex++) {
                String zcode = zcodes.get(regionIndex);
                progress.region = zcode;
                progress.page = 0;
                progress.received = 0;
                progress.stage = Stage.PREPARE_SNAPSHOT;
                try (var writer = snapshot.openRegion(zcode)) {
                    downloadRegion(task, zcode, zcodes.size() - regionIndex - 1, writer, progress);
                }
            }
            // Earlier pages must not appear newly observed merely because a long download finished.
            OffsetDateTime observedAt = now;
            progress.stage = Stage.APPLY_SNAPSHOT;
            progress.region = "ALL";
            progress.page = 0;
            progress.received = snapshot.itemCount();
            if (task.equals("metadata")) {
                store.saveMetadata(snapshot, observedAt);
            } else {
                store.saveStatuses(snapshot, observedAt);
            }
            log.info("Collector {} completed: regions={}, items={}, callsToday={}/{}", task, zcodes.size(),
                    snapshot.itemCount(), usage().calls(), dailyBudget);
        } catch (Exception ex) {
            int failures = Math.min(state.failures() + 1, 10);
            OffsetDateTime retryAfter = ex instanceof BudgetExhausted
                    ? LocalDate.now(SEOUL).plusDays(1).atStartOfDay(SEOUL).toOffsetDateTime()
                    : now().plusMinutes(Math.min(360, 5L << (failures - 1)));
            ledger.defer(task, retryAfter, failures);
            Reason reason = ex instanceof CollectionException failure ? failure.reason() : Reason.UNEXPECTED_FAILURE;
            log.warn("Collector deferred task={} region={} page={} received={} expectedTotal={} reportedTotal={} "
                            + "returned={} reportedRows={} requestedRows={} stage={} reason={} retryAfter={}",
                    task, progress.region, progress.page, progress.received, progress.expectedTotal, progress.reportedTotal,
                    progress.returned, progress.reportedRows, rows, progress.stage, reason, retryAfter);
        }
    }

    private void downloadRegion(String task, String zcode, int remainingRegions,
                                CollectedSnapshot.RegionWriter writer, Progress progress) {
        int page = 1;
        int capacity = 0;
        int expectedTotal = -1;
        int pageLimit = task.equals("metadata") ? metadataBudget : dailyBudget;
        progress.region = zcode;
        progress.received = 0;
        progress.expectedTotal = -1;
        while (true) {
            progress.page = page;
            progress.returned = 0;
            progress.reportedRows = 0;
            progress.reportedTotal = -1;
            progress.stage = Stage.FETCH_PAGE;
            if (page > pageLimit) throw new CollectionException(Reason.PAGE_LIMIT_REACHED);
            reserveCall(task);
            var result = client.fetch(task, serviceKey, zcode, page, rows);
            progress.returned = result.items().size();
            progress.reportedRows = result.numOfRows();
            progress.reportedTotal = result.totalCount();
            progress.stage = Stage.VALIDATE_PAGE;
            if (result.pageNo() != 0 && result.pageNo() != page) {
                throw new CollectionException(Reason.PAGE_NUMBER_MISMATCH);
            }
            if (result.totalCount() >= 0) {
                if (task.equals("metadata") && expectedTotal >= 0 && expectedTotal != result.totalCount()) {
                    throw new CollectionException(Reason.METADATA_TOTAL_CHANGED);
                }
                expectedTotal = result.totalCount();
            }
            progress.expectedTotal = expectedTotal;
            if (capacity == 0 && progress.returned > 0) {
                // Only an estimate for quota planning. The first actual page also reveals a server cap.
                // numOfRows may be the current page's item count, including a short final page.
                capacity = progress.returned;
            }
            long received = (long) writer.itemCount() + progress.returned;
            if (task.equals("metadata") && expectedTotal >= 0 && received > expectedTotal) {
                throw new CollectionException(Reason.ITEM_COUNT_MISMATCH);
            }
            if (progress.returned == 0 && expectedTotal >= 0 && received < expectedTotal) {
                throw new CollectionException(Reason.EMPTY_PAGE_BEFORE_TOTAL);
            }
            boolean complete = expectedTotal >= 0 ? received >= expectedTotal : progress.returned == 0;
            long remainingPages = complete ? 0 : expectedTotal >= 0
                    ? (expectedTotal - received + capacity - 1L) / capacity : 1;
            ensureBudget(task, remainingPages + remainingRegions);
            progress.stage = Stage.WRITE_SPOOL;
            writer.append(result.items());
            progress.received = writer.itemCount();
            log.info("Collector page task={} region={} page={} returned={} received={} expectedTotal={} "
                            + "reportedRows={} requestedRows={} reason={}", task, zcode, page, progress.returned,
                    progress.received, expectedTotal, result.numOfRows(), rows, complete ? "REGION_COMPLETE" : "MORE_PAGES");
            if (complete) break;
            page++;
        }
        if (task.equals("metadata") && expectedTotal >= 0 && writer.itemCount() != expectedTotal) {
            throw new CollectionException(Reason.ITEM_COUNT_MISMATCH);
        }
        log.info("Collector {} region {} downloaded {} pages", task, zcode, page);
    }

    private void ensureBudget(String task, long needed) {
        Usage usage = usage();
        if (usage.calls() + (long) needed > dailyBudget
                || task.equals("metadata") && usage.metadataCalls() + (long) needed > metadataBudget) {
            throw new BudgetExhausted();
        }
    }

    private Usage usage() {
        return ledger.usage(LocalDate.now(SEOUL));
    }

    private void reserveCall(String task) {
        if (!ledger.reserve(LocalDate.now(SEOUL), task.equals("metadata"), dailyBudget, metadataBudget)) {
            throw new BudgetExhausted();
        }
    }

    private boolean lock(Connection connection, boolean acquire) throws SQLException {
        String sql = acquire ? "SELECT pg_try_advisory_lock(?)" : "SELECT pg_advisory_unlock(?)";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, LOCK_ID);
            try (var result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(SEOUL);
    }

    private static class BudgetExhausted extends CollectionException {
        BudgetExhausted() {
            super(Reason.BUDGET_EXHAUSTED);
        }
    }

    private enum Stage { PREPARE_SNAPSHOT, FETCH_PAGE, VALIDATE_PAGE, WRITE_SPOOL, APPLY_SNAPSHOT }

    private static final class Progress {
        private String region = "-";
        private int page;
        private int received;
        private int expectedTotal = -1;
        private int reportedTotal = -1;
        private int returned;
        private int reportedRows;
        private Stage stage = Stage.PREPARE_SNAPSHOT;
    }
}
