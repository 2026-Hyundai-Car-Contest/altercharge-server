package com.server.altercharger_server.history;

import com.server.altercharger_server.history.repository.VehicleSnapshotRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.history.demo-enabled", havingValue = "true", matchIfMissing = true)
public class DemoHistoryRefresher {
    private static final Logger log = LoggerFactory.getLogger(DemoHistoryRefresher.class);
    private final VehicleSnapshotRepository snapshots;
    private final Clock clock;
    private LocalDate refreshedDate;

    @Scheduled(initialDelay = 1000, fixedDelay = 60000)
    public synchronized void refreshIfNeeded() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        LocalDate today = now.atZoneSameInstant(HistoryPolicy.SEOUL).toLocalDate();
        if (today.equals(refreshedDate)) return;
        try {
            int inserted = snapshots.refreshDemoHistory(now);
            // The repository transaction has committed before remembering this process's date.
            refreshedDate = today;
            log.info("Demo history refresh completed: date={}, inserted={}", today, inserted);
        } catch (DataAccessException | TransactionException ex) {
            // Leave the date pending and retry next tick without logging SQL or arbitrary messages.
            log.warn("Demo history refresh deferred: date={} reason=DATABASE_REFRESH_FAILED", today);
        }
    }
}
