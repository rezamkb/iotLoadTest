package org.example.cepbench.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reads a benchmark config file, resolves the API token from the environment, and refuses anything
 * that would produce a confusing run.
 *
 * <p>Validation is strict on purpose. Every value here ends up either in a resource name on a shared
 * sandbox or in a rule the platform has to compile, and a typo that survives to the API produces
 * hundreds of misnamed resources that the manifest then has to clean up.
 */
public final class ConfigLoader {

    /** Run ids become part of every resource name, so keep them short and shell safe. */
    private static final Pattern RUN_ID = Pattern.compile("[a-z0-9][a-z0-9-]{2,31}");
    private static final Pattern LOCATION_CODE = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final int MAX_CONCURRENCY = 32;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final java.util.function.Function<String, String> environment;

    public ConfigLoader() {
        this(System::getenv);
    }

    /** Test seam: lets a test supply an environment without touching the real process environment. */
    public ConfigLoader(java.util.function.Function<String, String> environment) {
        this.environment = environment;
    }

    public BenchmarkConfig load(Path configFile) throws IOException {
        if (!Files.isRegularFile(configFile)) {
            throw new IllegalArgumentException("Config file not found: " + configFile.toAbsolutePath());
        }
        JsonNode root = objectMapper.readTree(Files.readString(configFile));
        return parse(root);
    }

    public BenchmarkConfig parse(JsonNode root) {
        List<String> problems = new ArrayList<>();

        JsonNode platformNode = required(root, "platform", problems);
        JsonNode runNode = required(root, "run", problems);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(describe(problems));
        }

        BenchmarkConfig.PlatformTarget platform = parsePlatform(platformNode, problems);
        BenchmarkConfig.RunSpec run = parseRun(runNode, problems);
        Path manifestDirectory = Path.of(text(root, "manifestDirectory", ".cepbench"));

        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(describe(problems));
        }
        return new BenchmarkConfig(platform, run, manifestDirectory);
    }

    private BenchmarkConfig.PlatformTarget parsePlatform(JsonNode node, List<String> problems) {
        String apiBaseUrl = text(node, "apiBaseUrl", "").trim();
        if (apiBaseUrl.isEmpty()) {
            problems.add("platform.apiBaseUrl is required");
        } else {
            if (apiBaseUrl.endsWith("/")) {
                apiBaseUrl = apiBaseUrl.substring(0, apiBaseUrl.length() - 1);
            }
            try {
                URI uri = URI.create(apiBaseUrl);
                if (uri.getScheme() == null || uri.getAuthority() == null) {
                    problems.add("platform.apiBaseUrl must be absolute, for example https://host/srv/iotsand");
                } else if (!uri.getScheme().equals("http") && !uri.getScheme().equals("https")) {
                    problems.add("platform.apiBaseUrl must be http or https");
                }
            } catch (IllegalArgumentException e) {
                problems.add("platform.apiBaseUrl is not a valid URL: " + e.getMessage());
            }
        }

        String tokenVariable = text(node, "tokenEnvironmentVariable", "").trim();
        String token = "";
        if (tokenVariable.isEmpty()) {
            problems.add("platform.tokenEnvironmentVariable is required; tokens are never read from the config file");
        } else {
            String resolved = environment.apply(tokenVariable);
            if (resolved == null || resolved.isBlank()) {
                problems.add("environment variable " + tokenVariable + " is not set; it must hold the bearer token without the \"Bearer \" prefix");
            } else {
                token = resolved.trim();
                if (token.regionMatches(true, 0, "Bearer ", 0, 7)) {
                    problems.add("environment variable " + tokenVariable + " must hold the raw token, without the \"Bearer \" prefix");
                }
            }
        }

        int concurrency = integer(node, "concurrency", 4);
        if (concurrency < 1 || concurrency > MAX_CONCURRENCY) {
            problems.add("platform.concurrency must be between 1 and " + MAX_CONCURRENCY);
        }

        return new BenchmarkConfig.PlatformTarget(
                apiBaseUrl,
                token,
                Duration.ofSeconds(positive(node, "connectTimeoutSeconds", 10, problems)),
                Duration.ofSeconds(positive(node, "requestTimeoutSeconds", 60, problems)),
                Duration.ofSeconds(positive(node, "activationTimeoutSeconds", 120, problems)),
                Duration.ofMillis(positive(node, "activationPollIntervalMillis", 1000, problems)),
                concurrency);
    }

    private BenchmarkConfig.RunSpec parseRun(JsonNode node, List<String> problems) {
        String runId = text(node, "runId", "").trim();
        if (!RUN_ID.matcher(runId).matches()) {
            problems.add("run.runId must match " + RUN_ID.pattern()
                    + "; it is embedded in every resource name and in the manifest filename");
        }

        String locationCode = text(node, "locationCode", "").trim();
        if (!LOCATION_CODE.matcher(locationCode).matches()) {
            problems.add("run.locationCode must match " + LOCATION_CODE.pattern()
                    + "; it becomes the \"code\" tag that maps devices and rules onto the same CEP node");
        }

        JsonNode scenariosNode = node.path("scenarios");
        BenchmarkConfig.ScenarioCounts scenarios = new BenchmarkConfig.ScenarioCounts(
                nonNegative(scenariosNode, "singleSelect", 0, problems),
                nonNegative(scenariosNode, "multiSelectTwoDevice", 0, problems),
                nonNegative(scenariosNode, "windowing", 0, problems));
        if (scenarios.total() < 1) {
            problems.add("run.scenarios must ask for at least one rule");
        }

        int maxRulesPerDevice = integer(node, "maxRulesPerDevice", 1);
        if (maxRulesPerDevice < 1) {
            problems.add("run.maxRulesPerDevice must be at least 1");
        }

        String alarmTypeCode = text(node, "alarmTypeCode", "").trim();
        if (alarmTypeCode.isEmpty()) {
            problems.add("run.alarmTypeCode is required");
        }

        JsonNode templateNode = node.path("template");
        BenchmarkConfig.RuleTemplateSpec template = new BenchmarkConfig.RuleTemplateSpec(
                integer(templateNode, "temperatureThreshold", 40),
                integer(templateNode, "windowAverageThreshold", 100),
                positive(templateNode, "windowLength", 3, problems));

        return new BenchmarkConfig.RunSpec(
                runId, locationCode, scenarios, maxRulesPerDevice, alarmTypeCode, template);
    }

    private static JsonNode required(JsonNode parent, String field, List<String> problems) {
        JsonNode node = parent.get(field);
        if (node == null || !node.isObject()) {
            problems.add("\"" + field + "\" object is required");
            // path() yields a MissingNode, so callers can keep reading and collect further problems.
            return parent.path(field);
        }
        return node;
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? fallback : value.asText(fallback);
    }

    private static int integer(JsonNode node, String field, int fallback) {
        JsonNode value = node.get(field);
        return (value == null || !value.isNumber()) ? fallback : value.asInt(fallback);
    }

    private static int positive(JsonNode node, String field, int fallback, List<String> problems) {
        int value = integer(node, field, fallback);
        if (value <= 0) {
            problems.add(field + " must be greater than zero");
            return fallback;
        }
        return value;
    }

    private static int nonNegative(JsonNode node, String field, int fallback, List<String> problems) {
        int value = integer(node, field, fallback);
        if (value < 0) {
            problems.add(field + " must not be negative");
            return fallback;
        }
        return value;
    }

    private static String describe(List<String> problems) {
        return "Invalid benchmark configuration:" + System.lineSeparator()
                + String.join(System.lineSeparator(), problems.stream().map(p -> "  - " + p).toList());
    }
}
