package com.server.altercharger_server.history;

import com.server.altercharger_server.common.Geo;
import com.server.altercharger_server.history.repository.StationVisitRepository;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class VisitService {
    private final StationVisitRepository visits;
    private final StationMatcher matcher;

    public VisitService(StationVisitRepository visits, StationMatcher matcher) {
        this.visits = visits;
        this.matcher = matcher;
    }

    public Map<String, Integer> realVisitCounts(String userId) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (var count : visits.countRealVisits(userId)) {
            counts.put(count.getStationId(), Math.toIntExact(Math.min(Integer.MAX_VALUE, count.getVisitCount())));
        }
        return counts;
    }

    /** Internal integration boundary for an authenticated, independently verified START. No REST route invokes this. */
    @Transactional
    public VerifiedVisit recordVerifiedStart(String userId, double lat, double lng, OffsetDateTime occurredAt) {
        if (userId == null || userId.isBlank() || occurredAt == null || !Geo.validCoordinates(lat, lng)) {
            throw HistoryPolicy.invalidBody("검증된 시작 이벤트의 사용자·좌표·시각이 필요합니다.");
        }
        String stationId = matcher.nearestWithin100Meters(lat, lng);
        if (stationId == null) return new VerifiedVisit(null, 0, false);
        int added = visits.addVerifiedVisit(UUID.randomUUID().toString(), userId, stationId,
                occurredAt.atZoneSameInstant(HistoryPolicy.SEOUL).toLocalDate());
        return new VerifiedVisit(stationId, added, added == 0);
    }

    public record VerifiedVisit(String stationId, int visitCountAdded, boolean dailyDuplicate) {}
}
