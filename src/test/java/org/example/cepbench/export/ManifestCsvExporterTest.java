package org.example.cepbench.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.cepbench.config.BenchmarkConfig;
import org.example.cepbench.config.ConfigLoader;
import org.example.cepbench.manifest.ManifestJournal;
import org.example.cepbench.manifest.ManifestState;
import org.example.cepbench.manifest.ResourceKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManifestCsvExporterTest {

    private static final String CONFIG_JSON = """
            {
              "platform": {
                "apiBaseUrl": "https://api.sandpod.ir/srv/iotsand",
                "tokenEnvironmentVariable": "CEPBENCH_TEST_TOKEN"
              },
              "run": {
                "runId": "demo-001",
                "locationCode": "1000",
                "scenarios": { "singleSelect": 1, "multiSelectTwoDevice": 1, "windowing": 0 },
                "maxRulesPerDevice": 1,
                "alarmTypeCode": "9001",
                "template": { "temperatureThreshold": 40, "windowAverageThreshold": 100, "windowLength": 3 }
              }
            }
            """;

    @Test
    void writesRuleRowsWithTheirDevicesAndRenderedWhenClause(@TempDir Path dir) throws Exception {
        seedJournal(dir);

        List<Path> written = new ManifestCsvExporter()
                .export(ManifestJournal.readState(dir, "demo-001"), config(), dir);

        assertEquals(List.of(dir.resolve("demo-001-rules.csv"), dir.resolve("demo-001-devices.csv")), written);

        List<String> rows = Files.readAllLines(written.get(0), StandardCharsets.UTF_8);
        assertEquals(3, rows.size(), "header plus two rules");
        assertTrue(rows.get(0).startsWith("\"ruleKey\",\"ruleId\",\"ruleName\""));

        String multi = rows.stream().filter(row -> row.contains("r-multi-0001")).findFirst().orElseThrow();
        assertTrue(multi.contains("MULTI_SELECT_TWO_DEVICE"), multi);
        // Both device ids, joined by the list separator rather than a comma.
        assertTrue(multi.contains("\"faoksqg88ao;2dsdyq5p8p6\""), multi);
        // The clause is re-rendered from the recorded ids, so it names the devices actually used.
        assertTrue(multi.contains("faoksqg88ao/twin/update/reported"), multi);
        assertTrue(multi.contains("2dsdyq5p8p6/twin/update/reported"), multi);

        String single = rows.stream().filter(row -> row.contains("r-single-0001")).findFirst().orElseThrow();
        assertTrue(single.contains("SINGLE_SELECT"), single);
        assertTrue(single.contains("\"false\""), "single select is not stateful: " + single);
    }

    @Test
    void invertsTheMappingSoEachDeviceListsTheRulesThatSelectOnIt(@TempDir Path dir) throws Exception {
        seedJournal(dir);

        new ManifestCsvExporter().export(ManifestJournal.readState(dir, "demo-001"), config(), dir);
        List<String> rows = Files.readAllLines(dir.resolve("demo-001-devices.csv"), StandardCharsets.UTF_8);

        assertEquals(4, rows.size(), "header plus three devices");

        String shared = rows.stream().filter(row -> row.contains("gqcrarftoxs")).findFirst().orElseThrow();
        assertTrue(shared.contains("r-single-0001"), shared);

        String multiDevice = rows.stream().filter(row -> row.contains("2dsdyq5p8p6")).findFirst().orElseThrow();
        assertTrue(multiDevice.contains("r-multi-0001"), multiDevice);
    }

    @Test
    void leavesMappingColumnsBlankForRulesRecordedBeforeTheMappingExisted(@TempDir Path dir) throws Exception {
        try (ManifestJournal journal = ManifestJournal.open(dir, "demo-001", "https://api.sandpod.ir/srv/iotsand")) {
            // The old four-field form, as written by an earlier version of the tool.
            journal.recordCreated(ResourceKind.RULE, "r-single-0001", "whl0o1mkh2u", "cepbench-demo-001-r-single-0001");
        }

        ManifestState state = ManifestJournal.readState(dir, "demo-001");
        assertFalse(state.of(ResourceKind.RULE).get("r-single-0001").hasRuleDetail());

        new ManifestCsvExporter().export(state, config(), dir);
        List<String> rows = Files.readAllLines(dir.resolve("demo-001-rules.csv"), StandardCharsets.UTF_8);

        String rule = rows.get(1);
        // Blank rather than a guess: the planner's current allocation may no longer match.
        assertTrue(rule.endsWith("\"\",\"\",\"0\",\"\",\"\""), rule);
    }

    private static void seedJournal(Path dir) throws IOException {
        try (ManifestJournal journal = ManifestJournal.open(dir, "demo-001", "https://api.sandpod.ir/srv/iotsand")) {
            journal.recordCreated(ResourceKind.DEVICE, "d0001", "gqcrarftoxs", "cepbench-demo-001-d0001");
            journal.recordCreated(ResourceKind.DEVICE, "d0002", "faoksqg88ao", "cepbench-demo-001-d0002");
            journal.recordCreated(ResourceKind.DEVICE, "d0003", "2dsdyq5p8p6", "cepbench-demo-001-d0003");
            journal.recordRuleCreated("r-single-0001", "whl0o1mkh2u", "cepbench-demo-001-r-single-0001",
                    "SINGLE_SELECT", List.of("gqcrarftoxs"));
            journal.recordRuleCreated("r-multi-0001", "ypmbccdeeqr", "cepbench-demo-001-r-multi-0001",
                    "MULTI_SELECT_TWO_DEVICE", List.of("faoksqg88ao", "2dsdyq5p8p6"));
        }
    }

    private static BenchmarkConfig config() throws IOException {
        // requireToken=false: the export path must not need credentials.
        return new ConfigLoader(name -> null)
                .parse(new ObjectMapper().readTree(CONFIG_JSON), false);
    }
}
