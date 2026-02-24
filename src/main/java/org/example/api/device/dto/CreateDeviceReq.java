package org.example.api.device.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.util.JSONPObject;

public class CreateDeviceReq {
    private String name;
    private String description;
    private String deviceTypeId;
    private String serialNumber;
    private String pushURL;
    private JsonNode tags;

    public CreateDeviceReq(String name, String description, String deviceTypeId,
                  String serialNumber, String pushURL) {
        this.name         = name;
        this.description  = description;
        this.deviceTypeId = deviceTypeId;
        this.serialNumber = serialNumber;
        this.pushURL      = pushURL;
    }

    public CreateDeviceReq(String name, String description, String deviceTypeId,
                           String serialNumber, String pushURL, JsonNode tags) {
        this.name         = name;
        this.description  = description;
        this.deviceTypeId = deviceTypeId;
        this.serialNumber = serialNumber;
        this.pushURL      = pushURL;
        this.tags         = tags;
    }

    // ─── Getters & Setters ────────────────────────────────────────────────
    public String getName()               { return name; }
    public String getDescription()        { return description; }
    public String getDeviceTypeId()       { return deviceTypeId; }
    public String getSerialNumber()       { return serialNumber; }
    public String getPushURL()            { return pushURL; }

    public JsonNode getTags() {
        return tags;
    }

    public void setTags(JsonNode tags) {
        this.tags = tags;
    }

    public void setName(String name)                  { this.name = name; }
    public void setDescription(String description)    { this.description = description; }
    public void setDeviceTypeId(String deviceTypeId)  { this.deviceTypeId = deviceTypeId; }
    public void setSerialNumber(String serialNumber)  { this.serialNumber = serialNumber; }
    public void setPushURL(String pushURL)            { this.pushURL = pushURL; }
}
