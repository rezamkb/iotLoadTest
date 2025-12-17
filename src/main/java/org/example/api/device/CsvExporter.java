package org.example.api.device;

import org.example.api.device.dto.CreateDeviceResponse;

import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

import com.opencsv.CSVWriter;
public class CsvExporter {
    public static void exportCreateDeviceResponses(List<CreateDeviceResponse> responses, String filePath) throws IOException {
        try (CSVWriter writer = new CSVWriter(new FileWriter(filePath, true))) {
            for (CreateDeviceResponse dr : responses) {
                writer.writeNext(new String[] {
                        dr.getId(),
                        dr.getName(),
                        dr.getClientId(),
                        dr.getAlternativeClientId(),
                        dr.getDeviceTypeId()
                });
            }
        }
    }

}
