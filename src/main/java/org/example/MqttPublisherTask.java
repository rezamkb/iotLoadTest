package org.example;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

public class MqttPublisherTask implements Runnable {

    private final String broker;
    private final String topic;
    private final String clientId;
    private final int qos;

    public MqttPublisherTask(String broker, String topic, String clientId, int qos) {
        this.broker = broker;
        this.topic = topic;
        this.clientId = clientId;
        this.qos = qos;
    }

    @Override
    public void run() {
        try {
            MqttClient client = new MqttClient(broker, clientId, new MemoryPersistence());
            client.connect(new MqttConnectOptions());

            String payload = """
                {
                    "$requestId": "tefeffsferst",
                    "deviceTwinDocument": {
                        "attributes": {
                            "reported": {
                                "temp": 10
                            }
                        }
                    }
                }
                """;

            client.publish(topic, payload.getBytes(), qos, false);
            client.disconnect();
            System.out.println("Publisher sent message successfully.");

        } catch (Exception e) {
            System.err.println("Publisher " + clientId + " failed: " + e.getMessage());
        }
    }
}
