package com.server.altercharger_server.collector;

import com.server.altercharger_server.collector.EvChargerXmlParser.Item;
import com.server.altercharger_server.station.StationStore;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "app.collector.enabled", havingValue = "true", matchIfMissing = true)
public class StationCollector {
    private static final Logger log = LoggerFactory.getLogger(StationCollector.class);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final long LOCK_ID = 462932114;
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final StationStore store;
    private final EvChargerClient client;
    private final String serviceKey;
    private final List<String> zcodes;
    private final int rows;
    private final int dailyBudget;
    private final int metadataBudget;
    private boolean missingKeyReported;

    public StationCollector(DataSource dataSource, JdbcTemplate jdbc, StationStore store, EvChargerClient client,
                            @Value("${app.collector.service-key:${DATA_GO_KR_SERVICE_KEY:}}") String serviceKey,
                            @Value("${app.collector.zcodes:${DATA_GO_KR_ZCODES:11}}") String zcodes,
                            @Value("${app.collector.rows:9999}") int rows,
                            @Value("${app.collector.daily-budget:900}") int dailyBudget,
                            @Value("${app.collector.metadata-budget:100}") int metadataBudget) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.store = store;
        this.client = client;
        this.serviceKey = serviceKey.strip();
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
    }

    @Scheduled(initialDelay = 1000, fixedDelay = 60000)
    public void collectIfDue() {
        if (serviceKey.isBlank()) {
            if (!missingKeyReported) {
                log.info("Collector disabled until DATA_GO_KR_SERVICE_KEY is configured; serving the existing cache");
                missingKeyReported = true;
            }
            return;
        }
        // Session advisory locks MUST be acquired and released on the same physical connection.
        // This dedicated connection is not a long-running transaction; quota reservations commit separately.
        try (Connection lockConnection = dataSource.getConnection()) {
            if (!lock(lockConnection, true)) return;
            try {
                runIfDue("metadata", Duration.ofDays(1));
                runIfDue("status", Duration.ofMinutes(5));
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
        State state = jdbc.queryForObject("""
                SELECT succeeded_at, retry_after, consecutive_failures FROM collection_state WHERE task = ?
                """, (rs, row) -> new State(rs.getObject(1, OffsetDateTime.class),
                rs.getObject(2, OffsetDateTime.class), rs.getInt(3)), task);
        if (state == null || state.retryAfter() != null && now.isBefore(state.retryAfter())
                || state.succeededAt() != null && now.isBefore(state.succeededAt().plus(interval))) return;
        try {
            ensureBudget(task, zcodes.size());
            Map<String, List<Item>> regions = new LinkedHashMap<>();
            for (int regionIndex = 0; regionIndex < zcodes.size(); regionIndex++) {
                String zcode = zcodes.get(regionIndex);
                regions.put(zcode, downloadRegion(task, zcode, zcodes.size() - regionIndex - 1));
            }
            OffsetDateTime observedAt = now();
            if (task.equals("metadata")) {
                store.saveMetadata(regions, observedAt);
            } else {
                store.saveStatuses(regions.values().stream().flatMap(List::stream).toList(), observedAt);
            }
            log.info("Collector {} completed: regions={}, items={}, callsToday={}/{}", task, zcodes.size(),
                    regions.values().stream().mapToInt(List::size).sum(), usage().calls(), dailyBudget);
        } catch (Exception ex) {
            int failures = Math.min(state.failures() + 1, 10);
            OffsetDateTime retryAfter = ex instanceof BudgetExhausted
                    ? LocalDate.now(SEOUL).plusDays(1).atStartOfDay(SEOUL).toOffsetDateTime()
                    : now().plusMinutes(Math.min(360, 5L << (failures - 1)));
            jdbc.update("UPDATE collection_state SET retry_after = ?, consecutive_failures = ? WHERE task = ?",
                    retryAfter, failures, task);
            log.warn("Collector {} deferred until {}; existing cache retained ({})", task, retryAfter,
                    ex.getClass().getSimpleName());
        }
    }

    private List<Item> downloadRegion(String task, String zcode, int remainingRegions) {
        List<Item> all = new ArrayList<>();
        int page = 1;
        int expectedPages = 1;
        int initialTotal = -1;
        do {
            reserveCall(task);
            var result = client.fetch(task, serviceKey, zcode, page, rows);
            if (result.pageNo() != 0 && result.pageNo() != page) {
                throw new CollectionException("Upstream returned an unexpected page");
            }
            int effectiveRows = result.numOfRows() > 0 ? result.numOfRows() : rows;
            if (result.totalCount() >= 0) {
                long totalPages = Math.max(1, (result.totalCount() + (long) effectiveRows - 1) / effectiveRows);
                if (totalPages > dailyBudget) throw new BudgetExhausted();
                expectedPages = (int) totalPages;
                if (task.equals("metadata") && initialTotal >= 0 && initialTotal != result.totalCount()) {
                    throw new CollectionException("Metadata changed during pagination; retrying the full snapshot later");
                }
                initialTotal = result.totalCount();
                if (result.items().isEmpty() && result.totalCount() > 0) {
                    throw new CollectionException("Upstream returned an incomplete page");
                }
            } else {
                // With no totalCount, probe until an empty page. A short page may be a server-side
                // page-size cap, and must not cause an incomplete metadata snapshot to replace cache.
                expectedPages = result.items().isEmpty() ? page : page + 1;
            }
            ensureBudget(task, Math.max(0, expectedPages - page) + remainingRegions);
            if (page == 1) {
                log.info("Collector {} region {}: total={}, pages={}, rows={}", task, zcode,
                        result.totalCount(), expectedPages, effectiveRows);
            }
            all.addAll(result.items());
            page++;
        } while (page <= expectedPages);
        if (task.equals("metadata") && initialTotal >= 0 && all.size() != initialTotal) {
            throw new CollectionException("Incomplete metadata snapshot; keeping the previous cache");
        }
        log.info("Collector {} region {} downloaded {} pages", task, zcode, page - 1);
        return all;
    }

    private void ensureBudget(String task, int needed) {
        Usage usage = usage();
        if (usage.calls() + (long) needed > dailyBudget
                || task.equals("metadata") && usage.metadataCalls() + (long) needed > metadataBudget) {
            throw new BudgetExhausted();
        }
    }

    private Usage usage() {
        LocalDate day = LocalDate.now(SEOUL);
        jdbc.update("INSERT INTO api_daily_usage(usage_date) VALUES (?) ON CONFLICT DO NOTHING", day);
        return jdbc.queryForObject("SELECT calls, metadata_calls FROM api_daily_usage WHERE usage_date = ?",
                (rs, row) -> new Usage(rs.getInt(1), rs.getInt(2)), day);
    }

    private void reserveCall(String task) {
        LocalDate day = LocalDate.now(SEOUL);
        jdbc.update("INSERT INTO api_daily_usage(usage_date) VALUES (?) ON CONFLICT DO NOTHING", day);
        boolean metadata = task.equals("metadata");
        int changed = jdbc.update("""
                UPDATE api_daily_usage SET calls = calls + 1, metadata_calls = metadata_calls + ?
                WHERE usage_date = ? AND calls < ? AND (NOT ? OR metadata_calls < ?)
                """, metadata ? 1 : 0, day, dailyBudget, metadata, metadataBudget);
        if (changed != 1) throw new BudgetExhausted();
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

    private record State(OffsetDateTime succeededAt, OffsetDateTime retryAfter, int failures) { }
    private record Usage(int calls, int metadataCalls) { }
    private static class BudgetExhausted extends CollectionException {
        BudgetExhausted() {
            super("Daily call budget exhausted");
        }
    }
}
