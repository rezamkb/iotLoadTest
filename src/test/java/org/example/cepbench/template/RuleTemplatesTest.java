package org.example.cepbench.template;

import org.example.cepbench.config.BenchmarkConfig;
import org.example.cepbench.model.RuleScenario;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The expected strings here are the supplied samples with the ids substituted, character for
 * character. If one of these tests has to be "fixed" by relaxing the expectation, the rule being
 * generated is no longer the rule that was known to compile.
 */
class RuleTemplatesTest {

    private final RuleTemplates templates = new RuleTemplates();
    private final BenchmarkConfig.RuleTemplateSpec spec =
            new BenchmarkConfig.RuleTemplateSpec(40, 100, 3);

    @Test
    void singleSelectMatchesTheSample() {
        assertEquals(
                "select * from gqcrarftoxs/twin/update/reported where temp > 40;",
                templates.renderWhen(RuleScenario.SINGLE_SELECT, List.of("gqcrarftoxs"), spec));
    }

    @Test
    void windowingMatchesTheSample() {
        assertEquals(
                "select *,(avg(temp) as tmp over window:length(3)) from jwlweo2ofum/twin/update/reported where tmp > 100;",
                templates.renderWhen(RuleScenario.WINDOWING, List.of("jwlweo2ofum"), spec));
    }

    @Test
    void multiSelectMatchesTheSampleIncludingItsSpacing() {
        assertEquals(
                "select * from faoksqg88ao/twin/update/reported where temp > 40 ;or select * from 2dsdyq5p8p6/twin/update/reported  where occ = true ;",
                templates.renderWhen(RuleScenario.MULTI_SELECT_TWO_DEVICE,
                        List.of("faoksqg88ao", "2dsdyq5p8p6"), spec));
    }

    @Test
    void thresholdsComeFromTheSpec() {
        BenchmarkConfig.RuleTemplateSpec custom = new BenchmarkConfig.RuleTemplateSpec(15, 250, 10);

        assertEquals("select * from abc/twin/update/reported where temp > 15;",
                templates.renderWhen(RuleScenario.SINGLE_SELECT, List.of("abc"), custom));
        assertEquals(
                "select *,(avg(temp) as tmp over window:length(10)) from abc/twin/update/reported where tmp > 250;",
                templates.renderWhen(RuleScenario.WINDOWING, List.of("abc"), custom));
    }

    @Test
    void wrongDeviceCountIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                templates.renderWhen(RuleScenario.MULTI_SELECT_TWO_DEVICE, List.of("only-one"), spec));
        assertThrows(IllegalArgumentException.class, () ->
                templates.renderWhen(RuleScenario.SINGLE_SELECT, List.of("a", "b"), spec));
    }

    @Test
    void idsThatCouldBreakOutOfTheTemplateAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> templates.renderWhen(
                RuleScenario.SINGLE_SELECT, List.of("abc/twin/update/reported where 1=1"), spec));
        assertThrows(IllegalArgumentException.class, () ->
                templates.renderWhen(RuleScenario.SINGLE_SELECT, List.of(""), spec));
    }

    @Test
    void thenRaisesExactlyOneAlarm() {
        assertEquals("{\"alarms\":[{\"alarmTypeId\":\"veu4nzr7x3r\"}]}",
                templates.renderThen("veu4nzr7x3r").toString());
    }

    @Test
    void tagsCarryTheLocationCodeUnderTheKeyThePlatformReads() {
        // LocationResolverService.LOCATION_TAG_KEY is "code"; devices and rules must agree on it or
        // they can be routed to different CEP nodes.
        assertEquals("{\"code\":\"1000\"}", templates.renderTags("1000").toString());
    }
}
