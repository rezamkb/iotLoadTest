package org.example.cepbench;

import org.example.cepbench.config.BenchmarkConfig;
import org.example.cepbench.config.ConfigLoader;
import org.example.cepbench.environment.CommandReport;
import org.example.cepbench.environment.EnvironmentManager;
import org.example.cepbench.export.ManifestCsvExporter;
import org.example.cepbench.manifest.ManifestJournal;
import org.example.cepbench.manifest.ManifestState;
import org.example.cepbench.manifest.ResourceKind;
import org.example.cepbench.model.EnvironmentPlan;
import org.example.cepbench.model.RuleScenario;
import org.example.cepbench.planning.EnvironmentPlanner;
import org.example.cepbench.template.RuleTemplates;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Control plane for a CEP benchmark environment.
 *
 * <pre>
 *   cepbench &lt;command&gt; &lt;config.json&gt;
 * </pre>
 *
 * <p>{@code plan} and {@code export} make no network call and work without a token; every other
 * command talks to the platform, and {@code provision}, {@code activate}, {@code deactivate} and
 * {@code cleanup} change it. There is no arming step: whatever the config points at is what gets
 * modified, so run {@code plan} first to see the target and the resource counts.
 */
public final class CepBenchMain {

    /** Commands that touch neither the platform nor the journal's write side. */
    private static final Set<String> OFFLINE = Set.of("plan", "export");
    private static final Set<String> COMMANDS =
            Set.of("plan", "provision", "status", "activate", "deactivate", "cleanup", "export",
                    "attach", "detach", "run", "reconcile");

    public static void main(String[] args) throws Exception {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) throws Exception {
        if (args.length != 2) {
            err.println(usage());
            return 2;
        }
        String command = args[0].toLowerCase(Locale.ROOT);
        if (!COMMANDS.contains(command)) {
            err.println("Unknown command '" + args[0] + "'." + System.lineSeparator() + usage());
            return 2;
        }

        BenchmarkConfig config;
        try {
            config = new ConfigLoader().load(Path.of(args[1]), !OFFLINE.contains(command));
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            return 2;
        }

        if (command.equals("plan")) {
            printPlan(config, out);
            return 0;
        }

        if (command.equals("export")) {
            return exportCsv(config, out, err);
        }

        try (EnvironmentManager manager = EnvironmentManager.open(config)) {
            CommandReport report = switch (command) {
                case "provision" -> manager.provision();
                case "status" -> manager.status();
                case "activate" -> manager.activateAll();
                case "deactivate" -> manager.deactivateAll();
                case "cleanup" -> manager.cleanup();
                case "reconcile" -> manager.reconcile();
                case "attach" -> manager.attachDevices();
                case "detach" -> manager.detachDevices();
                // Progress goes straight to stdout as it happens: a run lasts minutes to hours, and
                // a sentinel failure is worth seeing the moment it occurs rather than at the end.
                case "run" -> manager.runWorkload(line -> out.println("  " + line));
                default -> throw new IllegalStateException("Unhandled command " + command);
            };
            print(report, out);
            return report.ok() ? 0 : 1;
        } catch (IllegalStateException missingSection) {
            // Thrown by requireEdge/requireWorkload: a configuration gap, not a runtime failure, so
            // it reads as a usage error rather than a stack trace.
            err.println(missingSection.getMessage());
            return 2;
        }
    }

    /**
     * Renders the plan without contacting the platform, including one example of each rule shape.
     * Worth running before every provision: it is the last cheap chance to notice that the device
     * sharing factor, the rule mix or the thresholds are not what was intended.
     */
    private static void printPlan(BenchmarkConfig config, PrintStream out) {
        EnvironmentPlan plan = new EnvironmentPlanner().plan(config.run());
        RuleTemplates templates = new RuleTemplates();

        out.println("plan for runId " + plan.runId());
        out.println("  apiBaseUrl        " + config.platform().apiBaseUrl());
        out.println("  locationCode      " + config.run().locationCode());
        out.println("  deviceType        " + plan.deviceTypeName());
        out.println("  alarmType         " + plan.alarmTypeName());
        out.println("  devices           " + plan.deviceCount());
        out.println("  rules             " + plan.ruleCount());
        for (RuleScenario scenario : RuleScenario.values()) {
            out.println("    " + pad(scenario.slug()) + plan.ruleCount(scenario));
        }
        out.println("  maxRulesPerDevice " + config.run().maxRulesPerDevice()
                + (config.run().maxRulesPerDevice() == 1
                ? "  (one rule per device: clean rule-count scaling)"
                : "  (each event fans out into up to this many entry points)"));
        out.println();
        out.println("example rendered rules, with placeholder device ids:");
        for (RuleScenario scenario : RuleScenario.values()) {
            List<String> placeholders = scenario.devicesPerRule() == 2
                    ? List.of("deviceAAA", "deviceBBB")
                    : List.of("deviceAAA");
            out.println("  " + scenario.slug() + ": "
                    + templates.renderWhen(scenario, placeholders, config.run().template()));
        }
        out.println("  then: " + templates.renderThen("alarmTypeXYZ"));
    }

    private static void print(CommandReport report, PrintStream out) {
        out.println(report.command() + " (runId " + report.runId() + "): "
                + (report.ok() ? "ok" : "FAILED"));
        for (Map.Entry<String, Object> fact : report.facts().entrySet()) {
            out.println("  " + pad(fact.getKey()) + fact.getValue());
        }
        if (!report.warnings().isEmpty()) {
            out.println("  warnings:");
            report.warnings().forEach(warning -> out.println("    - " + warning));
        }
        if (!report.failures().isEmpty()) {
            out.println("  failures (" + report.failures().size() + "):");
            report.failures().stream().limit(50).forEach(failure -> out.println("    - " + failure));
            if (report.failures().size() > 50) {
                out.println("    ... and " + (report.failures().size() - 50) + " more");
            }
        }
    }

    private static String pad(String label) {
        return label.length() >= 25 ? label + " " : "%-25s".formatted(label);
    }

    /**
     * Folds the journal and writes the two CSVs. Makes no network call, so it still answers after
     * cleanup has removed everything from the platform.
     */
    private static int exportCsv(BenchmarkConfig config, PrintStream out, PrintStream err) throws IOException {
        ManifestState state = ManifestJournal.readState(config.manifestDirectory(), config.run().runId());

        if (state.isEmpty()) {
            err.println("Manifest for run " + config.run().runId() + " records no resources; nothing to export.");
            return 1;
        }

        List<Path> written = new ManifestCsvExporter()
                .export(state, config, config.manifestDirectory());

        long rulesWithoutMapping = state.of(ResourceKind.RULE).values().stream()
                .filter(rule -> !rule.hasRuleDetail())
                .count();

        out.println("export");
        out.println("  " + pad("rules") + state.count(ResourceKind.RULE));
        out.println("  " + pad("devices") + state.count(ResourceKind.DEVICE));
        for (Path path : written) {
            out.println("  " + pad("wrote") + path);
        }
        if (rulesWithoutMapping > 0) {
            // Journals written before rules recorded their devices. Saying so beats emitting blank
            // columns and letting someone conclude the rules have no devices.
            err.println("  warning: " + rulesWithoutMapping + " rule(s) predate the device mapping; "
                    + "their deviceIds and when columns are blank. Re-provision to record them.");
        }
        if (!state.unreadableLines().isEmpty()) {
            err.println("  warning: unreadable journal lines: " + String.join(", ", state.unreadableLines()));
        }
        return 0;
    }

    private static String usage() {
        return """
                usage: cepbench <command> <config.json>

                  plan        render the plan and example rules; makes no network calls
                  provision   create the device type, alarm type, devices and rules (inactive)
                  status      report what the manifest owns and its activation state
                  activate    activate every rule and wait until the platform confirms it
                  deactivate  deactivate every rule and wait until the platform confirms it
                  reconcile   adopt resources this run created but never recorded
                  attach      attach every provisioned device to the configured edge
                  detach      detach them again, leaving the edge itself untouched
                  run         publish device reports through the edge and watch a sentinel rule
                  cleanup     detach, then delete everything the manifest records
                  export      write <runId>-rules.csv and <runId>-devices.csv from the manifest

                The API token comes from the config's "token" field, or from the environment
                variable named in "tokenEnvironmentVariable". plan and export make no network
                calls and need neither.
                """;
    }

    private CepBenchMain() {
    }
}
