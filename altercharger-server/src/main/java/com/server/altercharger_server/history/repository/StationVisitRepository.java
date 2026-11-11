package com.server.altercharger_server.history.repository;

import com.server.altercharger_server.history.entity.StationVisit;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StationVisitRepository extends JpaRepository<StationVisit, String> {
    @Modifying
    @Query(value = """
            INSERT INTO station_visits(visit_id, user_id, station_id, visit_date, source, verified)
            VALUES (:visitId, :userId, :stationId, :visitDate, 'REAL', true)
            ON CONFLICT (user_id, station_id, visit_date) DO NOTHING
            """, nativeQuery = true)
    int addVerifiedVisit(@Param("visitId") String visitId, @Param("userId") String userId,
                         @Param("stationId") String stationId, @Param("visitDate") LocalDate visitDate);

    @Query("select v.stationId as stationId, count(v) as visitCount from StationVisit v where v.userId = :userId and v.source = 'REAL' and v.verified = true group by v.stationId")
    List<VisitCount> countRealVisits(@Param("userId") String userId);

    interface VisitCount {
        String getStationId();
        long getVisitCount();
    }
}
