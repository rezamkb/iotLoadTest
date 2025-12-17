package org.example.jms;

import javax.jms.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.util.concurrent.RateLimiter;
import org.apache.activemq.ActiveMQConnection;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.example.mqtt.ClientInfo;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class JMSLoadTester {
    // Broker URL and queue name—customize as needed
    private static final String BROKER_URL = "tcp://10.35.44.209:61616";
    private static final Path CSV_FILE = Path.of("/home/r.taleb/IdeaProjects/iotLoadTest/devices.csv");

    // Target throughput: 100 messages per second
    private static final double MESSAGES_PER_SECOND = 100.0;

    public static void main(String[] args) {
        // Create a RateLimiter that paces at 100 permits per second
        RateLimiter rateLimiter = RateLimiter.create(MESSAGES_PER_SECOND);
        ObjectMapper objectMapper = new ObjectMapper();
        List<String> ids = null;
        // Set up JMS connection factory
        try {
            ids = readCsv(CSV_FILE);
            ActiveMQConnectionFactory cf = new ActiveMQConnectionFactory("root","MWiXxzmtucVWkpECyQud8maoZe",BROKER_URL);
             ActiveMQConnection connection = (ActiveMQConnection) cf.createConnection();
             Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Destination destination = session.createQueue(JmsQueueConfig.ASYNC_QUEUE_DEVICE_REPORTED_INPUT);
             MessageProducer producer = session.createProducer(destination);

            long counter = 0;
//            System.out.println("Starting load test: sending "
//                    + (int)MESSAGES_PER_SECOND
//                    + " messages/sec to " + QUEUE_NAME);

            // Loop forever (or until interrupted), sending messages
            while (true) {
                // block until it's OK to send the next message
                rateLimiter.acquire();

//                String body = "LoadTest message #" + (++counter)
//                        + " at " + System.currentTimeMillis();
                for (String id : ids) {


                    BytesMessage message = session.createBytesMessage();
                    message.writeBytes(objectMapper.writeValueAsBytes(new DiranaInputMessage(id)));
                    producer.send(message);

                }
                // Optional: log every Nth message
                if (counter % (int)MESSAGES_PER_SECOND == 0) {
                    System.out.println("Sent " + counter + " messages so far...");
                }
            }

        } catch (Exception e) {
            System.err.println("Unexpected error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static List<String> readCsv(Path filePath) throws Exception {
        List<String> list = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(filePath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",", 5);
                if (parts.length == 5) {
                    list.add(parts[0].trim());
                }
            }
        }
        return list;
    }
}



