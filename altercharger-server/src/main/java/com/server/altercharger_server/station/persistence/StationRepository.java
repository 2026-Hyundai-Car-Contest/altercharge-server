package com.server.altercharger_server.station.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StationRepository extends JpaRepository<StationEntity, String> {
    // Fetch the complete collection, then filter deleted chargers in the DTO mapping.
    // A filtered collection fetch would leave a misleading partial managed collection.
    @Query("""
            select distinct s from StationEntity s left join fetch s.chargers
            where exists (select c.id from ChargerEntity c where c.station = s and c.deleted = false)
            order by s.stationId
            """)
    List<StationEntity> findActiveWithChargers();

    @Query("select distinct s from StationEntity s left join fetch s.chargers where s.stationId = :stationId")
    Optional<StationEntity> findWithChargers(@Param("stationId") String stationId);

}
