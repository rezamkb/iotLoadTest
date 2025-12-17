package org.example.mqtt.edge;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class MqttLoadTestEdge2 {
    private static final String BROKER_URL = "tcp://10.35.44.18:1883";


    private static final Path CSV_FILE = Path.of("edgeData/edge_5tq9l78nso1.csv");

    public static void main(String[] args) throws Exception {
       AtomicInteger counter = new AtomicInteger();
        List<String> deviceIds = readDeviceId(CSV_FILE).subList(0,60);
        String CLIENT_ID = "8NB6MEG574SC6B2MV65GGB8";

//        edgddce133,(rep)8118491,(acc)8118493,8118494,8118495,(doc)8118492

//        ExecutorService feeder = Executors.newFixedThreadPool(
//                Runtime.getRuntime().availableProcessors());

//        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

//        MqttSubscriberEdge subscriber = new MqttSubscriberEdge(
//                    BROKER_URL,
//                    "dvcout/5tq9l78nso1/JOQHROH4WIA1TFK6AN33NZA/edge/twin/#",
//                    0,
//                    "8NB6MEG574SC6B2MV65GGB8"
//            );
//            subscriber.start();



        MqttClient pubClient = new MqttClient(
                BROKER_URL, CLIENT_ID, new MemoryPersistence());
        pubClient.connect(new MqttConnectOptions());
        ScheduledExecutorService sched =
                Executors.newScheduledThreadPool(50);

       Runnable batch = () ->
//        while (true)
                deviceIds.stream().forEach(id -> {
            try {
               // System.out.println(generateDevicePayload(id));
                byte[] payload = generateDevicePayload(id).getBytes();
                int i = counter.incrementAndGet();
                if (i > 50000) return;
                pubClient.publish(EdgeConfig.PUB_TOPIC, payload, 0,false);
                System.out.println(i+"- "+ id);

            } catch (MqttException ex) {
                System.out.println("Publish failed for "+  ex.getMessage());
            }
        });



     sched.scheduleAtFixedRate(batch, 0, 1000, TimeUnit.MILLISECONDS);



//        try {
//          //  sched.awaitTermination(2000, TimeUnit.MINUTES);
//        } finally {
//            sched.shutdownNow();
////            subscriber.stop();                    // implement stop() to disconnect
//        }



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
