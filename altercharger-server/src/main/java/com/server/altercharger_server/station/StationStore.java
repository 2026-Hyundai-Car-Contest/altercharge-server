package com.server.altercharger_server.station;

import com.server.altercharger_server.collector.CollectionException;
import com.server.altercharger_server.collector.EvChargerXmlParser.Item;
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

    /** A full regional download replaces its cache atomically, including soft deletions. */
    @Transactional
    public void saveMetadata(Map<String, List<Item>> regions, OffsetDateTime observedAt) {
        validateSnapshot(regions);
        Set<String> incomingStationIds = regions.values().stream().flatMap(List::stream)
                .filter(Item::hasIdentity).map(Item::statId).collect(Collectors.toSet());
        Map<String, StationEntity> knownStations = stations.findSnapshotTargets(regions.keySet(), incomingStationIds)
                .stream().collect(Collectors.toMap(StationEntity::getStationId, Function.identity()));
        Map<ChargerId, ChargerEntity> knownChargers = new HashMap<>();
        for (var station : knownStations.values()) {
            for (var charger : station.getChargers()) {
                knownChargers.put(charger.getId(), charger);
                if (regions.containsKey(station.getZcode())) charger.setDeleted(true);
            }
        }
        for (var region : regions.entrySet()) {
            for (Item item : region.getValue()) {
                if (!item.validMetadata()) continue;
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
                station.setZcode(region.getKey());
                station.setMetadataObservedAt(observedAt);
                if (newStation) entityManager.persist(station);

                ChargerId id = new ChargerId(item.statId(), item.chgerId());
                ChargerEntity charger = knownChargers.get(id);
                boolean newCharger = charger == null;
                if (newCharger) {
                    charger = new ChargerEntity(station, item.chgerId());
                    station.getChargers().add(charger);
                    knownChargers.put(id, charger);
                }
                charger.setRawConnectorType(item.chgerType());
                charger.setOutputKw(item.output() != null && item.output() >= 0 ? item.output() : null);
                charger.setDeleted(false);
                if (canApplyStatus(charger, item.statUpdDt())) {
                    applyStatus(charger, item.stat().isBlank() || item.stat().length() > 100 ? "0" : item.stat(),
                            item.statUpdDt(), observedAt);
                }
                if (newCharger) entityManager.persist(charger);
            }
        }
        markSucceeded("metadata", observedAt);
    }

    /** Status is a change feed: mutate only known chargers and never replace the collection. */
    @Transactional
    public void saveStatuses(List<Item> items, OffsetDateTime observedAt) {
        Set<String> stationIds = items.stream().filter(Item::hasIdentity).map(Item::statId).collect(Collectors.toSet());
        Map<ChargerId, ChargerEntity> known = stationIds.isEmpty() ? Map.of()
                : chargers.findForStationIds(stationIds).stream()
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
        markSucceeded("status", observedAt);
    }

    private void validateSnapshot(Map<String, List<Item>> regions) {
        if (regions.isEmpty() || regions.values().stream().anyMatch(List::isEmpty)) {
            throw new CollectionException("Empty metadata snapshot; keeping the previous cache");
        }
        if (!hasCollectedData() && regions.values().stream().flatMap(List::stream).noneMatch(Item::validMetadata)) {
            throw new CollectionException("Initial metadata snapshot contains no usable chargers");
        }
        Set<ChargerId> identities = new HashSet<>();
        for (var items : regions.values()) {
            for (Item item : items) {
                if (item.hasIdentity() && !identities.add(new ChargerId(item.statId(), item.chgerId()))) {
                    throw new CollectionException("Duplicate charger in metadata snapshot; keeping the previous cache");
                }
            }
        }
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
