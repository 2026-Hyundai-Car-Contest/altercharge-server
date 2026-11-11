package com.server.altercharger_server.history;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class HistoryCleanup {
    private final HistoryService history;

    public HistoryCleanup(HistoryService history) { this.history = history; }

    @Scheduled(cron = "${app.history.cleanup-cron:0 10 3 * * *}", zone = "Asia/Seoul")
    public void purgeExpiredSnapshots() { history.purgeExpiredSnapshots(); }
}
