package org.example.mqtt;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

public class MqttPublisher implements Runnable {

    private final String broker;
    private final String topic;
    private final String clientId;
    private final int qos;
    private final String payload;

    public MqttPublisher(String broker, String topic, String clientId, int qos, String payload) {
        this.broker = broker;
        this.topic = topic;
        this.clientId = clientId;
        this.qos = qos;
        this.payload = payload;
    }

    public MqttPublisher(String broker, String topic, String clientId, int qos) {
        this.broker = broker;
        this.topic = topic;
        this.clientId = clientId;
        this.qos = qos;
        this.payload = "payload";
    }


    @Override
    public void run() {
        try {
            MqttClient client = new MqttClient(broker, clientId, new MemoryPersistence());
            client.connect(new MqttConnectOptions());

                for (int i = 0; i < 3; i++)
                    client.publish(topic, payload.getBytes(), qos, false);

            System.out.println("Publisher " + clientId + " sent message.");

            client.disconnect();


        } catch (Exception e) {
            System.err.println("Publisher " + clientId + " failed: " + e.getMessage());
        }
    }
}
