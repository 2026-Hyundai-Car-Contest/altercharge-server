package com.server.altercharger_server.collector;

import java.io.StringReader;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

@Component
public class EvChargerXmlParser {
    private static final DateTimeFormatter UPSTREAM_TIME = DateTimeFormatter.ofPattern("uuuuMMddHHmmss")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    public Page parse(String xml) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler());
            var root = builder.parse(new InputSource(new StringReader(xml))).getDocumentElement();
            String resultCode = value(root, "resultCode");
            if (!("00".equals(resultCode) || "0".equals(resultCode))) {
                throw new CollectionException("Upstream rejected the request or omitted its result code");
            }
            List<Item> items = new ArrayList<>();
            NodeList nodes = root.getElementsByTagNameNS("*", "item");
            for (int i = 0; i < nodes.getLength(); i++) {
                var node = (Element) nodes.item(i);
                items.add(new Item(value(node, "statId"), value(node, "statNm"), value(node, "addr"),
                        number(value(node, "lat")), number(value(node, "lng")), value(node, "chgerId"),
                        value(node, "chgerType"), number(value(node, "output")), value(node, "stat"),
                        timestamp(value(node, "statUpdDt")), "Y".equalsIgnoreCase(value(node, "delYn"))));
            }
            return new Page(List.copyOf(items), integer(value(root, "totalCount"), -1),
                    integer(value(root, "pageNo"), 0), integer(value(root, "numOfRows"), 0));
        } catch (CollectionException ex) {
            throw ex;
        } catch (Exception ex) {
            // Do not propagate parser messages: the upstream response can contain credentials.
            throw new CollectionException("Invalid or unsafe upstream XML");
        }
    }

    private static String value(Element parent, String name) {
        var nodes = parent.getElementsByTagNameNS("*", name);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().strip();
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
