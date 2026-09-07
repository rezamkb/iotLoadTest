package org.example.cepbench.edge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.cepbench.edge.EdgeReportPayloadFactory.DeviceReading;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gateway validates every uplink against IOT-in-edge-twin-report.json with
 * {@code additionalProperties: false}, so a payload that drifts from that schema is rejected before
 * it reaches CEP and the benchmark silently measures nothing. These tests pin the shape.
 */
class EdgeReportPayloadFactoryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EdgeReportPayloadFactory factory = new EdgeReportPayloadFactory();

    @Test
    void aSingleReadingProducesTheObjectFormWithOnlyTheTwoAllowedRootKeys() throws Exception {
        JsonNode root = parse(factory.single("req-1", DeviceReading.temperature("gqcrarftoxs", 41.0d)));

        assertEquals(List.of("$requestId", "deviceReport"), fieldNames(root),
                "the schema forbids additional root properties");
        assertEquals("req-1", root.path("$requestId").asText());

        JsonNode report = root.path("deviceReport");
        assertTrue(report.isObject(), "a single reading uses the object form");
        assertEquals(List.of("deviceId", "deviceTwinDocument"), fieldNames(report));
        assertEquals("gqcrarftoxs", report.path("deviceId").asText());
        assertEquals(41.0d,
                report.path("deviceTwinDocument").path("attributes").path("reported").path("temp").asDouble());
    }

    @Test
    void severalReadingsProduceTheArrayFormTheSchemaAlsoAllows() throws Exception {
        JsonNode root = parse(factory.batch("req-2", List.of(
                DeviceReading.temperature("devA", 0.0d),
                DeviceReading.temperature("devB", 0.0d))));

        JsonNode report = root.path("deviceReport");
        assertTrue(report.isArray(), "a batch uses the array form");
        assertEquals(2, report.size());
        assertEquals("devA", report.get(0).path("deviceId").asText());
        assertEquals("devB", report.get(1).path("deviceId").asText());
    }

    @Test
    void anOccupancyReadingReportsOccAndNotTemp() throws Exception {
        JsonNode reported = parse(factory.single("req-3", DeviceReading.occupancy("2dsdyq5p8p6", true)))
                .path("deviceReport").path("deviceTwinDocument").path("attributes").path("reported");

        assertTrue(reported.path("occ").asBoolean());
        // The occ branch of a two-device rule must not also carry temp: that would let the other
        // branch match and make it impossible to attribute a firing to one device.
        assertTrue(reported.path("temp").isMissingNode(), "occupancy readings carry only occ");
    }

    @Test
    void anOverlongRequestIdIsTruncatedToTheSchemaLimit() throws Exception {
        String tooLong = "x".repeat(200);

        String requestId = parse(factory.single(tooLong, DeviceReading.temperature("devA", 1.0d)))
                .path("$requestId").asText();

        assertEquals(128, requestId.length(), "the schema caps $requestId at 128 characters");
    }

    @Test
    void anEmptyBatchIsRejectedRatherThanProducingAnEmptyArray() {
        assertThrows(IllegalArgumentException.class, () -> factory.batch("req-4", List.of()));
    }

    private static JsonNode parse(byte[] payload) throws Exception {
        return MAPPER.readTree(new String(payload, StandardCharsets.UTF_8));
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            names.add(it.next());
        }
        return names;
    }
}
