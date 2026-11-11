package com.server.altercharger_server.collector;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvChargerJsonParserTest {
    private final EvChargerJsonParser parser = new EvChargerJsonParser();

    @Test
    void mapsInfoFixtureWithoutLosingIdsCompositeCodesOrMissingOutput() throws Exception {
        try (var fixture = getClass().getResourceAsStream("/fixtures/charger-info.json")) {
            var page = parser.parse(fixture, "application/json;charset=UTF-8");
            assertThat(page.totalCount()).isEqualTo(2);
            var first = page.items().getFirst();
            assertThat(first.statId()).isEqualTo("ST-001");
            assertThat(first.chgerId()).isEqualTo("01");
            assertThat(first.stat()).isEqualTo("2");
            assertThat(first.chgerType()).isEqualTo("06");
            assertThat(first.output()).isEqualTo(100.5);
            assertThat(first.lat()).isEqualTo(37.551);
            assertThat(first.statUpdDt()).isEqualTo(OffsetDateTime.parse("2026-11-11T20:00:00+09:00"));
            assertThat(first.validMetadata()).isTrue();
            assertThat(page.items().get(1).output()).isNull();
            assertThat(page.items().get(1).stat()).isEqualTo("57");
            assertThat(page.items().get(1).deleted()).isTrue();
        }
    }

    @Test
    void mapsStatusChangeFeedAndRoundTripsSpoolInBoundedBatches() throws Exception {
        try (var fixture = getClass().getResourceAsStream("/fixtures/charger-status.json");
             var snapshot = new CollectedSnapshot()) {
            var page = parser.parse(fixture, "application/json");
            assertThat(page.totalCount()).isEqualTo(2);
            assertThat(page.items().getFirst().statUpdDt()).isEqualTo(OffsetDateTime.parse("2020-01-01T12:00:00+09:00"));
            assertThat(page.items().getFirst().validMetadata()).isFalse();
            assertThat(page.items().get(1).stat()).isEqualTo("77");
            assertThat(page.items().get(1).statUpdDt()).isNull();
            try (var writer = snapshot.openRegion("11")) {
                for (int index = 0; index < 126; index++) writer.append(page.items());
            }
            List<Integer> sizes = new ArrayList<>();
            snapshot.forEachBatch((region, items) -> {
                assertThat(region).isEqualTo("11");
                assertThat(items.getFirst()).isEqualTo(page.items().getFirst());
                sizes.add(items.size());
            });
            assertThat(sizes).containsExactly(250, 2);
            assertThat(snapshot.itemCount()).isEqualTo(252);
        }
    }

    @Test
    void handlesEmptyItemsAndRejectsErrorsWithoutEchoingUpstreamPayload() {
        for (String body : List.of("", ",\"items\":{}", ",\"items\":null", ",\"items\":\"\"",
                ",\"items\":{\"item\":[]}", ",\"items\":{\"item\":null}")) {
            assertThat(parse("{\"resultCode\":\"00\",\"totalCount\":0" + body + "}").items()).isEmpty();
        }
        for (String body : List.of("{\"resultCode\":\"30\",\"resultMsg\":\"private-value\"}",
                "<response>private-value</response>", "<html>private-value</html>",
                "{\"resultCode\":\"00\",\"items\":{\"item\":{}}}",
                "{\"resultCode\":\"00\",\"resultCode\":\"30\"}")) {
            assertThatThrownBy(() -> parse(body)).isInstanceOf(CollectionException.class)
                    .hasMessageNotContaining("private-value");
        }
        assertThatThrownBy(() -> parser.parse(bytes("{}"), "application/xml"))
                .isInstanceOf(CollectionException.class).hasMessageContaining("content type");
    }

    @Test
    void rejectsOversizedStreamWithoutBufferingTheWholeBody() {
        InputStream excessiveWhitespace = new InputStream() {
            private int read;
            @Override public int read() { return read++ <= EvChargerJsonParser.MAX_RESPONSE_BYTES ? ' ' : -1; }
            @Override public int read(byte[] bytes, int offset, int length) {
                int count = Math.min(length, EvChargerJsonParser.MAX_RESPONSE_BYTES + 1 - read);
                if (count <= 0) return -1;
                java.util.Arrays.fill(bytes, offset, offset + count, (byte) ' ');
                read += count;
                return count;
            }
        };
        assertThatThrownBy(() -> parser.parse(excessiveWhitespace, "application/json"))
                .isInstanceOf(CollectionException.class).hasMessage("Invalid or oversized upstream JSON");
    }

    private EvChargerJsonParser.Page parse(String json) { return parser.parse(bytes(json), "application/json"); }
    private static InputStream bytes(String value) { return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)); }
}
