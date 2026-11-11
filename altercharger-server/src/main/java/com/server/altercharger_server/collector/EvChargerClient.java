package com.server.altercharger_server.collector;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

@Component
public class EvChargerClient {
    private static final int MAX_RESPONSE_BYTES = 20 * 1024 * 1024;
    private final EvChargerXmlParser parser;

    public EvChargerClient(EvChargerXmlParser parser) {
        this.parser = parser;
    }

    public EvChargerXmlParser.Page fetch(String task, String serviceKey, String zcode, int page, int rows) {
        HttpURLConnection connection = null;
        try {
            // Accept either the portal's decoded key or its encoded key; encode exactly once.
            String decodedKey = serviceKey.contains("%")
                    ? URLDecoder.decode(serviceKey, StandardCharsets.UTF_8) : serviceKey;
            String query = "serviceKey=" + URLEncoder.encode(decodedKey, StandardCharsets.UTF_8)
                    + "&pageNo=" + page + "&numOfRows=" + rows + "&dataType=XML&zcode=" + zcode;
            // Poll about every five minutes with overlap for scheduling, paging, and network delay.
            if (task.equals("status")) query += "&period=10";
            String operation = task.equals("metadata") ? "getChargerInfo" : "getChargerStatus";
            var url = URI.create("https://apis.data.go.kr/B552584/EvCharger/" + operation + "?" + query).toURL();
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(20000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/xml");
            if (connection.getResponseCode() != 200) throw new CollectionException("Upstream HTTP failure");
            try (var body = connection.getInputStream()) {
                byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES) throw new CollectionException("Upstream XML exceeds the size limit");
                return parser.parse(new String(bytes, StandardCharsets.UTF_8));
            }
        } catch (IOException | IllegalArgumentException ex) {
            // Network exceptions and URIs can contain the key. Never retain them as causes.
            throw new CollectionException("Upstream request failed");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
