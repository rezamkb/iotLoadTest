package org.example.cepbench.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.cepbench.config.BenchmarkConfig;
import org.example.cepbench.model.RuleScenario;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Renders the {@code when} and {@code then} halves of a rule.
 *
 * <p>The three {@code when} shapes are reproduced character for character from DSL that the platform
 * is known to compile, including its slightly irregular spacing around the {@code or} separator. The
 * benchmark deliberately offers no way to supply an arbitrary expression: the point is to measure
 * the engine under known-good rules, and a rule the parser rejects fails at create time with an
 * error that says nothing about capacity.
 *
 * <p>Ids are substituted, never concatenated from unchecked input, and are validated first.
 */
public final class RuleTemplates {

    /** Platform ids are short alphanumerics; anything else means a bug upstream, not user input. */
    private static final Pattern PLATFORM_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @param deviceIds resolved platform device ids, in template order, exactly
     *                  {@code scenario.devicesPerRule()} of them
     */
    public String renderWhen(RuleScenario scenario,
                             List<String> deviceIds,
                             BenchmarkConfig.RuleTemplateSpec template) {
        if (deviceIds.size() != scenario.devicesPerRule()) {
            throw new IllegalArgumentException("%s needs exactly %d device id(s), got %d"
                    .formatted(scenario, scenario.devicesPerRule(), deviceIds.size()));
        }
        List<String> ids = deviceIds.stream().map(RuleTemplates::requirePlatformId).toList();

        return switch (scenario) {
            case SINGLE_SELECT -> "select * from %s/twin/update/reported where temp > %d;"
                    .formatted(ids.get(0), template.temperatureThreshold());

            // Both branches read the reported topic, matching the supplied sample. Keeping both on
            // one topic also keeps the whole benchmark on a single CEP input queue.
            case MULTI_SELECT_TWO_DEVICE ->
                    "select * from %s/twin/update/reported where temp > %d ;or select * from %s/twin/update/reported  where occ = true ;"
                            .formatted(ids.get(0), template.temperatureThreshold(), ids.get(1));

            case WINDOWING ->
                    "select *,(avg(temp) as tmp over window:length(%d)) from %s/twin/update/reported where tmp > %d;"
                            .formatted(template.windowLength(), ids.get(0), template.windowAverageThreshold());
        };
    }

    /**
     * One fixed consequence for every rule: raise an alarm. Nothing else, so a fired rule costs the
     * same regardless of scenario and the measurement is not confounded by the action.
     */
    public JsonNode renderThen(String alarmTypeId) {
        ObjectNode alarm = objectMapper.createObjectNode();
        alarm.put("alarmTypeId", requirePlatformId(alarmTypeId));

        ArrayNode alarms = objectMapper.createArrayNode();
        alarms.add(alarm);

        ObjectNode then = objectMapper.createObjectNode();
        then.set("alarms", alarms);
        return then;
    }

    /** The {@code code} tag the platform maps to a location, and through it to a CEP node. */
    public JsonNode renderTags(String locationCode) {
        ObjectNode tags = objectMapper.createObjectNode();
        tags.put("code", locationCode);
        return tags;
    }

    private static String requirePlatformId(String id) {
        if (id == null || !PLATFORM_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Refusing to build a rule with a suspicious id: " + id);
        }
        return id;
    }
}
