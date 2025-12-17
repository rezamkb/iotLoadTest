package org.example.mqtt;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public class MqttLoadTest {
    private static final String BROKER_URL = "tcp://10.35.44.18:1883";
    private static final int QOS = 0;
    private static final long MAX_WAIT_SECONDS = 30;

    private static final Path CSV_FILE = Path.of("/home/r.taleb/IdeaProjects/iotLoadTest/devices.csv");

    public static void main(String[] args) throws Exception {

        List<ClientInfo> clients = readCsv(CSV_FILE);

        List<MqttSubscriber> subscribers = new ArrayList<>();
        ExecutorService exec = Executors.newFixedThreadPool(50);
        // 3) Scheduler to fire your submission loop every second
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

        // Start all subscribers
//        for (ClientInfo info : clients) {
//            MqttSubscriber subscriber = new MqttSubscriber(
//                    BROKER_URL,
//                    info.subscriberTopic,
//                    QOS,
//                    info.subscriberClientId,
//                    1
//            );
//            subscriber.start();
//            subscribers.add(subscriber);
//        }

        // Wait a bit to ensure subscribers are ready
     //   Thread.sleep(1000);

        // Start all publihers
//        Runnable submitBatch = () -> {
            for (ClientInfo info : clients) {
                exec.submit(new MqttPublisher(
                        BROKER_URL,
                        info.publisherTopic,
                        info.publisherClientId,
                        0,
                        info.payload
                ));
            }
//        };

        // 5) Schedule at fixed rate: initial delay 0, period = 1 second
//        ScheduledFuture<?> batchHandle = scheduler.scheduleAtFixedRate(
//                submitBatch,
//                0,                // start immediately
//                3,
//                TimeUnit.SECONDS
//        );

//        exec.shutdown();
//        exec.awaitTermination(5, TimeUnit.MINUTES);


        // 6) After 30 minutes, stop scheduling and shut everything down
//        scheduler.schedule(() -> {
//            System.out.println("=== Load test complete; shutting down ===");
//            batchHandle.cancel(false);
//            scheduler.shutdown();
//
//            exec.shutdown();
//            try {
//                if (!exec.awaitTermination(1, TimeUnit.MINUTES)) {
//                    exec.shutdownNow();
//                }
//            } catch (InterruptedException e) {
//                exec.shutdownNow();
//            }
//        }, 30, TimeUnit.MINUTES);
//

//        // Wait for all collectors to receive their message
//        for (MqttSubscriber subscriber : subscribers) {
//            List<String> messages = subscriber.stopAndGet(MAX_WAIT_SECONDS);
//          //  System.out.println("Collector for " + collector.getClientId() + " received " + messages.size() + " message(s).");
//            messages.forEach(System.out::println);
//        }
    }

    private static List<ClientInfo> readCsv(Path filePath) throws Exception {
        List<ClientInfo> list = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(filePath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",", 5);
                if (parts.length == 5) {
                    list.add(new ClientInfo(
                            stripQuotes(parts[0].trim()),
                            stripQuotes(parts[2].trim()),
                            stripQuotes(parts[3].trim())
                    ));
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


    public static void deviceLoadTest() throws Exception {

        List<ClientInfo> clients = readCsv(CSV_FILE);

        List<MqttSubscriber> subscribers = new ArrayList<>();
        ExecutorService exec = Executors.newFixedThreadPool(50);
        // 3) Scheduler to fire your submission loop every second
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

        // Start all subscribers
//        for (ClientInfo info : clients) {
//            MqttSubscriber subscriber = new MqttSubscriber(
//                    BROKER_URL,
//                    info.subscriberTopic,
//                    QOS,
//                    info.subscriberClientId,
//                    1
//            );
//            subscriber.start();
//            subscribers.add(subscriber);
//        }

        // Wait a bit to ensure subscribers are ready
        //   Thread.sleep(1000);

        // Start all publihers
        Runnable submitBatch = () -> {
            for (ClientInfo info : clients) {
                exec.submit(new MqttPublisher(
                        BROKER_URL,
                        info.publisherTopic,
                        info.publisherClientId,
                        0,
                        info.payload
                ));
            }
        };

        // 5) Schedule at fixed rate: initial delay 0, period = 1 second
        ScheduledFuture<?> batchHandle = scheduler.scheduleAtFixedRate(
                submitBatch,
                0,                // start immediately
                3,
                TimeUnit.SECONDS
        );

//        exec.shutdown();
//        exec.awaitTermination(5, TimeUnit.MINUTES);


        // 6) After 30 minutes, stop scheduling and shut everything down
//        scheduler.schedule(() -> {
//            System.out.println("=== Load test complete; shutting down ===");
//            batchHandle.cancel(false);
//            scheduler.shutdown();
//
//            exec.shutdown();
//            try {
//                if (!exec.awaitTermination(1, TimeUnit.MINUTES)) {
//                    exec.shutdownNow();
//                }
//            } catch (InterruptedException e) {
//                exec.shutdownNow();
//            }
//        }, 30, TimeUnit.MINUTES);
//

//        // Wait for all collectors to receive their message
//        for (MqttSubscriber subscriber : subscribers) {
//            List<String> messages = subscriber.stopAndGet(MAX_WAIT_SECONDS);
//          //  System.out.println("Collector for " + collector.getClientId() + " received " + messages.size() + " message(s).");
//            messages.forEach(System.out::println);
//        }
    }


}
