package org.example.cepbench.workload;

import org.example.cepbench.client.PlatformApiClient;
import org.example.cepbench.config.BenchmarkConfig;
import org.example.cepbench.edge.EdgeMqttPublisher;
import org.example.cepbench.edge.EdgeReportPayloadFactory;
import org.example.cepbench.edge.EdgeReportPayloadFactory.DeviceReading;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Drives device reports through the edge at a fixed rate and watches whether the engine is still
 * firing.
 *
 * <p>Publishing runs on the calling thread and the sentinel on its own, because they answer
 * different questions and must not block each other: the sentinel waits seconds for an alarm, and
 * doing that inline would collapse the send rate it is supposed to be measuring alongside.
 *
 * <p>The default workload is entirely non-matching traffic. That is deliberate. A stateless fact is
 * only retracted when its rule fires, so non-matching events are exactly what accumulates in the
 * entry points, and accumulation is the mechanism behind the failure being reproduced.
 */
public final class LoadRunner {

    private final BenchmarkConfig.WorkloadSpec workload;
    private final WorkloadTargets targets;
    private final EdgeMqttPublisher publisher;
    private final EdgeReportPayloadFactory payloads = new EdgeReportPayloadFactory();
    private final SentinelProbe sentinel;
    private final Consumer<String> progress;
    private final Random random = new Random();

    private final AtomicBoolean stopping = new AtomicBoolean();

    public LoadRunner(BenchmarkConfig.WorkloadSpec workload,
                      WorkloadTargets targets,
                      EdgeMqttPublisher publisher,
                      PlatformApiClient client,
                      Consumer<String> progress) {
        this.workload = workload;
        this.targets = targets;
        this.publisher = publisher;
        this.progress = progress;
        this.sentinel = targets.sentinel()
                .map(target -> new SentinelProbe(client, publisher, payloads, target, workload.sentinelTimeout()))
                .orElse(null);
    }

    public Result run() {
        if (targets.background().isEmpty()) {
            throw new IllegalStateException(
                    "No devices to drive. Provision and activate the environment first, and make sure "
                            + "the manifest records each rule's devices.");
        }

        List<SentinelSample> sentinelSamples = new ArrayList<>();
        ExecutorService sentinelExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "cepbench-sentinel");
            thread.setDaemon(true);
            return thread;
        });

        Instant startedAt = Instant.now();
        Instant deadline = startedAt.plus(workload.duration());
        long publishesPerSecond = publishesPerSecond();
        long intervalNanos = TimeUnit.SECONDS.toNanos(1) / Math.max(1L, publishesPerSecond);

        long eventsSent = 0L;
        long publishFailures = 0L;
        int cursor = 0;

        Instant nextSentinel = startedAt.plus(workload.sentinelInterval());
        Instant nextProgress = startedAt.plus(workload.progressInterval());
        Future<SentinelProbe.Result> inFlightSentinel = null;
        // When the in-flight probe was submitted, which is within milliseconds of when it published.
        // A probe that times out finishes sentinelTimeout later, so completion time would place the
        // failure that much too late.
        Instant inFlightSince = null;

        // Absolute pacing rather than sleep-per-iteration: a fixed sleep accumulates the cost of
        // every publish and the achieved rate drifts below the requested one.
        long nextPublishNanos = System.nanoTime();

        try {
            while (Instant.now().isBefore(deadline) && !stopping.get()) {
                List<DeviceReading> batch = new ArrayList<>(workload.reportsPerPublish());
                for (int index = 0; index < workload.reportsPerPublish(); index++) {
                    WorkloadTargets.Target target = targets.background().get(cursor);
                    cursor = (cursor + 1) % targets.background().size();
                    batch.add(shouldMatch() ? target.matching() : target.nonMatching());
                }

                byte[] payload = (batch.size() == 1)
                        ? payloads.single(requestId(eventsSent), batch.get(0))
                        : payloads.batch(requestId(eventsSent), batch);

                if (publisher.publish(payload)) {
                    eventsSent += batch.size();
                } else {
                    publishFailures++;
                }

                Instant now = Instant.now();

                if (sentinel != null && !now.isBefore(nextSentinel) && inFlightSentinel == null) {
                    inFlightSentinel = sentinelExecutor.submit(sentinel::probe);
                    inFlightSince = now;
                    nextSentinel = now.plus(workload.sentinelInterval());
                }
                if (inFlightSentinel != null && inFlightSentinel.isDone()) {
                    SentinelProbe.Result result = takeResult(inFlightSentinel);
                    Duration probedAt = Duration.between(startedAt, inFlightSince);
                    inFlightSentinel = null;
                    inFlightSince = null;
                    sentinelSamples.add(new SentinelSample(probedAt, result));
                    progress.accept("sentinel: " + result.describe());
                    if (result.isFiringFailure() && workload.stopOnSentinelFailure()) {
                        progress.accept("Stopping: the sentinel rule stopped firing. Capture a thread "
                                + "dump and /diagnostics/drools from the CEP node before restarting it.");
                        stopping.set(true);
                    }
                }
                if (!now.isBefore(nextProgress)) {
                    long elapsedSeconds = Math.max(1L, Duration.between(startedAt, now).toSeconds());
                    progress.accept("sent=%d rate=%d/s publishFailures=%d"
                            .formatted(eventsSent, eventsSent / elapsedSeconds, publishFailures));
                    nextProgress = now.plus(workload.progressInterval());
                }

                nextPublishNanos += intervalNanos;
                long sleepNanos = nextPublishNanos - System.nanoTime();
                if (sleepNanos > 0) {
                    TimeUnit.NANOSECONDS.sleep(sleepNanos);
                } else {
                    // Behind schedule: publishing is the bottleneck, not the pacer. Reset the clock
                    // so the loop does not spin trying to catch up a debt it cannot repay.
                    nextPublishNanos = System.nanoTime();
                }
            }

            if (inFlightSentinel != null) {
                sentinelSamples.add(new SentinelSample(
                        Duration.between(startedAt, inFlightSince), takeResult(inFlightSentinel)));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            sentinelExecutor.shutdownNow();
        }

        Duration elapsed = Duration.between(startedAt, Instant.now());
        return new Result(
                eventsSent,
                publisher.publishedCount(),
                publishFailures,
                elapsed,
                elapsed.toSeconds() == 0L ? eventsSent : eventsSent / elapsed.toSeconds(),
                targets.background().size(),
                sentinel == null ? null : sentinel.ruleId(),
                List.copyOf(sentinelSamples),
                stopping.get());
    }

    public void stop() {
        stopping.set(true);
    }

    private long publishesPerSecond() {
        return Math.max(1L, workload.eventsPerSecond() / Math.max(1, workload.reportsPerPublish()));
    }

    private boolean shouldMatch() {
        return workload.matchingFraction() > 0.0d && random.nextDouble() < workload.matchingFraction();
    }

    private static String requestId(long sequence) {
        return "cepbench-" + sequence;
    }

    private static SentinelProbe.Result takeResult(Future<SentinelProbe.Result> future) {
        try {
            return future.get();
        } catch (Exception e) {
            return SentinelProbe.Result.error("Sentinel did not complete: " + e.getMessage());
        }
    }

    /**
     * One sentinel probe and how far into the run it was published.
     *
     * <p>The offset is measured, not derived from the probe's position multiplied by
     * {@code sentinelIntervalSeconds}. Those two disagree whenever a probe could not run — an
     * unreachable API costs a slot without costing a firing — and again whenever the publish loop
     * drifts, so an index-derived figure names the wrong moment for the thing the run exists to
     * timestamp.
     */
    public record SentinelSample(Duration at, SentinelProbe.Result result) {
    }

    public record Result(long eventsSent,
                         long publishesAccepted,
                         long publishFailures,
                         Duration elapsed,
                         long achievedEventsPerSecond,
                         int devicesDriven,
                         String sentinelRuleId,
                         List<SentinelSample> sentinelSamples,
                         boolean stoppedEarly) {

        /** The first probe that ran and did not fire: when the engine stopped. */
        public java.util.Optional<SentinelSample> firstFiringFailure() {
            return sentinelSamples.stream()
                    .filter(sample -> sample.result().isFiringFailure())
                    .findFirst();
        }

        public long sentinelsFired() {
            return sentinelSamples.stream().filter(sample -> sample.result().fired()).count();
        }

        /**
         * Probes that never got to ask the question, because the baseline read or the publish
         * failed. They are not evidence either way and must not be read as failures to fire.
         */
        public long sentinelsCouldNotRun() {
            return sentinelSamples.stream().filter(sample -> sample.result().couldNotRun()).count();
        }
    }
}
