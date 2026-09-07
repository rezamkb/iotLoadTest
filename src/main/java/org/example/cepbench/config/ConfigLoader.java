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
    /**
     * Shape of an environment variable name. A JWT fails it on the dots alone, which is the whole
     * point: the field names where the token lives, it never holds one.
     */
    private static final Pattern ENV_VARIABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
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
        return load(configFile, true);
    }

    /**
     * @param requireToken false for commands that make no network call, so {@code plan} and
     *                     {@code export} still work on a machine that has no credentials. The
     *                     config must still name a token variable; only its value is optional.
     */
    public BenchmarkConfig load(Path configFile, boolean requireToken) throws IOException {
        if (!Files.isRegularFile(configFile)) {
            throw new IllegalArgumentException("Config file not found: " + configFile.toAbsolutePath());
        }
        JsonNode root = objectMapper.readTree(Files.readString(configFile));
        return parse(root, requireToken);
    }

    public BenchmarkConfig parse(JsonNode root) {
        return parse(root, true);
    }

    public BenchmarkConfig parse(JsonNode root, boolean requireToken) {
        List<String> problems = new ArrayList<>();

        JsonNode platformNode = required(root, "platform", problems);
        JsonNode runNode = required(root, "run", problems);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(describe(problems));
        }

        BenchmarkConfig.PlatformTarget platform = parsePlatform(platformNode, problems, requireToken);
        BenchmarkConfig.RunSpec run = parseRun(runNode, problems);
        Path manifestDirectory = Path.of(text(root, "manifestDirectory", ".cepbench"));

        // Both sections are optional so plan, provision, activate and cleanup keep working on a
        // config that has not been extended for the workload yet. The commands that need them say so.
        // Parsed before the problem check so a bad edge section is reported alongside a bad platform
        // one rather than only after the first is fixed.
        BenchmarkConfig.EdgeTarget edge =
                root.has("edge") ? parseEdge(root.get("edge"), problems) : null;
        BenchmarkConfig.WorkloadSpec workload =
                root.has("workload") ? parseWorkload(root.get("workload"), problems) : null;

        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(describe(problems));
        }
        return new BenchmarkConfig(platform, run, manifestDirectory, edge, workload);
    }

    private BenchmarkConfig.PlatformTarget parsePlatform(JsonNode node, List<String> problems, boolean requireToken) {
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

        // Two ways to supply the token. "token" is the blunt one: convenient, and the reason
        // .gitignore excludes *.local.json. "tokenEnvironmentVariable" keeps the file committable.
        // When both are present the inline value wins, because it is the more specific choice.
        String inlineToken = text(node, "token", "").trim();
        String tokenVariable = text(node, "tokenEnvironmentVariable", "").trim();
        String token = "";

        if (!inlineToken.isEmpty()) {
            token = inlineToken;
            if (token.regionMatches(true, 0, "Bearer ", 0, 7)) {
                problems.add("platform.token must hold the raw token, without the \"Bearer \" prefix");
            }
        } else if (tokenVariable.isEmpty()) {
            problems.add("platform needs either \"token\" (the raw bearer token, for a config file you "
                    + "do not commit) or \"tokenEnvironmentVariable\" (the name of an environment "
                    + "variable holding it)");
        } else if (!ENV_VARIABLE_NAME.matcher(tokenVariable).matches()) {
            // Almost always a token pasted into the field that should name where to find one. The
            // value is deliberately not echoed: it is very likely a live credential, and repeating
            // it would put it in the console, the CI log and the bug report.
            problems.add("platform.tokenEnvironmentVariable must be the NAME of an environment variable, "
                    + "such as CEPBENCH_API_TOKEN, not the token itself. Either put the token in that "
                    + "variable, or use the \"token\" field instead if you want it in the config file.");
        } else {
            String resolved = environment.apply(tokenVariable);
            if (resolved == null || resolved.isBlank()) {
                // Offline commands leave the token empty rather than failing, so the plan and the
                // CSV export remain usable without credentials.
                if (requireToken) {
                    problems.add("environment variable " + tokenVariable + " is not set; it must hold the bearer token without the \"Bearer \" prefix");
                }
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

    /**
     * The edge is the operator's, not the run's. Nothing here is created or deleted by the
     * benchmark, so every field is a value that must already exist on the platform.
     */
    private BenchmarkConfig.EdgeTarget parseEdge(JsonNode node, List<String> problems) {
        if (!node.isObject()) {
            problems.add("\"edge\" must be an object");
            return null;
        }

        String edgeId = requireText(node, "edge.edgeId", text(node, "edgeId", ""), problems);
        String clientId = requireText(node, "edge.clientId", text(node, "clientId", ""), problems);
        String brokerUrl = requireText(node, "edge.brokerUrl", text(node, "brokerUrl", ""), problems);
        String publishTopic = requireText(node, "edge.publishTopic", text(node, "publishTopic", ""), problems);

        // Only needed by a downlink subscriber, which the benchmark does not run today. Recorded so
        // the value travels with the config rather than being rediscovered later.
        String alternativeClientId = text(node, "alternativeClientId", "").trim();

        if (!brokerUrl.isEmpty() && !brokerUrl.startsWith("tcp://") && !brokerUrl.startsWith("ssl://")) {
            problems.add("edge.brokerUrl must start with tcp:// or ssl://");
        }

        int qos = nonNegative(node, "qos", 0, problems);
        if (qos > 2) {
            problems.add("edge.qos must be 0, 1 or 2");
        }

        return new BenchmarkConfig.EdgeTarget(
                edgeId,
                clientId,
                alternativeClientId,
                brokerUrl,
                publishTopic,
                resolveSecret(node, "username", "usernameEnvironmentVariable", problems),
                resolveSecret(node, "password", "passwordEnvironmentVariable", problems),
                qos,
                positive(node, "maxInflight", 10_000, problems));
    }

    private BenchmarkConfig.WorkloadSpec parseWorkload(JsonNode node, List<String> problems) {
        if (!node.isObject()) {
            problems.add("\"workload\" must be an object");
            return null;
        }

        double matchingFraction = node.path("matchingFraction").asDouble(0.0d);
        if (matchingFraction < 0.0d || matchingFraction > 1.0d) {
            problems.add("workload.matchingFraction must be between 0.0 and 1.0");
        }

        return new BenchmarkConfig.WorkloadSpec(
                positive(node, "eventsPerSecond", 100, problems),
                Duration.ofSeconds(positive(node, "durationSeconds", 600, problems)),
                positive(node, "reportsPerPublish", 1, problems),
                matchingFraction,
                Duration.ofSeconds(positive(node, "sentinelIntervalSeconds", 30, problems)),
                Duration.ofSeconds(positive(node, "sentinelTimeoutSeconds", 15, problems)),
                Duration.ofSeconds(positive(node, "progressIntervalSeconds", 10, problems)),
                node.path("stopOnSentinelFailure").asBoolean(true));
    }

    /**
     * Reads a value either inline or from the environment, the same choice the API token offers.
     * Absent means absent: an empty broker credential is normal, since the usual setup authenticates
     * on the MQTT client id alone.
     */
    private String resolveSecret(JsonNode node, String inlineField, String variableField, List<String> problems) {
        String inline = text(node, inlineField, "");
        if (!inline.isEmpty()) {
            return inline;
        }
        String variable = text(node, variableField, "").trim();
        if (variable.isEmpty()) {
            return "";
        }
        if (!ENV_VARIABLE_NAME.matcher(variable).matches()) {
            problems.add("edge." + variableField + " must be the NAME of an environment variable");
            return "";
        }
        String resolved = environment.apply(variable);
        return (resolved == null) ? "" : resolved.trim();
    }

    private static String requireText(JsonNode node, String label, String value, List<String> problems) {
        String trimmed = (value == null) ? "" : value.trim();
        if (trimmed.isEmpty()) {
            problems.add(label + " is required");
        }
        return trimmed;
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
