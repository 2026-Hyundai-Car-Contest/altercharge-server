package com.server.altercharger_server.station;

import com.server.altercharger_server.common.error.ApiErrorAdvice;
import com.server.altercharger_server.history.VisitService;
import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StationControllerTests {
    private StationStore store;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        store = mock(StationStore.class);
        var visits = mock(VisitService.class);
        var query = new StationQueryService(store, visits);
        mvc = MockMvcBuilders.standaloneSetup(new StationController(query),
                        new StationDecisionController(new StationDecisionService(query, visits, Clock.systemUTC(), 15)))
                .setControllerAdvice(new ApiErrorAdvice()).build();
    }

    @ParameterizedTest
    @CsvSource({
            "91,127,3,0,20", "37,181,3,0,20", "NaN,127,3,0,20", "37,Infinity,3,0,20",
            "37,127,NaN,0,20", "37,127,0,0,20", "37,127,3.1,0,20", "37,127,3,-1,20",
            "37,127,3,0,201", "37,127,3,0,0", "not-a-number,127,3,0,20"
    })
    void invalidSearchInputReturns400(String lat, String lng, String radius, String page, String size) throws Exception {
        mvc.perform(get("/api/v1/stations").param("lat", lat).param("lng", lng)
                        .param("radiusKm", radius).param("page", page).param("size", size))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void missingCoordinateReturns400() throws Exception {
        mvc.perform(get("/api/v1/stations").param("lng", "127"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
        mvc.perform(get("/api/v1/stations/recommendation").param("lat", "Infinity")
                        .param("lng", "127").param("chargeRecommended", "true"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void cacheReadinessDistinguishesUnavailableFromMissingStation() throws Exception {
        mvc.perform(get("/api/v1/stations/missing"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("STATION_DATA_UNAVAILABLE"));

        when(store.hasCollectedData()).thenReturn(true);
        when(store.findById("missing")).thenReturn(Optional.empty());
        mvc.perform(get("/api/v1/stations/missing"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("STATION_NOT_FOUND"));
    }
}
