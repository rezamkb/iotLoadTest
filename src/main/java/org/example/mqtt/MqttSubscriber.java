package org.example.mqtt;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class MqttSubscriber implements MqttCallback {

    private final String broker;
    private final String topic;
    private final int qos;
    private final String clientId;

    private final ConcurrentLinkedQueue<String> store = new ConcurrentLinkedQueue<>();
    private final AtomicInteger counter = new AtomicInteger();
    private final CountDownLatch latch;
    private MqttClient client;

    public MqttSubscriber(String broker, String topic, int qos, String clientId, int expectedMessages) {
        this.broker = broker;
        this.topic = topic;
        this.qos = qos;
        this.clientId = clientId;
        this.latch = new CountDownLatch(expectedMessages); // how many messages to wait for
    }

    public void start() throws MqttException {
        client = new MqttClient(broker, clientId, new MemoryPersistence());
        client.setCallback(this);
        client.connect(new MqttConnectOptions());
        client.subscribe(topic, qos);
        System.out.println("Collector subscribed to " + topic);
    }

    public List<String> stopAndGet(long timeoutSeconds) throws Exception {
        System.out.println("Waiting for messages...");
        boolean received = latch.await(timeoutSeconds, TimeUnit.SECONDS);
        if (!received) {
            System.err.println("Timed out waiting for messages.");
        } else {
            System.out.println("Expected messages received.");
        }

        client.disconnect();
        return List.copyOf(store);
    }

    @Override
    public void connectionLost(Throwable cause) {
        System.err.println("Collector lost connection: " + cause.getMessage());
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        String msg = new String(message.getPayload());

        System.out.println("message Arrived");
        store.add(msg);
        counter.incrementAndGet();
        latch.countDown();  // count down when message is received
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // Not needed
    }
}
