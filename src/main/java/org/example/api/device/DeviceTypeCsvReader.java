package org.example.api.device;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class DeviceTypeCsvReader {
    private static final String DEVICE_TYPE_ID = "deviceTypeId";
    private static final String DEVICE_TYPE_NAME = "deviceTypeName";
    private static final String NUMBER_OF_DEVICE = "numberOfDevice";

    private DeviceTypeCsvReader() {
    }

    public static List<DeviceTypeCreationPlan> read(Path filePath) throws IOException {
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreHeaderCase(true)
                .setTrim(true)
                .build();

        try (Reader reader = Files.newBufferedReader(filePath);
             CSVParser parser = format.parse(reader)) {
            validateHeaders(parser, filePath);

            List<DeviceTypeCreationPlan> plans = new ArrayList<>();
            for (CSVRecord record : parser) {
                if (record.size() == 0 || Arrays.stream(record.values()).allMatch(String::isBlank)) {
                    continue;
                }
                plans.add(toCreationPlan(record, filePath));
            }
            return List.copyOf(plans);
        }
    }

    private static void validateHeaders(CSVParser parser, Path filePath) {
        List<String> headers = parser.getHeaderNames();
        if (!containsIgnoreCase(headers, DEVICE_TYPE_ID)
                || !containsIgnoreCase(headers, DEVICE_TYPE_NAME)
                || !containsIgnoreCase(headers, NUMBER_OF_DEVICE)) {
            throw new IllegalArgumentException(
                    "CSV " + filePath + " must contain headers: "
                            + DEVICE_TYPE_ID + ", " + DEVICE_TYPE_NAME + ", " + NUMBER_OF_DEVICE);
        }
    }

    private static DeviceTypeCreationPlan toCreationPlan(CSVRecord record, Path filePath) {
        String deviceTypeId = record.get(DEVICE_TYPE_ID);
        String deviceTypeName = record.get(DEVICE_TYPE_NAME);
        String countValue = record.get(NUMBER_OF_DEVICE);

        try {
            return new DeviceTypeCreationPlan(
                    deviceTypeId,
                    deviceTypeName,
                    Integer.parseInt(countValue));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Invalid numberOfDevice at " + filePath + " line " + record.getRecordNumber()
                            + ": " + countValue,
                    e);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid device type at " + filePath + " line " + record.getRecordNumber()
                            + ": " + e.getMessage(),
                    e);
        }
    }

    private static boolean containsIgnoreCase(List<String> values, String expected) {
        return values.stream().anyMatch(expected::equalsIgnoreCase);
    }
}
