package org.example.cepbench.edge;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.example.cepbench.config.BenchmarkConfig;

import java.io.Closeable;
import java.util.concurrent.atomic.LongAdder;

/**
 * One MQTT connection standing in for an edge.
 *
 * <p>Connects using the edge's {@code clientId} as the MQTT client identifier, which is how the
 * broker recognises it: the platform registers both of an edge's client ids with Dirana when the
 * edge is created, so no separate credential is normally required.
 *
 * <p>{@code alternativeClientId} is the downlink identity, used to subscribe to
 * {@code dvcout/<edgeId>/<clientId>/edge/twin/#}. This publisher never uses it. Connecting with the
 * wrong one of the two produces a connection that looks healthy and delivers nothing.
 */
public final class EdgeMqttPublisher implements Closeable {

    private final BenchmarkConfig.EdgeTarget edge;
    private final MqttClient client;
    private final LongAdder published = new LongAdder();
    private final LongAdder failed = new LongAdder();

    public EdgeMqttPublisher(BenchmarkConfig.EdgeTarget edge) throws MqttException {
        this.edge = edge;
        this.client = new MqttClient(edge.brokerUrl(), edge.clientId(), new MemoryPersistence());
    }

    public void connect() throws MqttException {
        if (client.isConnected()) {
            return;
        }
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);
        // The load runner decides when to give up, so reconnection stays visible rather than being
        // silently absorbed by the client while the measured rate quietly collapses.
        options.setAutomaticReconnect(false);
        options.setConnectionTimeout(10);
        options.setKeepAliveInterval(60);
        options.setMaxInflight(edge.maxInflight());
        if (!edge.username().isEmpty()) {
            options.setUserName(edge.username());
        }
        if (!edge.password().isEmpty()) {
            options.setPassword(edge.password().toCharArray());
        }
        client.connect(options);
    }

    public boolean isConnected() {
        return client.isConnected();
    }

    /**
     * Publishes one payload, reconnecting once if the connection has dropped.
     *
     * @return true when the broker accepted it
     */
    public boolean publish(byte[] payload) {
        try {
            if (!client.isConnected()) {
                connect();
            }
            client.publish(edge.publishTopic(), payload, edge.qos(), false);
            published.increment();
            return true;
        } catch (MqttException e) {
            failed.increment();
            return false;
        }
    }

    public long publishedCount() {
        return published.sum();
    }

    public long failedCount() {
        return failed.sum();
    }

    @Override
    public void close() {
        try {
            if (client.isConnected()) {
                client.disconnect();
            }
        } catch (MqttException ignored) {
            // Nothing useful to do while shutting down; the counters have already been reported.
        }
        try {
            client.close();
        } catch (MqttException ignored) {
            // Same.
        }
    }
}
