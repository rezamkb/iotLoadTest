package org.example.mqtt.edge;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/** Receives and logs messages addressed to the simulated edge. */
public final class MqttSubscriberEdge implements MqttCallback, AutoCloseable {
    private final String broker;
    private final String topic;
    private final int qos;
    private final String clientId;
    private final String username;
    private final String password;
    private final AtomicLong receivedCount = new AtomicLong();

    private volatile MqttClient client;

    public MqttSubscriberEdge(String broker, String topic, int qos, String clientId) {
        this(broker, topic, qos, clientId, null, null);
    }

    public MqttSubscriberEdge(String broker,
                              String topic,
                              int qos,
                              String clientId,
                              String username,
                              String password) {
        this.broker = broker;
        this.topic = topic;
        this.qos = qos;
        this.clientId = clientId;
        this.username = username;
        this.password = password;
    }

    public synchronized void start() throws MqttException {
        stop();

        MqttClient newClient = new MqttClient(broker, clientId, new MemoryPersistence());
        newClient.setCallback(this);

        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);
        options.setAutomaticReconnect(false);
        options.setConnectionTimeout(10);
        options.setKeepAliveInterval(60);
        if (username != null) {
            options.setUserName(username);
        }
        if (password != null) {
            options.setPassword(password.toCharArray());
        }

        try {
            newClient.connect(options);
            newClient.subscribe(topic, qos);
            client = newClient;
            System.out.printf(
                    "Edge subscriber connected: clientId=%s, topic=%s, qos=%d%n",
                    clientId,
                    topic,
                    qos
            );
        } catch (MqttException failure) {
            try {
                if (newClient.isConnected()) {
                    newClient.disconnect();
                }
                newClient.close();
            } catch (MqttException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    public boolean isConnected() {
        MqttClient currentClient = client;
        return currentClient != null && currentClient.isConnected();
    }

    public long receivedCount() {
        return receivedCount.get();
    }

    public synchronized void stop() {
        MqttClient currentClient = client;
        client = null;
        if (currentClient == null) {
            return;
        }

        try {
            if (currentClient.isConnected()) {
                currentClient.disconnect();
            }
        } catch (MqttException failure) {
            System.err.println("Subscriber disconnect failed: " + failure.getMessage());
        } finally {
            try {
                currentClient.close();
            } catch (MqttException failure) {
                System.err.println("Subscriber close failed: " + failure.getMessage());
            }
        }
    }

    @Override
    public void close() {
        stop();
    }

    @Override
    public void connectionLost(Throwable cause) {
        String reason = cause == null ? "unknown reason" : cause.getMessage();
        System.err.println("Edge subscriber lost connection: " + reason);
    }

    @Override
    public void messageArrived(String arrivedTopic, MqttMessage message) {
        long count = receivedCount.incrementAndGet();
        String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
        System.out.printf(
                "Received %,d: topic=%s, qos=%d, retained=%s, duplicate=%s, payload=%s%n",
                count,
                arrivedTopic,
                message.getQos(),
                message.isRetained(),
                message.isDuplicate(),
                payload
        );
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // The subscriber does not publish messages.
    }
}
