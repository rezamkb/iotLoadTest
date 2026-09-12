package org.example.cepbench.environment;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.example.cepbench.client.CepDiagnosticsClient;
import org.example.cepbench.client.PlatformApiClient;
import org.example.cepbench.client.PlatformApiException;
import org.example.cepbench.config.BenchmarkConfig;
import org.example.cepbench.edge.EdgeMqttPublisher;
import org.example.cepbench.workload.LoadRunner;
import org.example.cepbench.workload.WorkloadTargets;
import org.example.cepbench.manifest.ManifestJournal;
import org.example.cepbench.manifest.ManifestState;
import org.example.cepbench.manifest.ResourceKind;
import org.example.cepbench.manifest.ResourceRef;
import org.example.cepbench.model.EnvironmentPlan;
import org.example.cepbench.model.RuleScenario;
import org.example.cepbench.planning.EnvironmentPlanner;
import org.example.cepbench.template.RuleTemplates;
import org.example.cepbench.template.WhenClauseDevices;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Creates, activates and removes the platform resources one benchmark run owns.
 *
 * <p>Everything it creates is recorded in a {@link ManifestJournal} before the next call is made, and
 * everything it deletes comes from that journal. It never searches the platform by name, so a
 * resource it did not create is never adopted and never deleted, however similar the name.
 *
 * <p>Provisioning is resumable. The plan is deterministic, so a run interrupted half way through can
 * be restarted and will create only what the journal does not already record.
 */
public final class EnvironmentManager implements Closeable {

    private static final String DEVICE_TYPE_DESCRIPTION = "cepbench synthetic device type";
    private static final String DEVICE_DESCRIPTION = "cepbench synthetic device";
    private static final String ALARM_TYPE_DESCRIPTION = "cepbench synthetic alarm type";
    private static final String ALARM_SEVERITY = "Warn";
    /** How many rule keys the firings report lists inline before collapsing to a count. */
    private static final int MAX_KEYS_LISTED = 20;
    private static final Map<String, String> DEVICE_ATTRIBUTES = Map.of(
            "temp", "number",
            "occ", "boolean");

    private final BenchmarkConfig config;
    private final PlatformApiClient client;
    private final ManifestJournal journal;
    private final EnvironmentPlan plan;
    private final RuleTemplates templates = new RuleTemplates();
    private final ExecutorService executor;

    private EnvironmentManager(BenchmarkConfig config,
                               PlatformApiClient client,
                               ManifestJournal journal,
                               EnvironmentPlan plan) {
        this.config = config;
        this.client = client;
        this.journal = journal;
        this.plan = plan;
        this.executor = Executors.newFixedThreadPool(config.platform().concurrency());
    }

    public static EnvironmentManager open(BenchmarkConfig config) throws IOException {
        EnvironmentPlan plan = new EnvironmentPlanner().plan(config.run());
        ManifestJournal journal = ManifestJournal.open(
                config.manifestDirectory(), config.run().runId(), config.platform().apiBaseUrl());
        return new EnvironmentManager(config, new PlatformApiClient(config.platform()), journal, plan);
    }

    public EnvironmentPlan plan() {
        return plan;
    }

    // ================================================================ provision

    /** Creates whatever the plan calls for and the journal does not already record. */
    public CommandReport provision() throws IOException {
        CommandReport.Builder report = CommandReport.builder("provision", plan.runId());
        ManifestState state = state(report);

        report.fact("manifest", journal.file().toString());
        report.fact("devicesPlanned", plan.deviceCount());
        report.fact("rulesPlanned", plan.ruleCount());
        for (RuleScenario scenario : RuleScenario.values()) {
            report.fact("rulesPlanned." + scenario.slug(), plan.ruleCount(scenario));
        }
        report.fact("maxRulesPerDevice", config.run().maxRulesPerDevice());

        String deviceTypeId = ensureSingleton(
                state.deviceType(), ResourceKind.DEVICE_TYPE, ResourceKind.DEVICE_TYPE_KEY,
                plan.deviceTypeName(),
                () -> client.createDeviceType(plan.deviceTypeName(), DEVICE_TYPE_DESCRIPTION, DEVICE_ATTRIBUTES),
                report);
        String alarmTypeId = ensureSingleton(
                state.alarmType(), ResourceKind.ALARM_TYPE, ResourceKind.ALARM_TYPE_KEY,
                plan.alarmTypeName(),
                () -> client.createAlarmType(plan.alarmTypeName(), config.run().alarmTypeCode(),
                        ALARM_TYPE_DESCRIPTION, ALARM_SEVERITY),
                report);

        if (deviceTypeId == null || alarmTypeId == null) {
            report.fail("Cannot continue without both a device type and an alarm type");
            return report.build();
        }
        report.fact("deviceTypeId", deviceTypeId);
        report.fact("alarmTypeId", alarmTypeId);

        Map<String, String> deviceIdsByKey = provisionDevices(state, deviceTypeId, report);
        provisionRules(state, alarmTypeId, deviceIdsByKey, report);

        ManifestState after = state(report);
        report.fact("devicesNow", after.count(ResourceKind.DEVICE));
        report.fact("rulesNow", after.count(ResourceKind.RULE));
        if (after.count(ResourceKind.RULE) < plan.ruleCount()) {
            report.warn("Not every planned rule exists yet; provision is resumable, so re-running it "
                    + "will create only what is missing");
        }
        return report.build();
    }

    private Map<String, String> provisionDevices(ManifestState state,
                                                 String deviceTypeId,
                                                 CommandReport.Builder report) {
        Map<String, String> deviceIdsByKey = new ConcurrentHashMap<>();
        state.of(ResourceKind.DEVICE).forEach((key, ref) -> deviceIdsByKey.put(key, ref.id()));

        List<EnvironmentPlan.PlannedDevice> missing = plan.devices().stream()
                .filter(device -> !deviceIdsByKey.containsKey(device.key()))
                .toList();
        report.fact("devicesExisting", plan.deviceCount() - missing.size());

        JsonNode tags = templates.renderTags(config.run().locationCode());
        Map<EnvironmentPlan.PlannedDevice, String> failures = forEachConcurrently(missing, device -> {
            String id = client.createDevice(
                    device.name(), DEVICE_DESCRIPTION, deviceTypeId, device.key(), tags);
            journal.recordCreated(ResourceKind.DEVICE, device.key(), id, device.name());
            deviceIdsByKey.put(device.key(), id);
        });

        report.fact("devicesCreated", missing.size() - failures.size());
        recordFailures(failures, report, device -> "device " + device.name());
        return deviceIdsByKey;
    }

    private void provisionRules(ManifestState state,
                                String alarmTypeId,
                                Map<String, String> deviceIdsByKey,
                                CommandReport.Builder report) {
        Set<String> existing = state.of(ResourceKind.RULE).keySet();
        List<EnvironmentPlan.PlannedRule> missing = plan.rules().stream()
                .filter(rule -> !existing.contains(rule.key()))
                .toList();
        report.fact("rulesExisting", plan.ruleCount() - missing.size());

        JsonNode then = templates.renderThen(alarmTypeId);
        JsonNode tags = templates.renderTags(config.run().locationCode());

        Map<EnvironmentPlan.PlannedRule, String> failures = forEachConcurrently(missing, rule -> {
            List<String> deviceIds = new ArrayList<>(rule.deviceKeys().size());
            for (String deviceKey : rule.deviceKeys()) {
                String deviceId = deviceIdsByKey.get(deviceKey);
                if (deviceId == null) {
                    // Its device failed earlier. Skipping keeps the environment consistent rather
                    // than creating a rule that selects on a device that does not exist.
                    throw new IllegalStateException("device " + deviceKey + " was not created");
                }
                deviceIds.add(deviceId);
            }
            String when = templates.renderWhen(rule.scenario(), deviceIds, config.run().template());
            PlatformApiClient.RuleView created = client.createRule(rule.name(), when, then, tags);
            // Records the devices alongside the rule, because this is the last moment the mapping is
            // certain: the clause above is now fixed on the platform, while the planner's allocation
            // would shift under any later config edit.
            journal.recordRuleCreated(
                    rule.key(), created.id(), rule.name(), rule.scenario().name(), deviceIds);
            if (created.activated()) {
                report.warn("Rule " + rule.name() + " came back already activated; "
                        + "provision expects rules to start inactive");
            }
        });

        report.fact("rulesCreated", missing.size() - failures.size());
        recordFailures(failures, report, rule -> "rule " + rule.name());
    }

    // ================================================================ activation

    public CommandReport activateAll() throws IOException {
        return changeActivation(true);
    }

    public CommandReport deactivateAll() throws IOException {
        return changeActivation(false);
    }

    /**
     * Requests the state change for every rule, then polls until the platform actually reports it.
     *
     * <p>The request and the confirmation are separated on purpose. Activation is asynchronous: the
     * API accepts the call and a command travels to the CEP node, so a success status means the
     * request was taken, not that the rule is compiled into the engine. Sending events before the
     * rules are really active is the easiest way to produce a benchmark result that means nothing.
     */
    private CommandReport changeActivation(boolean activate) throws IOException {
        String command = activate ? "activate" : "deactivate";
        CommandReport.Builder report = CommandReport.builder(command, plan.runId());
        ManifestState state = state(report);

        Collection<ResourceRef> rules = state.of(ResourceKind.RULE).values();
        report.fact("rules", rules.size());
        if (rules.isEmpty()) {
            report.warn("The manifest records no rules; run provision first");
            return report.build();
        }

        Instant started = Instant.now();
        Map<ResourceRef, String> requestFailures = forEachConcurrently(rules, rule -> {
            if (activate) {
                client.activateRule(rule.id());
            } else {
                client.deactivateRule(rule.id());
            }
        });
        recordFailures(requestFailures, report, rule -> command + " " + rule.name());

        Set<ResourceRef> pending = new LinkedHashSet<>(rules);
        Instant deadline = started.plus(config.platform().activationTimeout());
        Map<ResourceRef, String> pollErrors = Map.of();

        while (true) {
            Set<ResourceRef> confirmed = ConcurrentHashMap.newKeySet();
            // Poll errors are not reported directly: a transient failure on one round is expected and
            // means nothing if a later round confirms the rule. Only rules still pending at the
            // deadline are failures, and then the last error explains why.
            pollErrors = forEachConcurrently(List.copyOf(pending), rule -> {
                if (client.getRule(rule.id()).activated() == activate) {
                    confirmed.add(rule);
                }
            });
            pending.removeAll(confirmed);

            if (pending.isEmpty() || !Instant.now().isBefore(deadline)) {
                break;
            }
            sleep(config.platform().activationPollInterval());
        }

        report.fact("confirmed", rules.size() - pending.size());
        report.fact("elapsedMillis", Duration.between(started, Instant.now()).toMillis());
        if (!pending.isEmpty()) {
            report.fail("%d rule(s) did not reach %s within %s; first few: %s".formatted(
                    pending.size(),
                    activate ? "activated" : "deactivated",
                    config.platform().activationTimeout(),
                    pending.stream().limit(10).map(ResourceRef::name).toList()));
            // Copied because the loop reassigns it, and a lambda may only capture an effectively
            // final local.
            Map<ResourceRef, String> lastRound = pollErrors;
            lastRound.entrySet().stream().limit(3).forEach(entry ->
                    report.warn("Last poll error for " + entry.getKey().name() + ": " + entry.getValue()));
        }
        return report.build();
    }

    // ================================================================ status

    /** Read only. Reports what the manifest owns and what the platform currently says about it. */
    public CommandReport status() throws IOException {
        CommandReport.Builder report = CommandReport.builder("status", plan.runId());
        ManifestState state = state(report);

        report.fact("manifest", journal.file().toString());
        report.fact("apiBaseUrl", config.platform().apiBaseUrl());
        report.fact("deviceType", state.deviceType().map(ResourceRef::id).orElse("<none>"));
        report.fact("alarmType", state.alarmType().map(ResourceRef::id).orElse("<none>"));
        report.fact("devicesPlanned", plan.deviceCount());
        report.fact("devices", state.count(ResourceKind.DEVICE));
        report.fact("rulesPlanned", plan.ruleCount());
        report.fact("rules", state.count(ResourceKind.RULE));

        Collection<ResourceRef> rules = state.of(ResourceKind.RULE).values();
        if (rules.isEmpty()) {
            return report.build();
        }

        Set<String> active = ConcurrentHashMap.newKeySet();
        Set<String> inactive = ConcurrentHashMap.newKeySet();
        Set<String> missing = ConcurrentHashMap.newKeySet();

        Map<ResourceRef, String> failures = forEachConcurrently(rules, rule -> {
            try {
                if (client.getRule(rule.id()).activated()) {
                    active.add(rule.id());
                } else {
                    inactive.add(rule.id());
                }
            } catch (PlatformApiException e) {
                if (!e.isNotFound()) {
                    throw e;
                }
                // The journal says we created it, the platform says it is gone: deleted outside the
                // benchmark. Cleanup will treat it as already removed.
                missing.add(rule.id());
            }
        });
        recordFailures(failures, report, rule -> "status " + rule.name());

        report.fact("rulesActive", active.size());
        report.fact("rulesInactive", inactive.size());
        report.fact("rulesMissingOnPlatform", missing.size());
        if (!missing.isEmpty()) {
            report.warn(missing.size() + " rule(s) in the manifest no longer exist on the platform");
        }
        if (!active.isEmpty() && !inactive.isEmpty()) {
            report.warn("Rules are in mixed activation states; a load run started now would not be "
                    + "attributable to a rule count");
        }
        return report.build();
    }

    // ================================================================ firings

    /**
     * Read only. Asks the platform how many times each rule in the manifest has fired.
     *
     * <p>This is the question the alarm list looks like it answers and does not.
     * {@code AlarmServiceImpl.raise} de-duplicates on {@code (tenant, alarmType, ruleId, ACTIVE)},
     * bumping {@code occurrenceCount} on the row it already has, so the number of alarm rows tells
     * you how many distinct rules fired at least once and nothing whatever about how often. Per-rule
     * firings is the sum of {@code occurrenceCount}, which is what
     * {@link PlatformApiClient#countRuleFirings(String)} returns.
     *
     * <p>Counts are cumulative over the life of the rule, not per run: a rule driven by three
     * successive workloads reports the total of all three. To attribute firings to one run, either
     * provision a fresh runId or take the difference between two invocations of this command.
     *
     * <p>A rule whose count could not be read is reported as a failure and left out of the totals
     * entirely. It is never folded into "never fired": an unreachable API is not evidence that a
     * rule is idle, and the two must not be allowed to look alike in a report.
     */
    public CommandReport firings() throws IOException {
        CommandReport.Builder report = CommandReport.builder("firings", plan.runId());
        ManifestState state = state(report);

        report.fact("apiBaseUrl", config.platform().apiBaseUrl());

        Collection<ResourceRef> rules = state.of(ResourceKind.RULE).values();
        report.fact("rules", rules.size());
        if (rules.isEmpty()) {
            report.warn("The manifest records no rule, so there is nothing to count. Provision first.");
            return report.build();
        }

        Map<String, Long> counted = new ConcurrentHashMap<>();
        Map<ResourceRef, String> failures = forEachConcurrently(rules,
                rule -> counted.put(rule.id(), client.countRuleFirings(rule.id())));
        recordFailures(failures, report, rule -> "count firings for " + rule.name());

        // Highest first, so the rules that did something are at the top whatever the rule count.
        // Key order breaks ties, so two runs of this command on an idle environment print the same.
        List<ResourceRef> answered = rules.stream()
                .filter(rule -> counted.containsKey(rule.id()))
                .sorted(Comparator.comparingLong((ResourceRef rule) -> counted.get(rule.id()))
                        .reversed()
                        .thenComparing(ResourceRef::key))
                .toList();

        List<ResourceRef> fired = answered.stream()
                .filter(rule -> counted.get(rule.id()) > 0L)
                .toList();
        List<ResourceRef> neverFired = answered.stream()
                .filter(rule -> counted.get(rule.id()) == 0L)
                .toList();
        long totalFirings = answered.stream().mapToLong(rule -> counted.get(rule.id())).sum();

        report.fact("rulesCounted", answered.size());
        report.fact("rulesFired", fired.size());
        report.fact("rulesNeverFired", neverFired.size());
        report.fact("totalFirings", totalFirings);

        // The same rule the run would hold back, chosen the same way, so its firings can be told
        // apart from the background load's. With matchingFraction at 0 it is the only one that can
        // have fired at all.
        WorkloadTargets targets = WorkloadTargets.from(state, config.run().template());
        String sentinelRuleId = targets.sentinel().map(WorkloadTargets.Target::ruleId).orElse(null);
        targets.sentinel().ifPresent(target -> report.fact("sentinelRule", target.ruleKey()));

        for (RuleScenario scenario : RuleScenario.values()) {
            List<ResourceRef> ofScenario = answered.stream()
                    .filter(rule -> scenario.name().equals(rule.scenario()))
                    .toList();
            if (ofScenario.isEmpty()) {
                continue;
            }
            long scenarioFirings = ofScenario.stream().mapToLong(rule -> counted.get(rule.id())).sum();
            long scenarioFired = ofScenario.stream().filter(rule -> counted.get(rule.id()) > 0L).count();
            report.fact("scenario." + scenario.slug(), scenarioFirings + " firing(s) from "
                    + scenarioFired + "/" + ofScenario.size() + " rule(s)");
        }

        for (ResourceRef rule : fired) {
            // The platform id is printed rather than the name because it is what you paste into
            // GET /alarms?ruleId= to see the alarms behind the number.
            report.fact("fired." + rule.key(), counted.get(rule.id())
                    + "  " + scenarioLabel(rule)
                    + "  " + rule.id()
                    + (rule.id().equals(sentinelRuleId) ? "  (sentinel)" : ""));
        }
        if (!neverFired.isEmpty()) {
            report.fact("neverFired", summariseKeys(neverFired));
        }

        if (fired.isEmpty() && !answered.isEmpty()) {
            report.warn("No rule in this manifest has ever fired. Check that the rules are active "
                    + "(status), that the devices are attached to the edge (status), and that the "
                    + "edge is publishing on the client id the platform registered.");
        }
        if (config.workload() != null && config.workload().matchingFraction() == 0.0d
                && !neverFired.isEmpty()) {
            // Not a fault, and the most common thing to misread in this report.
            report.warn("workload.matchingFraction is 0.0, so every background event is built NOT to "
                    + "satisfy its rule; only the sentinel is sent matching readings. "
                    + neverFired.size() + " rule(s) at zero is the configured behaviour, not a "
                    + "failure. Raise matchingFraction to exercise the rest.");
        }
        return report.build();
    }

    private static String scenarioLabel(ResourceRef rule) {
        // Journals written before rules recorded their scenario. Saying so beats printing a blank
        // column and letting someone read it as a shape the benchmark does not generate.
        return (rule.scenario() == null) ? "<scenario not recorded>" : rule.scenario();
    }

    /** Keys on one line, capped, so a 500-rule environment does not print 480 zeroes. */
    private static String summariseKeys(List<ResourceRef> refs) {
        int shown = Math.min(refs.size(), MAX_KEYS_LISTED);
        String keys = refs.subList(0, shown).stream()
                .map(ResourceRef::key)
                .sorted()
                .collect(java.util.stream.Collectors.joining(", "));
        return (refs.size() > shown) ? keys + ", (+" + (refs.size() - shown) + " more)" : keys;
    }

    // ================================================================ cleanup

    /**
     * Deletes every resource the journal records, in reverse dependency order, appending a tombstone
     * after each confirmed delete so an interrupted cleanup can simply be re-run.
     */
    public CommandReport cleanup() throws IOException {
        CommandReport.Builder report = CommandReport.builder("cleanup", plan.runId());
        ManifestState state = state(report);

        deleteAll(state, ResourceKind.RULE, report);
        // Before the devices go, so the operator's edge is not left holding attachments to devices
        // that no longer exist. The edge itself is never touched: the run does not own it.
        detachRecorded(state, report);
        deleteAll(state, ResourceKind.DEVICE, report);
        deleteAll(state, ResourceKind.ALARM_TYPE, report);
        deleteAll(state, ResourceKind.DEVICE_TYPE, report);

        ManifestState after = state(report);
        int remaining = totalLive(after);
        report.fact("remaining", remaining);
        if (remaining == 0) {
            report.fact("outcome", "manifest is empty; keep the journal file as the audit record");
        }
        return report.build();
    }

    // ================================================================ reconcile

    /**
     * Adopts resources this run created on the platform but never recorded.
     *
     * <p>The gap is real and not rare: a POST that times out may already have committed on the
     * server, and the client deliberately does not retry creates, so the resource exists with no
     * journal entry. It is then invisible to cleanup and causes a 409 on the next provision.
     *
     * <p>Adoption is deliberately narrow. A platform resource is claimed only when its name is
     * <em>exactly</em> one this run's plan produces, which means it carries the
     * {@code cepbench-<runId>-} prefix and a plan key this run owns. Anything else that happens to
     * share the location tag is left alone. This is the one place the "never adopt by name" rule is
     * relaxed, and the exact-name match is what keeps it safe.
     */
    public CommandReport reconcile() throws IOException {
        CommandReport.Builder report = CommandReport.builder("reconcile", plan.runId());
        ManifestState state = state(report);
        String tagFilter = "code:" + config.run().locationCode();
        report.fact("tagFilter", tagFilter);

        adoptSingleton(state.deviceType(), ResourceKind.DEVICE_TYPE, ResourceKind.DEVICE_TYPE_KEY,
                plan.deviceTypeName(), "device-types", report);
        adoptSingleton(state.alarmType(), ResourceKind.ALARM_TYPE, ResourceKind.ALARM_TYPE_KEY,
                plan.alarmTypeName(), "alarm-types", report);

        adoptDevices(state, tagFilter, report);
        adoptRules(state, tagFilter, report);

        ManifestState after = state(report);
        report.fact("devicesNow", after.count(ResourceKind.DEVICE));
        report.fact("rulesNow", after.count(ResourceKind.RULE));
        report.fact("devicesPlanned", plan.deviceCount());
        report.fact("rulesPlanned", plan.ruleCount());
        if (after.count(ResourceKind.DEVICE) < plan.deviceCount()
                || after.count(ResourceKind.RULE) < plan.ruleCount()) {
            report.fact("outcome", "still incomplete; run provision to create what is genuinely missing");
        }
        return report.build();
    }

    private void adoptSingleton(Optional<ResourceRef> existing,
                                ResourceKind kind,
                                String key,
                                String expectedName,
                                String collection,
                                CommandReport.Builder report) {
        if (existing.isPresent()) {
            return;
        }
        for (JsonNode candidate : client.listAll(collection, Map.of("name", expectedName))) {
            // The name filter may be a prefix or contains match on the platform side, so the exact
            // comparison is repeated here rather than trusted.
            if (expectedName.equals(candidate.path("name").asText())) {
                String id = candidate.path("id").asText();
                journal.recordCreated(kind, key, id, expectedName);
                report.fact("adopted." + kind.name().toLowerCase(Locale.ROOT), id);
                return;
            }
        }
    }

    private void adoptDevices(ManifestState state, String tagFilter, CommandReport.Builder report) {
        Map<String, String> keyByName = new LinkedHashMap<>();
        plan.devices().forEach(device -> keyByName.put(device.name(), device.key()));

        List<JsonNode> onPlatform = client.listAll("devices", Map.of("tag", tagFilter));
        report.fact("devicesSeenWithTag", onPlatform.size());

        int adopted = 0;
        for (JsonNode candidate : onPlatform) {
            String name = candidate.path("name").asText();
            String key = keyByName.get(name);
            if (key == null || state.find(ResourceKind.DEVICE, key).isPresent()) {
                continue;
            }
            journal.recordCreated(ResourceKind.DEVICE, key, candidate.path("id").asText(), name);
            adopted++;
        }
        report.fact("devicesAdopted", adopted);
    }

    private void adoptRules(ManifestState state, String tagFilter, CommandReport.Builder report) {
        Map<String, EnvironmentPlan.PlannedRule> ruleByName = new LinkedHashMap<>();
        plan.rules().forEach(rule -> ruleByName.put(rule.name(), rule));

        List<JsonNode> onPlatform = client.listAll("rules", Map.of("tag", tagFilter));
        report.fact("rulesSeenWithTag", onPlatform.size());

        int adopted = 0;
        int withoutDevices = 0;
        for (JsonNode candidate : onPlatform) {
            String name = candidate.path("name").asText();
            EnvironmentPlan.PlannedRule planned = ruleByName.get(name);
            if (planned == null || state.find(ResourceKind.RULE, planned.key()).isPresent()) {
                continue;
            }
            // Taken from the clause the platform actually stored, not from the planner: that clause
            // is what the rule really watches, and it cannot drift the way an allocation can.
            List<String> deviceIds = WhenClauseDevices.parse(candidate.path("when").asText());
            if (deviceIds.isEmpty()) {
                withoutDevices++;
            }
            journal.recordRuleCreated(planned.key(), candidate.path("id").asText(), name,
                    planned.scenario().name(), deviceIds);
            adopted++;
        }
        report.fact("rulesAdopted", adopted);
        if (withoutDevices > 0) {
            report.warn(withoutDevices + " adopted rule(s) had no parseable device in their when "
                    + "clause, so they carry no device mapping and the workload will skip them.");
        }
    }

    // ================================================================ diagnostics

    /**
     * Reads the CEP node's Drools diagnostics and reports the verdict.
     *
     * <p>Read only and cheap by default. Run it the moment a sentinel fails and before anyone
     * restarts the node: a restart rebuilds the session and erases the evidence.
     *
     * @param includeFactCounts adds per-entry-point fact counts, which is what shows retention
     *                          pressure. It takes the working memory lock, so it can block behind a
     *                          wedged firing thread; leave it off while a workload is running.
     */
    public CommandReport diagnostics(boolean includeFactCounts) {
        BenchmarkConfig.CepTarget target = config.requireCep("diagnostics");
        CommandReport.Builder report = CommandReport.builder("diagnostics", plan.runId());
        report.fact("cepNode", target.diagnosticsBaseUrl());

        CepDiagnosticsClient diagnosticsClient = new CepDiagnosticsClient(target);
        JsonNode snapshot;
        try {
            snapshot = diagnosticsClient.snapshot(includeFactCounts, 20);
        } catch (RuntimeException e) {
            report.fail("Could not read diagnostics from " + target.diagnosticsBaseUrl() + ": "
                    + e.getMessage() + ". If the workload is running and this timed out, that is "
                    + "itself a finding: the node is not answering.");
            return report.build();
        }

        CepDiagnosticsClient.summarise(snapshot).forEach(report::fact);

        JsonNode counters = snapshot.path("counters");
        counters.fieldNames().forEachRemaining(name ->
                report.fact("counter." + name, counters.path(name).asLong()));

        snapshot.path("jmsConsumers").forEach(consumer ->
                report.fact("consumers." + consumer.path("queueName").asText(),
                        consumer.path("activeConnections").asInt()
                                + " active, " + consumer.path("failedConnections").asInt() + " failed"));

        if (includeFactCounts) {
            JsonNode sessions = snapshot.path("sessions");
            report.fact("facts.fireUntilHalt", sessions.path("fireUntilHalt").path("totalFactCount").asLong());
            report.fact("facts.fireAllRules", sessions.path("fireAllRules").path("totalFactCount").asLong());
        }

        if (!snapshot.path("firingLoop").path("alive").asBoolean(true)) {
            report.fail("The stateless firing loop is not running. Capture a thread dump now; a "
                    + "restart is the only recovery and it destroys the evidence.");
        }
        return report.build();
    }

    /** Returns null and records a warning rather than failing the run when diagnostics are absent. */
    private JsonNode readDiagnostics(CepDiagnosticsClient diagnostics,
                                     CommandReport.Builder report,
                                     String label) {
        if (diagnostics == null) {
            return null;
        }
        try {
            return diagnostics.snapshot();
        } catch (RuntimeException e) {
            report.warn(label + " unavailable from " + diagnostics.describeTarget() + ": " + e.getMessage());
            return null;
        }
    }

    // ================================================================ edge attachment

    /**
     * Attaches every device the manifest owns to the configured edge.
     *
     * <p>The edge belongs to the operator, so this only ever adds and removes attachments. Each
     * successful attach is journalled, which is what lets cleanup detach exactly what it attached and
     * nothing else.
     */
    public CommandReport attachDevices() throws IOException {
        BenchmarkConfig.EdgeTarget edge = config.requireEdge("attach");
        CommandReport.Builder report = CommandReport.builder("attach", plan.runId());
        ManifestState state = state(report);

        report.fact("edgeId", edge.edgeId());
        Collection<ResourceRef> devices = state.of(ResourceKind.DEVICE).values();
        Set<String> alreadyRecorded = state.of(ResourceKind.EDGE_ATTACHMENT).keySet();

        List<ResourceRef> pending = devices.stream()
                .filter(device -> !alreadyRecorded.contains(device.key()))
                .toList();
        report.fact("devices", devices.size());
        report.fact("alreadyAttached", devices.size() - pending.size());

        Set<String> wereAlreadyAttached = ConcurrentHashMap.newKeySet();
        Map<ResourceRef, String> failures = forEachConcurrently(pending, device -> {
            if (!client.attachDeviceToEdge(edge.edgeId(), device.id())) {
                wereAlreadyAttached.add(device.id());
            }
            // Journalled either way: the platform reports it attached, so this run is responsible
            // for detaching it.
            journal.recordCreated(ResourceKind.EDGE_ATTACHMENT, device.key(), device.id(), device.name());
        });

        report.fact("attached", pending.size() - failures.size());
        if (!wereAlreadyAttached.isEmpty()) {
            report.fact("attachedBefore", wereAlreadyAttached.size());
        }
        recordFailures(failures, report, device -> "attach device " + device.name());
        return report.build();
    }

    /** Reverses {@link #attachDevices()} without deleting anything. */
    public CommandReport detachDevices() throws IOException {
        config.requireEdge("detach");
        CommandReport.Builder report = CommandReport.builder("detach", plan.runId());
        ManifestState state = state(report);
        detachRecorded(state, report);
        return report.build();
    }

    private void detachRecorded(ManifestState state, CommandReport.Builder report) {
        Collection<ResourceRef> attachments = state.of(ResourceKind.EDGE_ATTACHMENT).values();
        if (attachments.isEmpty()) {
            return;
        }
        if (config.edge() == null) {
            report.warn(attachments.size() + " device(s) are recorded as attached to an edge, but the "
                    + "config has no \"edge\" section, so they cannot be detached. Add it and re-run.");
            return;
        }
        String edgeId = config.edge().edgeId();

        Set<String> notAttached = ConcurrentHashMap.newKeySet();
        Map<ResourceRef, String> failures = forEachConcurrently(attachments, attachment -> {
            if (!client.detachDeviceFromEdge(edgeId, attachment.id())) {
                notAttached.add(attachment.id());
            }
            journal.recordDeleted(ResourceKind.EDGE_ATTACHMENT, attachment.key(), attachment.id());
        });

        report.fact("detached", attachments.size() - failures.size() - notAttached.size());
        if (!notAttached.isEmpty()) {
            report.fact("alreadyDetached", notAttached.size());
        }
        recordFailures(failures, report, attachment -> "detach device " + attachment.name());
    }

    // ================================================================ workload

    /**
     * Publishes device reports through the edge for the configured duration, with a sentinel rule
     * proving the engine is still firing.
     */
    public CommandReport runWorkload(Consumer<String> progress) throws IOException {
        BenchmarkConfig.EdgeTarget edge = config.requireEdge("run");
        BenchmarkConfig.WorkloadSpec workload = config.requireWorkload("run");

        CommandReport.Builder report = CommandReport.builder("run", plan.runId());
        ManifestState state = state(report);

        WorkloadTargets targets = WorkloadTargets.from(state, config.run().template());
        if (targets.isEmpty()) {
            report.fail("The manifest records no rule with its devices. Provision (and if this run "
                    + "predates the device mapping, re-provision) before running a workload.");
            return report.build();
        }
        if (state.of(ResourceKind.EDGE_ATTACHMENT).isEmpty()) {
            report.warn("No device is recorded as attached to the edge. Events published for an "
                    + "unattached device are dropped before they reach CEP; run attach first.");
        }

        report.fact("edgeId", edge.edgeId());
        report.fact("broker", edge.brokerUrl());
        report.fact("topic", edge.publishTopic());
        report.fact("devicesDriven", targets.background().size());
        report.fact("sentinelRule", targets.sentinel().map(WorkloadTargets.Target::ruleKey).orElse("<none>"));
        report.fact("requestedEventsPerSecond", workload.eventsPerSecond());
        report.fact("reportsPerPublish", workload.reportsPerPublish());
        report.fact("matchingFraction", workload.matchingFraction());

        // Optional: without a "cep" section the run still works, it just cannot say why firing
        // stopped, only that it did.
        CepDiagnosticsClient diagnostics =
                (config.cep() == null) ? null : new CepDiagnosticsClient(config.cep());
        JsonNode before = readDiagnostics(diagnostics, report, "diagnosticsAtStart");

        try (EdgeMqttPublisher publisher = new EdgeMqttPublisher(edge)) {
            publisher.connect();
            LoadRunner.Result result =
                    new LoadRunner(workload, targets, publisher, client, progress).run();

            report.fact("elapsedSeconds", result.elapsed().toSeconds());
            report.fact("eventsSent", result.eventsSent());
            report.fact("achievedEventsPerSecond", result.achievedEventsPerSecond());
            report.fact("publishFailures", result.publishFailures());
            report.fact("sentinelsRun", result.sentinelSamples().size());
            report.fact("sentinelsFired", result.sentinelsFired());

            long couldNotRun = result.sentinelsCouldNotRun();
            if (couldNotRun > 0L) {
                report.fact("sentinelsCouldNotRun", couldNotRun);
            }

            java.util.Optional<LoadRunner.SentinelSample> firstFailure = result.firstFiringFailure();
            if (firstFailure.isPresent()) {
                long secondsIn = firstFailure.get().at().toSeconds();
                report.fact("firstFiringFailureAfterSeconds", secondsIn);
                report.fail("The sentinel rule stopped firing " + secondsIn + "s into the run while "
                        + "publishing continued. This is the production symptom. Capture a thread "
                        + "dump and GET /diagnostics/drools from the CEP node BEFORE restarting it; "
                        + "a restart destroys the evidence.");
            } else if (result.sentinelSamples().isEmpty()) {
                report.warn("No sentinel completed, so this run proves nothing about firing. Either "
                        + "the run was shorter than sentinelIntervalSeconds, or no rule was usable.");
            }
            if (couldNotRun > 0L) {
                // Named rather than smoothed over: each one is a probe the run paid for and did not
                // get an answer from, so the firing evidence is thinner than sentinelsRun suggests.
                report.warn(couldNotRun + " sentinel probe(s) could not run at all — the platform API "
                        + "or the broker was unreachable, not the engine. Those slots produced no "
                        + "evidence either way.");
            }
            if (result.publishFailures() > 0) {
                report.warn(result.publishFailures() + " publish(es) failed; the achieved rate is "
                        + "below the requested one for MQTT reasons, not CEP ones.");
            }

            // Read straight after the workload, before anyone restarts anything. The counter deltas
            // localise the break: events that never arrived, arrived but were not inserted, inserted
            // but never matched, or matched but never fired.
            JsonNode after = readDiagnostics(diagnostics, report, "diagnosticsAtEnd");
            if (after != null) {
                CepDiagnosticsClient.summarise(after).forEach((key, value) -> report.fact("cep." + key, value));
                if (before != null) {
                    CepDiagnosticsClient.counterDeltas(before, after)
                            .forEach((key, value) -> report.fact("cep.delta." + key, value));
                }
            }
        } catch (MqttException e) {
            report.fail("MQTT failure against " + edge.brokerUrl() + ": " + e.getMessage());
        }
        return report.build();
    }

    private void deleteAll(ManifestState state, ResourceKind kind, CommandReport.Builder report) {
        Collection<ResourceRef> refs = state.of(kind).values();
        if (refs.isEmpty()) {
            return;
        }
        Set<String> alreadyGone = ConcurrentHashMap.newKeySet();

        Map<ResourceRef, String> failures = forEachConcurrently(refs, ref -> {
            if (!client.delete(kind.pathSegment(), ref.id())) {
                alreadyGone.add(ref.id());
            }
            // Recorded whether the platform deleted it now or it was already absent: either way this
            // run no longer owns it.
            journal.recordDeleted(kind, ref.key(), ref.id());
        });

        String label = kind.name().toLowerCase(Locale.ROOT);
        report.fact("deleted." + label, refs.size() - failures.size() - alreadyGone.size());
        if (!alreadyGone.isEmpty()) {
            report.fact("alreadyGone." + label, alreadyGone.size());
        }
        recordFailures(failures, report, ref -> "delete " + label + " " + ref.name());
    }

    // ================================================================ helpers

    private ManifestState state(CommandReport.Builder report) throws IOException {
        ManifestState state = journal.state();
        if (!state.unreadableLines().isEmpty()) {
            report.warn("Manifest has unreadable entries (" + String.join(", ", state.unreadableLines())
                    + "). Resources created around those lines may not be tracked, so check the "
                    + "platform for leftovers before reusing this runId.");
        }
        return state;
    }

    private String ensureSingleton(Optional<ResourceRef> existing,
                                   ResourceKind kind,
                                   String key,
                                   String name,
                                   ThrowingSupplier create,
                                   CommandReport.Builder report) {
        if (existing.isPresent()) {
            return existing.get().id();
        }
        try {
            String id = create.get();
            journal.recordCreated(kind, key, id, name);
            return id;
        } catch (RuntimeException e) {
            report.fail("Could not create " + kind.name().toLowerCase(Locale.ROOT)
                    + " " + name + ": " + e.getMessage());
            return null;
        }
    }

    private static int totalLive(ManifestState state) {
        int total = 0;
        for (ResourceKind kind : ResourceKind.values()) {
            total += state.count(kind);
        }
        return total;
    }

    /**
     * Runs one action per item across the bounded pool and returns the items that failed, mapped to
     * their error. Failures are returned rather than thrown so a run that fails on 3 of 500 rules
     * reports those 3 instead of discarding the 497 it already created and recorded.
     */
    private <T> Map<T, String> forEachConcurrently(Collection<T> items, ThrowingConsumer<T> action) {
        if (items.isEmpty()) {
            return Map.of();
        }
        Map<Future<?>, T> submitted = new LinkedHashMap<>();
        for (T item : items) {
            submitted.put(executor.submit(() -> {
                action.accept(item);
                return null;
            }), item);
        }

        Map<T, String> failures = new LinkedHashMap<>();
        submitted.forEach((future, item) -> {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failures.put(item, "interrupted");
            } catch (ExecutionException e) {
                Throwable cause = (e.getCause() == null) ? e : e.getCause();
                String message = (cause.getMessage() == null) ? cause.toString() : cause.getMessage();
                failures.put(item, message);
            }
        });
        return failures;
    }

    private static <T> void recordFailures(Map<T, String> failures,
                                           CommandReport.Builder report,
                                           Function<T, String> describe) {
        failures.forEach((item, message) -> report.fail(describe.apply(item) + ": " + message));
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() throws IOException {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        journal.close();
    }

    @FunctionalInterface
    private interface ThrowingConsumer<T> {
        void accept(T item);
    }

    @FunctionalInterface
    private interface ThrowingSupplier {
        String get();
    }
}
