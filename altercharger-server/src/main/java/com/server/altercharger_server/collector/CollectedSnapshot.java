package com.server.altercharger_server.collector;

import com.server.altercharger_server.collector.EvChargerJsonParser.Item;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Private temporary spool keeps completed pages off the heap until the whole download succeeds. */
public final class CollectedSnapshot implements AutoCloseable {
    public static final int BATCH_SIZE = 250;
    private static final Logger log = LoggerFactory.getLogger(CollectedSnapshot.class);
    private final Path directory;
    private final Map<String, Region> regions = new LinkedHashMap<>();

    public CollectedSnapshot() {
        try {
            directory = Files.createTempDirectory("altercharge-collector-");
        } catch (IOException ex) {
            throw new CollectionException("Cannot prepare a temporary collection snapshot");
        }
    }

    public RegionWriter openRegion(String zcode) {
        try {
            Region region = new Region(Files.createTempFile(directory, "region-", ".cache"));
            regions.put(zcode, region);
            return new RegionWriter(region);
        } catch (IOException ex) {
            throw new CollectionException("Cannot write a temporary collection snapshot");
        }
    }

    public Set<String> regionCodes() { return Set.copyOf(regions.keySet()); }
    public int itemCount() { return regions.values().stream().mapToInt(region -> region.count).sum(); }

    public void forEachBatch(BiConsumer<String, List<Item>> consumer) {
        for (var entry : regions.entrySet()) {
            Region region = entry.getValue();
            try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(region.path)))) {
                for (int remaining = region.count; remaining > 0;) {
                    int size = Math.min(remaining, BATCH_SIZE);
                    List<Item> items = new ArrayList<>(size);
                    for (int index = 0; index < size; index++) items.add(readItem(input));
                    consumer.accept(entry.getKey(), items);
                    remaining -= size;
                }
                if (input.read() != -1) throw new IOException("Unexpected snapshot contents");
            } catch (IOException ex) {
                throw new CollectionException("Cannot read the complete temporary collection snapshot");
            }
        }
    }

    @Override
    public void close() {
        try {
            try (var files = Files.list(directory)) {
                for (Path file : files.toList()) Files.deleteIfExists(file);
            }
            Files.deleteIfExists(directory);
        } catch (IOException ex) {
            // Cleanup is best effort after committed work; never log paths or snapshot contents.
            log.warn("Temporary collector snapshot cleanup failed ({})", ex.getClass().getSimpleName());
        }
    }

    private static Item readItem(DataInputStream input) throws IOException {
        String stationId = input.readUTF(), name = input.readUTF(), address = input.readUTF();
        Double lat = readNumber(input), lng = readNumber(input);
        String chargerId = input.readUTF(), type = input.readUTF();
        Double output = readNumber(input);
        String status = input.readUTF(), updatedAt = input.readUTF();
        return new Item(stationId, name, address, lat, lng, chargerId, type, output, status,
                updatedAt.isEmpty() ? null : OffsetDateTime.parse(updatedAt), input.readBoolean());
    }

    private static Double readNumber(DataInputStream input) throws IOException {
        return input.readBoolean() ? input.readDouble() : null;
    }

    private static void writeNumber(DataOutputStream output, Double number) throws IOException {
        output.writeBoolean(number != null);
        if (number != null) output.writeDouble(number);
    }

    private static final class Region {
        private final Path path;
        private int count;
        private Region(Path path) { this.path = path; }
    }

    public static final class RegionWriter implements AutoCloseable {
        private final Region region;
        private final DataOutputStream output;

        private RegionWriter(Region region) throws IOException {
            this.region = region;
            output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(region.path)));
        }

        public void append(List<Item> items) {
            try {
                for (Item item : items) {
                    output.writeUTF(item.statId()); output.writeUTF(item.statNm()); output.writeUTF(item.addr());
                    writeNumber(output, item.lat()); writeNumber(output, item.lng());
                    output.writeUTF(item.chgerId()); output.writeUTF(item.chgerType()); writeNumber(output, item.output());
                    output.writeUTF(item.stat()); output.writeUTF(item.statUpdDt() == null ? "" : item.statUpdDt().toString());
                    output.writeBoolean(item.deleted());
                    region.count++;
                }
            } catch (IOException ex) {
                throw new CollectionException("Cannot write a temporary collection snapshot");
            }
        }

        public int itemCount() { return region.count; }

        @Override
        public void close() {
            try {
                output.close();
            } catch (IOException ex) {
                throw new CollectionException("Cannot finish the temporary collection snapshot");
            }
        }
    }
}
