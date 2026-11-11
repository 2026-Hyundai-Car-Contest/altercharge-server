package com.server.altercharger_server.history;

import com.server.altercharger_server.common.error.ApiException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class HistoryPolicy {
    public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final Clock clock;
    private final int retentionDays;
    private final int maxFutureMinutes;

    public HistoryPolicy(Clock clock, @Value("${app.history.retention-days:56}") int retentionDays,
                         @Value("${app.history.max-future-minutes:5}") int maxFutureMinutes) {
        if (retentionDays < 1 || retentionDays > 56 || maxFutureMinutes < 0 || maxFutureMinutes > 60) {
            throw new IllegalArgumentException("History retention or future tolerance is outside the supported range");
        }
        this.clock = clock;
        this.retentionDays = retentionDays;
        this.maxFutureMinutes = maxFutureMinutes;
    }

    public LocalDate today() { return LocalDate.now(clock.withZone(SEOUL)); }
    public int retentionDays() { return retentionDays; }
    public OffsetDateTime cutoff() { return today().minusDays(retentionDays - 1L).atStartOfDay(SEOUL).toOffsetDateTime(); }

    public OffsetDateTime normalize(OffsetDateTime value) {
        if (value == null) throw invalidBody("오프셋이 포함된 측정 시각이 필요합니다.");
        return value.withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    public void validateNewTimestamp(OffsetDateTime value) {
        if (value.isBefore(cutoff()) || value.isAfter(OffsetDateTime.now(clock).plusMinutes(maxFutureMinutes))) {
            throw invalidBody("측정 시각은 보관 기간 안에 있어야 하며 허용된 미래 오차를 넘을 수 없습니다.");
        }
    }

    public void validateNumbers(Integer soc, Double odometer, boolean requiredOdometer) {
        if (soc == null || soc < 0 || soc > 100 || requiredOdometer && odometer == null
                || odometer != null && (!Double.isFinite(odometer) || odometer < 0)) {
            throw invalidBody("SOC는 0~100, 누적 주행거리는 유한한 0 이상 값이어야 합니다.");
        }
    }

    public static ApiException invalidBody(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST_BODY", message);
    }
}
