package com.server.altercharger_server.history;

import com.server.altercharger_server.common.error.ApiErrorAdvice;
import com.server.altercharger_server.common.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HistoryControllerTests {
    private final HistoryService history = mock(HistoryService.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new HistoryController(history)).setControllerAdvice(new ApiErrorAdvice()).build();
    }

    @Test
    void invalidJsonAndBodyConstraintsReturn400AndServiceErrorsKeep404409() throws Exception {
        mvc.perform(post("/api/v1/vehicles/snapshots").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vehicleId\":\"mock-vehicle-001\",\"recordedAt\":\"2026-11-11T01:00:00+09:00\",\"socPercent\":101,\"odometerKm\":1}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_REQUEST_BODY"));
        mvc.perform(post("/api/v1/charging-events").contentType(MediaType.APPLICATION_JSON).content("{\"eventType\":\"END\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_REQUEST_BODY"));
        mvc.perform(post("/api/v1/charging-events").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"invalid-coordinate\",\"vehicleId\":\"mock-vehicle-001\",\"eventType\":\"START\",\"occurredAt\":\"2026-11-11T01:00:00+09:00\",\"lat\":\"NaN\",\"lng\":127,\"socPercent\":35}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_REQUEST_BODY"));
        mvc.perform(post("/api/v1/vehicles/snapshots").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vehicleId\":\"mock-vehicle-001\",\"recordedAt\":\"2026-11-11T01:00:00+09:00\",\"socPercent\":50,\"odometerKm\":\"Infinity\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_REQUEST_BODY"));
        mvc.perform(get("/api/v1/charging-events/latest").param("vehicleId", " "))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
        when(history.latestCharge("unknown")).thenThrow(new ApiException(HttpStatus.NOT_FOUND, "VEHICLE_NOT_FOUND", "해당 차량을 찾을 수 없습니다."));
        mvc.perform(get("/api/v1/charging-events/latest").param("vehicleId", "unknown"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("VEHICLE_NOT_FOUND"));
        when(history.saveSnapshot(any())).thenThrow(new ApiException(HttpStatus.CONFLICT, "SNAPSHOT_CONFLICT", "같은 차량과 측정 시각에 다른 값이 저장되어 있습니다."));
        mvc.perform(post("/api/v1/vehicles/snapshots").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vehicleId\":\"mock-vehicle-001\",\"recordedAt\":\"2026-11-11T01:00:00+09:00\",\"socPercent\":50,\"odometerKm\":1}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("SNAPSHOT_CONFLICT"));
    }
}
