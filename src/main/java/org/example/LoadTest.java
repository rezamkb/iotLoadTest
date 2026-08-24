package org.example;

import org.example.mqtt.MqttPublisher;

import java.util.List;
import java.util.concurrent.*;


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


        Thread.sleep(1000);

        try (ExecutorService exec = Executors.newFixedThreadPool(10)) {
            exec.submit(new MqttPublisher(BROKER_URL, PUB_TOPIC, pubClientId, QOS));

            exec.shutdown();
            exec.awaitTermination(10, TimeUnit.SECONDS);
        }

         // Wait for message(s) or timeout
        //  List<String> messages = collector.stopAndGet(30);
       // System.out.println("Received " + messages.size() + " message(s)");
        //  messages.forEach(System.out::println);
    }


}
