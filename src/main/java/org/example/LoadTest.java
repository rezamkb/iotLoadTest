package org.example;

import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * End‑to‑end MQTT load test:
 *   – one always‑on subscriber (collector)
 *   – N publishers (virtual threads) loaded from client_ids.csv
 *   – writes received payloads to received_messages.txt
 */
public class LoadTest {

    // === CONFIGURATION ====================================================
    private static final String BROKER_URL = "tcp://10.35.44.18:1883";
    private static final String PUB_TOPIC = "dvcasy/twin/update/reported";
    private static final String SUB_TOPIC = "dvcout/y5hy3437nq7/6YOIIYRSJUJHZLWJPQZTW1J/twin/#";
    static String pubClientId =  "6YOIIYRSJUJHZLWJPQZTW1J";
    static String subClientId =  "BP9NSPTMZUPE9IA5BZ7AXTM";
    private static final int QOS = 0;
    private static final long TEST_TIMEOUT_MIN = 10;

    public static void main(String[] args) throws Exception {

        MqttCollector collector = new MqttCollector(BROKER_URL, SUB_TOPIC, QOS, subClientId, 2);
        collector.start();

        // Give subscriber a moment to connect before publishing
        Thread.sleep(1000);

        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            exec.submit(new MqttPublisherTask(BROKER_URL, PUB_TOPIC, pubClientId, QOS));
            exec.shutdown();
            exec.awaitTermination(10, TimeUnit.SECONDS);
        }

        // Wait for message(s) or timeout
        List<String> messages = collector.stopAndGet(30);
        System.out.println("Received " + messages.size() + " message(s)");
        messages.forEach(System.out::println);
    }


}
