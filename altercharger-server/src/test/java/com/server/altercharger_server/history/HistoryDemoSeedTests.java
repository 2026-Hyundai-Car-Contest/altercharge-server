package com.server.altercharger_server.history;

import com.server.altercharger_server.common.QuerydslConfig;
import com.server.altercharger_server.history.HistoryDtos.SnapshotRecord;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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
    @MockitoBean StationMatcher matcher;

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

        var latest = upgradeFlyway("latest");
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

    private Flyway upgradeFlyway(String target) {
        return Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").schemas("demo_seed_upgrade").defaultSchema("demo_seed_upgrade")
                .cleanDisabled(true).target(target).load();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClock {
        @Bean
        Clock clock() { return Clock.systemUTC(); }
    }
}
