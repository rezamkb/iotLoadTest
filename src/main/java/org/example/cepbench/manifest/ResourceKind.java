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
    RULE("rules");

    /** Path segment of the collection endpoint, so delete URLs are derived rather than repeated. */
    private final String pathSegment;

    ResourceKind(String pathSegment) {
        this.pathSegment = pathSegment;
    }

    public String pathSegment() {
        return pathSegment;
    }

    /** Singleton resources use a fixed key; there is exactly one of each per run. */
    public static final String DEVICE_TYPE_KEY = "deviceType";
    public static final String ALARM_TYPE_KEY = "alarmType";
}
