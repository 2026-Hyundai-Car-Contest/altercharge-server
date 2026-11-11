package com.server.altercharger_server.collector;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class EvChargerClient {
    private final EvChargerJsonParser parser;
    private final URI baseUri;
    private final String dataType;

    public EvChargerClient(EvChargerJsonParser parser, @Value("${app.collector.base-url}") String baseUrl,
                           @Value("${app.collector.data-type}") String dataType) {
        this.parser = parser;
        try {
            URI parsed = URI.create(baseUrl.strip());
            if (!"https".equalsIgnoreCase(parsed.getScheme()) || parsed.getHost() == null
                    || parsed.getUserInfo() != null || parsed.getQuery() != null || parsed.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            this.baseUri = URI.create(parsed.toString().replaceAll("/+$", "") + "/");
        } catch (IllegalArgumentException ex) {
            // A malformed URL can include credentials; do not retain its parser exception.
            throw new IllegalArgumentException("Collector base URL must be HTTPS without credentials, query, or fragment");
        }
        this.dataType = dataType.strip().toUpperCase(Locale.ROOT);
        if (!"JSON".equals(this.dataType)) throw new IllegalArgumentException("Collector response format must be JSON");
    }

    public EvChargerJsonParser.Page fetch(String task, String serviceKey, String zcode, int page, int rows) {
        HttpURLConnection connection = null;
        try {
            // Accept either the portal's decoded key or its encoded key; encode exactly once.
            String decodedKey = serviceKey.contains("%")
                    ? URLDecoder.decode(serviceKey, StandardCharsets.UTF_8) : serviceKey;
            String query = "serviceKey=" + URLEncoder.encode(decodedKey, StandardCharsets.UTF_8)
                    + "&pageNo=" + page + "&numOfRows=" + rows + "&dataType=" + dataType + "&zcode=" + zcode;
            // Poll about every five minutes with overlap for scheduling, paging, and network delay.
            if (task.equals("status")) query += "&period=10";
            String operation = task.equals("metadata") ? "getChargerInfo" : "getChargerStatus";
            var url = baseUri.resolve(operation + "?" + query).toURL();
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(20000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            if (connection.getResponseCode() != 200) throw new CollectionException("Upstream HTTP failure");
            try (var body = connection.getInputStream()) {
                return parser.parse(body, connection.getContentType());
            }
        } catch (IOException | IllegalArgumentException ex) {
            // Network exceptions and URIs can contain the key. Never retain them as causes.
            throw new CollectionException("Upstream request failed");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
