package org.example.cepbench.manifest;

/**
 * Kinds of platform resource a run owns, listed in dependency order.
 *
 * <p>Creation follows this order and cleanup reverses it, because a device type cannot be deleted
 * while devices reference it and a device cannot be deleted while a rule selects on it.
 */
public enum ResourceKind {

    DEVICE_TYPE("device-types"),
    ALARM_TYPE("alarm-types"),
    DEVICE("devices"),
    /**
     * A device attached to an edge the operator owns. The edge itself is never created or deleted by
     * the benchmark; only the attachment is, and its reversal is a detach rather than a delete, so it
     * has no collection path of its own.
     *
     * <p>Placed after DEVICE so the reversed order detaches before the device is deleted.
     */
    EDGE_ATTACHMENT(null),
    RULE("rules");

    /** Path segment of the collection endpoint, so delete URLs are derived rather than repeated. */
    private final String pathSegment;

    ResourceKind(String pathSegment) {
        this.pathSegment = pathSegment;
    }

    public String pathSegment() {
        if (pathSegment == null) {
            throw new IllegalStateException(name() + " is not removed by deleting a collection resource");
        }
        return pathSegment;
    }

    /** False for kinds whose reversal is something other than DELETE on a collection path. */
    public boolean deletableByPath() {
        return pathSegment != null;
    }

    /** Singleton resources use a fixed key; there is exactly one of each per run. */
    public static final String DEVICE_TYPE_KEY = "deviceType";
    public static final String ALARM_TYPE_KEY = "alarmType";
}
