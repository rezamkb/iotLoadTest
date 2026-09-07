package org.example.cepbench.export;

import com.opencsv.CSVWriter;
import org.example.cepbench.config.BenchmarkConfig;
import org.example.cepbench.manifest.ManifestState;
import org.example.cepbench.manifest.ResourceKind;
import org.example.cepbench.manifest.ResourceRef;
import org.example.cepbench.model.RuleScenario;
import org.example.cepbench.template.RuleTemplates;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes the run's rules and devices as two CSV files.
 *
 * <p>Entirely offline: it folds the journal and nothing else. No API token and no network call, so
 * it still works after the sandbox environment has been cleaned up, or on a machine that never had
 * credentials, which is the point of keeping the journal in the first place.
 *
 * <p>Activation state is deliberately absent. It is live platform state that changes without the
 * journal knowing, so reporting it belongs to {@code status}; baking a stale copy into a CSV would
 * invite someone to trust it.
 */
public final class ManifestCsvExporter {

    private static final String[] RULE_HEADER = {
            "ruleKey", "ruleId", "ruleName", "scenario", "stateful", "deviceCount", "deviceIds", "when"
    };

    private static final String[] DEVICE_HEADER = {
            "deviceKey", "deviceId", "deviceName", "ruleCount", "ruleKeys", "ruleIds"
    };

    /** Separator inside the multi-valued columns; commas would need quoting and read badly. */
    private static final String LIST_SEPARATOR = ";";

    private final RuleTemplates templates = new RuleTemplates();

    /**
     * @return the files written, rules first
     */
    public List<Path> export(ManifestState state,
                             BenchmarkConfig config,
                             Path directory) throws IOException {
        Files.createDirectories(directory);
        String runId = config.run().runId();

        Path rulesFile = directory.resolve(runId + "-rules.csv");
        Path devicesFile = directory.resolve(runId + "-devices.csv");

        writeRules(state, config, rulesFile);
        writeDevices(state, devicesFile);

        return List.of(rulesFile, devicesFile);
    }

    private void writeRules(ManifestState state, BenchmarkConfig config, Path file) throws IOException {
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
             CSVWriter csv = new CSVWriter(writer)) {
            csv.writeNext(RULE_HEADER);
            for (ResourceRef rule : sortedByKey(state.of(ResourceKind.RULE))) {
                RuleScenario scenario = scenarioOf(rule);
                csv.writeNext(new String[]{
                        rule.key(),
                        rule.id(),
                        rule.name(),
                        scenario == null ? "" : scenario.name(),
                        scenario == null ? "" : Boolean.toString(scenario.isStateful()),
                        Integer.toString(rule.deviceIds().size()),
                        String.join(LIST_SEPARATOR, rule.deviceIds()),
                        renderWhen(scenario, rule, config)
                });
            }
        }
    }

    private void writeDevices(ManifestState state, Path file) throws IOException {
        // Invert the rule-to-device mapping recorded in the journal. A device that no rule selects
        // on still gets a row with a zero count, because that is worth noticing.
        Map<String, Set<ResourceRef>> rulesByDeviceId = new LinkedHashMap<>();
        for (ResourceRef rule : sortedByKey(state.of(ResourceKind.RULE))) {
            for (String deviceId : rule.deviceIds()) {
                rulesByDeviceId.computeIfAbsent(deviceId, id -> new LinkedHashSet<>()).add(rule);
            }
        }

        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
             CSVWriter csv = new CSVWriter(writer)) {
            csv.writeNext(DEVICE_HEADER);
            for (ResourceRef device : sortedByKey(state.of(ResourceKind.DEVICE))) {
                Set<ResourceRef> rules = rulesByDeviceId.getOrDefault(device.id(), Set.of());
                csv.writeNext(new String[]{
                        device.key(),
                        device.id(),
                        device.name(),
                        Integer.toString(rules.size()),
                        rules.stream().map(ResourceRef::key).reduce((a, b) -> a + LIST_SEPARATOR + b).orElse(""),
                        rules.stream().map(ResourceRef::id).reduce((a, b) -> a + LIST_SEPARATOR + b).orElse("")
                });
            }
        }
    }

    /**
     * Re-renders the clause from the recorded scenario and device ids, so the column shows what the
     * platform was actually asked to compile. Blank for journals written before rules recorded their
     * devices, rather than a guess.
     */
    private String renderWhen(RuleScenario scenario, ResourceRef rule, BenchmarkConfig config) {
        if (scenario == null || rule.deviceIds().size() != scenario.devicesPerRule()) {
            return "";
        }
        try {
            return templates.renderWhen(scenario, rule.deviceIds(), config.run().template());
        } catch (RuntimeException e) {
            return "";
        }
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

    private static List<ResourceRef> sortedByKey(Map<String, ResourceRef> refs) {
        List<ResourceRef> sorted = new ArrayList<>(refs.values());
        sorted.sort(Comparator.comparing(ResourceRef::key));
        return sorted;
    }
}
