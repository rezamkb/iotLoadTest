package org.example.api.device;

import org.example.mqtt.ClientInfo;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class CsvReader {




    public static List<String> readDeviceIds(Path filePath) throws Exception {
        List<String> list = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(filePath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",", 5);
                if (parts.length == 5) {
                    list.add(stripQuotes(parts[0].trim()));
                }
            }
        }
        return list;
    }


    private static String stripQuotes(String s) {
        if (s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }



}
