package com.server.altercharger_server.collector;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;

@Component
public class EvChargerJsonParser {
    public static final int MAX_RESPONSE_BYTES = 20 * 1024 * 1024;
    private static final DateTimeFormatter UPSTREAM_TIME = DateTimeFormatter.ofPattern("uuuuMMddHHmmss")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final JsonFactory factory = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16)
                    .maxStringLength(16384).maxNumberLength(64).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION).build();

    public Page parse(InputStream input, String contentType) {
        if (contentType == null || !contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT).equals("application/json")) {
            throw new CollectionException("Upstream response content type must be application/json");
        }
        try (JsonParser parser = factory.createParser(ObjectReadContext.empty(), new LimitedInput(input))) {
            require(parser.nextToken() == JsonToken.START_OBJECT);
            List<Item> items = new ArrayList<>();
            String resultCode = "";
            int totalCount = -1, pageNo = 0, numOfRows = 0;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                require(parser.currentToken() == JsonToken.PROPERTY_NAME);
                String field = parser.currentName();
                parser.nextToken();
                switch (field) {
                    case "resultCode" -> resultCode = scalar(parser);
                    case "resultMsg" -> scalar(parser); // Validate the field without retaining/logging upstream text.
                    case "totalCount" -> totalCount = integer(scalar(parser), -1);
                    case "pageNo" -> pageNo = integer(scalar(parser), 0);
                    case "numOfRows" -> numOfRows = integer(scalar(parser), 0);
                    case "items" -> readItems(parser, items);
                    default -> parser.skipChildren();
                }
            }
            require(parser.nextToken() == null);
            if (!("00".equals(resultCode) || "0".equals(resultCode))) {
                throw new CollectionException("Upstream rejected the request or omitted its result code");
            }
            return new Page(List.copyOf(items), totalCount, pageNo, numOfRows);
        } catch (CollectionException ex) {
            throw ex;
        } catch (Exception ex) {
            // Jackson and I/O messages may contain upstream bodies or authentication-bearing URLs.
            throw new CollectionException("Invalid or oversized upstream JSON");
        }
    }

    private static void readItems(JsonParser parser, List<Item> items) {
        if (parser.currentToken() == JsonToken.VALUE_NULL
                || parser.currentToken() == JsonToken.VALUE_STRING && parser.getString().isBlank()) return;
        require(parser.currentToken() == JsonToken.START_OBJECT);
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            require(parser.currentToken() == JsonToken.PROPERTY_NAME);
            String field = parser.currentName();
            parser.nextToken();
            if (!field.equals("item")) {
                parser.skipChildren();
                continue;
            }
            if (parser.currentToken() == JsonToken.VALUE_NULL) continue;
            require(parser.currentToken() == JsonToken.START_ARRAY);
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                require(parser.currentToken() == JsonToken.START_OBJECT && items.size() < 9999);
                items.add(readItem(parser));
            }
        }
    }

    private static Item readItem(JsonParser parser) {
        String stationId = "", name = "", address = "", chargerId = "", type = "", status = "", updatedAt = "", deleted = "";
        Double lat = null, lng = null, output = null;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            require(parser.currentToken() == JsonToken.PROPERTY_NAME);
            String field = parser.currentName();
            parser.nextToken();
            switch (field) {
                case "statId" -> stationId = scalar(parser);
                case "statNm" -> name = scalar(parser);
                case "addr" -> address = scalar(parser);
                case "lat" -> lat = number(scalar(parser));
                case "lng" -> lng = number(scalar(parser));
                case "chgerId" -> chargerId = scalar(parser);
                case "chgerType" -> type = scalar(parser);
                case "output" -> output = number(scalar(parser));
                case "stat" -> status = scalar(parser);
                case "statUpdDt" -> updatedAt = scalar(parser);
                case "delYn" -> deleted = scalar(parser);
                default -> parser.skipChildren();
            }
        }
        return new Item(stationId, name, address, lat, lng, chargerId, type, output, status,
                timestamp(updatedAt), "Y".equalsIgnoreCase(deleted));
    }

    private static String scalar(JsonParser parser) {
        if (parser.currentToken() == JsonToken.VALUE_NULL) return "";
        require(parser.currentToken() == JsonToken.VALUE_STRING || parser.currentToken() == JsonToken.VALUE_NUMBER_INT
                || parser.currentToken() == JsonToken.VALUE_NUMBER_FLOAT);
        return parser.getValueAsString("").strip();
    }

    private static void require(boolean valid) {
        if (!valid) throw new CollectionException("Unexpected upstream JSON structure");
    }

    private static Double number(String value) {
        try {
            double parsed = Double.parseDouble(value);
            return Double.isFinite(parsed) ? parsed : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static int integer(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed >= 0 ? parsed : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static OffsetDateTime timestamp(String value) {
        if (value.isBlank()) return null;
        try {
            return LocalDateTime.parse(value, UPSTREAM_TIME).atZone(SEOUL).toOffsetDateTime();
        } catch (DateTimeParseException ex) {
            try {
                return OffsetDateTime.parse(value);
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }

    private static final class LimitedInput extends FilterInputStream {
        private long count;

        private LimitedInput(InputStream input) { super(input); }

        @Override
        public int read() throws IOException {
            int value = in.read();
            if (value >= 0) check(1);
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = in.read(bytes, offset, (int) Math.min(length, MAX_RESPONSE_BYTES - count + 1));
            if (read > 0) check(read);
            return read;
        }

        private void check(int read) throws IOException {
            count += read;
            if (count > MAX_RESPONSE_BYTES) throw new IOException("Upstream JSON exceeds the response size limit");
        }
    }

    public record Page(List<Item> items, int totalCount, int pageNo, int numOfRows) { }

    public record Item(String statId, String statNm, String addr, Double lat, Double lng,
                       String chgerId, String chgerType, Double output, String stat,
                       OffsetDateTime statUpdDt, boolean deleted) {
        public boolean hasIdentity() {
            return !statId.isBlank() && statId.length() <= 100 && !chgerId.isBlank() && chgerId.length() <= 100;
        }

        public boolean validMetadata() {
            return hasIdentity() && !deleted && !statNm.isBlank() && !addr.isBlank()
                    && lat != null && lat >= -90 && lat <= 90 && lng != null && lng >= -180 && lng <= 180
                    && !(lat == 0 && lng == 0) && !chgerType.isBlank() && chgerType.length() <= 100;
        }
    }
}
