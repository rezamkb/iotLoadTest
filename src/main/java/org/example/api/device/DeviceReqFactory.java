package org.example.api.device;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.api.device.dto.CreateDeviceReq;

import java.util.UUID;

public class DeviceReqFactory {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static CreateDeviceReq createDeviceReq() {
        String uniqueName = "device-" + UUID.randomUUID();
        String serial     = UUID.randomUUID().toString();
        // Create tags JsonNode

        ObjectNode tags = MAPPER.createObjectNode();
        tags.put("code", "202");

        return new CreateDeviceReq(
                uniqueName,
                Config.DEFAULT_DESCRIPTION,
                Config.DEFAULT_DEVICE_TYPE_ID,
                serial,
                Config.DEFAULT_PUSH_URL,
                tags
        );
    }








}
