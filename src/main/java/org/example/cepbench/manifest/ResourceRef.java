package org.example.cepbench.manifest;

/**
 * A resource this run created and is therefore allowed to delete.
 *
 * @param key  plan key, stable across runs of the planner, used to resume provisioning
 * @param id   platform id, the only thing cleanup ever acts on
 * @param name name sent to the platform, kept for human readable reporting
 */
public record ResourceRef(ResourceKind kind, String key, String id, String name) {
}
