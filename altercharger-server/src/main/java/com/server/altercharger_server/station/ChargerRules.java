package com.server.altercharger_server.station;

import com.server.altercharger_server.station.StationStore.Charger;
import com.server.altercharger_server.station.StationStore.Station;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;

final class ChargerRules {
    private ChargerRules() {}

    static List<Charger> validChargers(Station station) {
        return station.chargers().stream()
                .filter(c -> !c.deleted() && c.chargerId() != null && !c.chargerId().isBlank())
                .filter(c -> c.rawConnectorType() != null && !c.rawConnectorType().isBlank()
                        && c.rawStatus() != null && !c.rawStatus().isBlank())
                .sorted(Comparator.comparing(Charger::chargerId)).toList();
    }

    static int availableCount(List<Charger> chargers) {
        return (int) chargers.stream().filter(c -> "2".equals(c.rawStatus())).count();
    }

    static Double maxOutput(List<Charger> chargers) {
        return chargers.stream().map(Charger::outputKw).filter(ChargerRules::validOutput)
                .max(Double::compare).orElse(null);
    }

    static boolean validOutput(Double output) {
        return output != null && Double.isFinite(output) && output > 0;
    }

    static OffsetDateTime observedAt(List<Charger> chargers) {
        if (chargers.stream().anyMatch(c -> c.statusObservedAt() == null)) return null;
        return chargers.stream().map(Charger::statusObservedAt).min(OffsetDateTime::compareTo).orElse(null);
    }

    static List<String> connectorTypes(String raw) {
        return switch (raw == null ? "" : raw) {
            case "01" -> List.of("DC_CHADEMO");
            case "02" -> List.of("AC_SLOW");
            case "03" -> List.of("DC_CHADEMO", "AC_3PHASE");
            case "04" -> List.of("DC_COMBO");
            case "05" -> List.of("DC_CHADEMO", "DC_COMBO");
            case "06" -> List.of("DC_CHADEMO", "AC_3PHASE", "DC_COMBO");
            case "07" -> List.of("AC_3PHASE");
            case "08" -> List.of("DC_COMBO_SLOW");
            case "09" -> List.of("NACS");
            case "10" -> List.of("DC_COMBO", "NACS");
            case "11" -> List.of("DC_COMBO_2");
            default -> List.of("UNKNOWN");
        };
    }

    static String status(String raw) {
        return switch (raw == null ? "" : raw) {
            case "1" -> "COMMUNICATION_ERROR";
            case "2" -> "AVAILABLE";
            case "3" -> "CHARGING";
            case "4" -> "STOPPED";
            case "5" -> "MAINTENANCE";
            case "6" -> "RESERVED";
            case "9" -> "UNCONFIRMED";
            default -> "UNKNOWN";
        };
    }
}
