package com.server.altercharger_server.station;

import com.server.altercharger_server.collector.CollectionException;
import com.server.altercharger_server.collector.EvChargerXmlParser.Item;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class StationStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public StationStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public List<Station> findAll() {
        return jdbc.query(SELECT + " ORDER BY s.station_id, c.charger_id", this::readStations);
    }

    public Optional<Station> findById(String stationId) {
        return jdbc.query(SELECT + " AND s.station_id = ? ORDER BY c.charger_id", this::readStations, stationId)
                .stream().findFirst();
    }

    public boolean hasCollectedData() {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM collection_state WHERE task = 'metadata' AND succeeded_at IS NOT NULL)
                """, Boolean.class));
    }

    /** A full, successful regional snapshot is applied atomically; partial downloads never reach here. */
    public void saveMetadata(Map<String, List<Item>> regions, OffsetDateTime observedAt) {
        if (regions.isEmpty() || regions.values().stream().anyMatch(List::isEmpty)) {
            throw new CollectionException("Empty metadata snapshot; keeping the previous cache");
        }
        if (!hasCollectedData() && regions.values().stream().flatMap(List::stream).noneMatch(Item::validMetadata)) {
            throw new CollectionException("Initial metadata snapshot contains no usable chargers");
        }
        Set<ChargerIdentity> identities = new HashSet<>();
        for (var items : regions.values()) {
            for (Item item : items) {
                if (item.hasIdentity() && !identities.add(new ChargerIdentity(item.statId(), item.chgerId()))) {
                    throw new CollectionException("Duplicate charger in metadata snapshot; keeping the previous cache");
                }
            }
        }
        transactions.executeWithoutResult(transaction -> {
            for (var region : regions.entrySet()) {
                jdbc.update("""
                        UPDATE chargers SET deleted = true
                        WHERE station_id IN (SELECT station_id FROM stations WHERE zcode = ?)
                        """, region.getKey());
                for (Item item : region.getValue()) {
                    if (!item.validMetadata()) continue;
                    jdbc.update("""
                            INSERT INTO stations(station_id, name, address, lat, lng, zcode, metadata_observed_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            ON CONFLICT (station_id) DO UPDATE SET name = EXCLUDED.name, address = EXCLUDED.address,
                              lat = EXCLUDED.lat, lng = EXCLUDED.lng, zcode = EXCLUDED.zcode,
                              metadata_observed_at = EXCLUDED.metadata_observed_at
                            """, item.statId(), item.statNm(), item.addr(), item.lat(), item.lng(), region.getKey(), observedAt);
                    jdbc.update("""
                            INSERT INTO chargers(station_id, charger_id, raw_connector_type, output_kw, raw_status,
                              status_updated_at, status_observed_at, deleted)
                            VALUES (?, ?, ?, ?, ?, ?, ?, false)
                            ON CONFLICT (station_id, charger_id) DO UPDATE SET
                              raw_connector_type = EXCLUDED.raw_connector_type, output_kw = EXCLUDED.output_kw,
                              deleted = false,
                              raw_status = CASE WHEN chargers.status_updated_at IS NULL OR
                                EXCLUDED.status_updated_at >= chargers.status_updated_at
                                THEN EXCLUDED.raw_status ELSE chargers.raw_status END,
                              status_updated_at = CASE WHEN chargers.status_updated_at IS NULL OR
                                EXCLUDED.status_updated_at >= chargers.status_updated_at
                                THEN EXCLUDED.status_updated_at ELSE chargers.status_updated_at END,
                              status_observed_at = CASE WHEN chargers.status_updated_at IS NULL OR
                                EXCLUDED.status_updated_at >= chargers.status_updated_at
                                THEN EXCLUDED.status_observed_at ELSE chargers.status_observed_at END
                            """, item.statId(), item.chgerId(), item.chgerType(),
                            item.output() != null && item.output() >= 0 ? item.output() : null,
                            item.stat().isBlank() || item.stat().length() > 100 ? "0" : item.stat(), item.statUpdDt(), observedAt);
                }
            }
            markSucceeded("metadata", observedAt);
        });
    }

    /** Status is a change feed: update known chargers only, without deleting any other charger. */
    public void saveStatuses(List<Item> items, OffsetDateTime observedAt) {
        transactions.executeWithoutResult(transaction -> {
            for (Item item : items) {
                if (!item.hasIdentity()) continue;
                if (item.deleted()) {
                    jdbc.update("""
                            UPDATE chargers SET deleted = true
                            WHERE station_id = ? AND charger_id = ?
                              AND (status_updated_at IS NULL OR ? >= status_updated_at)
                            """, item.statId(), item.chgerId(), item.statUpdDt());
                } else if (!item.stat().isBlank() && item.stat().length() <= 100) {
                    jdbc.update("""
                            UPDATE chargers SET raw_status = ?, status_updated_at = ?, status_observed_at = ?
                            WHERE station_id = ? AND charger_id = ? AND NOT deleted
                              AND (status_updated_at IS NULL OR ? >= status_updated_at)
                            """, item.stat(), item.statUpdDt(), observedAt, item.statId(), item.chgerId(), item.statUpdDt());
                }
            }
            markSucceeded("status", observedAt);
        });
    }

    private void markSucceeded(String task, OffsetDateTime at) {
        jdbc.update("""
                UPDATE collection_state SET succeeded_at = ?, retry_after = NULL, consecutive_failures = 0
                WHERE task = ?
                """, at, task);
    }

    private List<Station> readStations(ResultSet rows) throws SQLException {
        Map<String, Station> stations = new LinkedHashMap<>();
        while (rows.next()) {
            String id = rows.getString("station_id");
            Station station = stations.get(id);
            if (station == null) {
                station = new Station(id, rows.getString("name"), rows.getString("address"),
                        rows.getDouble("lat"), rows.getDouble("lng"), new ArrayList<>());
                stations.put(id, station);
            }
            station.chargers().add(new Charger(rows.getString("charger_id"), rows.getString("raw_connector_type"),
                    rows.getObject("output_kw", Double.class), rows.getString("raw_status"),
                    rows.getObject("status_updated_at", OffsetDateTime.class),
                    rows.getObject("status_observed_at", OffsetDateTime.class), false));
        }
        return stations.values().stream().map(station -> new Station(station.stationId(), station.name(), station.address(),
                station.lat(), station.lng(), List.copyOf(station.chargers()))).toList();
    }

    private static final String SELECT = """
            SELECT s.station_id, s.name, s.address, s.lat, s.lng, c.charger_id, c.raw_connector_type,
                   c.output_kw, c.raw_status, c.status_updated_at, c.status_observed_at
            FROM stations s JOIN chargers c ON c.station_id = s.station_id WHERE NOT c.deleted
            """;

    public record Station(String stationId, String name, String address, double lat, double lng, List<Charger> chargers) { }
    public record Charger(String chargerId, String rawConnectorType, Double outputKw, String rawStatus,
                          OffsetDateTime statusUpdatedAt, OffsetDateTime statusObservedAt, boolean deleted) { }
    private record ChargerIdentity(String stationId, String chargerId) { }
}
