package org.example.mqtt.edge;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simulates one edge that reports data for all of its devices over one MQTT
 * connection. Producers put one device payload per interval into a bounded
 * queue and a dedicated publisher drains that queue.
 */
public final class EdgeMqttLoadSimulator implements AutoCloseable {
    private final Config config;
    private final DeviceMessageCatalog catalog;
    private final BlockingQueue<OutboundMessage> queue;
    private final ScheduledExecutorService producer;
    private final ExecutorService publisher;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong enqueuedCount = new AtomicLong();
    private final AtomicLong publishedCount = new AtomicLong();
    private final MqttClient client;

    EdgeMqttLoadSimulator(Config config, DeviceMessageCatalog catalog) throws MqttException {
        this.config = config;
        this.catalog = catalog;
        this.queue = new ArrayBlockingQueue<>(config.queueCapacity());
        this.producer = Executors.newSingleThreadScheduledExecutor(r ->
                new Thread(r, "edge-message-producer"));
        this.publisher = Executors.newSingleThreadExecutor(r ->
                new Thread(r, "edge-mqtt-publisher"));
        this.client = new MqttClient(
                config.brokerUri(),
                config.clientId(),
                new MemoryPersistence()
        );
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.from(args);
        if (config == null) {
            return;
        }

        DeviceMessageCatalog catalog = DeviceMessageCatalog.load(
                config.devicesCsv(),
                config.payloadDirectory(),
                config.generatedVariantCount()
        );

        if (config.validateOnly()) {
            int minimumVariants = catalog.deviceIds().stream()
                    .mapToInt(catalog::variantCount)
                    .min()
                    .orElse(0);
            int maximumVariants = catalog.deviceIds().stream()
                    .mapToInt(catalog::variantCount)
                    .max()
                    .orElse(0);
            System.out.printf(
                    "Payload mapping is valid: devices=%d, variants-per-device=%d..%d%n",
                    catalog.deviceCount(),
                    minimumVariants,
                    maximumVariants
            );
            return;
        }

        EdgeMqttLoadSimulator simulator = new EdgeMqttLoadSimulator(config, catalog);
        Runtime.getRuntime().addShutdownHook(new Thread(simulator::close, "edge-shutdown"));
        simulator.start();

        System.out.printf(
                "Edge simulator started: devices=%d, broker=%s, topic=%s, interval=%dms%n",
                catalog.deviceCount(),
                config.brokerUri(),
                config.topic(),
                config.batchInterval().toMillis()
        );
        new CountDownLatch(1).await();
    }

    void start() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Simulator is already running");
        }

        publisher.execute(this::publishLoop);
        producer.scheduleAtFixedRate(
                this::enqueueDeviceBatch,
                0,
                config.batchInterval().toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    private void enqueueDeviceBatch() {
        try {
            for (String deviceId : catalog.deviceIds()) {
                if (!running.get()) {
                    return;
                }
                queue.put(new OutboundMessage(deviceId, catalog.nextMessage(deviceId)));
                enqueuedCount.incrementAndGet();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException failure) {
            System.err.println("Could not create device batch: " + failure.getMessage());
        }
    }

    private void publishLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                OutboundMessage message = queue.take();
                if (publishWithRetry(message)) {
                    long total = publishedCount.incrementAndGet();
                    if (total == 1 || total % 1_000 == 0) {
                        System.out.printf(
                                "Published %,d messages (last device=%s, queued=%d)%n",
                                total,
                                message.deviceId(),
                                queue.size()
                        );
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private boolean publishWithRetry(OutboundMessage message) throws InterruptedException {
        long backoffMillis = 1_000;
        while (running.get()) {
            try {
                if (!client.isConnected()) {
                    connect();
                }
                client.publish(config.topic(), message.payload(), config.qos(), false);
                return true;
            } catch (MqttException failure) {
                System.err.printf(
                        "Publish failed for device %s: %s; retrying in %dms%n",
                        message.deviceId(),
                        failure.getMessage(),
                        backoffMillis
                );
                TimeUnit.MILLISECONDS.sleep(backoffMillis);
                backoffMillis = Math.min(30_000, backoffMillis * 2);
            }
        }
        return false;
    }

    private void connect() throws MqttException {
        if (client.isConnected()) {
            return;
        }

        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);
        options.setAutomaticReconnect(false);
        options.setConnectionTimeout(10);
        options.setKeepAliveInterval(60);
        options.setMaxInflight(config.maxInflight());
        if (config.username() != null) {
            options.setUserName(config.username());
        }
        if (config.password() != null) {
            options.setPassword(config.password().toCharArray());
        }
        client.connect(options);
    }

    long enqueuedCount() {
        return enqueuedCount.get();
    }

    long publishedCount() {
        return publishedCount.get();
    }

    @Override
    public void close() {
        if (!running.getAndSet(false)) {
            return;
        }

        producer.shutdownNow();
        publisher.shutdownNow();
        try {
            publisher.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }

        try {
            if (client.isConnected()) {
                client.disconnect();
            }
            client.close();
        } catch (MqttException failure) {
            System.err.println("MQTT shutdown failed: " + failure.getMessage());
        }

        System.out.printf(
                "Edge simulator stopped: enqueued=%,d, published=%,d, remaining=%d%n",
                enqueuedCount(),
                publishedCount(),
                queue.size()
        );
    }

    private record OutboundMessage(String deviceId, byte[] payload) {
    }

    record Config(String brokerUri,
                  String topic,
                  String clientId,
                  Path devicesCsv,
                  Path payloadDirectory,
                  Duration batchInterval,
                  int queueCapacity,
                  int qos,
                  int generatedVariantCount,
                  int maxInflight,
                  boolean validateOnly,
                  String username,
                  String password) {

        private static Config from(String[] args) {
            Map<String, String> values = parseArguments(args);
            if (values.containsKey("help")) {
                printUsage();
                return null;
            }

            long intervalMillis = positiveLong(values, "interval-ms", 1_000);
            int queueCapacity = positiveInt(values, "queue-capacity", 100_000);
            int variants = positiveInt(values, "variants", 3);
            int maxInflight = positiveInt(values, "max-inflight", 10_000);
            int qos = integer(values, "qos", 0);
            if (qos < 0 || qos > 2) {
                throw new IllegalArgumentException("--qos must be 0, 1, or 2");
            }

            return new Config(
                    values.getOrDefault("broker", EdgeConfig.BROKER_URL),
                    values.getOrDefault("topic", EdgeConfig.PUB_TOPIC),
                    values.getOrDefault("client-id", EdgeConfig.CLIENT_ID),
                    Path.of(values.getOrDefault("devices", "devices5.csv")),
                    Path.of(values.getOrDefault("payload-dir", "devices_payload")),
                    Duration.ofMillis(intervalMillis),
                    queueCapacity,
                    qos,
                    variants,
                    maxInflight,
                    values.containsKey("validate-only"),
                    values.get("username"),
                    values.get("password")
            );
        }

        private static Map<String, String> parseArguments(String[] args) {
            Map<String, String> values = new HashMap<>();
            for (int index = 0; index < args.length; index++) {
                String argument = args[index];
                if (argument.equals("--help")) {
                    values.put("help", "true");
                    continue;
                }
                if (argument.equals("--validate-only")) {
                    values.put("validate-only", "true");
                    continue;
                }
                if (!argument.startsWith("--")) {
                    throw new IllegalArgumentException("Unknown argument: " + argument);
                }

                String key;
                String value;
                int equals = argument.indexOf('=');
                if (equals > 2) {
                    key = argument.substring(2, equals);
                    value = argument.substring(equals + 1);
                } else {
                    key = argument.substring(2);
                    if (++index >= args.length) {
                        throw new IllegalArgumentException("Missing value for --" + key);
                    }
                    value = args[index];
                }
                values.put(key, value);
            }
            return values;
        }

        private static int positiveInt(Map<String, String> values,
                                       String key,
                                       int defaultValue) {
            int value = integer(values, key, defaultValue);
            if (value < 1) {
                throw new IllegalArgumentException("--" + key + " must be at least 1");
            }
            return value;
        }

        private static int integer(Map<String, String> values,
                                   String key,
                                   int defaultValue) {
            String raw = values.get(key);
            return raw == null ? defaultValue : Integer.parseInt(raw);
        }

        private static long positiveLong(Map<String, String> values,
                                         String key,
                                         long defaultValue) {
            String raw = values.get(key);
            long value = raw == null ? defaultValue : Long.parseLong(raw);
            if (value < 1) {
                throw new IllegalArgumentException("--" + key + " must be at least 1");
            }
            return value;
        }

        private static void printUsage() {
            System.out.println("""
                    Usage: EdgeMqttLoadSimulator [options]
                      --broker URI             MQTT broker (default from EdgeConfig)
                      --topic TOPIC            publish topic (default from EdgeConfig)
                      --client-id ID           edge MQTT client ID
                      --devices PATH           device CSV (default: devices5.csv)
                      --payload-dir PATH       payload samples (default: devices_payload)
                      --interval-ms N          delay between complete device batches
                      --queue-capacity N       bounded outbound queue capacity
                      --qos 0|1|2              MQTT publish QoS
                      --variants N             variants generated for a single sample
                      --max-inflight N         Paho in-flight message limit
                      --validate-only          build and verify mapping without MQTT
                      --username VALUE         optional MQTT username
                      --password VALUE         optional MQTT password
                    """);
        }
    }
}
