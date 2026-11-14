package com.server.altercharger_server.history;

import com.server.altercharger_server.history.repository.VehicleSnapshotRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.TransactionSystemException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class DemoHistoryRefresherTests {
    @Test
    void startupAndKoreanMidnightRefreshOnlyOncePerDateEvenWithinTheSameUtcDate() {
        var snapshots = mock(VehicleSnapshotRepository.class);
        var clock = mock(Clock.class);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(clock.instant()).thenReturn(Instant.parse("2026-11-14T14:59:00Z"));
        var refresher = new DemoHistoryRefresher(snapshots, clock);
        refresher.refreshIfNeeded();
        refresher.refreshIfNeeded();
        verify(snapshots, times(1)).refreshDemoHistory(any());

        when(clock.instant()).thenReturn(Instant.parse("2026-11-14T15:00:00Z"));
        refresher.refreshIfNeeded();
        refresher.refreshIfNeeded();
        verify(snapshots, times(2)).refreshDemoHistory(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void databaseOrCommitFailureRetriesOnTheNextTickWithoutLoggingArbitraryMessages(boolean commitFailure,
                                                                                     CapturedOutput output) {
        var snapshots = mock(VehicleSnapshotRepository.class);
        RuntimeException failure = commitFailure ? new TransactionSystemException("PRIVATE_FIXTURE_MESSAGE")
                : new DataAccessResourceFailureException("PRIVATE_FIXTURE_MESSAGE");
        when(snapshots.refreshDemoHistory(any())).thenThrow(failure).thenReturn(0);
        var refresher = new DemoHistoryRefresher(snapshots,
                Clock.fixed(Instant.parse("2026-11-14T15:00:00Z"), ZoneOffset.UTC));

        refresher.refreshIfNeeded();
        refresher.refreshIfNeeded();
        refresher.refreshIfNeeded();

        verify(snapshots, times(2)).refreshDemoHistory(any());
        assertTrue(output.getOut().contains("DATABASE_REFRESH_FAILED"));
        assertFalse(output.getAll().contains("PRIVATE_FIXTURE_MESSAGE"));
    }
}
