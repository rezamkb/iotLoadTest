package org.example.cepbench.model;

/**
 * The three rule shapes under test. Each maps to one of the DSL templates the platform is known to
 * compile, so the benchmark never invents syntax the parser has not seen.
 */
public enum RuleScenario {

    /** One device, one attribute compared to a threshold. Compiles to a stateless entry-point rule. */
    SINGLE_SELECT("single", 1),

    /**
     * Two devices joined by {@code or}. Both branches read the reported topic, matching the sample
     * DRL: one rule, one entry point, two alternative patterns.
     */
    MULTI_SELECT_TWO_DEVICE("multi", 2),

    /**
     * One device with a length window and an average. Compiles to an accumulate over the shared
     * "stateful" entry point, so these rules land in the fireAllRules session rather than the
     * fireUntilHalt one.
     */
    WINDOWING("window", 1);

    private final String slug;
    private final int devicesPerRule;

    RuleScenario(String slug, int devicesPerRule) {
        this.slug = slug;
        this.devicesPerRule = devicesPerRule;
    }

    /** Short, filename and rule-name safe identifier. */
    public String slug() {
        return slug;
    }

    /** How many distinct devices one rule of this shape needs. */
    public int devicesPerRule() {
        return devicesPerRule;
    }

    /**
     * Whether the rule is evaluated in the shared stateful session. Windowing rules are, which is
     * why they do not get their own entry point and are not retracted by the stateless listener.
     */
    public boolean isStateful() {
        return this == WINDOWING;
    }
}
