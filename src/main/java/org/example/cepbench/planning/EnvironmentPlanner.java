package org.example.cepbench.planning;

import org.example.cepbench.config.BenchmarkConfig;
import org.example.cepbench.model.EnvironmentPlan;
import org.example.cepbench.model.RuleScenario;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns scenario counts into a concrete set of devices and rules.
 *
 * <p>Device allocation is the interesting part. {@code maxRulesPerDevice} decides how many rules may
 * select on the same device, which decides how far one incoming event fans out inside CEP: the
 * stateless path inserts a copy of the event into the entry point of every rule attached to that
 * device. At 1 the mapping is one to one and a rule-count sweep measures rule scaling. Raised, the
 * same event count produces that many times more retained facts, which is what grows working memory.
 *
 * <p>Devices are filled in order and never shared within a single rule, so a two-device rule always
 * selects on two genuinely different devices.
 */
public final class EnvironmentPlanner {

    public EnvironmentPlan plan(BenchmarkConfig.RunSpec run) {
        String prefix = EnvironmentPlan.prefix(run.runId());

        List<EnvironmentPlan.PlannedDevice> devices = new ArrayList<>();
        List<Integer> remainingCapacity = new ArrayList<>();
        List<EnvironmentPlan.PlannedRule> rules = new ArrayList<>();

        // Cursor past devices that are already at capacity. Without it, allocating a large run
        // rescans the whole device list for every rule.
        int firstAvailable = 0;

        for (ScenarioRequest request : requests(run.scenarios())) {
            for (int ordinal = 1; ordinal <= request.count(); ordinal++) {
                int needed = request.scenario().devicesPerRule();
                List<Integer> chosen = new ArrayList<>(needed);

                for (int probe = firstAvailable; probe < devices.size() && chosen.size() < needed; probe++) {
                    if (remainingCapacity.get(probe) > 0) {
                        chosen.add(probe);
                    }
                }
                while (chosen.size() < needed) {
                    devices.add(new EnvironmentPlan.PlannedDevice(
                            deviceKey(devices.size() + 1),
                            "%s-%s".formatted(prefix, deviceKey(devices.size() + 1))));
                    remainingCapacity.add(run.maxRulesPerDevice());
                    chosen.add(devices.size() - 1);
                }

                List<String> deviceKeys = new ArrayList<>(needed);
                for (int index : chosen) {
                    remainingCapacity.set(index, remainingCapacity.get(index) - 1);
                    deviceKeys.add(devices.get(index).key());
                }
                while (firstAvailable < devices.size() && remainingCapacity.get(firstAvailable) == 0) {
                    firstAvailable++;
                }

                String ruleKey = ruleKey(request.scenario(), ordinal);
                rules.add(new EnvironmentPlan.PlannedRule(
                        ruleKey,
                        "%s-%s".formatted(prefix, ruleKey),
                        request.scenario(),
                        deviceKeys));
            }
        }

        return new EnvironmentPlan(
                run.runId(),
                prefix + "-type",
                prefix + "-alarm",
                devices,
                rules);
    }

    private static List<ScenarioRequest> requests(BenchmarkConfig.ScenarioCounts counts) {
        return List.of(
                new ScenarioRequest(RuleScenario.SINGLE_SELECT, counts.singleSelect()),
                new ScenarioRequest(RuleScenario.MULTI_SELECT_TWO_DEVICE, counts.multiSelectTwoDevice()),
                new ScenarioRequest(RuleScenario.WINDOWING, counts.windowing()));
    }

    private static String deviceKey(int ordinal) {
        return "d%04d".formatted(ordinal);
    }

    private static String ruleKey(RuleScenario scenario, int ordinal) {
        return "r-%s-%04d".formatted(scenario.slug().toLowerCase(Locale.ROOT), ordinal);
    }

    private record ScenarioRequest(RuleScenario scenario, int count) {
    }
}
