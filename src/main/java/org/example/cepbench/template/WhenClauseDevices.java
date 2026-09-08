package org.example.cepbench.template;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the device ids a rule selects on, from the rule's own {@code when} clause.
 *
 * <p>Used when adopting a rule the platform created but the manifest never recorded. The clause read
 * back from the platform is the authoritative statement of which devices that rule watches: the
 * planner's allocation can no longer be trusted for it, because the allocation shifts whenever
 * {@code maxRulesPerDevice} or the scenario counts change while the stored clause does not.
 *
 * <p>Every template puts the device id immediately before {@code /twin/update/}, in all three shapes:
 *
 * <pre>
 *   select * from &lt;id&gt;/twin/update/reported where temp &gt; 40;
 *   select * from &lt;a&gt;/twin/update/reported where temp &gt; 40 ;or select * from &lt;b&gt;/twin/update/reported  where occ = true ;
 *   select *,(avg(temp) as tmp over window:length(3)) from &lt;id&gt;/twin/update/reported where tmp &gt; 100;
 * </pre>
 */
public final class WhenClauseDevices {

    private static final Pattern DEVICE_BEFORE_TOPIC =
            Pattern.compile("([A-Za-z0-9_-]+)/twin/update/");

    private WhenClauseDevices() {
    }

    /**
     * @return device ids in the order they appear, without duplicates. Order matters: it is what
     * distinguishes the temperature branch of a two-device rule from the occupancy branch.
     */
    public static List<String> parse(String whenClause) {
        if (whenClause == null || whenClause.isBlank()) {
            return List.of();
        }
        // A LinkedHashSet rather than a list: a rule that names the same device twice should still
        // yield one device, and insertion order is preserved.
        Set<String> ids = new LinkedHashSet<>();
        Matcher matcher = DEVICE_BEFORE_TOPIC.matcher(whenClause);
        while (matcher.find()) {
            ids.add(matcher.group(1));
        }
        return new ArrayList<>(ids);
    }
}
