package org.example.cepbench.manifest;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The set of resources that exist right now, folded from the journal.
 *
 * @param unreadableLines lines that could not be parsed. A single trailing entry is the expected
 *                        shape of a hard kill mid-write; anything more means the file was edited or
 *                        corrupted, and cleanup may not know about every resource that was created.
 */
public record ManifestState(
        Map<ResourceKind, Map<String, ResourceRef>> live,
        List<String> unreadableLines
) {

    public ManifestState {
        // An enhanced-for rather than forEach: a lambda cannot capture "live" when the compact
        // constructor also reassigns it.
        Map<ResourceKind, Map<String, ResourceRef>> copy = new LinkedHashMap<>();
        for (Map.Entry<ResourceKind, Map<String, ResourceRef>> entry : live.entrySet()) {
            copy.put(entry.getKey(), Map.copyOf(entry.getValue()));
        }
        live = Collections.unmodifiableMap(copy);
        unreadableLines = List.copyOf(unreadableLines);
    }

    public Optional<ResourceRef> find(ResourceKind kind, String key) {
        return Optional.ofNullable(live.getOrDefault(kind, Map.of()).get(key));
    }

    public Optional<ResourceRef> deviceType() {
        return find(ResourceKind.DEVICE_TYPE, ResourceKind.DEVICE_TYPE_KEY);
    }

    public Optional<ResourceRef> alarmType() {
        return find(ResourceKind.ALARM_TYPE, ResourceKind.ALARM_TYPE_KEY);
    }

    public Map<String, ResourceRef> of(ResourceKind kind) {
        return live.getOrDefault(kind, Map.of());
    }

    public int count(ResourceKind kind) {
        return of(kind).size();
    }

    public boolean isEmpty() {
        return live.values().stream().allMatch(Map::isEmpty);
    }
}
