package org.example.cepbench.model;

import java.util.List;

/**
 * Everything one run intends to create, computed before a single API call is made.
 *
 * <p>The plan is pure and deterministic: the same config always yields the same keys and names. That
 * is what makes {@code provision} resumable, because a partially provisioned run can be matched back
 * to the plan by key and only the missing pieces created.
 */
public record EnvironmentPlan(
        String runId,
        String deviceTypeName,
        String alarmTypeName,
        List<PlannedDevice> devices,
        List<PlannedRule> rules
) {

    public EnvironmentPlan {
        devices = List.copyOf(devices);
        rules = List.copyOf(rules);
    }

    /** Name prefix shared by every resource this run owns; also what makes leftovers recognisable. */
    public static String prefix(String runId) {
        return "cepbench-" + runId;
    }

    public int deviceCount() {
        return devices.size();
    }

    public int ruleCount() {
        return rules.size();
    }

    public long ruleCount(RuleScenario scenario) {
        return rules.stream().filter(rule -> rule.scenario() == scenario).count();
    }

    /**
     * @param key  stable identity within the run, used to correlate plan and manifest
     * @param name the name sent to the platform
     */
    public record PlannedDevice(String key, String name) {
    }

    /**
     * @param deviceKeys the plan keys of the devices this rule selects on, in template order. Always
     *                   distinct, and always exactly {@code scenario.devicesPerRule()} of them.
     */
    public record PlannedRule(String key, String name, RuleScenario scenario, List<String> deviceKeys) {
        public PlannedRule {
            deviceKeys = List.copyOf(deviceKeys);
        }
    }
}
