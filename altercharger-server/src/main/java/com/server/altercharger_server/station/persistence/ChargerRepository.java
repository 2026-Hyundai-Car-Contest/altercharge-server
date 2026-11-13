package com.server.altercharger_server.station.persistence;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChargerRepository extends JpaRepository<ChargerEntity, ChargerId> {
    @Query("select c from ChargerEntity c join fetch c.station where c.id in :ids")
    List<ChargerEntity> findByIds(@Param("ids") Collection<ChargerId> ids);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ChargerEntity c set c.deleted = true
            where c.station.stationId in (select s.stationId from StationEntity s where s.zcode in :zcodes)
            """)
    int markDeletedForRegions(@Param("zcodes") Collection<String> zcodes);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ChargerEntity c set c.statusObservedAt = :observedAt
            where c.deleted = false and c.station.stationId in
                (select s.stationId from StationEntity s where s.zcode in :zcodes)
            """)
    int refreshStatusObservedAtForRegions(@Param("zcodes") Collection<String> zcodes,
                                         @Param("observedAt") OffsetDateTime observedAt);
}
