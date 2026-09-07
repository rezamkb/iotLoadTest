package org.example.cepbench;

import org.example.cepbench.config.BenchmarkConfig;
import org.example.cepbench.config.ConfigLoader;
import org.example.cepbench.environment.CommandReport;
import org.example.cepbench.environment.EnvironmentManager;
import org.example.cepbench.model.EnvironmentPlan;
import org.example.cepbench.model.RuleScenario;
import org.example.cepbench.planning.EnvironmentPlanner;
import org.example.cepbench.template.RuleTemplates;

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
 * <p>Commands that change anything on the platform require {@code CEPBENCH_CONFIRM} to be set to
 * {@code &lt;runId&gt;@&lt;api host&gt;}. Provisioning creates hundreds of resources on a shared
 * sandbox and cleanup deletes them, so the operator is made to name both the run and the target
 * host. {@code plan} and {@code status} read only and need no confirmation.
 */
public final class CepBenchMain {

    private static final String CONFIRM_VARIABLE = "CEPBENCH_CONFIRM";
    private static final Set<String> READ_ONLY = Set.of("plan", "status");
    private static final Set<String> COMMANDS =
            Set.of("plan", "provision", "status", "activate", "deactivate", "cleanup");

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
            config = new ConfigLoader().load(Path.of(args[1]));
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            return 2;
        }

        if (!READ_ONLY.contains(command)) {
            String problem = confirmationProblem(config);
            if (problem != null) {
                err.println(problem);
                return 3;
            }
        }

        if (command.equals("plan")) {
            printPlan(config, out);
            return 0;
        }

        try (EnvironmentManager manager = EnvironmentManager.open(config)) {
            CommandReport report = switch (command) {
                case "provision" -> manager.provision();
                case "status" -> manager.status();
                case "activate" -> manager.activateAll();
                case "deactivate" -> manager.deactivateAll();
                case "cleanup" -> manager.cleanup();
                default -> throw new IllegalStateException("Unhandled command " + command);
            };
            print(report, out);
            return report.ok() ? 0 : 1;
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

    private static String confirmationProblem(BenchmarkConfig config) {
        String expected = config.run().runId() + "@" + config.platform().authority();
        String actual = System.getenv(CONFIRM_VARIABLE);
        if (actual != null && actual.trim().equals(expected)) {
            return null;
        }
        return """
                Refusing to run: this command changes resources on %s.
                Set %s to exactly:

                    %s

                PowerShell:  $env:%s = '%s'
                """.formatted(config.platform().apiBaseUrl(), CONFIRM_VARIABLE, expected,
                CONFIRM_VARIABLE, expected);
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

    private static String usage() {
        return """
                usage: cepbench <command> <config.json>

                  plan        render the plan and example rules; makes no network calls
                  provision   create the device type, alarm type, devices and rules (inactive)
                  status      report what the manifest owns and its activation state
                  activate    activate every rule and wait until the platform confirms it
                  deactivate  deactivate every rule and wait until the platform confirms it
                  cleanup     delete everything the manifest records, in dependency order

                Mutating commands require CEPBENCH_CONFIRM=<runId>@<api host>.
                The API token is read from the environment variable named in the config.
                """;
    }

    private CepBenchMain() {
    }
}
