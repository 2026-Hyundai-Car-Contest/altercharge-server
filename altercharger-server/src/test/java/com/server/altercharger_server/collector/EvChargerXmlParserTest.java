package com.server.altercharger_server.collector;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvChargerXmlParserTest {
    private final EvChargerXmlParser parser = new EvChargerXmlParser();

    @Test
    void mapsFixtureWithoutLosingIdsCompositeCodesOrMissingOutput() throws Exception {
        try (var fixture = getClass().getResourceAsStream("/fixtures/charger-info.xml")) {
            var page = parser.parse(new String(fixture.readAllBytes(), StandardCharsets.UTF_8));
            assertThat(page.totalCount()).isEqualTo(2);
            var first = page.items().getFirst();
            assertThat(first.statId()).isEqualTo("ST-001");
            assertThat(first.chgerId()).isEqualTo("01");
            assertThat(first.stat()).isEqualTo("2");
            assertThat(first.chgerType()).isEqualTo("06");
            assertThat(first.output()).isEqualTo(100.5);
            assertThat(first.statUpdDt()).isEqualTo(OffsetDateTime.parse("2026-11-11T20:00:00+09:00"));
            assertThat(first.validMetadata()).isTrue();
            assertThat(page.items().get(1).output()).isNull();
            assertThat(page.items().get(1).deleted()).isTrue();
        }
    }

    @Test
    void rejectsExternalEntitiesAndUpstreamErrorWithoutEchoingPayload() {
        assertThatThrownBy(() -> parser.parse("""
                <!DOCTYPE response [<!ENTITY secret SYSTEM "file:///must-not-be-read">]>
                <response><resultCode>00</resultCode><resultMsg>&secret;</resultMsg></response>
                """))
                .isInstanceOf(CollectionException.class)
                .hasMessage("Invalid or unsafe upstream XML");
        assertThatThrownBy(() -> parser.parse("""
                <response><header><resultCode>30</resultCode><resultMsg>private-value</resultMsg></header></response>
                """))
                .isInstanceOf(CollectionException.class)
                .hasMessageNotContaining("private-value");
    }
}
