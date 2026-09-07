package org.example.cepbench.config;

import java.nio.file.Path;
import java.time.Duration;

/**
 * The whole configuration for one benchmark run, already validated.
 *
 * <p>A config that names {@code tokenEnvironmentVariable} carries no credential and is safe to
 * commit. A config that uses the inline {@code token} field is not, which is why {@code *.local.json}
 * is excluded by {@code .gitignore}.
 */
public record BenchmarkConfig(
        PlatformTarget platform,
        RunSpec run,
        Path manifestDirectory,
        /** Null until an "edge" section is configured; required by attach, detach and run. */
        EdgeTarget edge,
        /** Null until a "workload" section is configured; required by run. */
        WorkloadSpec workload
) {

    public EdgeTarget requireEdge(String command) {
        if (edge == null) {
            throw new IllegalStateException(
                    command + " needs an \"edge\" section in the config: edgeId, clientId, "
                            + "alternativeClientId, brokerUrl and publishTopic");
        }
        return edge;
    }

    public WorkloadSpec requireWorkload(String command) {
        if (workload == null) {
            throw new IllegalStateException(command + " needs a \"workload\" section in the config");
        }
        return workload;
    }

    public record PlatformTarget(
            String apiBaseUrl,
            /** Bearer token, resolved from the environment. Never log or serialise this. */
            String token,
            Duration connectTimeout,
            Duration requestTimeout,
            Duration activationTimeout,
            Duration activationPollInterval,
            /** Parallel in-flight API calls during provision, activate and cleanup. */
            int concurrency
    ) {
        /** Host of the API, for reporting which environment a command is about to act on. */
        public String authority() {
            return java.net.URI.create(apiBaseUrl).getAuthority();
        }
    }

    public record RunSpec(
            String runId,
            /** Value of the {@code code} tag on every device and rule; the platform maps it to a location. */
            String locationCode,
            ScenarioCounts scenarios,
            /**
             * Upper bound on how many rules may share one device.
             *
             * <p>This is the single most important knob in the benchmark and it pulls in two
             * directions. At 1 every rule gets its own devices, so an event reaches exactly one
             * entry point and the numbers describe rule-count scaling cleanly. Raised, one incoming
             * event fans out into that many entry points and that many retained facts, which is what
             * grows working memory. Vary it deliberately, never incidentally.
             */
            int maxRulesPerDevice,
            String alarmTypeCode,
            RuleTemplateSpec template
    ) {
        public int totalRules() {
            return scenarios.total();
        }
    }

    public record ScenarioCounts(int singleSelect, int multiSelectTwoDevice, int windowing) {
        public int total() {
            return singleSelect + multiSelectTwoDevice + windowing;
        }
    }

    public record RuleTemplateSpec(int temperatureThreshold, int windowAverageThreshold, int windowLength) {
    }

    /**
     * An edge that already exists and belongs to the operator, not to the run.
     *
     * <p>The benchmark attaches its devices to it and detaches them again, and never creates or
     * deletes the edge itself. {@code clientId} is the uplink identity used as the MQTT client
     * identifier when publishing; {@code alternativeClientId} is the downlink identity a subscriber
     * would use for {@code dvcout/<edgeId>/<clientId>/edge/twin/#}. They are not interchangeable.
     */
    public record EdgeTarget(
            String edgeId,
            String clientId,
            String alternativeClientId,
            String brokerUrl,
            /** Uplink topic, e.g. {@code dvcasy/edge/twin/report}. Environment specific. */
            String publishTopic,
            /** Empty when the broker authenticates on client id alone, which is the usual case. */
            String username,
            String password,
            int qos,
            int maxInflight
    ) {
    }

    public record WorkloadSpec(
            /** Device reports per second across all devices, not MQTT publishes per second. */
            int eventsPerSecond,
            Duration duration,
            /**
             * Device reports packed into one publish via the array form of {@code deviceReport}.
             * At 1, one publish carries one event and MQTT throughput equals event throughput.
             */
            int reportsPerPublish,
            /**
             * Fraction of generated events that satisfy their rule's condition, 0.0 to 1.0.
             *
             * <p>Zero is the interesting default: non-matching facts are never retracted from a
             * stateless entry point, so pure non-matching traffic is what grows working memory.
             */
            double matchingFraction,
            Duration sentinelInterval,
            Duration sentinelTimeout,
            Duration progressInterval,
            /** Stop the run as soon as a sentinel fails, so the failure state is preserved. */
            boolean stopOnSentinelFailure
    ) {
    }
}
