package com.server.altercharger_server.station.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ChargerRepository extends JpaRepository<ChargerEntity, ChargerId>, ChargerQueries {
}
