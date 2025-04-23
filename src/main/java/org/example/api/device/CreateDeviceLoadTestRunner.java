package org.example.api.device;

import org.example.api.device.dto.CreateDeviceReq;
import org.example.api.device.dto.CreateDeviceResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class CreateDeviceLoadTestRunner {
    private final DeviceClient client  = new DeviceClient();
    private final ExecutorService executor =
            Executors.newFixedThreadPool(Config.THREAD_POOL_SIZE);

    // thread-safe list to store only the subset of response fields
    private final List<CreateDeviceResponse> storedResponses =
            Collections.synchronizedList(new ArrayList<>());

    public void run() {
        List<CompletableFuture<Void>> tasks = IntStream.range(0, Config.TOTAL_DEVICES)
                .mapToObj(i -> CompletableFuture.runAsync(() -> {
                    CreateDeviceReq dev = DeviceReqFactory.createDeviceReq();
                    try {
                        CreateDeviceResponse dr = client.create(dev);
                        storedResponses.add(dr);
                        System.out.printf("Created ID=%s, name=%s, clientId=%s, altClientId=%s, typeId=%s → OK%n",
                                dr.getId(), dr.getName(), dr.getClientId(), dr.getAlternativeClientId(), dr.getDeviceTypeId());
                    } catch (Exception e) {
                        System.err.printf("Failed creation for %s → %s%n", dev.getName(), e.getMessage());
                    }
                }, executor))
                .collect(Collectors.toList());

        CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();
        shutdown();

        // Export stored responses to CSV
        try {
            CsvExporter.exportCreateDeviceResponses(storedResponses, "devices.csv");
            System.out.println("Exported " + storedResponses.size() + " records to devices.csv");
        } catch (IOException e) {
            System.err.println("Failed to export CSV: " + e.getMessage());
    }

    }

    private void shutdown() {
        executor.shutdown();
    }
}
