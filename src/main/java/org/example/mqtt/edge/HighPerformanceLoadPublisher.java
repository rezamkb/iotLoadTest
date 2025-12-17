package org.example.mqtt.edge;



import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.concurrent.*;

/**
 * High‑performance MQTT load publisher limited to **one** TCP connection.
 * <p>
 * Design decisions:
 * <ul>
 *   <li>Uses <b>MqttAsyncClient</b> to prevent network blocking.</li>
 *   <li>A <b>BlockingQueue</b> buffers payloads from many producer threads.</li>
 *   <li>A <b>single, dedicated publisher thread</b> keeps the connection’s socket write‑buffer full without cross‑thread contention inside Paho.</li>
 *   <li>Large <code>MAX_INFLIGHT</code> (10 000) and 50 ms tick size maximise throughput while giving the JVM time to batch system calls.</li>
 *   <li>Automatic reconnect ensures the load test survives a broker hiccup.</li>
 * </ul>
 */
//public final class HighPerformanceLoadPublisher {
//
//    private static final String BROKER_URL = EdgeConfig.BROKER_URL;
//    private static final String TOPIC = EdgeConfig.PUB_TOPIC;
//
//    /** Max simultaneous in‑flight PUBLISH packets (QoS > 0). */
//    private static final int MAX_INFLIGHT = 10_000;
//    /** How often to enqueue a full batch of device payloads. */
//    private static final int BATCH_INTERVAL_MS = 50;
//    /** Worker threads for payload generation. */
//    private static final int PRODUCER_THREADS = Runtime.getRuntime().availableProcessors() * 2;
//    /** Size of the hand‑off queue between producers and the MQTT publisher thread. */
//    private static final int QUEUE_CAPACITY = 100_000;
//
//    private final MqttAsyncClient client;
//    private final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
//
//    public HighPerformanceLoadPublisher() throws MqttException {
//        MqttConnectionOptions opts = new MqttConnectionOptions();
//        opts.setAutomaticReconnect(true);
//        opts.setCleanStart(true);
//        opts.setMaxInflight(MAX_INFLIGHT);
//
//        client = new MqttAsyncClient(BROKER_URL, EdgeConfig.CLIENT_ID + "-pub", new MemoryPersistence());
//        client.connect(opts).waitForCompletion();
//    }
//
//    /**
//     * Starts the publishing loop. Call once from <code>main</code>.
//     */
//    public void start(Collection<String> deviceIds) {
//        // 1 – Producer pool – cheap CPU work, no network I/O
//        ExecutorService producers = Executors.newFixedThreadPool(PRODUCER_THREADS, r -> {
//            Thread t = new Thread(r, "producer-" + System.nanoTime());
//            t.setDaemon(true);
//            return t;
//        });
//
//        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
//            Thread t = new Thread(r, "batch‑tick");
//            t.setDaemon(true);
//            return t;
//        });
//
//        Runnable batch = () -> producers.execute(() ->
//                deviceIds.parallelStream().forEach(id -> {
//                    byte[] pl = generateDevicePayload(id).getBytes(StandardCharsets.UTF_8);
//                    // Back‑pressure if queue is full
//                    while (!queue.offer(pl)) {
//                        Thread.yield();
//                    }
//                })
//        );
//
//        scheduler.scheduleAtFixedRate(batch, 0, BATCH_INTERVAL_MS, TimeUnit.MILLISECONDS);
//
//        // 2 – Dedicated publisher thread using one MQTT connection
//        Thread publisher = new Thread(() -> {
//            try {
//                while (!Thread.currentThread().isInterrupted()) {
//                    byte[] payload = queue.take();
//                    try {
//                        client.publish(TOPIC, payload, 0, false, null, null);
//                    } catch (MqttException e) {
//                        System.err.println("Publish failed: " + e.getMessage());
//                    }
//                }
//            } catch (InterruptedException ignored) {
//                Thread.currentThread().interrupt();
//            }
//        }, "mqtt‑publisher");
//        publisher.setDaemon(true);
//        publisher.start();
//    }
//
//    private static String generateDevicePayload(String deviceId) {
//        return "{\"id\":\"" + deviceId + "\",\"ts\":" + System.currentTimeMillis() + '}';
//    }
//}
