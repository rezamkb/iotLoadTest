package org.example.mqtt.edge;

import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.example.mqtt.ClientInfo;
import org.example.mqtt.MqttPublisher;
import org.example.mqtt.MqttSubscriber;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class MqttLoadTestEdge {
    private static final String BROKER_URL = "tcp://10.35.44.18:1883";


    private static final Path CSV_FILE = Path.of("/home/r.taleb/IdeaProjects/iotLoadTest/edge_8suw2sqhpr8.csv");

    public static void main(String[] args) throws Exception {
       AtomicInteger counter = new AtomicInteger();
        List<String> deviceIds = readDeviceId(CSV_FILE);


//        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

//        MqttSubscriberEdge subscriber = new MqttSubscriberEdge(
//                    BROKER_URL,
//                   EdgeConfig.SUB_TOPIC,
//                    0,
//                    EdgeConfig.ALTER_CLIENT_ID
//            );
//            subscriber.start();

//        MqttClient client = new MqttClient(BROKER_URL, EdgeConfig.CLIENT_ID, new MemoryPersistence());
//        client.connect(new MqttConnectOptions());

//        Runnable submitBatch = () ->
//            deviceIds.forEach(deviceId -> {
//                try {
//                    MqttClient client = new MqttClient(BROKER_URL, EdgeConfig.CLIENT_ID, new MemoryPersistence());
//                    client.connect(new MqttConnectOptions());
//                    byte[] b = generateDevicePayload(deviceId).getBytes();
//                    for (int i = 0; i < 15 ; i++)
//                        client.publish(EdgeConfig.PUB_TOPIC,b , 0, false);
//                    System.out.println("deviceId : "+ deviceId + " sent message : " + counter.incrementAndGet());
//                    client.disconnect();
//                } catch (MqttException e) {
//                    System.out.println(e.getMessage());
//                }
//            });
        // single long-lived publisher client


        MqttClient pubClient = new MqttClient(
                BROKER_URL, EdgeConfig.CLIENT_ID, new MemoryPersistence());
        pubClient.connect(new MqttConnectOptions());
        ScheduledExecutorService sched =
                Executors.newScheduledThreadPool(50);

       Runnable batch = () ->

                deviceIds.parallelStream().forEach(id -> {
            try {
               // System.out.println(generateDevicePayload(id));
                byte[] payload = generateDevicePayload(id).getBytes();
                int i =counter.incrementAndGet();
                if (i > 3000) return;
                pubClient.publish(EdgeConfig.PUB_TOPIC, payload, 0, false);
                System.out.println("deviceId : "+ id + " sent message : " + i);





//                System.out.println("deviceId : "+ id + " sent message : " + counter.incrementAndGet());
            } catch (MqttException ex) {
                System.out.println("Publish failed for "+  ex.getMessage());
            }
        });



        sched.scheduleAtFixedRate(batch, 0, 1, TimeUnit.SECONDS);


        try {
            sched.awaitTermination(2000, TimeUnit.MINUTES);
        } finally {
            sched.shutdownNow();
//            subscriber.stop();                    // implement stop() to disconnect
        }



    }

    private static List<String> readDeviceId(Path filePath) throws Exception {
        List<String> list = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(filePath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",", 5);
                if (parts.length == 5) {
                    list.add(
                            stripQuotes(parts[0].trim())
                    );
                }
            }
        }
        return list;
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
