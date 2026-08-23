package org.example.api.device;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CsvReaderTest {
    @TempDir
    Path tempDir;

    @Test
    void skipsBlankRowsAndDuplicateDeviceIds() throws IOException {
        Path csv = tempDir.resolve("devices.csv");
        Files.writeString(csv, """
                "first-id","first-name","client-1","alt-1","type-1"
                ,,,,
                "second-id","second-name","client-2","alt-2","type-2"
                "first-id","first-name","client-1","alt-1","type-1"
                """);

        assertEquals(
                List.of("first-id", "second-id"),
                CsvReader.readDeviceIds(csv));
    }
}
