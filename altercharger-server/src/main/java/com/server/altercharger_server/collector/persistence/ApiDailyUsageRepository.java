package com.server.altercharger_server.collector.persistence;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApiDailyUsageRepository extends JpaRepository<ApiDailyUsageEntity, LocalDate> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from ApiDailyUsageEntity u where u.usageDate = :day")
    Optional<ApiDailyUsageEntity> findForReservation(@Param("day") LocalDate day);
}
