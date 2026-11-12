package com.server.altercharger_server.history;

import com.server.altercharger_server.history.repository.StationVisitRepository;
import com.server.altercharger_server.station.StationStore;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VisitServiceTests {
    @Test
    void verifiedStartUsesNearestIdTieAndKoreanDateWhileUniqueInsertReportsDailyDuplicate() {
        StationStore stations = mock(StationStore.class);
        when(stations.hasCollectedData()).thenReturn(true);
        when(stations.findNearby(37.55, 127.14, 100)).thenReturn(List.of(
                new StationStore.Station("B", "시연", "시연", 37.55, 127.14, List.of()),
                new StationStore.Station("outside", "시연", "시연", 37.5508, 127.141, List.of()),
                new StationStore.Station("A", "시연", "시연", 37.55, 127.14, List.of())));
        StationVisitRepository repository = mock(StationVisitRepository.class);
        when(repository.addVerifiedVisit(anyString(), eq("mock-user-001"), eq("A"), eq(LocalDate.of(2026, 11, 12)))).thenReturn(1, 0);
        VisitService visits = new VisitService(repository, new StationMatcher(stations));
        OffsetDateTime time = OffsetDateTime.parse("2026-11-11T15:01:00Z");
        var first = visits.recordVerifiedStart("mock-user-001", 37.55, 127.14, time);
        assertEquals("A", first.stationId());
        assertEquals(1, first.visitCountAdded());
        assertFalse(first.dailyDuplicate());
        var duplicate = visits.recordVerifiedStart("mock-user-001", 37.55, 127.14, time.plusHours(1));
        assertEquals(0, duplicate.visitCountAdded());
        assertTrue(duplicate.dailyDuplicate());
        verify(repository, times(2)).addVerifiedVisit(anyString(), eq("mock-user-001"), eq("A"), eq(LocalDate.of(2026, 11, 12)));
        assertTrue(visits.realVisitCounts("unknown-user").isEmpty());
        verify(stations, never()).findAll();
    }
}
