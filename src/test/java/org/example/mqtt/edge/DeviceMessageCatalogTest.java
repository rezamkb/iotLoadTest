package org.example.mqtt.edge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeviceMessageCatalogTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDirectory;

    @Test
    void mapsEachDeviceToGeneratedPayloadVariants() throws Exception {
        Path devices = writeDevices("""
                "device-a","A","client-a","alt-a","type-1"
                "device-b","B","client-b","alt-b","type-1"
                """);
        Path payloads = Files.createDirectory(tempDirectory.resolve("payloads"));
        Files.writeString(payloads.resolve("type-1"), payload(100, false));

        DeviceMessageCatalog catalog = DeviceMessageCatalog.load(devices, payloads, 3);

        assertEquals(2, catalog.deviceCount());
        assertEquals(3, catalog.variantCount("device-a"));

        JsonNode first = parse(catalog.nextMessage("device-a"));
        JsonNode second = parse(catalog.nextMessage("device-a"));
        JsonNode third = parse(catalog.nextMessage("device-a"));
        JsonNode cycled = parse(catalog.nextMessage("device-a"));

        assertEquals("device-a", first.at("/deviceReport/0/deviceId").asText());
        assertEquals("device-a", second.at("/deviceReport/0/deviceId").asText());
        assertEquals(9, first.at(
                "/deviceReport/0/deviceTwinDocument/attributes/reported/co2"
        ).asInt());
        assertEquals(99, second.at(
                "/deviceReport/0/deviceTwinDocument/attributes/reported/co2"
        ).asInt());
        assertEquals(999, third.at(
                "/deviceReport/0/deviceTwinDocument/attributes/reported/co2"
        ).asInt());
        assertNotEquals(
                first.at("/deviceReport/0/deviceTwinDocument/attributes/reported/value"),
                second.at("/deviceReport/0/deviceTwinDocument/attributes/reported/value")
        );
        assertNotEquals(second, third);
        assertEquals(first, cycled);
    }

    @Test
    void usesProvidedJsonArrayVariantsWithoutGeneratingMore() throws Exception {
        Path devices = writeDevices("\"device-a\",\"A\",\"client-a\",\"alt-a\",\"type-1\"\n");
        Path payloads = Files.createDirectory(tempDirectory.resolve("payloads"));
        Files.writeString(
                payloads.resolve("type-1.json"),
                "[" + payload(10, false) + "," + payload(20, true) + "]"
        );

        DeviceMessageCatalog catalog = DeviceMessageCatalog.load(devices, payloads, 3);

        assertEquals(2, catalog.variantCount("device-a"));
        assertEquals(10, parse(catalog.nextMessage("device-a"))
                .at("/deviceReport/0/deviceTwinDocument/attributes/reported/value").asInt());
        assertEquals(20, parse(catalog.nextMessage("device-a"))
                .at("/deviceReport/0/deviceTwinDocument/attributes/reported/value").asInt());
    }

    @Test
    void acceptsMultipleTopLevelJsonSamples() throws Exception {
        Path devices = writeDevices("\"device-a\",\"A\",\"client-a\",\"alt-a\",\"type-1\"\n");
        Path payloads = Files.createDirectory(tempDirectory.resolve("payloads"));
        Files.writeString(payloads.resolve("type-1"), payload(10, false) + payload(20, true));

        DeviceMessageCatalog catalog = DeviceMessageCatalog.load(devices, payloads);

        assertEquals(2, catalog.variantCount("device-a"));
    }

    @Test
    void reportsAllDeviceTypesWithMissingPayloadFiles() throws Exception {
        Path devices = writeDevices("""
                "device-a","A","client-a","alt-a","type-1"
                "device-b","B","client-b","alt-b","type-2"
                """);
        Path payloads = Files.createDirectory(tempDirectory.resolve("payloads"));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> DeviceMessageCatalog.load(devices, payloads)
        );

        assertTrue(failure.getMessage().contains("type-1"));
        assertTrue(failure.getMessage().contains("type-2"));
    }

    private Path writeDevices(String contents) throws Exception {
        Path devices = tempDirectory.resolve("devices.csv");
        Files.writeString(devices, contents, StandardCharsets.UTF_8);
        return devices;
    }

    private static JsonNode parse(byte[] payload) throws Exception {
        return MAPPER.readTree(payload);
    }

    private static String payload(int value, boolean enabled) {
        return """
                {
                  "deviceReport": [
                    {
                      "deviceId": "template-id",
                      "deviceTwinDocument": {
                        "attributes": {
                          "reported": {
                            "value": %d,
                            "co2": 500,
                            "enabled": %s,
                            "last_seen": "2026-01-01T00:00:00Z"
                          }
                        }
                      }
                    }
                  ]
                }
                """.formatted(value, enabled);
    }
}
