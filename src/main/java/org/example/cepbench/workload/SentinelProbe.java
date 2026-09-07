package org.example.cepbench.workload;

import org.example.cepbench.client.PlatformApiClient;
import org.example.cepbench.edge.EdgeMqttPublisher;
import org.example.cepbench.edge.EdgeReportPayloadFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Answers the one question the load generator cannot: is Drools still firing?
 *
 * <p>Publish rate proves only that the producer is alive. The production failure being reproduced
 * looks exactly like a healthy run from the outside: events are accepted, inserted into the session,
 * and never evaluated. So the probe sends a reading that must match its rule and then waits for the
 * platform's alarm count for that rule to advance. A count that stops advancing while publishing
 * continues is the failure.
 *
 * <p>The sentinel rule is excluded from the background load, so an advance can only have come from
 * this probe.
 */
public final class SentinelProbe {

    private final PlatformApiClient client;
    private final EdgeMqttPublisher publisher;
    private final EdgeReportPayloadFactory payloads;
    private final WorkloadTargets.Target target;
    private final Duration timeout;
    private final Duration pollInterval;
    private final AtomicLong sequence = new AtomicLong();

    public SentinelProbe(PlatformApiClient client,
                         EdgeMqttPublisher publisher,
                         EdgeReportPayloadFactory payloads,
                         WorkloadTargets.Target target,
                         Duration timeout) {
        this.client = client;
        this.publisher = publisher;
        this.payloads = payloads;
        this.target = target;
        this.timeout = timeout;
        // Frequent enough to measure latency usefully, slow enough not to hammer the alarms endpoint
        // for the whole timeout window.
        this.pollInterval = Duration.ofMillis(500);
    }

    public String ruleId() {
        return target.ruleId();
    }

    public String deviceId() {
        return target.deviceId();
    }

    /**
     * Publishes one matching reading and waits for the rule's alarm count to rise.
     *
     * <p>The baseline is read immediately before publishing rather than cached between probes: an
     * alarm raised by a previous probe that landed late would otherwise be counted as this one
     * succeeding.
     */
    public Result probe() {
        long baseline;
        try {
            baseline = client.countAlarmsForRule(target.ruleId());
        } catch (RuntimeException e) {
            return Result.error("Could not read the alarm baseline: " + e.getMessage());
        }

        long id = sequence.incrementAndGet();
        byte[] payload = payloads.single(
                "cepbench-sentinel-" + id, target.matching());

        Instant sentAt = Instant.now();
        if (!publisher.publish(payload)) {
            return Result.error("Sentinel publish failed; the MQTT connection is down");
        }

        Instant deadline = sentAt.plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            try {
                Thread.sleep(pollInterval.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Result.error("Interrupted while waiting for the sentinel alarm");
            }
            long current;
            try {
                current = client.countAlarmsForRule(target.ruleId());
            } catch (RuntimeException e) {
                // A transient API failure is not evidence that firing stopped; keep waiting.
                continue;
            }
            if (current > baseline) {
                return Result.fired(Duration.between(sentAt, Instant.now()), baseline, current);
            }
        }
        return Result.timedOut(timeout, baseline);
    }

    /**
     * @param fired          whether the alarm count advanced within the timeout
     * @param latency        time from publish to the first observed advance; null when it did not
     * @param error          set when the probe itself could not run, which is not the same as a
     *                       failure to fire and must not be reported as one
     */
    public record Result(boolean fired, Duration latency, long baseline, long observed, String error) {

        static Result fired(Duration latency, long baseline, long observed) {
            return new Result(true, latency, baseline, observed, null);
        }

        static Result timedOut(Duration timeout, long baseline) {
            return new Result(false, null, baseline, baseline,
                    "No alarm within " + timeout.toSeconds() + "s; the rule did not fire");
        }

        static Result error(String message) {
            return new Result(false, null, -1L, -1L, message);
        }

        /** True when the probe ran and the rule did not fire: the reproduction being hunted. */
        public boolean isFiringFailure() {
            return !fired && baseline >= 0L;
        }

        public String describe() {
            if (fired) {
                return "fired in " + latency.toMillis() + "ms (alarms " + baseline + " -> " + observed + ")";
            }
            return error;
        }
    }
}
