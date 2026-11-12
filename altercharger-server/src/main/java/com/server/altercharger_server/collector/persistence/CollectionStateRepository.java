package com.server.altercharger_server.collector.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CollectionStateRepository extends JpaRepository<CollectionStateEntity, String> {
    boolean existsByTaskAndSucceededAtIsNotNull(String task);

    @Query("""
            select sync from CollectionStateEntity sync, StationEntity station
            where station.stationId = :stationId and sync.task = concat('region:', station.zcode)
            """)
    Optional<CollectionStateEntity> findRegionForStation(@Param("stationId") String stationId);
}
