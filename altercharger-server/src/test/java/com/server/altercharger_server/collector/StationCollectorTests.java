package com.server.altercharger_server.collector;

import com.server.altercharger_server.collector.EvChargerJsonParser.Item;
import com.server.altercharger_server.collector.EvChargerJsonParser.Page;
import com.server.altercharger_server.collector.CollectedSnapshot.RegionCoverage;
import com.server.altercharger_server.collector.persistence.CollectorLedger;
import com.server.altercharger_server.collector.persistence.CollectorLedger.State;
import com.server.altercharger_server.collector.persistence.CollectorLedger.Usage;
import com.server.altercharger_server.collector.persistence.CollectionStateEntity;
import com.server.altercharger_server.history.VisitService;
import com.server.altercharger_server.station.StationDecisionService;
import com.server.altercharger_server.station.StationQueryService;
import com.server.altercharger_server.station.StationStore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class StationCollectorTests {
    private final DataSource dataSource = mock(DataSource.class);
    private final CollectorLedger ledger = mock(CollectorLedger.class);
    private final StationStore store = mock(StationStore.class);
    private final EvChargerClient client = mock(EvChargerClient.class);
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger metadataCalls = new AtomicInteger();
    private final AtomicReference<List<Item>> saved = new AtomicReference<>();
    private final AtomicReference<OffsetDateTime> savedObservedAt = new AtomicReference<>();
    private final AtomicReference<RegionCoverage> savedCoverage = new AtomicReference<>();
    private final AtomicReference<Set<String>> savedRegions = new AtomicReference<>();
    private String activeTask = "metadata";

    @BeforeEach
    void setup() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getBoolean(1)).thenReturn(true);
        when(store.hasCollectedData()).thenReturn(true);
        when(ledger.state(anyString())).thenAnswer(invocation -> invocation.getArgument(0).equals(activeTask)
                ? new State(null, null, 0) : new State(OffsetDateTime.now(), null, 0));
        when(ledger.usage(any())).thenAnswer(invocation -> new Usage(calls.get(), metadataCalls.get()));
        when(ledger.reserve(any(), anyBoolean(), anyInt(), anyInt())).thenAnswer(invocation -> {
            boolean metadata = invocation.getArgument(1);
            int dailyBudget = invocation.getArgument(2), metadataBudget = invocation.getArgument(3);
            if (calls.get() >= dailyBudget || metadata && metadataCalls.get() >= metadataBudget) return false;
            calls.incrementAndGet();
            if (metadata) metadataCalls.incrementAndGet();
            return true;
        });
        doAnswer(invocation -> {
            capture(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(store).saveMetadata(any(CollectedSnapshot.class), any(OffsetDateTime.class));
        doAnswer(invocation -> {
            capture(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(store).saveStatuses(any(CollectedSnapshot.class), any(OffsetDateTime.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"metadata", "status"})
    void finalPageActualNumOfRowsDoesNotCreateAnotherRequest(String task) {
        activeTask = task;
        AtomicReference<OffsetDateTime> firstFetchAt = new AtomicReference<>();
        when(client.fetch(eq(task), anyString(), eq("11"), anyInt(), eq(10))).thenAnswer(invocation -> {
            firstFetchAt.compareAndSet(null, OffsetDateTime.now());
            return switch ((int) invocation.getArgument(3)) {
                case 1 -> page(0, 10, 15, 1, 10);
                case 2 -> page(10, 5, 15, 2, 5);
                default -> throw new AssertionError("A completed collection must not request page 3");
            };
        });

        collector(100).collectIfDue();

        assertEquals(15, saved.get().size());
        assertEquals(2, calls.get());
        assertTrue(savedCoverage.get().totalKnownAndStable());
        assertEquals(2, savedCoverage.get().pageCount());
        assertEquals(15, savedCoverage.get().expectedTotal());
        assertFalse(savedObservedAt.get().toInstant().isAfter(firstFetchAt.get().toInstant()),
                "The end of a slow download must not make older pages appear newly observed");
        verify(client, times(2)).fetch(eq(task), anyString(), eq("11"), anyInt(), eq(10));
        if (task.equals("status")) {
            verify(store, never()).saveMetadata(any(CollectedSnapshot.class), any(OffsetDateTime.class));
            assertTrue(saved.get().stream().allMatch(item -> item.stat().equals("3")));
        } else {
            verify(store, never()).saveStatuses(any(CollectedSnapshot.class), any(OffsetDateTime.class));
        }
        verify(ledger, never()).defer(anyString(), any(), anyInt());
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 10})
    void serverCapUsesActualFirstPageForBudgetAndCumulativeTotalForCompletion(int firstReportedRows) {
        when(client.fetch(eq("metadata"), anyString(), eq("11"), anyInt(), eq(10))).thenAnswer(invocation ->
                switch ((int) invocation.getArgument(3)) {
                    case 1 -> page(0, 3, 7, 1, firstReportedRows);
                    case 2 -> page(3, 3, 7, 2, 3);
                    case 3 -> page(6, 1, 7, 3, 1);
                    default -> throw new AssertionError("The complete capped collection needs only three pages");
                });

        collector(100).collectIfDue();

        assertEquals(7, saved.get().size());
        assertEquals(3, calls.get());
        verify(ledger, never()).defer(anyString(), any(), anyInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"metadata", "status"})
    void unknownTotalProbesUntilEmptyEvenAfterAShortPageWithoutCertifyingCoverage(String task) {
        activeTask = task;
        when(client.fetch(eq(task), anyString(), eq("11"), anyInt(), eq(10))).thenAnswer(invocation ->
                switch ((int) invocation.getArgument(3)) {
                    case 1 -> page(0, 3, -1, 1, 3);
                    case 2 -> page(3, 1, -1, 2, 1);
                    case 3 -> page(4, 0, -1, 3, 0);
                    default -> throw new AssertionError("An empty terminal page must end unknown-total probing");
                });

        collector(100).collectIfDue();

        assertEquals(4, saved.get().size());
        assertEquals(3, calls.get());
        assertFalse(savedCoverage.get().totalKnownAndStable());
        assertEquals(-1, savedCoverage.get().expectedTotal());
    }

    @Test
    void emptyKnownStatusFeedStillCarriesVerifiedRegionalCoverage() {
        activeTask = "status";
        when(client.fetch(eq("status"), anyString(), eq("11"), eq(1), eq(10)))
                .thenReturn(page(0, 0, 0, 1, 0));

        collector(100).collectIfDue();

        assertTrue(saved.get().isEmpty());
        assertTrue(savedCoverage.get().totalKnownAndStable());
        assertEquals(0, savedCoverage.get().expectedTotal());
        assertEquals(1, savedCoverage.get().pageCount());
        var order = inOrder(store, client);
        order.verify(store).beginStatusSync(Set.of("11"));
        order.verify(client).fetch(eq("status"), anyString(), eq("11"), eq(1), eq(10));
        order.verify(store).saveStatuses(any(CollectedSnapshot.class), any(OffsetDateTime.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"metadata", "status"})
    void missingNumOfRowsOnANonemptyPagePreservesRowsWithoutCertifyingCoverage(String task) {
        activeTask = task;
        when(client.fetch(eq(task), anyString(), eq("11"), eq(1), eq(10)))
                .thenReturn(page(0, 1, 1, 1, 0));

        collector(100).collectIfDue();

        assertEquals(1, saved.get().size());
        assertEquals(1, savedCoverage.get().expectedTotal());
        assertFalse(savedCoverage.get().totalKnownAndStable());
        verify(ledger, never()).defer(anyString(), any(), anyInt());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void statusFeedOver9999ItemsRequiresEveryPageBeforeSaving(boolean failSecondPage) {
        activeTask = "status";
        when(client.fetch(eq("status"), anyString(), eq("11"), anyInt(), eq(9999))).thenAnswer(invocation -> {
            int pageNumber = invocation.getArgument(3);
            if (pageNumber == 1) return page(0, 9999, 10_000, 1, 9999);
            if (pageNumber != 2) throw new AssertionError("The complete feed needs exactly two pages");
            if (failSecondPage) throw new CollectionException(CollectionException.Reason.UPSTREAM_REQUEST_FAILED);
            return page(9999, 1, 10_000, 2, 1);
        });

        collector(100, 9999).collectIfDue();

        assertEquals(2, calls.get());
        verify(store).beginStatusSync(Set.of("11"));
        if (failSecondPage) {
            assertNull(saved.get());
            verify(store, never()).saveStatuses(any(CollectedSnapshot.class), any(OffsetDateTime.class));
            verify(ledger).defer(eq("status"), any(OffsetDateTime.class), eq(1));
        } else {
            assertEquals(10_000, saved.get().size());
            assertTrue(savedCoverage.get().totalKnownAndStable());
            assertEquals(2, savedCoverage.get().pageCount());
            verify(ledger, never()).defer(anyString(), any(), anyInt());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"TOTAL_CHANGED", "TOTAL_MISSING", "PAGE_NUMBER_MISSING"})
    void uncertainStatusPaginationCanMergeRowsWithoutCertifyingRegionalCoverage(String uncertainty) {
        activeTask = "status";
        when(client.fetch(eq("status"), anyString(), eq("11"), anyInt(), eq(10))).thenAnswer(invocation -> {
            int pageNumber = invocation.getArgument(3);
            if (pageNumber == 1) return page(0, 10, 15, 1, 10);
            return switch (uncertainty) {
                case "TOTAL_CHANGED" -> page(10, 5, 14, 2, 5);
                case "TOTAL_MISSING" -> page(10, 5, -1, 2, 5);
                case "PAGE_NUMBER_MISSING" -> page(10, 5, 15, 0, 5);
                default -> throw new AssertionError(uncertainty);
            };
        });

        collector(100).collectIfDue();

        assertEquals(15, saved.get().size());
        assertFalse(savedCoverage.get().totalKnownAndStable());
        verify(store).beginStatusSync(Set.of("11"));
    }

    @Test
    void slowPagesRetainRequestBoundsInsteadOfClaimingCompletionTimeAsTheObservation() {
        activeTask = "status";
        OffsetDateTime start = OffsetDateTime.now();
        AtomicReference<OffsetDateTime> time = new AtomicReference<>(start);
        StationCollector collector = spy(collector(100));
        doAnswer(invocation -> time.get()).when(collector).now();
        when(client.fetch(eq("status"), anyString(), eq("11"), anyInt(), eq(10))).thenAnswer(invocation -> {
            int pageNumber = invocation.getArgument(3);
            time.set(start.plusMinutes(pageNumber == 1 ? 1 : 11));
            return pageNumber == 1 ? page(0, 10, 15, 1, 10) : page(10, 5, 15, 2, 5);
        });

        collector.collectIfDue();

        assertEquals(start, savedObservedAt.get());
        assertEquals(start, savedCoverage.get().firstRequestStartedAt());
        assertEquals(start.plusMinutes(11), savedCoverage.get().lastResponseFinishedAt());
        assertTrue(savedCoverage.get().totalKnownAndStable(), "Page completeness does not certify time continuity");
        assertTrue(savedCoverage.get().lastResponseFinishedAt().minusMinutes(10)
                .isAfter(savedCoverage.get().firstRequestStartedAt()), "The store must see the empty common time window");
    }

    @Test
    void dueStatusAttemptInvalidatesRegionalSyncEvenWhenBudgetIsAlreadyExhausted() {
        activeTask = "status";
        calls.set(900);

        collector(100).collectIfDue();

        verify(store).beginStatusSync(Set.of("11"));
        verifyNoInteractions(client);
        verify(store, never()).saveStatuses(any(CollectedSnapshot.class), any(OffsetDateTime.class));
        verify(ledger).defer(eq("status"), any(OffsetDateTime.class), eq(1));
    }

    @Test
    void futureRetryDeadlineDoesNotBeginANewStatusAttempt() {
        activeTask = "status";
        when(ledger.state("status")).thenReturn(new State(null, OffsetDateTime.now().plusHours(1), 7));

        collector(100).collectIfDue();

        verify(store, never()).beginStatusSync(anySet());
        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TOTAL_CHANGED", "EMPTY_MIDDLE", "MISSING_ITEMS", "WRONG_PAGE", "COUNT_OVERFLOW"})
    void incompleteOrChangedMetadataNeverReachesDatabaseReplacement(String problem, CapturedOutput output) {
        when(client.fetch(eq("metadata"), anyString(), eq("11"), anyInt(), eq(10))).thenAnswer(invocation -> {
            int page = invocation.getArgument(3);
            if (page == 1) return page(0, 10, 15, 1, 10);
            return switch (problem) {
                case "TOTAL_CHANGED" -> page(10, 5, 16, page, 5);
                case "EMPTY_MIDDLE" -> page(10, 0, 15, page, 0);
                case "MISSING_ITEMS" -> page == 2 ? page(10, 4, 15, 2, 4) : page(14, 0, 15, page, 0);
                case "WRONG_PAGE" -> page(10, 5, 15, 1, 5);
                case "COUNT_OVERFLOW" -> page(10, 6, 15, page, 6);
                default -> throw new AssertionError(problem);
            };
        });

        collector(100).collectIfDue();

        assertNull(saved.get());
        verify(store, never()).saveMetadata(any(CollectedSnapshot.class), any(OffsetDateTime.class));
        verify(ledger).defer(eq("metadata"), any(OffsetDateTime.class), eq(1));
        String reason = switch (problem) {
            case "TOTAL_CHANGED" -> "METADATA_TOTAL_CHANGED";
            case "EMPTY_MIDDLE", "MISSING_ITEMS" -> "EMPTY_PAGE_BEFORE_TOTAL";
            case "WRONG_PAGE" -> "PAGE_NUMBER_MISMATCH";
            case "COUNT_OVERFLOW" -> "ITEM_COUNT_MISMATCH";
            default -> throw new AssertionError(problem);
        };
        assertTrue(output.getAll().contains("reason=" + reason));
        assertTrue(output.getAll().contains("task=metadata region=11 page="));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void knownAndUnknownCollectionsStopWithinDurableCallBudget(boolean unknownTotal, CapturedOutput output) {
        when(client.fetch(eq("metadata"), anyString(), eq("11"), anyInt(), eq(10))).thenAnswer(invocation -> {
            int page = invocation.getArgument(3);
            return unknownTotal ? page(page - 1, 1, -1, page, 1) : page(0, 10, 11, 1, 10);
        });

        collector(unknownTotal ? 2 : 1).collectIfDue();

        assertNull(saved.get());
        assertEquals(unknownTotal ? 2 : 1, calls.get());
        assertTrue(output.getAll().contains("reason=BUDGET_EXHAUSTED"));
        verify(store, never()).saveMetadata(any(CollectedSnapshot.class), any(OffsetDateTime.class));
    }

    @Test
    void existingRetryDeadlineIsPreservedAndFailureLogsExcludeArbitraryExceptionText(CapturedOutput output) {
        when(ledger.state("metadata")).thenReturn(new State(null, OffsetDateTime.now().plusHours(1), 5));
        collector(100).collectIfDue();
        verifyNoInteractions(client);
        verify(ledger, never()).defer(anyString(), any(), anyInt());

        when(ledger.state("metadata")).thenReturn(new State(null, null, 5));
        when(client.fetch(eq("metadata"), anyString(), eq("11"), eq(1), eq(10)))
                .thenThrow(new CollectionException("https://invalid.example/?serviceKey=fixture-private-value"));
        collector(100).collectIfDue();
        assertFalse(output.getAll().contains("fixture-private-value"));
        assertFalse(output.getAll().contains("https://invalid.example"));
        assertTrue(output.getAll().contains("stage=FETCH_PAGE reason=COLLECTION_FAILED"));
        verify(ledger).defer(eq("metadata"), any(OffsetDateTime.class), eq(6));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void recentGlobalMetadataOnlyRunsEarlyWhenRegionalBaselineNeedsRecovery(boolean needsRecovery,
                                                                           CapturedOutput output) {
        when(ledger.state("metadata")).thenReturn(new State(OffsetDateTime.now().minusHours(1), null, 0));
        when(store.needsStatusBaselineRecovery(Set.of("11"))).thenReturn(needsRecovery);
        when(client.fetch(eq("metadata"), anyString(), eq("11"), eq(1), eq(10)))
                .thenReturn(page(0, 1, 1, 1, 1));
        StationCollector collector = collector(100);

        collector.collectIfDue();

        verify(store).needsStatusBaselineRecovery(Set.of("11"));
        if (needsRecovery) {
            assertEquals(1, saved.get().size());
            assertTrue(output.getAll().contains("reason=INITIAL_BASELINE_RECOVERY"));
            // A completed full collection consumes the store's one-time bootstrap opportunity.
            when(store.needsStatusBaselineRecovery(Set.of("11"))).thenReturn(false);
            collector.collectIfDue();
            verify(client, times(1)).fetch(eq("metadata"), anyString(), eq("11"), eq(1), eq(10));
        } else {
            assertNull(saved.get());
            verifyNoInteractions(client);
            assertFalse(output.getAll().contains("reason=INITIAL_BASELINE_RECOVERY"));
        }
    }

    @Test
    void metadataRetryDeadlineIsCheckedBeforeConsideringInitialBaselineRecovery() {
        when(ledger.state("metadata")).thenReturn(new State(OffsetDateTime.now().minusHours(1),
                OffsetDateTime.now().plusMinutes(10), 2));

        collector(100).collectIfDue();

        verify(store, never()).needsStatusBaselineRecovery(anySet());
        verifyNoInteractions(client);
        verify(ledger, never()).defer(anyString(), any(), anyInt());
    }

    @Test
    void normallyDueMetadataDoesNotNeedAnExtraRecoveryQuery() {
        when(client.fetch(eq("metadata"), anyString(), eq("11"), eq(1), eq(10)))
                .thenReturn(page(0, 1, 1, 1, 1));

        collector(100).collectIfDue();

        assertEquals(1, saved.get().size());
        verify(store, never()).needsStatusBaselineRecovery(anySet());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void initialBaselineRecoveryStillRespectsDailyAndMetadataBudgets(boolean metadataLimit,
                                                                    CapturedOutput output) {
        when(ledger.state("metadata")).thenReturn(new State(OffsetDateTime.now().minusHours(1), null, 0));
        when(store.needsStatusBaselineRecovery(Set.of("11"))).thenReturn(true);
        calls.set(metadataLimit ? 100 : 900);
        metadataCalls.set(metadataLimit ? 100 : 0);

        collector(100).collectIfDue();

        assertNull(saved.get());
        verifyNoInteractions(client);
        verify(ledger).defer(eq("metadata"), any(OffsetDateTime.class), eq(1));
        assertTrue(output.getAll().contains("reason=BUDGET_EXHAUSTED"));
    }

    @Test
    void initialBaselineRecoveryChecksAndCollectsAllConfiguredRegions() {
        when(ledger.state("metadata")).thenReturn(new State(OffsetDateTime.now().minusHours(1), null, 0));
        when(store.needsStatusBaselineRecovery(Set.of("11", "26"))).thenReturn(true);
        when(client.fetch(eq("metadata"), anyString(), anyString(), eq(1), eq(10)))
                .thenAnswer(call -> page(call.getArgument(2).equals("11") ? 0 : 1, 1, 1, 1, 1));
        var collector = new StationCollector(dataSource, ledger, store, client, "FIXTURE_ONLY_NOT_A_SERVICE_KEY",
                "11,26", 10, 900, 100, 300, 86400);

        collector.collectIfDue();

        verify(store).needsStatusBaselineRecovery(Set.of("11", "26"));
        assertEquals(Set.of("11", "26"), savedRegions.get());
        assertEquals(2, saved.get().size());
        assertEquals(2, calls.get());
        verify(client).fetch(eq("metadata"), anyString(), eq("11"), eq(1), eq(10));
        verify(client).fetch(eq("metadata"), anyString(), eq("26"), eq(1), eq(10));
    }

    @Test
    void failedInitialBaselineRecoveryOnlyRetriesAfterItsPersistedBackoff() {
        OffsetDateTime start = OffsetDateTime.now();
        AtomicReference<OffsetDateTime> time = new AtomicReference<>(start);
        AtomicReference<State> metadataState = new AtomicReference<>(new State(start.minusHours(1), null, 0));
        when(ledger.state("metadata")).thenAnswer(call -> metadataState.get());
        when(ledger.state("status")).thenReturn(new State(start.plusDays(1), null, 0));
        when(store.needsStatusBaselineRecovery(Set.of("11"))).thenReturn(true);
        doAnswer(call -> {
            metadataState.set(new State(start.minusHours(1), call.getArgument(1), call.getArgument(2)));
            return null;
        }).when(ledger).defer(eq("metadata"), any(OffsetDateTime.class), anyInt());
        when(client.fetch(eq("metadata"), anyString(), eq("11"), eq(1), eq(10)))
                .thenThrow(new CollectionException(CollectionException.Reason.UPSTREAM_REQUEST_FAILED))
                .thenReturn(page(0, 1, 1, 1, 1));
        StationCollector collector = spy(collector(100));
        doAnswer(call -> time.get()).when(collector).now();

        collector.collectIfDue();
        assertNull(saved.get());
        assertEquals(start.plusMinutes(5), metadataState.get().retryAfter());

        collector.collectIfDue();
        assertEquals(1, calls.get());
        verify(store, times(1)).needsStatusBaselineRecovery(Set.of("11"));

        time.set(metadataState.get().retryAfter().plusNanos(1));
        collector.collectIfDue();

        assertEquals(1, saved.get().size());
        assertEquals(2, calls.get(), "Failed HTTP attempts remain charged against the durable budget");
        verify(store, times(2)).needsStatusBaselineRecovery(Set.of("11"));
        verify(ledger, times(1)).defer(eq("metadata"), any(OffsetDateTime.class), eq(1));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void statusTimeoutRetriesCanPreserveCoverageButNeverBridgeARealGap(int failedRequests) {
        activeTask = "status";
        var lastSuccess = OffsetDateTime.now();
        var baseline = lastSuccess.minusMinutes(20);
        var region = new CollectionStateEntity("region:11");
        region.establishStatusBaseline(baseline);
        for (int minute : List.of(15, 10, 5, 0)) {
            region.completeStatusSync(lastSuccess.minusMinutes(minute + 10), lastSuccess.minusMinutes(minute));
        }
        assertTrue(region.isStatusSyncValid());
        var time = new AtomicReference<>(lastSuccess.plusMinutes(5));
        var statusState = new AtomicReference<>(new State(lastSuccess, null, 0));
        when(ledger.state("status")).thenAnswer(call -> statusState.get());
        doAnswer(call -> {
            var previous = statusState.get();
            statusState.set(new State(previous.succeededAt(), call.getArgument(1), call.getArgument(2)));
            return null;
        }).when(ledger).defer(eq("status"), any(), anyInt());
        doAnswer(call -> { region.beginStatusSync(); return null; }).when(store).beginStatusSync(Set.of("11"));
        doAnswer(call -> {
            CollectedSnapshot snapshot = call.getArgument(0);
            var coverage = snapshot.regionCoverage("11");
            assertTrue(coverage.totalKnownAndStable());
            region.completeStatusSync(coverage.lastResponseFinishedAt().minusMinutes(10), coverage.firstRequestStartedAt());
            statusState.set(new State(call.getArgument(1), null, 0));
            return null;
        }).when(store).saveStatuses(any(CollectedSnapshot.class), any());
        var requests = new AtomicInteger();
        when(client.fetch(eq("status"), anyString(), eq("11"), eq(1), eq(10))).thenAnswer(call -> {
            if (requests.getAndIncrement() < failedRequests) {
                time.set(time.get().plusSeconds(20));
                throw new CollectionException(CollectionException.Reason.UPSTREAM_REQUEST_FAILED);
            }
            time.set(time.get().plusNanos(200_000_000));
            return page(0, 0, 0, 1, 0);
        });
        var collector = spy(collector(100));
        doAnswer(call -> time.get()).when(collector).now();

        for (int failure = 0; failure < failedRequests; failure++) {
            collector.collectIfDue();
            assertFalse(region.isStatusSyncValid());
            var deadline = time.get().plusMinutes(1L << failure);
            assertEquals(deadline, statusState.get().retryAfter());
            collector.collectIfDue();
            assertEquals(failure + 1, calls.get(), "Persistent backoff prevents requests before the deadline");
            time.set(deadline);
        }
        collector.collectIfDue();

        var unchanged = new StationStore.Charger("01", "04", 50.0, "2", baseline.minusHours(1), baseline, false);
        when(store.findById("A")).thenReturn(Optional.of(new StationStore.Station(
                "A", "example", "example", 37.55, 127.14, List.of(unchanged))));
        when(store.findStatusSyncForStation("A")).thenReturn(region.isStatusSyncValid()
                ? Optional.of(new StationStore.StatusSync(region.getSyncBaselineAt(), region.getLastStatusSyncAt()))
                : Optional.empty());
        var visits = mock(VisitService.class);
        var decisions = new StationDecisionService(new StationQueryService(store, visits), visits,
                Clock.fixed(time.get().toInstant(), ZoneOffset.UTC), 15);
        var navigation = decisions.navigationCheck("A");
        assertEquals(failedRequests < 3, navigation.navigationAllowed());
        assertEquals(failedRequests < 3 ? "OK" : "STATUS_STALE", navigation.reasonCode().name());
        assertEquals(baseline, unchanged.statusObservedAt());
        assertEquals(failedRequests + 1, calls.get(), "Every retry consumes the same durable call budget");
        assertEquals(0, metadataCalls.get(), "A later gap must not trigger extra full collections");
    }

    private StationCollector collector(int metadataBudget) {
        return collector(metadataBudget, 10);
    }

    private StationCollector collector(int metadataBudget, int rows) {
        return new StationCollector(dataSource, ledger, store, client, "FIXTURE_ONLY_NOT_A_SERVICE_KEY",
                "11", rows, 900, metadataBudget, 300, 86400);
    }

    private void capture(CollectedSnapshot snapshot, OffsetDateTime observedAt) {
        List<Item> items = new ArrayList<>();
        snapshot.forEachBatch((region, batch) -> items.addAll(batch));
        saved.set(List.copyOf(items));
        savedObservedAt.set(observedAt);
        savedCoverage.set(snapshot.regionCoverage("11"));
        savedRegions.set(snapshot.regionCodes());
    }

    private static Page page(int start, int count, int total, int page, int reportedRows) {
        List<Item> items = IntStream.range(start, start + count).mapToObj(index -> new Item(
                "TEST-STATION", "예시 충전소", "예시 주소", 37.55, 127.14, Integer.toString(index), "04",
                100.0, "3", OffsetDateTime.parse("2026-11-12T00:00:00+09:00"), false)).toList();
        return new Page(items, total, page, reportedRows);
    }
}
