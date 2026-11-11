package com.server.altercharger_server.collector.persistence;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CollectorLedger {
    private final CollectionStateRepository states;
    private final ApiDailyUsageRepository usage;
    private final EntityManager entityManager;

    @Transactional(readOnly = true)
    public State state(String task) {
        var state = states.findById(task).orElseThrow();
        return new State(state.getSucceededAt(), state.getRetryAfter(), state.getConsecutiveFailures());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void defer(String task, OffsetDateTime retryAfter, int failures) {
        var state = states.findById(task).orElseThrow();
        state.setRetryAfter(retryAfter);
        state.setConsecutiveFailures(failures);
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Usage usage(LocalDate day) {
        return usage.findById(day).map(value -> new Usage(value.getCalls(), value.getMetadataCalls()))
                .orElseGet(() -> new Usage(0, 0));
    }

    /** Called only while the process-wide PostgreSQL collector advisory lock is held. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean reserve(LocalDate day, boolean metadata, int dailyBudget, int metadataBudget) {
        var current = usage.findForReservation(day).orElseGet(() -> {
            var fresh = new ApiDailyUsageEntity(day);
            entityManager.persist(fresh);
            return fresh;
        });
        if (current.getCalls() >= dailyBudget || metadata && current.getMetadataCalls() >= metadataBudget) return false;
        current.reserve(metadata);
        // The transaction commits before the caller starts HTTP, including failures and crashes afterwards.
        return true;
    }

    public record State(OffsetDateTime succeededAt, OffsetDateTime retryAfter, int failures) { }
    public record Usage(int calls, int metadataCalls) { }
}
