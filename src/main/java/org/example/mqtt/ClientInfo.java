package org.example.mqtt;

public class ClientInfo {
    public final String subscriberTopic;
    public final String subscriberClientId;
    public final String publisherTopic;
    public final String publisherClientId;
    public final String payload;

    public ClientInfo(String deviceId,  String clientId,String alternativeClientId) {
        this.subscriberTopic = "dvcout/"+deviceId+"/"+clientId+"/twin/#";
        this.subscriberClientId = alternativeClientId;
        this.publisherTopic = "dvcasy/twin/update/reported";
        this.publisherClientId = clientId;
        this.payload = generateDevicePayload(deviceId);
    }

    private String generateDevicePayload(String deviceId) {

        String payload = String.format("""
                {
                    "$requestId": "%s",
                    "deviceTwinDocument": {
                        "attributes": {
                            "reported": {
                                "temp": 10
                            }
                        }
                    }
                }
                """, deviceId);
        return payload;

    }


}
