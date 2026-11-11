package com.server.altercharger_server.station;

import com.server.altercharger_server.collector.CollectedSnapshot;
import com.server.altercharger_server.collector.CollectionException;
import com.server.altercharger_server.collector.EvChargerJsonParser.Item;
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
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StationStore {
    private final StationRepository stations;
    private final ChargerRepository chargers;
    private final CollectionStateRepository collectionStates;
    private final EntityManager entityManager;

    public List<Station> findAll() {
        return stations.findActiveWithChargers().stream().map(this::toStation).toList();
    }

    public Optional<Station> findById(String stationId) {
        return stations.findWithChargers(stationId).map(this::toStation)
                .filter(station -> !station.chargers().isEmpty());
    }

    public boolean hasCollectedData() {
        return collectionStates.existsByTaskAndSucceededAtIsNotNull("metadata");
    }

    /** Completed disk pages are validated before any replacement, then applied in one transaction. */
    @Transactional
    public void saveMetadata(CollectedSnapshot snapshot, OffsetDateTime observedAt) {
        applyMetadata(snapshot.regionCodes(), snapshot::forEachBatch, observedAt);
    }

    /** Retains the in-process fixture/import contract; production uses the disk-backed overload. */
    @Transactional
    public void saveMetadata(Map<String, List<Item>> regions, OffsetDateTime observedAt) {
        applyMetadata(regions.keySet(), action -> regions.forEach((region, items) ->
                forBatches(items, batch -> action.accept(region, batch))), observedAt);
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
            if (canApplyStatus(charger, item.statUpdDt())) {
                applyStatus(charger, item.stat().isBlank() || item.stat().length() > 100 ? "0" : item.stat(),
                        item.statUpdDt(), observedAt);
            }
            if (newCharger) entityManager.persist(charger);
        }
        // Flush is not a commit: later failure rolls back every batch and the initial soft deletion.
        entityManager.flush();
        entityManager.clear();
    }

    /** Status is a change feed; omitted chargers and their observation times remain untouched. */
    @Transactional
    public void saveStatuses(CollectedSnapshot snapshot, OffsetDateTime observedAt) {
        snapshot.forEachBatch((region, items) -> updateStatuses(items, observedAt));
        markSucceeded("status", observedAt);
    }

    @Transactional
    public void saveStatuses(List<Item> items, OffsetDateTime observedAt) {
        forBatches(items, batch -> updateStatuses(batch, observedAt));
        markSucceeded("status", observedAt);
    }

    private void updateStatuses(List<Item> items, OffsetDateTime observedAt) {
        Set<ChargerId> ids = items.stream().filter(Item::hasIdentity)
                .map(item -> new ChargerId(item.statId(), item.chgerId())).collect(Collectors.toSet());
        if (ids.isEmpty()) return;
        Map<ChargerId, ChargerEntity> known = chargers.findByIds(ids).stream()
                .collect(Collectors.toMap(ChargerEntity::getId, Function.identity()));
        for (Item item : items) {
            if (!item.hasIdentity()) continue;
            ChargerEntity charger = known.get(new ChargerId(item.statId(), item.chgerId()));
            if (charger == null || !canApplyStatus(charger, item.statUpdDt())) continue;
            if (item.deleted()) {
                charger.setDeleted(true);
            } else if (!charger.isDeleted() && !item.stat().isBlank() && item.stat().length() <= 100) {
                applyStatus(charger, item.stat(), item.statUpdDt(), observedAt);
            }
        }
        entityManager.flush();
        entityManager.clear();
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
}
