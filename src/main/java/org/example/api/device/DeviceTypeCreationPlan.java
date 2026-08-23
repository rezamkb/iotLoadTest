package org.example.api.device;

public record DeviceTypeCreationPlan(
        String deviceTypeId,
        String deviceTypeName,
        int numberOfDevices
) {
    public DeviceTypeCreationPlan {
        if (deviceTypeId == null || deviceTypeId.isBlank()) {
            throw new IllegalArgumentException("deviceTypeId must not be blank");
        }
        if (deviceTypeName == null || deviceTypeName.isBlank()) {
            throw new IllegalArgumentException("deviceTypeName must not be blank");
        }
        if (numberOfDevices < 0) {
            throw new IllegalArgumentException("numberOfDevice must not be negative");
        }
    }
}
