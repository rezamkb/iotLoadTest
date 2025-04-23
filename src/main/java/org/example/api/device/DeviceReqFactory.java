package org.example.api.device;

import org.example.api.device.dto.CreateDeviceReq;

import java.util.UUID;

public class DeviceReqFactory {

    public static CreateDeviceReq createDeviceReq() {
        String uniqueName = "device-" + UUID.randomUUID();
        String serial     = UUID.randomUUID().toString();
        return new CreateDeviceReq(
                uniqueName,
                Config.DEFAULT_DESCRIPTION,
                Config.DEFAULT_DEVICE_TYPE_ID,
                serial,
                Config.DEFAULT_PUSH_URL
        );
    }








}
