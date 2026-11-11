package com.server.altercharger_server.station.persistence;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChargerRepository extends JpaRepository<ChargerEntity, ChargerId> {
    @Query("select c from ChargerEntity c where c.id.stationId in :stationIds")
    List<ChargerEntity> findForStationIds(@Param("stationIds") Collection<String> stationIds);
}
