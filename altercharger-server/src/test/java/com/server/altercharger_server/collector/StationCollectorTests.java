package com.server.altercharger_server.collector;

import com.server.altercharger_server.collector.EvChargerJsonParser.Item;
import com.server.altercharger_server.collector.EvChargerJsonParser.Page;
import com.server.altercharger_server.collector.persistence.CollectorLedger;
import com.server.altercharger_server.collector.persistence.CollectorLedger.State;
import com.server.altercharger_server.collector.persistence.CollectorLedger.Usage;
import com.server.altercharger_server.station.StationStore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
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

    @Test
    void unknownTotalProbesUntilEmptyEvenAfterAShortPage() {
        when(client.fetch(eq("metadata"), anyString(), eq("11"), anyInt(), eq(10))).thenAnswer(invocation ->
                switch ((int) invocation.getArgument(3)) {
                    case 1 -> page(0, 3, -1, 1, 3);
                    case 2 -> page(3, 1, -1, 2, 1);
                    case 3 -> page(4, 0, -1, 3, 0);
                    default -> throw new AssertionError("An empty terminal page must end unknown-total probing");
                });

        collector(100).collectIfDue();

        assertEquals(4, saved.get().size());
        assertEquals(3, calls.get());
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

    private StationCollector collector(int metadataBudget) {
        return new StationCollector(dataSource, ledger, store, client, "FIXTURE_ONLY_NOT_A_SERVICE_KEY",
                "11", 10, 900, metadataBudget, 300, 86400);
    }

    private void capture(CollectedSnapshot snapshot, OffsetDateTime observedAt) {
        List<Item> items = new ArrayList<>();
        snapshot.forEachBatch((region, batch) -> items.addAll(batch));
        saved.set(List.copyOf(items));
        savedObservedAt.set(observedAt);
    }

    private static Page page(int start, int count, int total, int page, int reportedRows) {
        List<Item> items = IntStream.range(start, start + count).mapToObj(index -> new Item(
                "TEST-STATION", "예시 충전소", "예시 주소", 37.55, 127.14, Integer.toString(index), "04",
                100.0, "3", OffsetDateTime.parse("2026-11-12T00:00:00+09:00"), false)).toList();
        return new Page(items, total, page, reportedRows);
    }
}
