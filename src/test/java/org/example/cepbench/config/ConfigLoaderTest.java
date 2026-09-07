package org.example.cepbench.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String VALID = """
            {
              "platform": {
                "apiBaseUrl": "https://api.sandpod.ir/srv/iotsand",
                "tokenEnvironmentVariable": "CEPBENCH_API_TOKEN",
                "concurrency": 4
              },
              "run": {
                "runId": "drools-001",
                "locationCode": "1000",
                "scenarios": { "singleSelect": 45, "multiSelectTwoDevice": 10, "windowing": 45 },
                "maxRulesPerDevice": 1,
                "alarmTypeCode": "9001",
                "template": { "temperatureThreshold": 40, "windowAverageThreshold": 100, "windowLength": 3 }
              }
            }
            """;

    private static ConfigLoader loaderWith(Map<String, String> environment) {
        return new ConfigLoader(environment::get);
    }

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void loadsAValidConfigAndResolvesTheTokenFromTheEnvironment() {
        BenchmarkConfig config = loaderWith(Map.of("CEPBENCH_API_TOKEN", "secret-token"))
                .parse(json(VALID));

        assertEquals("https://api.sandpod.ir/srv/iotsand", config.platform().apiBaseUrl());
        assertEquals("secret-token", config.platform().token());
        assertEquals("api.sandpod.ir", config.platform().authority());
        assertEquals(100, config.run().totalRules());
        assertEquals(1, config.run().maxRulesPerDevice());
        assertEquals(3, config.run().template().windowLength());
    }

    @Test
    void defaultsApplyWhenOptionalTimeoutsAreOmitted() {
        BenchmarkConfig config = loaderWith(Map.of("CEPBENCH_API_TOKEN", "t")).parse(json(VALID));

        assertEquals(10, config.platform().connectTimeout().toSeconds());
        assertEquals(60, config.platform().requestTimeout().toSeconds());
        assertEquals(".cepbench", config.manifestDirectory().toString());
    }

    @Test
    void missingTokenIsRejectedWithTheVariableName() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> loaderWith(Map.of()).parse(json(VALID)));

        assertTrue(failure.getMessage().contains("CEPBENCH_API_TOKEN"), failure.getMessage());
    }

    @Test
    void aTokenThatAlreadyCarriesTheBearerPrefixIsRejected() {
        // Pasting the whole Authorization header value is an easy mistake and produces a confusing
        // 401 much later, once resources have already been created.
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> loaderWith(Map.of("CEPBENCH_API_TOKEN", "Bearer abc")).parse(json(VALID)));

        assertTrue(failure.getMessage().contains("without the"), failure.getMessage());
    }

    @Test
    void runIdMustBeSafeForResourceNames() {
        String raw = VALID.replace("\"drools-001\"", "\"Drools 001!\"");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> loaderWith(Map.of("CEPBENCH_API_TOKEN", "t")).parse(json(raw)));

        assertTrue(failure.getMessage().contains("runId"), failure.getMessage());
    }

    @Test
    void aRunWithNoRulesIsRejected() {
        String raw = VALID.replace("\"singleSelect\": 45", "\"singleSelect\": 0")
                .replace("\"multiSelectTwoDevice\": 10", "\"multiSelectTwoDevice\": 0")
                .replace("\"windowing\": 45", "\"windowing\": 0");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> loaderWith(Map.of("CEPBENCH_API_TOKEN", "t")).parse(json(raw)));

        assertTrue(failure.getMessage().contains("at least one rule"), failure.getMessage());
    }

    @Test
    void everyProblemIsReportedAtOnce() {
        String raw = """
                {
                  "platform": { "apiBaseUrl": "not-a-url", "tokenEnvironmentVariable": "" },
                  "run": { "runId": "", "locationCode": "", "scenarios": {}, "alarmTypeCode": "" }
                }
                """;

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> loaderWith(Map.of()).parse(json(raw)));

        // Fixing a config one error per run is miserable; the loader collects them.
        String message = failure.getMessage();
        assertTrue(message.contains("apiBaseUrl"), message);
        assertTrue(message.contains("tokenEnvironmentVariable"), message);
        assertTrue(message.contains("runId"), message);
        assertTrue(message.contains("locationCode"), message);
        assertTrue(message.contains("alarmTypeCode"), message);
    }

    @Test
    void trailingSlashOnTheBaseUrlIsNormalised() {
        String raw = VALID.replace("/srv/iotsand", "/srv/iotsand/");

        BenchmarkConfig config = loaderWith(Map.of("CEPBENCH_API_TOKEN", "t")).parse(json(raw));

        // Paths are appended as "/rules", so a trailing slash would produce "//rules".
        assertEquals("https://api.sandpod.ir/srv/iotsand", config.platform().apiBaseUrl());
    }
}
