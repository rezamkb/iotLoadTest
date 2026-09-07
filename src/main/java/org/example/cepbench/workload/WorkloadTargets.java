package org.example.cepbench.workload;

import org.example.cepbench.config.BenchmarkConfig;
import org.example.cepbench.edge.EdgeReportPayloadFactory.DeviceReading;
import org.example.cepbench.manifest.ManifestState;
import org.example.cepbench.manifest.ResourceKind;
import org.example.cepbench.manifest.ResourceRef;
import org.example.cepbench.model.RuleScenario;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Turns the manifest into the set of devices the generator drives, and decides what a matching and a
 * non-matching reading looks like for each one.
 *
 * <p>The mapping comes from the journal, never from re-running the planner. A rule's {@code when}
 * clause is fixed on the platform at creation time, while the planner's device allocation shifts
 * whenever the config changes, so recomputing it would generate readings for the wrong devices.
 *
 * <p>One rule and its device are held back as the sentinel and excluded from the background load.
 * Without that reservation, background traffic could fire the sentinel rule by coincidence and the
 * probe would report health that was never measured.
 */
public final class WorkloadTargets {

    private final List<Target> background;
    private final Target sentinel;

    private WorkloadTargets(List<Target> background, Target sentinel) {
        this.background = List.copyOf(background);
        this.sentinel = sentinel;
    }

    /**
     * @param state manifest state, which must already carry the rule-to-device mapping
     */
    public static WorkloadTargets from(ManifestState state, BenchmarkConfig.RuleTemplateSpec template) {
        List<Target> all = new ArrayList<>();

        for (ResourceRef rule : state.of(ResourceKind.RULE).values()) {
            RuleScenario scenario = scenarioOf(rule);
            if (scenario == null || rule.deviceIds().isEmpty()) {
                // Written before rules recorded their devices. Driving it would mean guessing which
                // devices it selects on, so it is left out and reported instead.
                continue;
            }
            List<String> deviceIds = rule.deviceIds();
            for (int position = 0; position < deviceIds.size(); position++) {
                all.add(new Target(deviceIds.get(position), rule.id(), rule.key(), scenario, position, template));
            }
        }

        if (all.isEmpty()) {
            return new WorkloadTargets(List.of(), null);
        }

        // Prefer a single-select rule: one device, one attribute, fires on the first matching event.
        // A windowing rule needs several events before it can fire and would make the sentinel's
        // latency meaningless.
        Target chosen = all.stream()
                .filter(target -> target.scenario() == RuleScenario.SINGLE_SELECT)
                .findFirst()
                .orElse(all.get(0));

        List<Target> remaining = new ArrayList<>();
        for (Target target : all) {
            // Exclude every device of the sentinel's rule, not just the chosen one: any of them
            // could fire it.
            if (!target.ruleId().equals(chosen.ruleId())) {
                remaining.add(target);
            }
        }
        return new WorkloadTargets(remaining, chosen);
    }

    /** Devices driven by the background load, in a stable order. */
    public List<Target> background() {
        return background;
    }

    /** Empty only when the manifest holds no usable rule. */
    public Optional<Target> sentinel() {
        return Optional.ofNullable(sentinel);
    }

    public boolean isEmpty() {
        return background.isEmpty() && sentinel == null;
    }

    private static RuleScenario scenarioOf(ResourceRef rule) {
        if (rule.scenario() == null) {
            return null;
        }
        for (RuleScenario scenario : RuleScenario.values()) {
            if (scenario.name().equals(rule.scenario())) {
                return scenario;
            }
        }
        return null;
    }

    /**
     * One device, in the role its rule gives it.
     *
     * @param position index of this device within its rule's device list, which is what distinguishes
     *                 the temperature device from the occupancy device in a two-device rule
     */
    public record Target(String deviceId,
                         String ruleId,
                         String ruleKey,
                         RuleScenario scenario,
                         int position,
                         BenchmarkConfig.RuleTemplateSpec template) {

        /** True when this device is the {@code occ == true} branch of a two-device rule. */
        public boolean isOccupancyBranch() {
            return scenario == RuleScenario.MULTI_SELECT_TWO_DEVICE && position == 1;
        }

        /**
         * A reading that satisfies the rule's condition, so the rule fires and, for stateless rules,
         * the retained facts in its entry point are retracted.
         */
        public DeviceReading matching() {
            if (isOccupancyBranch()) {
                return DeviceReading.occupancy(deviceId, true);
            }
            int threshold = (scenario == RuleScenario.WINDOWING)
                    ? template.windowAverageThreshold()
                    : template.temperatureThreshold();
            return DeviceReading.temperature(deviceId, threshold + 1.0d);
        }

        /**
         * A valid reading that does not satisfy the condition. This is the interesting case: nothing
         * retracts a non-matching fact from a stateless entry point, so this is what accumulates.
         */
        public DeviceReading nonMatching() {
            return isOccupancyBranch()
                    ? DeviceReading.occupancy(deviceId, false)
                    : DeviceReading.temperature(deviceId, 0.0d);
        }
    }
}
