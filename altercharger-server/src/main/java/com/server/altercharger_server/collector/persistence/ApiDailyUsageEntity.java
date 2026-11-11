package com.server.altercharger_server.collector.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "api_daily_usage")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApiDailyUsageEntity {
    @Id
    @Column(name = "usage_date")
    private LocalDate usageDate;

    @Column(nullable = false)
    private int calls;

    @Column(name = "metadata_calls", nullable = false)
    private int metadataCalls;

    public ApiDailyUsageEntity(LocalDate day) {
        usageDate = day;
    }

    public void reserve(boolean metadata) {
        calls++;
        if (metadata) metadataCalls++;
    }
}
