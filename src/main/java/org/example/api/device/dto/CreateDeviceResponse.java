package org.example.api.device.dto;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class CreateDeviceResponse {
    private String id;
    private String name;
    private String clientId;
    private String alternativeClientId;
    private String deviceTypeId;

    public CreateDeviceResponse() { }

    // ─── Getters & Setters ─────────────────────────────────────────
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }

    public String getAlternativeClientId() { return alternativeClientId; }
    public void setAlternativeClientId(String alternativeClientId) { this.alternativeClientId = alternativeClientId; }

    public String getDeviceTypeId() { return deviceTypeId; }
    public void setDeviceTypeId(String deviceTypeId) { this.deviceTypeId = deviceTypeId; }
}
