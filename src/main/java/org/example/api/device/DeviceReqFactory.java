package org.example.api.device;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.api.device.dto.CreateDeviceReq;

import java.util.UUID;

public class DeviceReqFactory {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static CreateDeviceReq createDeviceReq() {
        return createDeviceReq(Config.DEFAULT_DEVICE_TYPE_ID, "device");
    }

    public static CreateDeviceReq createDeviceReq(String deviceTypeId, String deviceTypeName) {
        if (deviceTypeId == null || deviceTypeId.isBlank()) {
            throw new IllegalArgumentException("deviceTypeId must not be blank");
        }
        if (deviceTypeName == null || deviceTypeName.isBlank()) {
            throw new IllegalArgumentException("deviceTypeName must not be blank");
        }

        String uniqueName = deviceTypeName + "-" + UUID.randomUUID();
        String serial     = UUID.randomUUID().toString();
        // Create tags JsonNode

        ObjectNode tags = MAPPER.createObjectNode();
        tags.put("code", "202");

        return new CreateDeviceReq(
                uniqueName,
                Config.DEFAULT_DESCRIPTION,
                deviceTypeId,
                serial,
                Config.DEFAULT_PUSH_URL,
                tags
        );
    }








}
