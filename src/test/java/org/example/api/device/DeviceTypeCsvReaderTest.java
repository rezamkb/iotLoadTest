package org.example.api.device;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeviceTypeCsvReaderTest {
    @TempDir
    Path tempDir;

    @Test
    void readsDeviceTypeAndRequestedCount() throws IOException {
        Path csv = writeCsv("""
                deviceTypeId,deviceTypeName,numberOfDevice
                1buay488cna,m_airQuality---nemoodar,3
                """);

        List<DeviceTypeCreationPlan> plans = DeviceTypeCsvReader.read(csv);

        assertEquals(List.of(new DeviceTypeCreationPlan(
                "1buay488cna",
                "m_airQuality---nemoodar",
                3)), plans);
    }

    @Test
    void rejectsInvalidCount() throws IOException {
        Path csv = writeCsv("""
                deviceTypeId,deviceTypeName,numberOfDevice
                1buay488cna,m_airQuality---nemoodar,three
                """);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> DeviceTypeCsvReader.read(csv));

        assertTrue(error.getMessage().contains("Invalid numberOfDevice"));
    }

    private Path writeCsv(String content) throws IOException {
        Path csv = tempDir.resolve("deviceType.csv");
        Files.writeString(csv, content);
        return csv;
    }
}
