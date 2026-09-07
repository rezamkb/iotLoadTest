package org.example.cepbench.environment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Uniform result of one control-plane command.
 *
 * <p>Every command reports the same three things: what it observed, what looked wrong but did not
 * stop it, and what actually failed. Keeping the shape uniform means the CLI has one printer and a
 * later phase can assert on {@link #ok()} without knowing which command ran.
 */
public record CommandReport(
        String command,
        String runId,
        Map<String, Object> facts,
        List<String> warnings,
        List<String> failures
) {

    public CommandReport {
        // Not Map.copyOf: that returns an unordered map, and the facts are meant to read in pipeline
        // order when printed.
        facts = Collections.unmodifiableMap(new LinkedHashMap<>(facts));
        warnings = List.copyOf(warnings);
        failures = List.copyOf(failures);
    }

    /** False when at least one operation failed; the CLI turns this into a non-zero exit code. */
    public boolean ok() {
        return failures.isEmpty();
    }

    public static Builder builder(String command, String runId) {
        return new Builder(command, runId);
    }

    public static final class Builder {
        private final String command;
        private final String runId;
        private final Map<String, Object> facts = new LinkedHashMap<>();
        private final List<String> warnings = new ArrayList<>();
        private final List<String> failures = new ArrayList<>();

        private Builder(String command, String runId) {
            this.command = command;
            this.runId = runId;
        }

        public Builder fact(String key, Object value) {
            facts.put(key, value);
            return this;
        }

        // warn and fail are called from worker threads during concurrent provisioning; fact is not.
        public synchronized Builder warn(String message) {
            warnings.add(message);
            return this;
        }

        public synchronized Builder fail(String message) {
            failures.add(message);
            return this;
        }

        public synchronized int failureCount() {
            return failures.size();
        }

        public CommandReport build() {
            return new CommandReport(command, runId, facts, warnings, failures);
        }
    }
}
