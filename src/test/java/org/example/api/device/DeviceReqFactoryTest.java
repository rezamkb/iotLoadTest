package org.example.api.device;

import org.example.api.device.dto.CreateDeviceReq;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeviceReqFactoryTest {
    @Test
    void usesDeviceTypeNameAsDeviceNamePrefix() {
        CreateDeviceReq request = DeviceReqFactory.createDeviceReq(
                "1buay488cna",
                "m_airQuality---nemoodar");

        assertEquals("1buay488cna", request.getDeviceTypeId());
        assertTrue(request.getName().startsWith("m_airQuality---nemoodar-"));
    }
}
