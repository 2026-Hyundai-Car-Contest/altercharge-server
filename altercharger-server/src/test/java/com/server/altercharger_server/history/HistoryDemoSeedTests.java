package com.server.altercharger_server.history;

import com.server.altercharger_server.common.QuerydslConfig;
import com.server.altercharger_server.history.HistoryDtos.SnapshotRecord;
import com.server.altercharger_server.history.entity.ChargingEvent;
import com.server.altercharger_server.history.entity.Vehicle;
import com.server.altercharger_server.history.entity.VehicleSnapshot;
import com.server.altercharger_server.history.repository.ChargingEventRepository;
import com.server.altercharger_server.history.repository.StationVisitRepository;
import com.server.altercharger_server.history.repository.VehicleRepository;
import com.server.altercharger_server.history.repository.VehicleSnapshotRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(showSql = false, properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false",
        "spring.flyway.baseline-on-migrate=false",
        "app.history.retention-days=56",
        "app.history.max-future-minutes=5"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import({QuerydslConfig.class, HistoryService.class, HistoryPolicy.class, HistoryDemoSeedTests.TestClock.class})
@Testcontainers
class HistoryDemoSeedTests {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("altercharge_demo_seed_test")
            .withUsername("altercharge_test")
            .withPassword(UUID.randomUUID().toString())
            .withCommand("postgres", "-c", "timezone=Pacific/Honolulu");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired HistoryService history;
    @Autowired HistoryPolicy policy;
    @Autowired DataSource dataSource;
    @Autowired VehicleSnapshotRepository snapshots;
    @Autowired ChargingEventRepository events;
    @Autowired VehicleRepository vehicles;
    @Autowired StationVisitRepository visits;
    @Autowired MutableClock clock;
    @MockitoBean StationMatcher matcher;

    @BeforeEach
    void resetClock() { clock.set(Instant.now()); }

    @Test
    void migratedDemoHistoryContainsFourteenCompleteKoreanDaysWithNondecreasingMileage() {
        var result = history.snapshots("mock-vehicle-001", null, null);

        assertEquals(70, result.records().size());
        assertEquals(14, result.completeDayCount());
        assertEquals(56, result.retentionDays());
        var days = result.records().stream().collect(Collectors.groupingBy(record ->
                record.recordedAt().atZoneSameInstant(HistoryPolicy.SEOUL).toLocalDate()));
        assertEquals(14, days.size());
        var times = List.of(LocalTime.of(0, 30), LocalTime.of(6, 15), LocalTime.NOON,
                LocalTime.of(17, 45), LocalTime.of(23, 30));
        for (int day = 1; day <= 14; day++) {
            var rows = days.get(policy.today().minusDays(day));
            assertNotNull(rows);
            assertEquals(times, rows.stream().map(row ->
                    row.recordedAt().atZoneSameInstant(HistoryPolicy.SEOUL).toLocalTime()).toList());
            assertEquals(40.0, rows.getLast().odometerKm() - rows.getFirst().odometerKm());
        }
        for (int index = 0; index < result.records().size(); index++) {
            SnapshotRecord row = result.records().get(index);
            assertTrue(row.socPercent() >= 0 && row.socPercent() <= 100);
            assertNull(row.drivableRangeKm());
            assertFalse(row.recordedAt().isAfter(java.time.OffsetDateTime.now()));
            if (index > 0) {
                SnapshotRecord previous = result.records().get(index - 1);
                assertTrue(row.odometerKm() >= previous.odometerKm());
                assertTrue(Duration.between(previous.recordedAt(), row.recordedAt()).compareTo(Duration.ofHours(6)) <= 0);
            }
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void upgradingAnExistingDatabasePreservesMeasurementsAndDoesNotSeedAgainOnRestart() throws Exception {
        upgradeFlyway("3").migrate();
        // SQL fixtures live only in this test container's separate upgrade schema.
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO demo_seed_upgrade.vehicles(vehicle_id, user_id)
                    VALUES ('other-demo-vehicle', 'mock-user-001')
                    """);
            statement.executeUpdate("""
                    INSERT INTO demo_seed_upgrade.vehicle_snapshots
                        (snapshot_id, vehicle_id, recorded_at, soc_percent, odometer_km)
                    VALUES ('existing-demo-measurement', 'mock-vehicle-001',
                        ((CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')::date - 1 + time '00:30')
                            AT TIME ZONE 'Asia/Seoul', 27, 12345),
                        ('other-vehicle-measurement', 'other-demo-vehicle', CURRENT_TIMESTAMP, 60, 500)
                    """);
        }

        var latest = upgradeFlyway("4");
        assertEquals(1, latest.migrate().migrationsExecuted);
        assertEquals(0, latest.migrate().migrationsExecuted);

        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("""
                    SELECT count(*) FROM demo_seed_upgrade.vehicle_snapshots
                    WHERE vehicle_id = 'mock-vehicle-001'
                    """)) {
                assertTrue(rows.next());
                assertEquals(70, rows.getInt(1));
            }
            try (var rows = statement.executeQuery("""
                    SELECT soc_percent, odometer_km FROM demo_seed_upgrade.vehicle_snapshots
                    WHERE snapshot_id = 'existing-demo-measurement'
                    """)) {
                assertTrue(rows.next());
                assertEquals(27, rows.getInt(1));
                assertEquals(12345.0, rows.getDouble(2));
            }
            try (var rows = statement.executeQuery("""
                    SELECT count(*) FROM demo_seed_upgrade.vehicle_snapshots
                    WHERE vehicle_id = 'other-demo-vehicle' AND snapshot_id = 'other-vehicle-measurement'
                        AND soc_percent = 60 AND odometer_km = 500
                    """)) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
            }
            for (String table : List.of("charging_events", "station_visits")) {
                try (var rows = statement.executeQuery("SELECT count(*) FROM demo_seed_upgrade." + table)) {
                    assertTrue(rows.next());
                    assertEquals(0, rows.getInt(1));
                }
            }
        }
    }

    @Test
    void rollingHistoryStaysCompleteAcrossDayChangesRestartsAndLongDowntime() {
        var initialDate = policy.today();
        for (int elapsedDays : new int[]{0, 1, 4, 40, 90}) {
            clock.set(initialDate.plusDays(elapsedDays).atTime(8, 0).atZone(HistoryPolicy.SEOUL).toInstant());
            history.purgeExpiredSnapshots();
            var asOf = OffsetDateTime.now(clock);
            snapshots.refreshDemoHistory(asOf);
            assertEquals(0, snapshots.refreshDemoHistory(asOf), "a restart must not insert duplicate rows");

            var result = history.snapshots("mock-vehicle-001", policy.today().minusDays(14), policy.today().minusDays(1));
            assertEquals(70, result.records().size());
            assertEquals(14, result.completeDayCount());
            var days = result.records().stream().collect(Collectors.groupingBy(row ->
                    row.recordedAt().atZoneSameInstant(HistoryPolicy.SEOUL).toLocalDate()));
            for (int day = 1; day <= 14; day++) {
                var rows = days.get(policy.today().minusDays(day));
                assertEquals(5, rows.size());
                assertEquals(40.0, rows.getLast().odometerKm() - rows.getFirst().odometerKm());
            }
            for (int index = 1; index < result.records().size(); index++) {
                assertTrue(result.records().get(index).odometerKm() >= result.records().get(index - 1).odometerKm());
            }
            assertTrue(result.records().stream().allMatch(row -> row.recordedAt().isBefore(asOf)));
            var retainedDates = history.snapshots("mock-vehicle-001", null, null).records().stream()
                    .map(row -> row.recordedAt().atZoneSameInstant(HistoryPolicy.SEOUL).toLocalDate()).distinct().toList();
            for (int index = 1; index < retainedDates.size(); index++) {
                assertEquals(retainedDates.get(index - 1).plusDays(1), retainedDates.get(index),
                        "retained gaps would make the app attribute all missing mileage to a single date");
            }
            assertTrue(snapshots.count() <= 56 * 5);
            assertNotNull(history.latestCharge("mock-vehicle-001").distanceSinceChargeKm());
        }
    }

    @Test
    void mockCompletionsMatchBatteryRisesAndSupplyEveryLastChargeFieldUsedByTheApp() {
        var records = history.snapshots("mock-vehicle-001", null, null).records();
        var completions = events.findAll();
        assertEquals(3, completions.size());
        for (var event : completions) {
            int index = java.util.stream.IntStream.range(0, records.size())
                    .filter(i -> records.get(i).recordedAt().toInstant().equals(event.getOccurredAt().toInstant()))
                    .findFirst().orElseThrow();
            assertTrue(index > 0);
            assertTrue(records.get(index).socPercent() > records.get(index - 1).socPercent());
            assertEquals(records.get(index).odometerKm(), event.getOdometerKm());
            assertEquals(HistoryDtos.EventType.COMPLETE, event.getEventType());
            assertEquals(HistoryDtos.Source.MOCK, event.getSource());
            assertFalse(event.isVerified());
            assertNull(event.getMatchedStationId());
            assertEquals(0, event.getVisitCountAdded());
            assertFalse(event.isDailyDuplicate());
        }
        var latest = history.latestCharge("mock-vehicle-001");
        assertEquals(policy.today().minusDays(2), latest.lastChargeCompletedAt().atZoneSameInstant(HistoryPolicy.SEOUL).toLocalDate());
        assertEquals(LocalTime.of(0, 30), latest.lastChargeCompletedAt().atZoneSameInstant(HistoryPolicy.SEOUL).toLocalTime());
        assertEquals(20480.0, latest.odometerAtLastChargeKm());
        assertEquals(20560.0, latest.currentOdometerKm());
        assertEquals(80.0, latest.distanceSinceChargeKm());
        assertEquals(HistoryDtos.Source.MOCK, latest.source());
        assertFalse(latest.trusted());
        assertEquals(0, visits.count());
    }

    @Test
    void rollingRefreshPreservesExistingMeasurementsEventsAndOtherVehicles() {
        var initialDate = policy.today();
        clock.set(initialDate.plusDays(3).atTime(8, 0).atZone(HistoryPolicy.SEOUL).toInstant());
        var existingAt = initialDate.atTime(0, 30).atZone(HistoryPolicy.SEOUL).toOffsetDateTime();
        var saved = history.saveSnapshot(new HistoryDtos.SnapshotRequest("mock-vehicle-001", existingAt, 27, 33333.0, 123));
        vehicles.saveAndFlush(new Vehicle("unrelated-vehicle", "mock-user-001"));
        var unrelated = VehicleSnapshot.builder().snapshotId(UUID.randomUUID().toString()).vehicleId("unrelated-vehicle")
                .recordedAt(existingAt).socPercent(66).odometerKm(8888.0).build();
        snapshots.saveAndFlush(unrelated);
        var completionAt = initialDate.plusDays(2).atTime(0, 30).atZone(HistoryPolicy.SEOUL).toOffsetDateTime();
        String completionId = "demo-complete:mock-vehicle-001:" + initialDate.plusDays(2);
        events.saveAndFlush(ChargingEvent.builder().eventId(completionId).vehicleId("mock-vehicle-001")
                .eventType(HistoryDtos.EventType.COMPLETE).source(HistoryDtos.Source.MOCK).verified(false)
                .occurredAt(completionAt).lat(37.55).lng(127.14).socPercent(67).odometerKm(20640.0).build());

        snapshots.refreshDemoHistory(OffsetDateTime.now(clock));

        var preserved = snapshots.findByVehicleIdAndRecordedAt("mock-vehicle-001", existingAt).orElseThrow();
        assertEquals(saved.body().snapshotId(), preserved.getSnapshotId());
        assertEquals(27, preserved.getSocPercent());
        assertEquals(33333.0, preserved.getOdometerKm());
        assertEquals(123, preserved.getDrivableRangeKm());
        assertEquals(8888.0, snapshots.findById(unrelated.getSnapshotId()).orElseThrow().getOdometerKm());
        assertEquals(1, history.snapshots("unrelated-vehicle", null, null).records().size());
        assertEquals(67, events.findById(completionId).orElseThrow().getSocPercent());
        assertEquals(0, visits.count());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void completionInsertFailureRollsBackTheNewSnapshotsAsWell() throws Exception {
        long previousSnapshots = snapshots.count();
        long previousEvents = events.count();
        var asOf = policy.today().plusDays(3).atTime(8, 0).atZone(HistoryPolicy.SEOUL).toOffsetDateTime();
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE FUNCTION fail_demo_completion() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN RAISE EXCEPTION 'FIXTURE_COMPLETION_FAILURE'; END; $$
                    """);
            statement.execute("""
                    CREATE TRIGGER fail_demo_completion BEFORE INSERT ON charging_events
                    FOR EACH ROW EXECUTE FUNCTION fail_demo_completion()
                    """);
        }
        try {
            assertThrows(DataAccessException.class, () -> snapshots.refreshDemoHistory(asOf));
            assertEquals(previousSnapshots, snapshots.count());
            assertEquals(previousEvents, events.count());
        } finally {
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                statement.execute("DROP TRIGGER fail_demo_completion ON charging_events");
                statement.execute("DROP FUNCTION fail_demo_completion()");
            }
        }
    }

    private Flyway upgradeFlyway(String target) {
        return Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").schemas("demo_seed_upgrade").defaultSchema("demo_seed_upgrade")
                .cleanDisabled(true).target(target).load();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClock {
        @Bean
        MutableClock clock() { return new MutableClock(); }
    }

    static class MutableClock extends Clock {
        private Instant now = Instant.now();
        void set(Instant now) { this.now = now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }
}
