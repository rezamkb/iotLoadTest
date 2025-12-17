package org.example.mqtt.edge;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;

public class HPMqttEdgePublisher {

    private static  ConcurrentHashMap<String,byte[]> messages;
    private  Path CSV_FILE = Path.of("/home/r.taleb/IdeaProjects/iotLoadTest/edgedevices.csv");
    private  String PUB_TOPIC = "dvcasy/edge/twin/report";
    private  String SUB_TOPIC = "dvcout/8suw2sqhpr8/0IPERYTPKCNO37O64KG6B46/edge/twin/#";
    private  String ID = "8suw2sqhpr8";
    private  String CLIENT_ID = "0IPERYTPKCNO37O64KG6B46";
    private  String ALTER_CLIENT_ID = "64VQB1GO82DKSHCQC9NDIU0";

    private static final String BROKER_URL = "tcp://10.35.44.18:1883";

    private final MqttClient mqttClient;
    private final ExecutorService publishExecutor;



    public HPMqttEdgePublisher(String device_path, String edgeId, String CLIENT_ID,String ALTER_CLIENT_ID) throws Exception {
        this.ALTER_CLIENT_ID = ALTER_CLIENT_ID;
        this.CLIENT_ID = CLIENT_ID;
        this.ID = edgeId;
        CSV_FILE = Path.of(device_path);
        messages = readDeviceId(CSV_FILE);
        mqttClient = new MqttClient(BROKER_URL, CLIENT_ID, new MemoryPersistence());
        mqttClient.connect(new MqttConnectOptions());
        publishExecutor = Executors.newFixedThreadPool(100);

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(5);

        Runnable batchPublisher = () -> messages.forEach((id,payload) ->
                publishExecutor.submit(() -> {
                            try {
                                mqttClient.publish(PUB_TOPIC, payload, 0, false);
                                System.out.println("deviceId : "+ id + " sent message : ");
                            } catch (MqttException e) {
                                System.err.println("Publish failed for device " + id + ": " + e.getMessage());
                            }
                        }

                ));
        scheduler.scheduleAtFixedRate(batchPublisher, 0, 1, TimeUnit.SECONDS);
    }

    public void shutdown() throws MqttException {
        publishExecutor.shutdown();
        mqttClient.disconnect();
        mqttClient.close();
    }




//    private void publishMessage(String deviceId , byte[] payload) {
//        try {
//            mqttClient.publish(TOPIC, payload, 0, false);
//
//        } catch (MqttException ex) {
//            System.err.println("Publish failed for device " + deviceId + ": " + ex.getMessage());
//        }
//    }

//    public void publishMessages() {
//        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
//
//        Runnable batchPublisher = () -> deviceIds.forEach(id ->
//                publishExecutor.submit(() -> publishMessage(id)));
//
//        scheduler.scheduleAtFixedRate(batchPublisher, 0, 1, TimeUnit.SECONDS);
//    }


//
//    public  void run () throws MqttException {
//        List<String> deviceIds = List.of("device1", "device2", "device3"); // Populate with actual IDs
//
//
//        mqttClient.publish(TOPIC, payload, 0, false);
//
//        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
//            try {
//                publisher.shutdown();
//            } catch (MqttException e) {
//                System.err.println("Error shutting down: " + e.getMessage());
//            }
//        }));
//    }























    private static ConcurrentHashMap<String,byte[]> readDeviceId(Path filePath) throws Exception {
        ConcurrentHashMap<String,byte[]> messages = new ConcurrentHashMap<String,byte[]>();
        try (BufferedReader reader = Files.newBufferedReader(filePath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",", 5);
                if (parts.length == 5) {
                    String id = stripQuotes(parts[0].trim());
                    messages.put(id,generateDevicePayload(id).getBytes());
                }
            }
        }
        return messages;
    }

    private static String stripQuotes(String s) {
        if (s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }






    public static String generateDevicePayload(String deviceId) {

        String payload = String.format("""
                {
                  "$requestId": "%s",
                  "deviceReport": {
                    "deviceId": "%s",
                    "deviceTwinDocument": {
                      "attributes": {
                        "reported": {
                          "temp": 3
                        }
                      }
                    }
                  }
                }
                """, deviceId,deviceId);
        return payload;

    }




}
