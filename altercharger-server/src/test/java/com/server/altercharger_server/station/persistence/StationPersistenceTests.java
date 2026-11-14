package com.server.altercharger_server.station.persistence;

import com.server.altercharger_server.collector.CollectedSnapshot;
import com.server.altercharger_server.collector.CollectedSnapshot.RegionCoverage;
import com.server.altercharger_server.collector.EvChargerJsonParser.Item;
import com.server.altercharger_server.collector.persistence.CollectionStateRepository;
import com.server.altercharger_server.common.Geo;
import com.server.altercharger_server.common.QuerydslConfig;
import com.server.altercharger_server.history.StationMatcher;
import com.server.altercharger_server.history.VisitService;
import com.server.altercharger_server.station.StationDecisionResponses.NavigationReason;
import com.server.altercharger_server.station.StationDecisionService;
import com.server.altercharger_server.station.StationQueryService;
import com.server.altercharger_server.station.StationStore;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.hibernate.Hibernate;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(showSql = false, properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.flyway.baseline-on-migrate=false",
        "app.stations.status-max-age-minutes=15",
        "logging.level.org.hibernate.stat=OFF"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import({QuerydslConfig.class, StationStore.class, StationQueryService.class, StationDecisionService.class,
        VisitService.class, StationMatcher.class, StationPersistenceTests.TestClock.class})
@Testcontainers
class StationPersistenceTests {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-14T05:00:00Z");
    private static final OffsetDateTime OLD = NOW.minusHours(2);

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("altercharge_querydsl_test")
            .withUsername("altercharge_test")
            .withPassword(UUID.randomUUID().toString());

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired StationRepository stations;
    @Autowired ChargerRepository chargers;
    @Autowired CollectionStateRepository states;
    @Autowired EntityManager entityManager;
    @Autowired StationStore store;
    @Autowired StationQueryService queries;
    @Autowired StationDecisionService decisions;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void boundsIncludeEdgesAndFetchCompleteCollectionsOnceWithoutDuplicateStations() {
        var a = station("A", "11", 37.55, 127.14);
        charger(a, "01", false);
        charger(a, "02", false);
        charger(a, "03", true);
        charger(station("B", "11", 37.56, 127.15), "01", false);
        charger(station("deleted", "11", 37.55, 127.14), "01", true);
        station("empty", "11", 37.55, 127.14);
        charger(station("far", "11", 35, 129), "01", false);
        flushAndClear();
        var statistics = statistics();
        statistics.clear();

        var results = stations.findActiveWithinBounds(37.55, 37.56, 127.14, 127.15, false);

        assertEquals(List.of("A", "B"), ids(results));
        assertTrue(Hibernate.isInitialized(results.getFirst().getChargers()));
        entityManager.clear();
        assertEquals(3, results.getFirst().getChargers().size());
        assertEquals(1, results.getFirst().getChargers().stream().filter(ChargerEntity::isDeleted).count());
        assertEquals(1, statistics.getPrepareStatementCount());
        assertEquals(List.of("A", "B", "far"), ids(stations.findActiveWithChargers()));
    }

    @ParameterizedTest
    @ValueSource(doubles = {179.99, -179.99})
    void dateLineBoundsIncludeBothSidesAndExcludeOtherLongitudes(double longitude) {
        charger(station("east", "11", 0, 179.995), "01", false);
        charger(station("west", "11", 0, -179.995), "01", false);
        charger(station("elsewhere", "11", 0, 0), "01", false);
        charger(station("north", "11", 1, 179.995), "01", false);
        flushAndClear();
        var bounds = Geo.boundingBox(0, longitude, 3000);

        assertTrue(bounds.wrapsLongitude());
        assertEquals(List.of("east", "west"), ids(within(bounds)));
    }

    @ParameterizedTest
    @ValueSource(doubles = {89.99, -89.99})
    void polarBoundsKeepAllLongitudes(double latitude) {
        charger(station("A", "11", latitude, -180), "01", false);
        charger(station("B", "11", latitude, 0), "01", false);
        charger(station("C", "11", latitude, 180), "01", false);
        charger(station("outside", "11", 0, 0), "01", false);
        flushAndClear();
        var bounds = Geo.boundingBox(latitude, 170, 3000);

        assertFalse(bounds.wrapsLongitude());
        assertEquals(List.of("A", "B", "C"), ids(within(bounds)));
    }

    @Test
    void listKeepsExactDistanceKeywordTieOrderAndPaginationAfterDatabaseBounds() {
        charger(station("B", "11", 37.55, 127.14), "01", false);
        charger(station("A", "11", 37.55, 127.14), "01", false);
        charger(station("corner", "11", 37.574, 127.17), "01", false);
        ready();
        flushAndClear();

        assertEquals(List.of("A", "B", "corner"), store.findNearby(37.55, 127.14, 3000).stream()
                .map(StationStore.Station::stationId).toList());
        var first = queries.list(37.55, 127.14, 3, " SeOuL ", 0, 1);
        var second = queries.list(37.55, 127.14, 3, "서울", 1, 1);
        assertEquals("A", first.items().getFirst().stationId());
        assertTrue(first.hasNext());
        assertEquals("B", second.items().getFirst().stationId());
        assertFalse(second.hasNext());
        assertTrue(queries.list(37.55, 127.14, 3, null, Integer.MAX_VALUE, 200).items().isEmpty());
    }

    @Test
    void detailFetchesDeletedOrEmptyCollectionsWithoutHidingRowsInTheManagedCollection() {
        var active = station("A", "11", 37.55, 127.14);
        charger(active, "01", false);
        charger(active, "02", true);
        charger(station("deleted", "11", 37.55, 127.14), "01", true);
        station("empty", "11", 37.55, 127.14);
        flushAndClear();

        var detail = stations.findWithChargers("A").orElseThrow();
        entityManager.clear();
        assertEquals(2, detail.getChargers().size());
        assertTrue(stations.findWithChargers("empty").orElseThrow().getChargers().isEmpty());
        assertTrue(stations.findWithChargers("missing").isEmpty());
        assertTrue(store.findById("empty").isEmpty());
        assertTrue(store.findById("deleted").isEmpty());
        assertEquals(1, store.findById("A").orElseThrow().chargers().size());
    }

    @Test
    void compositeIdLookupKeepsPairsAndFetchesStationsWithoutExtraQueries() {
        for (String id : List.of("A", "B")) {
            var station = station(id, "11", 37.55, 127.14);
            charger(station, "01", false);
            charger(station, "02", true);
        }
        flushAndClear();
        var statistics = statistics();
        statistics.clear();

        var rows = chargers.findByIds(List.of(new ChargerId("A", "01"), new ChargerId("B", "02"),
                new ChargerId("missing", "01")));

        assertEquals(Set.of(new ChargerId("A", "01"), new ChargerId("B", "02")),
                rows.stream().map(ChargerEntity::getId).collect(java.util.stream.Collectors.toSet()));
        rows.forEach(row -> assertTrue(Hibernate.isInitialized(row.getStation())));
        entityManager.clear();
        rows.forEach(row -> assertEquals("11", row.getStation().getZcode()));
        assertEquals(1, statistics.getPrepareStatementCount());
    }

    @Test
    void bulkConfirmationFlushesDeltasClearsManagedRowsAndPreservesOtherFieldsAndRegions() {
        var a = station("A", "11", 37.55, 127.14);
        var changed = charger(a, "01", false);
        charger(a, "02", false);
        charger(a, "03", true);
        charger(station("B", "26", 35, 129), "01", false);
        entityManager.flush();
        changed.setRawStatus("3");
        changed.setStatusUpdatedAt(NOW.minusMinutes(2));
        a.setName("pending metadata");
        var statistics = statistics();
        statistics.clear();

        assertEquals(2, chargers.refreshStatusObservedAtForRegions(Set.of("11"), NOW));
        assertFalse(entityManager.contains(changed));
        assertFalse(entityManager.contains(a));
        assertEquals(0, statistics.getEntityLoadCount());
        var refreshed = chargers.findById(new ChargerId("A", "01")).orElseThrow();
        assertEquals("3", refreshed.getRawStatus());
        assertEquals(NOW.minusMinutes(2), refreshed.getStatusUpdatedAt());
        assertEquals(NOW, refreshed.getStatusObservedAt());
        assertEquals(100.0, refreshed.getOutputKw());
        assertEquals("04", refreshed.getRawConnectorType());
        assertEquals("pending metadata", refreshed.getStation().getName());
        assertEquals(OLD, refreshed.getStation().getMetadataObservedAt());
        assertEquals(NOW, chargers.findById(new ChargerId("A", "02")).orElseThrow().getStatusObservedAt());
        assertEquals(OLD, chargers.findById(new ChargerId("A", "03")).orElseThrow().getStatusObservedAt());
        assertEquals(OLD, chargers.findById(new ChargerId("B", "01")).orElseThrow().getStatusObservedAt());
    }

    @Test
    void bulkDeletionOnlyChangesTheSelectedRegionsDeletionFlag() {
        var a = station("A", "11", 37.55, 127.14);
        var active = charger(a, "01", false);
        charger(a, "02", true);
        charger(station("B", "26", 35, 129), "01", false);
        entityManager.flush();
        a.setAddress("pending address");
        var statistics = statistics();
        statistics.clear();

        assertEquals(2, chargers.markDeletedForRegions(Set.of("11")));
        assertFalse(entityManager.contains(active));
        assertEquals(0, statistics.getEntityLoadCount());
        var deleted = chargers.findById(new ChargerId("A", "01")).orElseThrow();
        assertTrue(deleted.isDeleted());
        assertEquals("2", deleted.getRawStatus());
        assertEquals(OLD.minusDays(1), deleted.getStatusUpdatedAt());
        assertEquals(OLD, deleted.getStatusObservedAt());
        assertEquals("pending address", deleted.getStation().getAddress());
        assertEquals(OLD, deleted.getStation().getMetadataObservedAt());
        assertFalse(chargers.findById(new ChargerId("B", "01")).orElseThrow().isDeleted());
    }

    @Test
    void emptyCollectionsNeverMatchAnyCharger() {
        charger(station("A", "11", 37.55, 127.14), "01", false);
        flushAndClear();

        assertTrue(chargers.findByIds(List.of()).isEmpty());
        assertEquals(0, chargers.refreshStatusObservedAtForRegions(Set.of(), NOW));
        assertEquals(0, chargers.markDeletedForRegions(Set.of()));
        var row = chargers.findById(new ChargerId("A", "01")).orElseThrow();
        assertFalse(row.isDeleted());
        assertEquals(OLD, row.getStatusObservedAt());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void emptyStatusPollOnlyConfirmsTheCollectedRegionWhenPageCoverageIsProven(boolean proven) {
        var a = station("A", "11", 37.55, 127.14);
        charger(a, "01", false);
        charger(a, "02", true);
        charger(station("B", "26", 35, 129), "01", false);
        ready();
        flushAndClear();

        try (var snapshot = emptyStatusSnapshot(proven)) {
            store.saveStatuses(snapshot, NOW.minusMinutes(1));
        }
        flushAndClear();

        assertEquals(proven ? NOW.minusMinutes(1) : OLD, queries.detail("A").statusObservedAt());
        assertEquals(proven ? NavigationReason.OK : NavigationReason.STATUS_STALE,
                decisions.navigationCheck("A").reasonCode());
        assertEquals(proven, decisions.navigationCheck("A").navigationAllowed());
        assertEquals(NavigationReason.STATUS_STALE, decisions.navigationCheck("B").reasonCode());
        assertEquals(OLD, chargers.findById(new ChargerId("A", "02")).orElseThrow().getStatusObservedAt());
        assertFalse(states.findById("region:11").orElseThrow().isStatusSyncValid());
    }

    @Test
    void metadataReplacementKeepsDeletionWatermarksAndOlderConflictingStatuses() {
        var a = station("A", "11", 37.55, 127.14);
        var deleted = charger(a, "01", true);
        deleted.setRawStatus("0");
        deleted.setStatusUpdatedAt(NOW.minusMinutes(1));
        var busy = charger(a, "02", false);
        busy.setRawStatus("3");
        busy.setStatusUpdatedAt(NOW.minusMinutes(1));
        charger(a, "03", false);
        charger(station("B", "26", 35, 129), "01", false);
        flushAndClear();
        var items = List.of(metadataItem("01"), metadataItem("02"));

        store.saveMetadata(Map.of("11", items), NOW);
        flushAndClear();

        assertEquals("0", chargers.findById(new ChargerId("A", "01")).orElseThrow().getRawStatus());
        assertEquals("3", chargers.findById(new ChargerId("A", "02")).orElseThrow().getRawStatus());
        assertEquals(OLD, chargers.findById(new ChargerId("A", "01")).orElseThrow().getStatusObservedAt());
        assertTrue(chargers.findById(new ChargerId("A", "03")).orElseThrow().isDeleted());
        assertFalse(chargers.findById(new ChargerId("B", "01")).orElseThrow().isDeleted());
        assertEquals(NOW, states.findById("metadata").orElseThrow().getSucceededAt());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void bulkConfirmationAndSuccessMarkersRollBackWithTheOuterTransaction() {
        var transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            charger(station("rollback", "11", 37.55, 127.14), "01", false);
        });
        var previousSuccess = transaction.execute(status -> states.findById("status").orElseThrow().getSucceededAt());
        try {
            assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
                var row = chargers.findById(new ChargerId("rollback", "01")).orElseThrow();
                row.setRawStatus("3");
                row.setStatusUpdatedAt(NOW.minusMinutes(2));
                try (var snapshot = emptyStatusSnapshot(true)) {
                    store.saveStatuses(snapshot, NOW.minusMinutes(1));
                }
                assertEquals(NOW.minusMinutes(1), states.findById("status").orElseThrow().getSucceededAt());
                throw new IllegalStateException("fixture transaction failure");
            }));
            transaction.executeWithoutResult(status -> {
                var restored = chargers.findById(new ChargerId("rollback", "01")).orElseThrow();
                assertEquals("2", restored.getRawStatus());
                assertEquals(OLD.minusDays(1), restored.getStatusUpdatedAt());
                assertEquals(OLD, restored.getStatusObservedAt());
                assertEquals(previousSuccess, states.findById("status").orElseThrow().getSucceededAt());
                assertTrue(states.findById("region:11").isEmpty());
            });
        } finally {
            transaction.executeWithoutResult(status -> {
                chargers.deleteById(new ChargerId("rollback", "01"));
                chargers.flush();
                stations.deleteById("rollback");
            });
        }
    }

    private StationEntity station(String id, String region, double latitude, double longitude) {
        var station = new StationEntity(id);
        station.setName("Seoul " + id);
        station.setAddress("서울 강동");
        station.setLat(latitude);
        station.setLng(longitude);
        station.setZcode(region);
        station.setMetadataObservedAt(OLD);
        entityManager.persist(station);
        return station;
    }

    private ChargerEntity charger(StationEntity station, String id, boolean deleted) {
        var charger = new ChargerEntity(station, id);
        charger.setRawConnectorType("04");
        charger.setOutputKw(100.0);
        charger.setRawStatus("2");
        charger.setStatusUpdatedAt(OLD.minusDays(1));
        charger.setStatusObservedAt(OLD);
        charger.setDeleted(deleted);
        station.getChargers().add(charger);
        entityManager.persist(charger);
        return charger;
    }

    private void ready() {
        states.findById("metadata").orElseThrow().succeed(OLD);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private Statistics statistics() {
        return entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
    }

    private List<StationEntity> within(Geo.BoundingBox bounds) {
        return stations.findActiveWithinBounds(bounds.south(), bounds.north(), bounds.west(), bounds.east(),
                bounds.wrapsLongitude());
    }

    private List<String> ids(List<StationEntity> rows) {
        return rows.stream().map(StationEntity::getStationId).toList();
    }

    private CollectedSnapshot emptyStatusSnapshot(boolean proven) {
        var snapshot = new CollectedSnapshot();
        try (var writer = snapshot.openRegion("11")) {
            writer.completeCoverage(new RegionCoverage(NOW.minusMinutes(1), NOW.minusSeconds(50), 1,
                    proven ? 0 : -1, proven));
        }
        return snapshot;
    }

    private Item metadataItem(String chargerId) {
        return new Item("A", "Seoul A", "서울 강동", 37.55, 127.14, chargerId, "04", 100.0,
                "2", OLD.minusDays(1), false);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClock {
        @Bean
        @Primary
        Clock clock() { return Clock.fixed(NOW.toInstant(), ZoneOffset.UTC); }
    }
}
