package org.example.cepbench.workload;

import org.example.cepbench.config.BenchmarkConfig;
import org.example.cepbench.manifest.ManifestJournal;
import org.example.cepbench.manifest.ManifestState;
import org.example.cepbench.manifest.ResourceKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkloadTargetsTest {

    private static final String API = "https://api.example.test/srv/iot";
    private static final BenchmarkConfig.RuleTemplateSpec TEMPLATE =
            new BenchmarkConfig.RuleTemplateSpec(40, 100, 3);

    @Test
    void theSentinelRuleAndAllOfItsDevicesAreHeldOutOfTheBackgroundLoad(@TempDir Path dir) throws IOException {
        ManifestState state = journal(dir, journal -> {
            journal.recordRuleCreated("r-single-0001", "ruleS", "single-one", "SINGLE_SELECT", List.of("devA"));
            journal.recordRuleCreated("r-multi-0001", "ruleM", "multi-one", "MULTI_SELECT_TWO_DEVICE",
                    List.of("devB", "devC"));
        });

        WorkloadTargets targets = WorkloadTargets.from(state, TEMPLATE);

        assertEquals("ruleS", targets.sentinel().orElseThrow().ruleId(),
                "a single-select rule is preferred: it fires on the first matching event");
        // Both devices of the sentinel rule would be excluded; here it has one.
        assertEquals(List.of("devB", "devC"),
                targets.background().stream().map(WorkloadTargets.Target::deviceId).toList());
    }

    @Test
    void theSecondDeviceOfATwoDeviceRuleDrivesOccupancyAndTheFirstDrivesTemperature(@TempDir Path dir)
            throws IOException {
        ManifestState state = journal(dir, journal ->
                journal.recordRuleCreated("r-multi-0001", "ruleM", "multi-one", "MULTI_SELECT_TWO_DEVICE",
                        List.of("devB", "devC")));

        List<WorkloadTargets.Target> background = WorkloadTargets.from(state, TEMPLATE).background();

        WorkloadTargets.Target temperatureBranch = background.get(0);
        WorkloadTargets.Target occupancyBranch = background.get(1);

        assertFalse(temperatureBranch.isOccupancyBranch());
        assertTrue(occupancyBranch.isOccupancyBranch());

        // Matching means "satisfies the rule", which differs per branch: temp above the threshold on
        // one, occ true on the other.
        assertEquals(41.0d, temperatureBranch.matching().temperature());
        assertTrue(occupancyBranch.matching().occupied());
        assertFalse(occupancyBranch.nonMatching().occupied());
        assertEquals(0.0d, temperatureBranch.nonMatching().temperature());
    }

    @Test
    void aWindowingRuleMatchesAgainstTheWindowAverageThreshold(@TempDir Path dir) throws IOException {
        ManifestState state = journal(dir, journal ->
                journal.recordRuleCreated("r-window-0001", "ruleW", "window-one", "WINDOWING", List.of("devD")));

        WorkloadTargets.Target target = WorkloadTargets.from(state, TEMPLATE).sentinel().orElseThrow();

        // 100 is the window average threshold, not the 40 used by single-select.
        assertEquals(101.0d, target.matching().temperature());
    }

    @Test
    void rulesRecordedWithoutTheirDevicesAreSkippedRatherThanGuessedAt(@TempDir Path dir) throws IOException {
        ManifestState state = journal(dir, journal ->
                journal.recordCreated(ResourceKind.RULE, "r-single-0001", "ruleS", "single-one"));

        WorkloadTargets targets = WorkloadTargets.from(state, TEMPLATE);

        assertTrue(targets.isEmpty(),
                "driving a rule whose devices are unknown would publish for the wrong devices");
    }

    private interface Seed {
        void accept(ManifestJournal journal);
    }

    private static ManifestState journal(Path dir, Seed seed) throws IOException {
        try (ManifestJournal journal = ManifestJournal.open(dir, "run-w", API)) {
            seed.accept(journal);
        }
        return ManifestJournal.readState(dir, "run-w");
    }
}
