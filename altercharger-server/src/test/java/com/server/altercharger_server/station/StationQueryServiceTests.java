package com.server.altercharger_server.station;

import com.server.altercharger_server.history.VisitService;
import com.server.altercharger_server.station.StationStore.Charger;
import com.server.altercharger_server.station.StationStore.Station;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StationQueryServiceTests {
    @Test
    void aggregatesOnlyAvailableValidChargersAndPreservesCompositeTypesAndObservationTimes() {
        StationStore store = mock(StationStore.class);
        OffsetDateTime oldest = OffsetDateTime.parse("2026-11-11T10:00:00+09:00");
        OffsetDateTime recent = oldest.plusMinutes(5);
        List<Charger> chargers = List.of(
                new Charger("01", "06", 100.0, "2", oldest.minusDays(1), oldest, false),
                new Charger("02", "02", null, "3", oldest, recent, false),
                new Charger("03", "04", 50.0, "4", oldest, recent, false),
                new Charger("04", "04", 50.0, "5", oldest, recent, false),
                new Charger("05", "04", 50.0, "1", oldest, recent, false),
                new Charger("06", "99", null, "9", oldest, recent, false),
                new Charger("07", "04", 500.0, "2", oldest, recent, true),
                new Charger("08", "", 500.0, "2", oldest, recent, false),
                new Charger("09", "04", 500.0, "", oldest, recent, false),
                new Charger("", "04", 500.0, "2", oldest, recent, false));
        Station station = new Station("A", "강동 충전소", "서울 강동", 37.55, 127.14, chargers);
        when(store.hasCollectedData()).thenReturn(true);
        when(store.findById("A")).thenReturn(Optional.of(station));
        when(store.findAll()).thenReturn(List.of(
                new Station("outside", "강동 외곽", "서울 강동", 37.60, 127.14, List.of()),
                new Station("B", "강동 두번째", "서울 강동", 37.55, 127.14, List.of()),
                station));
        VisitService visits = mock(VisitService.class);
        when(visits.realVisitCounts("mock-user-001")).thenReturn(java.util.Map.of());
        StationQueryService service = new StationQueryService(store, visits);

        var detail = service.detail("A");
        assertEquals(1, detail.availableCount());
        assertEquals(6, detail.totalValidCount());
        assertEquals(oldest, detail.statusObservedAt());
        assertEquals(List.of("DC_CHADEMO", "AC_3PHASE", "DC_COMBO"), detail.chargers().getFirst().connectorTypes());
        assertEquals("DC_CHADEMO+AC_3PHASE+DC_COMBO", detail.chargers().getFirst().connectorType());
        assertEquals("06", detail.chargers().getFirst().chgerType());
        assertEquals("2", detail.chargers().getFirst().stat());
        assertEquals(oldest.minusDays(1), detail.chargers().getFirst().statusUpdatedAt());
        assertEquals("UNCONFIRMED", detail.chargers().getLast().status());
        assertEquals("99", detail.chargers().getLast().chgerType());
        assertNull(detail.chargers().getLast().outputKw());

        var firstPage = service.list(37.55, 127.14, 3, "강동", 0, 1);
        assertEquals("A", firstPage.items().getFirst().stationId());
        assertEquals(100.0, firstPage.items().getFirst().maxOutputKw());
        assertNull(firstPage.items().getFirst().visitCount());
        assertTrue(firstPage.hasNext());
        var secondPage = service.list(37.55, 127.14, 3, "강동", 1, 1);
        assertEquals("B", secondPage.items().getFirst().stationId());
        assertFalse(secondPage.hasNext());
        var beyondLastPage = service.list(37.55, 127.14, 3, null, Integer.MAX_VALUE, 200);
        assertTrue(beyondLastPage.items().isEmpty());
        assertFalse(beyondLastPage.hasNext());
    }
}
