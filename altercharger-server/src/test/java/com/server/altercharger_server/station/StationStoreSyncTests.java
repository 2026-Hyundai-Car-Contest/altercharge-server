package com.server.altercharger_server.station;

import com.server.altercharger_server.collector.CollectedSnapshot;
import com.server.altercharger_server.collector.CollectedSnapshot.RegionCoverage;
import com.server.altercharger_server.collector.EvChargerJsonParser.Item;
import com.server.altercharger_server.collector.persistence.CollectionStateEntity;
import com.server.altercharger_server.collector.persistence.CollectionStateRepository;
import com.server.altercharger_server.history.VisitService;
import com.server.altercharger_server.station.StationDecisionResponses.NavigationReason;
import com.server.altercharger_server.station.persistence.ChargerEntity;
import com.server.altercharger_server.station.persistence.ChargerRepository;
import com.server.altercharger_server.station.persistence.StationEntity;
import com.server.altercharger_server.station.persistence.StationRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class StationStoreSyncTests {
    private final StationRepository stations = mock(StationRepository.class);
    private final ChargerRepository chargers = mock(ChargerRepository.class);
    private final CollectionStateRepository states = mock(CollectionStateRepository.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final StationStore store = new StationStore(stations, chargers, states, entityManager);
    private final Map<String, CollectionStateEntity> stateRows = new HashMap<>();
    private final Map<String, StationEntity> stationRows = new HashMap<>();
    private final OffsetDateTime baseline = OffsetDateTime.parse("2026-11-12T14:00:00+09:00");
    private ChargerEntity charger;

    @BeforeEach
    void setup() {
        stateRows.put("metadata", new CollectionStateEntity("metadata"));
        stateRows.put("status", new CollectionStateEntity("status"));
        charger = addStation("A", "11", baseline);
        when(states.findById(anyString())).thenAnswer(call -> Optional.ofNullable(stateRows.get(call.getArgument(0))));
        when(states.findAllById(any())).thenAnswer(call -> {
            Iterable<String> ids = call.getArgument(0);
            var found = new java.util.ArrayList<CollectionStateEntity>();
            ids.forEach(id -> { if (stateRows.containsKey(id)) found.add(stateRows.get(id)); });
            return found;
        });
        when(states.existsByTaskAndSucceededAtIsNotNull("metadata"))
                .thenAnswer(call -> stateRows.get("metadata").getSucceededAt() != null);
        when(states.findRegionForStation(anyString())).thenAnswer(call -> {
            var station = stationRows.get(call.getArgument(0));
            return station == null ? Optional.empty()
                    : Optional.ofNullable(stateRows.get(CollectionStateEntity.regionTask(station.getZcode())));
        });
        when(stations.findAllById(any())).thenAnswer(call -> {
            Iterable<String> ids = call.getArgument(0);
            var found = new java.util.ArrayList<StationEntity>();
            ids.forEach(id -> { if (stationRows.containsKey(id)) found.add(stationRows.get(id)); });
            return found;
        });
        when(stations.findWithChargers(anyString()))
                .thenAnswer(call -> Optional.ofNullable(stationRows.get(call.getArgument(0))));
        when(chargers.findByIds(anyCollection())).thenAnswer(call -> {
            java.util.Collection<?> ids = call.getArgument(0);
            return stationRows.values().stream().flatMap(s -> s.getChargers().stream())
                    .filter(c -> ids.contains(c.getId())).toList();
        });
        when(chargers.refreshStatusObservedAtForRegions(anyCollection(), any())).thenAnswer(call -> {
            java.util.Collection<String> regions = call.getArgument(0);
            OffsetDateTime at = call.getArgument(1);
            var active = stationRows.values().stream().filter(s -> regions.contains(s.getZcode()))
                    .flatMap(s -> s.getChargers().stream()).filter(c -> !c.isDeleted()).toList();
            active.forEach(c -> c.setStatusObservedAt(at));
            return active.size();
        });
        doAnswer(call -> {
            if (call.getArgument(0) instanceof CollectionStateEntity state) stateRows.put(state.getTask(), state);
            return null;
        }).when(entityManager).persist(any());
    }

    @Test
    void zeroChangesRefreshAllActiveRegionalClocksWithoutLoadingTheirEntities() {
        metadata("A", "11", baseline);
        var sameRegion = addStation("B", "11", baseline);
        sameRegion.setRawStatus("3");
        var elsewhere = addStation("C", "26", baseline);
        var deleted = addStation("D", "11", baseline);
        deleted.setDeleted(true);
        clearInvocations(chargers, entityManager);

        for (int minute : List.of(5, 10, 15)) status("11", minute, List.of());

        assertThat(navigation("A", 16)).isEqualTo(NavigationReason.OK);
        assertThat(charger.getStatusObservedAt()).isEqualTo(baseline.plusMinutes(15));
        assertThat(sameRegion.getStatusObservedAt()).isEqualTo(baseline.plusMinutes(15));
        assertThat(sameRegion.getRawStatus()).isEqualTo("3");
        assertThat(elsewhere.getStatusObservedAt()).isEqualTo(baseline);
        assertThat(deleted.getStatusObservedAt()).isEqualTo(baseline);
        assertThat(charger.getStatusUpdatedAt()).isEqualTo(baseline.minusHours(1));
        assertThat(stationRows.get("A").getMetadataObservedAt()).isEqualTo(baseline);
        assertThat(region("11").getLastStatusSyncAt()).isEqualTo(baseline.plusMinutes(15));
        assertThat(stateRows).hasSize(3);
        for (int minute : List.of(5, 10, 15)) {
            verify(chargers).refreshStatusObservedAtForRegions(Set.of("11"), baseline.plusMinutes(minute));
        }
        verify(chargers, never()).findByIds(anyCollection());
        verify(entityManager, never()).persist(any());
        verify(stations, never()).findActiveWithChargers();
    }

    @Test
    void legacyReadyCacheWithoutRegionalBaselineNeedsOneRealFullResponse() {
        store.saveMetadata(Map.of("11", List.of(item("A", "2", baseline.minusHours(1)))), baseline);
        assertThat(store.hasCollectedData()).isTrue();
        assertThat(store.needsStatusBaselineRecovery(Set.of("11"))).isTrue();
        status("11", 5, List.of());
        assertThat(store.needsStatusBaselineRecovery(Set.of("11"))).isTrue();

        metadata("A", "11", baseline.plusMinutes(6));

        assertThat(store.needsStatusBaselineRecovery(Set.of("11"))).isFalse();
        assertThat(region("11").getSucceededAt()).isEqualTo(baseline.plusMinutes(6));
        assertThat(region("11").getSyncBaselineAt()).isEqualTo(baseline.plusMinutes(6));
    }

    @Test
    void completedButUnprovenFullResponseCannotCauseRepeatedEarlyFullDownloads() {
        try (var snapshot = snapshot("11", List.of(item("A", "2", baseline.minusHours(1))),
                baseline, baseline.plusSeconds(1), false)) {
            store.saveMetadata(snapshot, baseline);
        }
        assertThat(region("11").getSucceededAt()).isEqualTo(baseline);
        assertThat(region("11").getSyncBaselineAt()).isNull();
        assertThat(store.findStatusSyncForStation("A")).isEmpty();
        assertThat(store.needsStatusBaselineRecovery(Set.of("11"))).isFalse();
    }

    @Test
    void recoveryExaminesAllConfiguredRegionsAndDoesNotRepeatAfterASubsequentGap() {
        metadata("A", "11", baseline);
        assertThat(store.needsStatusBaselineRecovery(Set.of("11"))).isFalse();
        assertThat(store.needsStatusBaselineRecovery(Set.of("11", "26"))).isTrue();
        addStation("B", "26", baseline);
        metadata("B", "26", baseline);
        assertThat(store.needsStatusBaselineRecovery(Set.of("11", "26"))).isFalse();

        status("11", 11, List.of());
        assertThat(region("11").getSyncBaselineAt()).isNull();
        assertThat(store.needsStatusBaselineRecovery(Set.of("11", "26"))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void fullSnapshotReconfirmsSameAvailableStateWithoutReversingAnOlderOrMissingSourceTime(boolean missingTime) {
        metadata("A", "11", baseline);
        var sourceAt = baseline.plusMinutes(4);
        status("11", 5, List.of(item("A", "2", sourceAt)));
        var fullAt = baseline.plusMinutes(20);
        try (var snapshot = snapshot("11", List.of(item("A", "2", missingTime ? null : baseline.minusHours(1))),
                fullAt, fullAt.plusSeconds(1), true)) {
            store.saveMetadata(snapshot, fullAt);
        }
        for (int minute : List.of(25, 30, 35)) status("11", minute, List.of());

        assertThat(navigation("A", 36)).isEqualTo(NavigationReason.OK);
        assertThat(charger.getRawStatus()).isEqualTo("2");
        assertThat(charger.getStatusUpdatedAt()).isEqualTo(sourceAt);
        assertThat(charger.getStatusObservedAt()).isEqualTo(baseline.plusMinutes(35));
        assertThat(region("11").getSyncBaselineAt()).isEqualTo(fullAt);
        assertThat(region("11").getLastStatusSyncAt()).isEqualTo(baseline.plusMinutes(35));
    }

    @Test
    void olderConflictingFullSnapshotCannotReconfirmTheNewerCachedState() {
        metadata("A", "11", baseline);
        var sourceAt = baseline.plusMinutes(4);
        status("11", 5, List.of(item("A", "2", sourceAt)));
        var fullAt = baseline.plusMinutes(20);
        try (var snapshot = snapshot("11", List.of(item("A", "3", baseline.minusHours(1))),
                fullAt, fullAt.plusSeconds(1), true)) {
            store.saveMetadata(snapshot, fullAt);
        }
        assertThat(charger.getStatusObservedAt()).isEqualTo(baseline.plusMinutes(5));
        for (int minute : List.of(25, 30, 35)) status("11", minute, List.of());

        assertThat(navigation("A", 36)).isEqualTo(NavigationReason.OK);
        assertThat(charger.getRawStatus()).isEqualTo("2");
        assertThat(charger.getStatusUpdatedAt()).isEqualTo(sourceAt);
        assertThat(charger.getStatusObservedAt()).isEqualTo(baseline.plusMinutes(35));
    }

    @Test
    void changesOverwriteTheSameRowAndDuplicateOrOlderEventsCannotAddHistoryOrReverseNewerState() {
        metadata("A", "11", baseline);
        var charging = item("A", "3", baseline.plusMinutes(4));
        status("11", 5, List.of(charging));
        status("11", 10, List.of(charging, charging));
        assertThat(store.findStatusSyncForStation("A")).isEmpty();
        status("11", 15, List.of(item("A", "2", baseline.minusMinutes(1))));

        assertThat(stationRows.get("A").getChargers()).containsExactly(charger);
        assertThat(charger.getRawStatus()).isEqualTo("3");
        assertThat(charger.getStatusUpdatedAt()).isEqualTo(baseline.plusMinutes(4));
        assertThat(charger.getStatusObservedAt()).isEqualTo(baseline.plusMinutes(15));
        assertThat(navigation("A", 16)).isEqualTo(NavigationReason.NO_AVAILABLE_CHARGER);
        verify(entityManager, never()).persist(isA(ChargerEntity.class));
    }

    @Test
    void failedAttemptDisablesOldSyncUntilACompleteOverlappingWindowRecoversIt() {
        metadata("A", "11", baseline);
        status("11", 5, List.of());

        store.beginStatusSync(Set.of("11")); // HTTP/page/transaction failure: no successful snapshot follows.

        assertThat(region("11").getLastStatusSyncAt()).isEqualTo(baseline.plusMinutes(5));
        assertThat(store.findStatusSyncForStation("A")).isEmpty();
        assertThat(charger.getStatusObservedAt()).isEqualTo(baseline.plusMinutes(5));
        assertThat(navigation("A", 21)).isEqualTo(NavigationReason.STATUS_STALE);
        assertThat(store.hasCollectedData()).isTrue();

        status("11", 12, List.of());
        assertThat(navigation("A", 21)).isEqualTo(NavigationReason.OK);
    }

    @Test
    void successfulPollRefreshesNavigationAfterAGapWhileRegionalDiagnosticStaysInvalid(CapturedOutput output) {
        metadata("A", "11", baseline);
        status("11", 11, List.of());
        assertThat(output.getAll()).contains("reason=SYNC_WINDOW_GAP");
        status("11", 16, List.of());
        assertThat(output.getAll()).contains("reason=BASELINE_MISSING");

        assertThat(region("11").getSyncBaselineAt()).isNull();
        assertThat(region("11").getLastStatusSyncAt()).isEqualTo(baseline);
        assertThat(charger.getStatusObservedAt()).isEqualTo(baseline.plusMinutes(16));
        assertThat(navigation("A", 16)).isEqualTo(NavigationReason.OK);
        assertThat(navigation("A", 32)).isEqualTo(NavigationReason.STATUS_STALE);

        metadata("A", "11", baseline.plusMinutes(20));
        status("11", 25, List.of());
        assertThat(navigation("A", 36)).isEqualTo(NavigationReason.OK);
        assertThat(charger.getStatusObservedAt()).isEqualTo(baseline.plusMinutes(25));
    }

    @Test
    void lastPageWindowMustOverlapEvenWhenFirstPageWindowWasOnTime() {
        metadata("A", "11", baseline);
        store.beginStatusSync(Set.of("11"));
        try (var snapshot = snapshot("11", List.of(), baseline.plusMinutes(5), baseline.plusMinutes(11), true)) {
            store.saveStatuses(snapshot, baseline.plusMinutes(5));
        }
        assertThat(region("11").getLastStatusSyncAt()).isEqualTo(baseline);
        assertThat(region("11").isStatusSyncValid()).isFalse();
    }

    @Test
    void unprovenPaginationCanApplyObservedChangesButCannotCertifyRegionalSync() {
        metadata("A", "11", baseline);
        var omitted = addStation("B", "11", baseline);
        clearInvocations(chargers);
        store.beginStatusSync(Set.of("11"));
        try (var snapshot = snapshot("11", List.of(item("A", "3", baseline.plusMinutes(4))),
                baseline.plusMinutes(5), baseline.plusMinutes(5).plusSeconds(1), false)) {
            store.saveStatuses(snapshot, baseline.plusMinutes(5));
        }
        assertThat(charger.getRawStatus()).isEqualTo("3");
        assertThat(charger.getStatusObservedAt()).isEqualTo(baseline.plusMinutes(5));
        assertThat(region("11").getLastStatusSyncAt()).isEqualTo(baseline);
        assertThat(store.findStatusSyncForStation("A")).isEmpty();
        assertThat(omitted.getStatusObservedAt()).isEqualTo(baseline);
        verify(chargers, never()).refreshStatusObservedAtForRegions(anyCollection(), any());
    }

    @Test
    void deltaWithoutInitialBaselineCannotCreateRegionalTrust() {
        status("11", 5, List.of());
        assertThat(region("11").getSyncBaselineAt()).isNull();
        assertThat(region("11").getLastStatusSyncAt()).isNull();
        assertThat(store.findStatusSyncForStation("A")).isEmpty();
        assertThat(charger.getStatusObservedAt()).isEqualTo(baseline.plusMinutes(5));
    }

    @Test
    void missingSourceTimestampPreservesRawStateWhilePollConfirmsItsCacheClock(CapturedOutput output) {
        metadata("A", "11", baseline);
        status("11", 5, List.of(item("A", "3", null)));
        assertThat(charger.getRawStatus()).isEqualTo("2");
        assertThat(charger.getStatusObservedAt()).isEqualTo(baseline.plusMinutes(5));
        assertThat(store.findStatusSyncForStation("A")).isEmpty();
        assertThat(output.getAll()).contains("reason=SOURCE_TIME_MISSING");
    }

    @Test
    void oneRegionsGapCannotInvalidateOrExtendAnotherRegionsCoverage() {
        addStation("B", "26", baseline.plusMinutes(1));
        metadata("A", "11", baseline);
        metadata("B", "26", baseline.plusMinutes(1));
        status("11", 5, List.of());
        status("26", 12, List.of());

        assertThat(navigation("A", 18)).isEqualTo(NavigationReason.OK);
        assertThat(navigation("B", 18)).isEqualTo(NavigationReason.OK);
        assertThat(charger.getStatusObservedAt()).isEqualTo(baseline.plusMinutes(5));
        assertThat(region("11").getLastStatusSyncAt()).isEqualTo(baseline.plusMinutes(5));
        assertThat(region("26").getLastStatusSyncAt()).isEqualTo(baseline.plusMinutes(1));
    }

    @Test
    void anotherRegionsChargerCannotBeChangedOrCertifyCoverage(CapturedOutput output) {
        var other = addStation("B", "26", baseline);
        metadata("A", "11", baseline);
        status("11", 5, List.of(item("B", "3", baseline.plusMinutes(4))));
        assertThat(other.getRawStatus()).isEqualTo("2");
        assertThat(other.getStatusObservedAt()).isEqualTo(baseline);
        assertThat(store.findStatusSyncForStation("A")).isEmpty();
        assertThat(output.getAll()).contains("reason=REGION_MISMATCH");
    }

    @Test
    void futureSourceTimestampCannotCertifyRegionalCoverage(CapturedOutput output) {
        metadata("A", "11", baseline);
        status("11", 5, List.of(item("A", "3", baseline.plusMinutes(6))));
        assertThat(charger.getRawStatus()).isEqualTo("3");
        assertThat(store.findStatusSyncForStation("A")).isEmpty();
        assertThat(region("11").getLastStatusSyncAt()).isEqualTo(baseline);
        assertThat(output.getAll()).contains("reason=SOURCE_TIME_FUTURE");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unknownChargerWithMissingOrFutureSourceTimeCannotCertifyOtherChargers(boolean future, CapturedOutput output) {
        metadata("A", "11", baseline);
        status("11", 5, List.of(item("NOT-IN-METADATA", "2", future ? baseline.plusMinutes(6) : null)));

        assertThat(store.findStatusSyncForStation("A")).isEmpty();
        assertThat(charger.getRawStatus()).isEqualTo("2");
        assertThat(charger.getStatusObservedAt()).isEqualTo(baseline.plusMinutes(5));
        verify(entityManager, never()).persist(isA(ChargerEntity.class));
        assertThat(output.getAll()).contains("reason=" + (future ? "SOURCE_TIME_FUTURE" : "SOURCE_TIME_MISSING"));
    }

    @Test
    void deletionWatermarkPreventsOlderMetadataFromRevivingAvailableStatus() {
        metadata("A", "11", baseline);
        var deleted = new Item("A", "", "", null, null, "01", "", null, "2", baseline.plusMinutes(4), true);
        status("11", 5, List.of(deleted));
        assertThat(charger.isDeleted()).isTrue();
        assertThat(charger.getStatusUpdatedAt()).isEqualTo(baseline.plusMinutes(4));

        var at = baseline.plusMinutes(6);
        try (var snapshot = snapshot("11", List.of(item("A", "2", baseline.minusHours(1))), at, at.plusSeconds(1), true)) {
            store.saveMetadata(snapshot, at);
        }
        assertThat(charger.getRawStatus()).isEqualTo("0");
        assertThat(charger.getStatusUpdatedAt()).isEqualTo(baseline.plusMinutes(4));
        assertThat(navigation("A", 7)).isEqualTo(NavigationReason.NO_AVAILABLE_CHARGER);
    }

    @Test
    void aLaterSuccessfulEmptyPollNeverRefreshesOrRevivesDeletedRows() {
        metadata("A", "11", baseline);
        var sourceAt = baseline.plusMinutes(4);
        status("11", 5, List.of(new Item("A", "", "", null, null, "01", "", null, "2", sourceAt, true)));
        status("11", 10, List.of());

        assertThat(charger.isDeleted()).isTrue();
        assertThat(charger.getRawStatus()).isEqualTo("0");
        assertThat(charger.getStatusUpdatedAt()).isEqualTo(sourceAt);
        assertThat(charger.getStatusObservedAt()).isEqualTo(baseline.plusMinutes(5));
    }

    @ParameterizedTest
    @ValueSource(ints = {251, 100_001})
    void crossBatchDuplicatesOrExcessiveIdentityProofDoNotCertifyCoverageButStillMergeRows(int count, CapturedOutput output) {
        metadata("A", "11", baseline);
        store.beginStatusSync(Set.of("11"));
        var at = baseline.plusMinutes(5);
        try (var snapshot = new CollectedSnapshot()) {
            try (var writer = snapshot.openRegion("11")) {
                for (int start = 0; start < count; start += CollectedSnapshot.BATCH_SIZE) {
                    var batch = new java.util.ArrayList<Item>();
                    for (int i = start; i < Math.min(start + CollectedSnapshot.BATCH_SIZE, count); i++) {
                        String id = i == 0 || count == 251 && i == 250 ? "01" : "delta-" + i;
                        batch.add(new Item("A", "", "", null, null, id, "", null, "3", at.minusMinutes(1), false));
                    }
                    writer.append(batch);
                }
                writer.completeCoverage(new RegionCoverage(at, at.plusSeconds(1), (count + 9998) / 9999, count, true));
            }
            store.saveStatuses(snapshot, at);
        }
        assertThat(charger.getRawStatus()).isEqualTo("3");
        assertThat(stationRows.get("A").getChargers()).containsExactly(charger);
        assertThat(region("11").getLastStatusSyncAt()).isEqualTo(baseline);
        assertThat(store.findStatusSyncForStation("A")).isEmpty();
        verify(entityManager, never()).persist(isA(ChargerEntity.class));
        assertThat(output.getAll()).contains("reason=" + (count == 251
                ? "DUPLICATE_DELTA_IDENTITY" : "DELTA_IDENTITY_LIMIT_EXCEEDED"));
    }

    private ChargerEntity addStation(String id, String region, OffsetDateTime at) {
        var station = new StationEntity(id);
        station.setName("example"); station.setAddress("example");
        station.setLat(37.55); station.setLng(127.14); station.setZcode(region);
        station.setMetadataObservedAt(at);
        var row = new ChargerEntity(station, "01");
        row.setRawConnectorType("04"); row.setRawStatus("2"); row.setOutputKw(100.0);
        row.setStatusUpdatedAt(at.minusHours(1)); row.setStatusObservedAt(at);
        station.getChargers().add(row);
        stationRows.put(id, station);
        return row;
    }

    private void metadata(String id, String region, OffsetDateTime at) {
        var sourceAt = stationRows.get(id).getChargers().getFirst().getStatusUpdatedAt();
        try (var snapshot = snapshot(region, List.of(item(id, "2", sourceAt)), at, at.plusSeconds(1), true)) {
            store.saveMetadata(snapshot, at);
        }
    }

    private void status(String region, int minute, List<Item> items) {
        var at = baseline.plusMinutes(minute);
        store.beginStatusSync(Set.of(region));
        try (var snapshot = snapshot(region, items, at, at.plusSeconds(1), true)) {
            store.saveStatuses(snapshot, at);
        }
    }

    private CollectedSnapshot snapshot(String region, List<Item> items, OffsetDateTime first,
                                       OffsetDateTime last, boolean proven) {
        var snapshot = new CollectedSnapshot();
        try (var writer = snapshot.openRegion(region)) {
            writer.append(items);
            writer.completeCoverage(new RegionCoverage(first, last, 1, proven ? items.size() : -1, proven));
        }
        return snapshot;
    }

    private Item item(String id, String raw, OffsetDateTime sourceAt) {
        return new Item(id, "example", "example", 37.55, 127.14, "01", "04", 100.0, raw, sourceAt, false);
    }

    private CollectionStateEntity region(String zcode) {
        return stateRows.get(CollectionStateEntity.regionTask(zcode));
    }

    private NavigationReason navigation(String id, int minute) {
        var visits = mock(VisitService.class);
        var service = new StationDecisionService(new StationQueryService(store, visits), visits,
                Clock.fixed(baseline.plusMinutes(minute).toInstant(), ZoneOffset.UTC), 15);
        return service.navigationCheck(id).reasonCode();
    }
}
