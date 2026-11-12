package com.server.altercharger_server.station;

import com.server.altercharger_server.common.Geo;
import com.server.altercharger_server.collector.CollectedSnapshot;
import com.server.altercharger_server.collector.CollectionException;
import com.server.altercharger_server.collector.EvChargerJsonParser.Item;
import com.server.altercharger_server.collector.persistence.CollectionStateEntity;
import com.server.altercharger_server.collector.persistence.CollectionStateRepository;
import com.server.altercharger_server.station.persistence.ChargerEntity;
import com.server.altercharger_server.station.persistence.ChargerId;
import com.server.altercharger_server.station.persistence.ChargerRepository;
import com.server.altercharger_server.station.persistence.StationEntity;
import com.server.altercharger_server.station.persistence.StationRepository;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StationStore {
    private static final Logger log = LoggerFactory.getLogger(StationStore.class);
    private final StationRepository stations;
    private final ChargerRepository chargers;
    private final CollectionStateRepository collectionStates;
    private final EntityManager entityManager;

    public List<Station> findAll() {
        return stations.findActiveWithChargers().stream().map(this::toStation).toList();
    }

    public List<Station> findNearby(double lat, double lng, double radiusMeters) {
        var bounds = Geo.boundingBox(lat, lng, radiusMeters);
        return stations.findActiveWithinBounds(bounds.south(), bounds.north(), bounds.west(), bounds.east(),
                bounds.wrapsLongitude()).stream().map(this::toStation).toList();
    }

    public Optional<Station> findById(String stationId) {
        return stations.findWithChargers(stationId).map(this::toStation)
                .filter(station -> !station.chargers().isEmpty());
    }

    public boolean hasCollectedData() {
        return collectionStates.existsByTaskAndSucceededAtIsNotNull("metadata");
    }

    public Optional<StatusSync> findStatusSyncForStation(String stationId) {
        return collectionStates.findRegionForStation(stationId)
                .filter(CollectionStateEntity::isStatusSyncValid)
                .filter(state -> state.getSyncBaselineAt() != null && state.getLastStatusSyncAt() != null)
                .map(state -> new StatusSync(state.getSyncBaselineAt(), state.getLastStatusSyncAt()));
    }

    /** Persist before network work so a failed/incomplete attempt cannot leave old coverage usable. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void beginStatusSync(Set<String> regions) {
        regions.forEach(region -> regionState(region).beginStatusSync());
    }

    /** Completed disk pages are validated before any replacement, then applied in one transaction. */
    @Transactional
    public void saveMetadata(CollectedSnapshot snapshot, OffsetDateTime observedAt) {
        applyMetadata(snapshot.regionCodes(), snapshot::forEachBatch, observedAt);
        for (String region : snapshot.regionCodes()) {
            var state = regionState(region);
            if (provenCoverage(snapshot, region)) state.establishStatusBaseline(observedAt);
            else state.invalidateStatusBaseline();
            logSync("metadata", region, snapshot.regionCoverage(region),
                    state.isStatusSyncValid() ? "BASELINE_ESTABLISHED" : "UNPROVEN_PAGES");
        }
    }

    /** Retains the in-process fixture/import contract; production uses the disk-backed overload. */
    @Transactional
    public void saveMetadata(Map<String, List<Item>> regions, OffsetDateTime observedAt) {
        applyMetadata(regions.keySet(), action -> regions.forEach((region, items) ->
                forBatches(items, batch -> action.accept(region, batch))), observedAt);
        // Fixture/import calls provide no page/window evidence and cannot establish regional trust.
        regions.keySet().forEach(region -> regionState(region).invalidateStatusBaseline());
    }

    private void applyMetadata(Set<String> regions, Batches source, OffsetDateTime observedAt) {
        validateSnapshot(regions, source);
        chargers.markDeletedForRegions(regions);
        source.forEach((region, items) -> updateMetadataBatch(region, items, observedAt));
        markSucceeded("metadata", observedAt);
    }

    private void updateMetadataBatch(String region, List<Item> items, OffsetDateTime observedAt) {
        List<Item> valid = items.stream().filter(Item::validMetadata).toList();
        if (valid.isEmpty()) return;
        Set<String> stationIds = valid.stream().map(Item::statId).collect(Collectors.toSet());
        Map<String, StationEntity> knownStations = stations.findAllById(stationIds).stream()
                .collect(Collectors.toMap(StationEntity::getStationId, Function.identity()));
        List<ChargerId> ids = valid.stream().map(item -> new ChargerId(item.statId(), item.chgerId())).toList();
        Map<ChargerId, ChargerEntity> knownChargers = chargers.findByIds(ids).stream()
                .collect(Collectors.toMap(ChargerEntity::getId, Function.identity()));
        for (Item item : valid) {
            StationEntity station = knownStations.get(item.statId());
            boolean newStation = station == null;
            if (newStation) {
                station = new StationEntity(item.statId());
                knownStations.put(item.statId(), station);
            }
            station.setName(item.statNm());
            station.setAddress(item.addr());
            station.setLat(item.lat());
            station.setLng(item.lng());
            station.setZcode(region);
            station.setMetadataObservedAt(observedAt);
            if (newStation) entityManager.persist(station);

            ChargerId id = new ChargerId(item.statId(), item.chgerId());
            ChargerEntity charger = knownChargers.get(id);
            boolean newCharger = charger == null;
            if (newCharger) charger = new ChargerEntity(station, item.chgerId());
            charger.setRawConnectorType(item.chgerType());
            charger.setOutputKw(item.output() != null && item.output() >= 0 ? item.output() : null);
            charger.setDeleted(false);
            String rawStatus = item.stat().isBlank() || item.stat().length() > 100 ? "0" : item.stat();
            if (canApplyStatus(charger, item.statUpdDt())) {
                applyStatus(charger, rawStatus, item.statUpdDt(), observedAt);
            } else if (rawStatus.equals(charger.getRawStatus())) {
                // A full response actually reconfirmed this state. Keep the newer source watermark,
                // but connect the real observation to the new baseline; omitted deltas never do this.
                charger.setStatusObservedAt(observedAt);
            }
            if (newCharger) entityManager.persist(charger);
        }
        // Flush is not a commit: later failure rolls back every batch and the initial soft deletion.
        entityManager.flush();
        entityManager.clear();
    }

    /** Only returned rows change. Regional coverage commits atomically with all delta batches. */
    @Transactional
    public void saveStatuses(CollectedSnapshot snapshot, OffsetDateTime observedAt) {
        Map<String, Boolean> applied = new HashMap<>();
        var identities = new DeltaIdentities();
        snapshot.regionCodes().forEach(region -> applied.put(region, true));
        snapshot.forEachBatch((region, items) -> {
            var coverage = snapshot.regionCoverage(region);
            boolean unique = identities.accept(region, items);
            boolean batchApplied = updateStatuses(items, observedAt, region,
                    coverage == null ? null : coverage.lastResponseFinishedAt()) && unique;
            applied.compute(region, (key, previous) -> Boolean.TRUE.equals(previous) && batchApplied);
        });
        for (String region : snapshot.regionCodes()) {
            var state = regionState(region);
            if (provenCoverage(snapshot, region) && Boolean.TRUE.equals(applied.get(region))) {
                var coverage = snapshot.regionCoverage(region);
                // Assume a complete upstream feed and sufficiently aligned clocks. The common window
                // uses the last response's lower bound and the FIRST request's upper bound, never completion.
                state.completeStatusSync(coverage.lastResponseFinishedAt().minusMinutes(10),
                        coverage.firstRequestStartedAt());
            } else state.invalidateStatusBaseline();
            logSync("status", region, snapshot.regionCoverage(region),
                    state.isStatusSyncValid() ? "CONTINUOUS_SYNC" : "UNPROVEN_OR_GAPPED_SYNC");
        }
        markSucceeded("status", observedAt);
    }

    @Transactional
    public void saveStatuses(List<Item> items, OffsetDateTime observedAt) {
        forBatches(items, batch -> updateStatuses(batch, observedAt, null, null));
        markSucceeded("status", observedAt);
    }

    private boolean updateStatuses(List<Item> items, OffsetDateTime observedAt,
                                   String region, OffsetDateTime responseFinishedAt) {
        boolean complete = true;
        Set<ChargerId> ids = items.stream().filter(Item::hasIdentity)
                .map(item -> new ChargerId(item.statId(), item.chgerId())).collect(Collectors.toSet());
        if (ids.isEmpty()) return items.isEmpty();
        Map<ChargerId, ChargerEntity> known = chargers.findByIds(ids).stream()
                .collect(Collectors.toMap(ChargerEntity::getId, Function.identity()));
        for (Item item : items) {
            if (!item.hasIdentity()) {
                complete = false;
                continue;
            }
            ChargerEntity charger = known.get(new ChargerId(item.statId(), item.chgerId()));
            // New chargers need full metadata; no availability is invented for an unknown identity.
            if (charger == null) continue;
            if (region != null && !region.equals(charger.getStation().getZcode())) {
                complete = false;
                continue;
            }
            if (item.statUpdDt() == null || responseFinishedAt != null
                    && (item.statUpdDt().isAfter(responseFinishedAt)
                    || charger.getStatusUpdatedAt() != null
                    && charger.getStatusUpdatedAt().isAfter(responseFinishedAt))) complete = false;
            if (!canApplyStatus(charger, item.statUpdDt())) {
                continue;
            }
            if (item.deleted()) {
                // Keep the deletion watermark; an older metadata status cannot revive AVAILABLE.
                applyStatus(charger, "0", item.statUpdDt(), observedAt);
                charger.setDeleted(true);
            } else if (!charger.isDeleted() && !item.stat().isBlank() && item.stat().length() <= 100) {
                applyStatus(charger, item.stat(), item.statUpdDt(), observedAt);
            } else if (!charger.isDeleted()) complete = false;
        }
        entityManager.flush();
        entityManager.clear();
        return complete;
    }

    /** Bounded delta-ID validation, never a list/cache of all stored charger entities. */
    private static final class DeltaIdentities {
        private static final int LIMIT = 100_000;
        private final Set<ChargerId> seen = new HashSet<>();
        private String region;
        private boolean exhausted;

        boolean accept(String currentRegion, List<Item> items) {
            // Snapshot batches for one region are contiguous, so only one region's IDs are retained.
            if (!currentRegion.equals(region)) {
                seen.clear();
                exhausted = false;
                region = currentRegion;
            }
            if (exhausted) return false;
            for (Item item : items) {
                if (seen.size() >= LIMIT || !seen.add(new ChargerId(item.statId(), item.chgerId()))) {
                    exhausted = true;
                    seen.clear();
                    return false;
                }
            }
            return true;
        }
    }

    private CollectionStateEntity regionState(String region) {
        String task = CollectionStateEntity.regionTask(region);
        return collectionStates.findById(task).orElseGet(() -> {
            var state = new CollectionStateEntity(task);
            entityManager.persist(state);
            return state;
        });
    }

    private static boolean provenCoverage(CollectedSnapshot snapshot, String region) {
        var coverage = snapshot.regionCoverage(region);
        return coverage != null && coverage.totalKnownAndStable() && coverage.pageCount() > 0
                && coverage.expectedTotal() >= 0 && coverage.firstRequestStartedAt() != null
                && coverage.lastResponseFinishedAt() != null
                && !coverage.lastResponseFinishedAt().isBefore(coverage.firstRequestStartedAt());
    }

    private static void logSync(String task, String region, CollectedSnapshot.RegionCoverage coverage, String reason) {
        log.info("Collector sync task={} region={} pages={} expectedTotal={} stage=SYNC_COVERAGE reason={}",
                task, region, coverage == null ? 0 : coverage.pageCount(),
                coverage == null ? -1 : coverage.expectedTotal(), reason);
    }

    private void validateSnapshot(Set<String> regions, Batches source) {
        if (regions.isEmpty()) throw new CollectionException("Empty metadata snapshot; keeping the previous cache");
        Map<String, Integer> counts = new HashMap<>();
        Set<ChargerId> identities = new HashSet<>();
        boolean[] hasValid = {false};
        source.forEach((region, items) -> {
            counts.merge(region, items.size(), Integer::sum);
            for (Item item : items) {
                hasValid[0] |= item.validMetadata();
                if (item.hasIdentity() && !identities.add(new ChargerId(item.statId(), item.chgerId()))) {
                    throw new CollectionException("Duplicate charger in metadata snapshot; keeping the previous cache");
                }
            }
        });
        if (regions.stream().anyMatch(region -> counts.getOrDefault(region, 0) == 0)) {
            throw new CollectionException("Empty metadata snapshot; keeping the previous cache");
        }
        if (!hasValid[0] && !hasCollectedData()) {
            throw new CollectionException("Initial metadata snapshot contains no usable chargers");
        }
    }

    private static void forBatches(List<Item> items, Consumer<List<Item>> action) {
        for (int start = 0; start < items.size(); start += CollectedSnapshot.BATCH_SIZE) {
            action.accept(items.subList(start, Math.min(start + CollectedSnapshot.BATCH_SIZE, items.size())));
        }
    }

    @FunctionalInterface
    private interface Batches {
        void forEach(BiConsumer<String, List<Item>> action);
    }

    private static boolean canApplyStatus(ChargerEntity charger, OffsetDateTime incomingTimestamp) {
        return charger.getStatusUpdatedAt() == null
                || incomingTimestamp != null && !incomingTimestamp.isBefore(charger.getStatusUpdatedAt());
    }

    private static void applyStatus(ChargerEntity charger, String rawStatus,
                                    OffsetDateTime updatedAt, OffsetDateTime observedAt) {
        charger.setRawStatus(rawStatus);
        charger.setStatusUpdatedAt(updatedAt);
        charger.setStatusObservedAt(observedAt);
    }

    private void markSucceeded(String task, OffsetDateTime at) {
        collectionStates.findById(task).orElseThrow().succeed(at);
    }

    private Station toStation(StationEntity station) {
        List<Charger> active = station.getChargers().stream().filter(charger -> !charger.isDeleted())
                .sorted(Comparator.comparing(charger -> charger.getId().getChargerId()))
                .map(charger -> new Charger(charger.getId().getChargerId(), charger.getRawConnectorType(),
                        charger.getOutputKw(), charger.getRawStatus(), charger.getStatusUpdatedAt(),
                        charger.getStatusObservedAt(), false)).toList();
        return new Station(station.getStationId(), station.getName(), station.getAddress(),
                station.getLat(), station.getLng(), active);
    }

    public record Station(String stationId, String name, String address, double lat, double lng, List<Charger> chargers) { }
    public record Charger(String chargerId, String rawConnectorType, Double outputKw, String rawStatus,
                          OffsetDateTime statusUpdatedAt, OffsetDateTime statusObservedAt, boolean deleted) { }

    public record StatusSync(OffsetDateTime baselineAt, OffsetDateTime lastStatusSyncAt) { }
}
