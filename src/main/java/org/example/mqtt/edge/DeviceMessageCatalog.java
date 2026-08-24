package org.example.mqtt.edge;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds the device-to-payload mapping used by the edge simulator.
 *
 * <p>Column 1 of the CSV is the device ID and column 5 is the device type ID.
 * A payload file is named after the device type ID. Payload files may contain
 * one JSON object, an array of JSON objects, or multiple top-level JSON objects.
 */
final class DeviceMessageCatalog {
    private static final int DEFAULT_VARIANT_COUNT = 3;

    private static final ObjectMapper MAPPER = new ObjectMapper(
            JsonFactory.builder()
                    .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
                    .build()
    );

    private final Map<String, DeviceMessages> messagesByDeviceId;

    private DeviceMessageCatalog(Map<String, DeviceMessages> messagesByDeviceId) {
        this.messagesByDeviceId = Collections.unmodifiableMap(messagesByDeviceId);
    }

    static DeviceMessageCatalog load(Path devicesCsv, Path payloadDirectory) throws IOException {
        return load(devicesCsv, payloadDirectory, DEFAULT_VARIANT_COUNT);
    }

    static DeviceMessageCatalog load(Path devicesCsv,
                                     Path payloadDirectory,
                                     int generatedVariantCount) throws IOException {
        if (generatedVariantCount < 1) {
            throw new IllegalArgumentException("generatedVariantCount must be at least 1");
        }

        List<Device> devices = readDevices(devicesCsv);
        if (devices.isEmpty()) {
            throw new IllegalArgumentException("No devices were found in " + devicesCsv);
        }

        Set<String> deviceTypes = new LinkedHashSet<>();
        devices.forEach(device -> deviceTypes.add(device.deviceTypeId()));

        Map<String, List<JsonNode>> templatesByType = new LinkedHashMap<>();
        List<String> missingPayloadTypes = new ArrayList<>();
        for (String deviceType : deviceTypes) {
            Path payloadFile = resolvePayloadFile(payloadDirectory, deviceType);
            if (payloadFile == null) {
                missingPayloadTypes.add(deviceType);
                continue;
            }
            templatesByType.put(deviceType, readTemplates(payloadFile));
        }

        if (!missingPayloadTypes.isEmpty()) {
            throw new IllegalArgumentException(
                    "Missing payload files for device types: " + String.join(", ", missingPayloadTypes));
        }

        Map<String, DeviceMessages> messages = new LinkedHashMap<>();
        for (Device device : devices) {
            List<JsonNode> templates = templatesByType.get(device.deviceTypeId());
            List<JsonNode> variants = createVariants(templates, generatedVariantCount);
            List<byte[]> boundMessages = new ArrayList<>(variants.size());

            for (JsonNode variant : variants) {
                JsonNode boundMessage = bindDeviceId(variant, device.id());
                boundMessages.add(MAPPER.writeValueAsBytes(boundMessage));
            }
            messages.put(device.id(), new DeviceMessages(List.copyOf(boundMessages)));
        }

        return new DeviceMessageCatalog(messages);
    }

    List<String> deviceIds() {
        return List.copyOf(messagesByDeviceId.keySet());
    }

    int deviceCount() {
        return messagesByDeviceId.size();
    }

    int variantCount(String deviceId) {
        return deviceMessages(deviceId).messages().size();
    }

    byte[] nextMessage(String deviceId) {
        DeviceMessages deviceMessages = deviceMessages(deviceId);
        int index = Math.floorMod(
                deviceMessages.nextIndex().getAndIncrement(),
                deviceMessages.messages().size()
        );
        return deviceMessages.messages().get(index).clone();
    }

    private DeviceMessages deviceMessages(String deviceId) {
        DeviceMessages result = messagesByDeviceId.get(deviceId);
        if (result == null) {
            throw new IllegalArgumentException("Unknown device ID: " + deviceId);
        }
        return result;
    }

    private static List<Device> readDevices(Path devicesCsv) throws IOException {
        Map<String, Device> devicesById = new LinkedHashMap<>();
        try (Reader reader = Files.newBufferedReader(devicesCsv, StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.parse(reader)) {
            for (CSVRecord row : parser) {
                if (row.size() < 5) {
                    continue;
                }

                String deviceId = row.get(0).trim();
                String deviceTypeId = row.get(4).trim();
                if (deviceId.isBlank() || deviceTypeId.isBlank()
                        || (deviceId.equalsIgnoreCase("id")
                        && deviceTypeId.equalsIgnoreCase("deviceTypeId"))) {
                    continue;
                }

                Device previous = devicesById.putIfAbsent(
                        deviceId,
                        new Device(deviceId, deviceTypeId)
                );
                if (previous != null && !previous.deviceTypeId().equals(deviceTypeId)) {
                    throw new IllegalArgumentException(
                            "Device " + deviceId + " has more than one device type");
                }
            }
        }
        return List.copyOf(devicesById.values());
    }

    private static Path resolvePayloadFile(Path payloadDirectory, String deviceTypeId) {
        Path exactName = payloadDirectory.resolve(deviceTypeId);
        if (Files.isRegularFile(exactName)) {
            return exactName;
        }

        Path jsonName = payloadDirectory.resolve(deviceTypeId + ".json");
        return Files.isRegularFile(jsonName) ? jsonName : null;
    }

    private static List<JsonNode> readTemplates(Path payloadFile) throws IOException {
        List<JsonNode> templates = new ArrayList<>();
        try (MappingIterator<JsonNode> values = MAPPER.readerFor(JsonNode.class)
                .readValues(payloadFile.toFile())) {
            while (values.hasNextValue()) {
                addTemplates(values.nextValue(), templates, payloadFile);
            }
        }

        if (templates.isEmpty()) {
            throw new IllegalArgumentException("No JSON payloads were found in " + payloadFile);
        }
        return List.copyOf(templates);
    }

    private static void addTemplates(JsonNode root,
                                     List<JsonNode> templates,
                                     Path payloadFile) {
        if (root.isArray()) {
            for (JsonNode element : root) {
                addTemplates(element, templates, payloadFile);
            }
            return;
        }

        if (!root.isObject()) {
            throw new IllegalArgumentException(
                    "Each payload in " + payloadFile + " must be a JSON object");
        }

        JsonNode reports = root.get("deviceReport");
        if (reports == null || !reports.isArray() || reports.isEmpty()) {
            throw new IllegalArgumentException(
                    "Each payload in " + payloadFile + " must contain a non-empty deviceReport array");
        }
        templates.add(root);
    }

    private static List<JsonNode> createVariants(List<JsonNode> templates,
                                                 int generatedVariantCount) {
        if (templates.size() > 1 || generatedVariantCount == 1) {
            return templates;
        }

        List<JsonNode> variants = new ArrayList<>(generatedVariantCount);
        for (int index = 0; index < generatedVariantCount; index++) {
            JsonNode variant = templates.getFirst().deepCopy();
            if (index > 0) {
                varyReportedAttributes(variant, index);
            }
            variants.add(variant);
        }
        return variants;
    }

    private static void varyReportedAttributes(JsonNode payload, int variantIndex) {
        for (JsonNode report : payload.path("deviceReport")) {
            JsonNode reported = report.path("deviceTwinDocument")
                    .path("attributes")
                    .path("reported");
            if (reported instanceof ObjectNode attributes) {
                varyObject(attributes, variantIndex);
            }
        }
    }

    private static void varyObject(ObjectNode object, int variantIndex) {
        List<String> fieldNames = new ArrayList<>();
        object.fieldNames().forEachRemaining(fieldNames::add);

        for (String fieldName : fieldNames) {
            JsonNode value = object.get(fieldName);
            if (value instanceof ObjectNode nestedObject) {
                varyObject(nestedObject, variantIndex);
            } else if (value instanceof ArrayNode nestedArray) {
                varyArray(nestedArray, variantIndex);
            } else if (value.isIntegralNumber()) {
                long current = value.longValue();
                long step = Math.max(1L, Math.round(Math.abs(current) * 0.03));
                long varied = variantIndex % 2 == 1
                        ? current + step
                        : Math.max(0L, current == 0L ? variantIndex : current - step);
                object.put(fieldName, varied);
            } else if (value.isFloatingPointNumber()) {
                BigDecimal current = value.decimalValue();
                BigDecimal step = current.abs().multiply(new BigDecimal("0.03"));
                if (step.compareTo(new BigDecimal("0.01")) < 0) {
                    step = new BigDecimal("0.01");
                }
                BigDecimal varied = variantIndex % 2 == 1
                        ? current.add(step)
                        : current.subtract(step).max(BigDecimal.ZERO);
                object.put(fieldName, varied.stripTrailingZeros());
            } else if (value.isBoolean() && variantIndex % 2 == 1) {
                object.put(fieldName, !value.booleanValue());
            } else if (value.isTextual()) {
                object.put(fieldName, varyText(fieldName, value.textValue(), variantIndex));
            }
        }
    }

    private static void varyArray(ArrayNode array, int variantIndex) {
        for (JsonNode value : array) {
            if (value instanceof ObjectNode object) {
                varyObject(object, variantIndex);
            } else if (value instanceof ArrayNode nestedArray) {
                varyArray(nestedArray, variantIndex);
            }
        }
    }

    private static String varyText(String fieldName, String value, int variantIndex) {
        if (fieldName.equalsIgnoreCase("last_seen")) {
            return Instant.now()
                    .minus(variantIndex, ChronoUnit.SECONDS)
                    .toString();
        }

        String normalized = value.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "on" -> variantIndex % 2 == 1 ? "off" : "on";
            case "off" -> variantIndex % 2 == 1 ? "on" : "off";
            case "open" -> variantIndex % 2 == 1 ? "close" : "open";
            case "close", "closed" -> variantIndex % 2 == 1 ? "open" : value;
            case "high" -> variantIndex % 2 == 1 ? "medium" : "low";
            case "low" -> variantIndex % 2 == 1 ? "medium" : "high";
            default -> value;
        };
    }

    private static JsonNode bindDeviceId(JsonNode template, String deviceId) {
        JsonNode copy = template.deepCopy();
        for (JsonNode report : copy.path("deviceReport")) {
            if (!(report instanceof ObjectNode reportObject)) {
                throw new IllegalArgumentException("Each deviceReport item must be a JSON object");
            }
            reportObject.put("deviceId", deviceId);
        }
        return copy;
    }

    private record Device(String id, String deviceTypeId) {
    }

    private record DeviceMessages(List<byte[]> messages, AtomicInteger nextIndex) {
        private DeviceMessages(List<byte[]> messages) {
            this(messages, new AtomicInteger());
        }
    }
}
