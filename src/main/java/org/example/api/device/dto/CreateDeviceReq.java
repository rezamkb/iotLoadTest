package org.example.api.device.dto;

public class CreateDeviceReq {
    private String name;
    private String description;
    private String deviceTypeId;
    private String serialNumber;
    private String pushURL;

    public CreateDeviceReq(String name, String description, String deviceTypeId,
                  String serialNumber, String pushURL) {
        this.name         = name;
        this.description  = description;
        this.deviceTypeId = deviceTypeId;
        this.serialNumber = serialNumber;
        this.pushURL      = pushURL;
    }

    // ─── Getters & Setters ────────────────────────────────────────────────
    public String getName()               { return name; }
    public String getDescription()        { return description; }
    public String getDeviceTypeId()       { return deviceTypeId; }
    public String getSerialNumber()       { return serialNumber; }
    public String getPushURL()            { return pushURL; }


    public void setName(String name)                  { this.name = name; }
    public void setDescription(String description)    { this.description = description; }
    public void setDeviceTypeId(String deviceTypeId)  { this.deviceTypeId = deviceTypeId; }
    public void setSerialNumber(String serialNumber)  { this.serialNumber = serialNumber; }
    public void setPushURL(String pushURL)            { this.pushURL = pushURL; }
}
