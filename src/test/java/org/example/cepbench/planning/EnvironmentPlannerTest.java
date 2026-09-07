package org.example.cepbench.planning;

import org.example.cepbench.config.BenchmarkConfig;
import org.example.cepbench.model.EnvironmentPlan;
import org.example.cepbench.model.RuleScenario;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvironmentPlannerTest {

    private final EnvironmentPlanner planner = new EnvironmentPlanner();

    private static BenchmarkConfig.RunSpec run(int single, int multi, int window, int maxRulesPerDevice) {
        return new BenchmarkConfig.RunSpec(
                "bench-001",
                "1000",
                new BenchmarkConfig.ScenarioCounts(single, multi, window),
                maxRulesPerDevice,
                "9001",
                new BenchmarkConfig.RuleTemplateSpec(40, 100, 3));
    }

    @Test
    void oneDevicePerRuleSlotWhenSharingIsDisabled() {
        EnvironmentPlan plan = planner.plan(run(2, 1, 1, 1));

        assertEquals(4, plan.ruleCount());
        // 2 single (1 device each) + 1 multi (2 devices) + 1 window (1 device)
        assertEquals(5, plan.deviceCount());
        assertEquals(5, distinctDeviceKeys(plan).size(), "no device may be reused when the cap is 1");
    }

    @Test
    void sharingReusesDevicesUpToTheCap() {
        EnvironmentPlan plan = planner.plan(run(2, 1, 1, 3));

        // 5 device slots spread over a cap of 3 uses per device: two devices suffice.
        assertEquals(4, plan.ruleCount());
        assertEquals(2, plan.deviceCount());
    }

    @Test
    void multiSelectRulesNeverSelectTheSameDeviceTwice() {
        // A high cap is the case where a naive allocator would hand the same device to both slots.
        EnvironmentPlan plan = planner.plan(run(0, 5, 0, 100));

        List<EnvironmentPlan.PlannedRule> rules = plan.rules();
        assertEquals(5, rules.size());
        for (EnvironmentPlan.PlannedRule rule : rules) {
            assertEquals(2, rule.deviceKeys().size());
            assertNotEquals(rule.deviceKeys().get(0), rule.deviceKeys().get(1),
                    "a two-device rule must select on two different devices");
        }
    }

    @Test
    void everyRuleReferencesDevicesThatArePlanned() {
        EnvironmentPlan plan = planner.plan(run(10, 5, 10, 4));

        Set<String> planned = new HashSet<>();
        plan.devices().forEach(device -> planned.add(device.key()));
        for (EnvironmentPlan.PlannedRule rule : plan.rules()) {
            assertTrue(planned.containsAll(rule.deviceKeys()),
                    "rule " + rule.key() + " references an unplanned device");
        }
    }

    @Test
    void planIsDeterministic() {
        EnvironmentPlan first = planner.plan(run(7, 3, 5, 2));
        EnvironmentPlan second = planner.plan(run(7, 3, 5, 2));

        // Resuming an interrupted provision depends on this: the same config must produce the same
        // keys and names, or the journal cannot be matched back to the plan.
        assertEquals(first, second);
    }

    @Test
    void scenarioCountsAreHonoured() {
        EnvironmentPlan plan = planner.plan(run(45, 10, 45, 1));

        assertEquals(100, plan.ruleCount());
        assertEquals(45, plan.ruleCount(RuleScenario.SINGLE_SELECT));
        assertEquals(10, plan.ruleCount(RuleScenario.MULTI_SELECT_TWO_DEVICE));
        assertEquals(45, plan.ruleCount(RuleScenario.WINDOWING));
    }

    @Test
    void namesAreScopedToTheRunAndAcceptedByThePlatformPattern() {
        EnvironmentPlan plan = planner.plan(run(1, 1, 1, 1));

        // ENTITY_NAME_PATTERN in the platform is [a-zA-Z0-9:_-]+ with a 128 character limit.
        for (EnvironmentPlan.PlannedDevice device : plan.devices()) {
            assertTrue(device.name().startsWith("cepbench-bench-001-"), device.name());
            assertTrue(device.name().matches("[a-zA-Z0-9:_-]{1,128}"), device.name());
        }
        for (EnvironmentPlan.PlannedRule rule : plan.rules()) {
            assertTrue(rule.name().matches("[a-zA-Z0-9:_-]{1,128}"), rule.name());
        }
        assertTrue(plan.deviceTypeName().matches("[a-zA-Z0-9:_-]{1,128}"));
        assertTrue(plan.alarmTypeName().matches("[a-zA-Z0-9:_-]{1,128}"));
    }

    private static Set<String> distinctDeviceKeys(EnvironmentPlan plan) {
        Set<String> keys = new HashSet<>();
        plan.rules().forEach(rule -> keys.addAll(rule.deviceKeys()));
        return keys;
    }
}
