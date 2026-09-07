package org.example.cepbench.manifest;

import java.util.List;

/**
 * A resource this run created and is therefore allowed to delete.
 *
 * <p>{@code scenario} and {@code deviceIds} are populated for rules only. They are recorded at
 * creation time rather than recomputed from the plan on demand: the planner reallocates devices to
 * rules when {@code maxRulesPerDevice} or the scenario counts change, whereas a rule's {@code when}
 * clause is fixed on the platform the moment it is created. Recomputing would therefore report a
 * confident but wrong mapping after any config edit.
 *
 * @param key       plan key, stable across runs of the planner, used to resume provisioning
 * @param id        platform id, the only thing cleanup ever acts on
 * @param name      name sent to the platform, kept for human readable reporting
 * @param scenario  {@link org.example.cepbench.model.RuleScenario} name for rules, else null
 * @param deviceIds platform device ids this rule selects on, in the order they appear in the
 *                  rendered {@code when} clause; empty for anything that is not a rule
 */
public record ResourceRef(
        ResourceKind kind,
        String key,
        String id,
        String name,
        String scenario,
        List<String> deviceIds
) {

    public ResourceRef {
        deviceIds = (deviceIds == null) ? List.of() : List.copyOf(deviceIds);
    }

    /** A resource with no rule specific detail: device types, alarm types and devices. */
    public ResourceRef(ResourceKind kind, String key, String id, String name) {
        this(kind, key, id, name, null, List.of());
    }

    /** True when this ref carries the rule-to-device mapping, i.e. it was written by a newer run. */
    public boolean hasRuleDetail() {
        return scenario != null && !deviceIds.isEmpty();
    }
}
