package org.example.mqtt.edge;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.concurrent.atomic.AtomicInteger;

public class MqttSubscriberEdge implements MqttCallback {

    private final String broker;
    private final String topic;
    private final int qos;
    private final String clientId;

    private final AtomicInteger counter = new AtomicInteger();
    private MqttClient client;

    public MqttSubscriberEdge(String broker, String topic, int qos, String clientId) {
        this.broker = broker;
        this.topic = topic;
        this.qos = qos;
        this.clientId = clientId;

    }

    public void start() throws MqttException {
        client = new MqttClient(broker, clientId, new MemoryPersistence());
        client.setCallback(this);
        client.connect(new MqttConnectOptions());
        client.subscribe(topic, qos);
        System.out.println("Collector subscribed to " + topic);
    }

    public void stop() throws MqttException {
      client.disconnect();
    }



    @Override
    public void connectionLost(Throwable cause) {
        System.err.println("Collector lost connection: " + cause.getMessage());
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        String msg = new String(message.getPayload());
        int i = counter.incrementAndGet();
        System.out.println("message Arrived  " + i + "with  payload : " + msg);
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // Not needed
    }
}
