package com.server.altercharger_server.collector.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CollectionStateRepository extends JpaRepository<CollectionStateEntity, String> {
    boolean existsByTaskAndSucceededAtIsNotNull(String task);
}
