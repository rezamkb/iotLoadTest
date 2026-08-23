package org.example.api.device;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class CsvReader {
    private CsvReader() {
    }

    public static List<String> readDeviceIds(Path filePath) throws IOException {
        Set<String> deviceIds = new LinkedHashSet<>();
        try (BufferedReader reader = Files.newBufferedReader(filePath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",", 5);
                if (parts.length == 5) {
                    String deviceId = stripQuotes(parts[0].trim());
                    if (!deviceId.isBlank() && !deviceId.equalsIgnoreCase("id")) {
                        deviceIds.add(deviceId);
                    }
                }
            }
        }
        return List.copyOf(deviceIds);
    }


    private static String stripQuotes(String s) {
        if (s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
