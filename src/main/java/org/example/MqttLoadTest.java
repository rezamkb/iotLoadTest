package org.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.io.BufferedReader;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class MqttLoadTest {

    // ---- Config ----
    private static final String BROKER_URI = "tcp://iot-mqtt.pod.ir:1883";
    private static final String TOPIC = "dvcasy/twin/update/reported";
    private static final int QOS = 0;
    private static final int INTERVAL_SECONDS = 5;

    private static final String DEVICES_CSV = "C:\\Users\\taleb\\Downloads\\my-project\\src\\main\\java\\org\\example\\device_11.csv";
    private static final String PAYLOADS_JSON = "C:\\Users\\taleb\\Downloads\\my-project\\src\\main\\java\\org\\example\\payloads.json";

    private static final String USERNAME = null;
    private static final String PASSWORD = null;

    private static final int MAX_CONCURRENT_CONNECTS = 200;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ---- Model ----
    static class Device {
        String id, name, clientId, alternativeClientId, deviceTypeId;
    }

    public static void main(String[] args) throws Exception {
        List<Device> devices = loadDevices(DEVICES_CSV);
        Map<String, List<JsonNode>> payloadMap = loadPayloadMap(PAYLOADS_JSON);

        System.out.println("Loaded devices: " + devices.size());
        System.out.println("Loaded payload mappings: " + payloadMap.size());
        System.out.println("Broker: " + BROKER_URI + " Topic: " + TOPIC + " Interval: " + INTERVAL_SECONDS + "s");

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(
                Math.min(500, Math.max(8, Runtime.getRuntime().availableProcessors() * 4))
        );

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nStopping...");
            scheduler.shutdownNow();
        }));

        Semaphore connectSem = new Semaphore(MAX_CONCURRENT_CONNECTS);

        for (Device d : devices) {
            scheduler.execute(() -> startDeviceLoop(d, payloadMap, scheduler, connectSem));
        }
    }

    private static void startDeviceLoop(Device d,
                                        Map<String, List<JsonNode>> payloadMap,
                                        ScheduledExecutorService scheduler,
                                        Semaphore connectSem) {
        AtomicInteger idx = new AtomicInteger(0);

        List<JsonNode> payloads = payloadMap.getOrDefault(d.deviceTypeId, List.of(
                MAPPER.createObjectNode()
                        .put("$requestId", "random")
                        .set("deviceTwinDocument",
                                MAPPER.createObjectNode()
                                        .set("attributes",
                                                MAPPER.createObjectNode()
                                                        .set("reported",
                                                                MAPPER.createObjectNode().put("state", "unknown")
                                                        )
                                        )
                        )
        ));

        long backoffMs = 1000;

        while (!Thread.currentThread().isInterrupted()) {
            MqttClient client = null;
            try {
                connectSem.acquire();

                client = new MqttClient(BROKER_URI, d.clientId, new MemoryPersistence());
                MqttConnectOptions opts = new MqttConnectOptions();
                opts.setCleanSession(true);
                opts.setAutomaticReconnect(true);
                opts.setKeepAliveInterval(60);

                if (USERNAME != null) opts.setUserName(USERNAME);
                if (PASSWORD != null) opts.setPassword(PASSWORD.toCharArray());

                client.connect(opts);
                backoffMs = 1000;
                connectSem.release();

                MqttClient finalClient = client;
                ScheduledFuture<?> publishTask = scheduler.scheduleAtFixedRate(() -> {
                    try {
                        JsonNode base = payloads.get(idx.getAndIncrement() % payloads.size());
                        JsonNode msgNode = withNewRequestId(base);
                        byte[] bytes = MAPPER.writeValueAsBytes(msgNode);

                        finalClient.publish(TOPIC, bytes, QOS, false);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, 0, INTERVAL_SECONDS, TimeUnit.SECONDS);


                while (finalClient.isConnected() && !Thread.currentThread().isInterrupted()) {
                    Thread.sleep(1000);
                }

                publishTask.cancel(true);

            } catch (Exception ex) {
                if (client != null) {
                    try { client.disconnect(); } catch (Exception ignored) {}
                    try { client.close(); } catch (Exception ignored) {}
                }
                connectSem.release();

                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                backoffMs = Math.min(30_000, backoffMs * 2);
            }
        }
    }

    private static JsonNode withNewRequestId(JsonNode base) {

        JsonNode copy = base.deepCopy();
        if (copy instanceof ObjectNode obj) {
            obj.put("$requestId", randomId(12));
        }
        return copy;
    }

    private static String randomId(int len) {
        String chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        Random r = new Random();
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }

    private static List<Device> loadDevices(String csvPath) throws Exception {
        List<Device> out = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(new FileReader(csvPath, StandardCharsets.UTF_8))) {
            String header = br.readLine();
            if (header == null) return out;

            String[] cols = header.split(",");
            Map<String, Integer> colIndex = new HashMap<>();
            for (int i = 0; i < cols.length; i++) colIndex.put(cols[i].trim(), i);

            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split(",", -1);

                Device d = new Device();
                d.id = get(parts, colIndex, "id");
                d.name = get(parts, colIndex, "name");
                d.clientId = get(parts, colIndex, "clientId");
                d.alternativeClientId = get(parts, colIndex, "alternativeClientId");
                d.deviceTypeId = get(parts, colIndex, "deviceTypeId");

                if (d.clientId != null && !d.clientId.isBlank()) out.add(d);
            }
        }
        return out;
    }

    private static String get(String[] parts, Map<String, Integer> idx, String key) {
        Integer i = idx.get(key);
        if (i == null || i < 0 || i >= parts.length) return "";
        return parts[i].trim();
    }

    private static Map<String, List<JsonNode>> loadPayloadMap(String jsonPath) throws Exception {
        JsonNode root = MAPPER.readTree(new java.io.File(jsonPath));
        JsonNode payloadsNode = root.get("payloads");
        if (payloadsNode == null || !payloadsNode.isObject()) {
            throw new IllegalArgumentException("payloads.json must contain an object field 'payloads'");
        }

        Map<String, List<JsonNode>> map = new HashMap<>();
        Iterator<String> fields = payloadsNode.fieldNames();

        while (fields.hasNext()) {
            String deviceTypeId = fields.next();
            JsonNode arr = payloadsNode.get(deviceTypeId);

            List<JsonNode> list = new ArrayList<>();
            if (arr != null && arr.isArray()) {
                for (JsonNode n : arr) {
                    if (n.isObject()) list.add(n);
                }
            }
            map.put(deviceTypeId, list);
        }
        return map;
    }
}