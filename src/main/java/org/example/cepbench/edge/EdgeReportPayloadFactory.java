package org.example.cepbench.edge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Builds the uplink payload an edge publishes on behalf of its devices.
 *
 * <p>The shape is fixed by {@code gateway/src/main/resources/json/IOT-in-edge-twin-report.json},
 * which the gateway validates against before the message goes anywhere. Two things in that schema
 * matter here:
 *
 * <ul>
 *   <li>{@code additionalProperties: false} at the root, so only {@code $requestId} and
 *       {@code deviceReport} are legal. Anything else is rejected upstream of CEP, and the benchmark
 *       would then be measuring nothing at all.</li>
 *   <li>{@code deviceReport} accepts an object <em>or</em> an array, which is what makes batching
 *       possible without changing the contract.</li>
 * </ul>
 *
 * <p>{@code deviceTwinDocument} is left as a plain object, matching an edge with encryption
 * disabled. The schema also allows a string there for the encrypted form, which this does not build.
 */
public final class EdgeReportPayloadFactory {

    /** The schema caps $requestId at 128 characters. */
    private static final int MAX_REQUEST_ID = 128;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * One device report per publish. Keeps MQTT throughput equal to event throughput, which is what
     * makes a rate figure comparable across runs.
     */
    public byte[] single(String requestId, DeviceReading reading) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("$requestId", trimRequestId(requestId));
        root.set("deviceReport", report(reading));
        return bytes(root);
    }

    /**
     * Several device reports in one publish, using the array form. Raises the achievable rate per
     * connection; each entry still becomes its own event downstream, so the CEP-side event count is
     * unchanged.
     */
    public byte[] batch(String requestId, List<DeviceReading> readings) {
        if (readings.isEmpty()) {
            throw new IllegalArgumentException("A batch needs at least one reading");
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.put("$requestId", trimRequestId(requestId));

        ArrayNode reports = root.putArray("deviceReport");
        for (DeviceReading reading : readings) {
            reports.add(report(reading));
        }
        return bytes(root);
    }

    private ObjectNode report(DeviceReading reading) {
        ObjectNode reported = objectMapper.createObjectNode();
        reading.applyTo(reported);

        ObjectNode attributes = objectMapper.createObjectNode();
        attributes.set("reported", reported);

        ObjectNode document = objectMapper.createObjectNode();
        document.set("attributes", attributes);

        ObjectNode report = objectMapper.createObjectNode();
        report.put("deviceId", reading.deviceId());
        report.set("deviceTwinDocument", document);
        return report;
    }

    private byte[] bytes(ObjectNode root) {
        try {
            return objectMapper.writeValueAsString(root).getBytes(StandardCharsets.UTF_8);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise an edge report", e);
        }
    }

    private static String trimRequestId(String requestId) {
        String safe = (requestId == null) ? "" : requestId;
        return safe.length() <= MAX_REQUEST_ID ? safe : safe.substring(0, MAX_REQUEST_ID);
    }

    /**
     * One device's reported attributes for one publish.
     *
     * <p>{@code temp} and {@code occ} mirror the device type the benchmark provisions. A reading is
     * either matching or not: a matching one satisfies its rule's condition and therefore causes the
     * fact to be retracted when the rule fires, while a non-matching one is retained in the entry
     * point indefinitely. That difference is the whole point of the retention workload.
     */
    public record DeviceReading(String deviceId, double temperature, boolean occupied, boolean includeOccupied) {

        public static DeviceReading temperature(String deviceId, double value) {
            return new DeviceReading(deviceId, value, false, false);
        }

        public static DeviceReading occupancy(String deviceId, boolean value) {
            return new DeviceReading(deviceId, 0.0d, value, true);
        }

        void applyTo(ObjectNode reported) {
            if (includeOccupied) {
                reported.put("occ", occupied);
            } else {
                reported.put("temp", temperature);
            }
        }
    }
}
