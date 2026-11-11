package com.server.altercharger_server.history.repository;

import com.server.altercharger_server.history.HistoryDtos.EventType;
import com.server.altercharger_server.history.entity.ChargingEvent;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChargingEventRepository extends JpaRepository<ChargingEvent, String> {
    Optional<ChargingEvent> findFirstByVehicleIdAndEventTypeOrderByOccurredAtDescEventIdDesc(String vehicleId, EventType eventType);
}
