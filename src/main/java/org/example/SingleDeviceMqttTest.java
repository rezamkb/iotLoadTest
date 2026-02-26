package org.example;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class SingleDeviceMqttTest {

    private static final String BROKER = "tcp://iot-mqtt.pod.ir:1883";
    private static final String TOPIC = "dvcasy/twin/update/reported";

    public static void main(String[] args) throws Exception {

        Path csv = Path.of("C:\\Users\\taleb\\Downloads\\my-project\\src\\main\\java\\org\\example\\device.csv");

        // ---- read first row ----
        String clientId;

        try (Reader reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     .build()
                     .parse(reader)) {

            CSVRecord first = parser.iterator().next();
            clientId = first.get("clientId");
        }

        System.out.println("Using clientId = " + clientId);

        // ---- create mqtt client ----
        MqttClient client = new MqttClient(BROKER, clientId, new MemoryPersistence());

        MqttConnectOptions options = new MqttConnectOptions();
//        options.setCleanSession(true);
//        options.setAutomaticReconnect(true);
//        options.setKeepAliveInterval(60);

        client.connect(options);

        System.out.println("Connected llllllllll");

        // ---- send sample payload ----
        String payload = """
        {
          "$requestId":"test",
          "deviceTwinDocument":{
            "attributes":{
              "reported":{
                "state":"on",
                "energy":55
              }
            }
          }
        }
        """;

        MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
        message.setQos(0);

        client.publish(TOPIC, message);

        System.out.println("Message published ✔");

        Thread.sleep(2000);

        client.disconnect();
        client.close();

        System.out.println("Done");
    }
}