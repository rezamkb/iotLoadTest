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
        Path manifestDirectory
) {

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
}
